package dev.jdx.index.kotlin

import dev.jdx.core.model.kotlinViewKey
import io.kotest.matchers.shouldBe
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeProjection
import kotlin.metadata.KmVariance
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isNullable
import kotlin.metadata.isSuspend
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import org.junit.jupiter.api.Test

/**
 * Tier-1 tests for [KotlinMembers] (T-077): synthetic `KmClass` graphs built in
 * memory, so no disk, no jars. The shapes mirror the `KotlinMembers` fixture
 * (`@JvmName`, `suspend`, mangled `internal`); fixture truth decodes in
 * `KotlinClassesTest` (tier 2).
 */
class KotlinMembersTest {

    private fun kmType(
        classifier: String,
        nullable: Boolean = false,
        arguments: List<KmTypeProjection> = emptyList(),
    ): KmType = KmType().apply {
        this.classifier = KmClassifier.Class(classifier)
        isNullable = nullable
        this.arguments.addAll(arguments)
    }

    private fun kmFunction(
        kotlinName: String,
        jvmName: String,
        jvmDescriptor: String,
        suspend: Boolean = false,
        returnType: KmType = kmType("kotlin/Unit"),
    ): KmFunction = KmFunction(kotlinName).apply {
        signature = JvmMethodSignature(jvmName, jvmDescriptor)
        isSuspend = suspend
        this.returnType = returnType
    }

    private fun kmClass(vararg functions: KmFunction): KmClass = KmClass().apply {
        this.functions.addAll(functions)
    }

    @Test
    fun `a JvmName function maps its JVM key to the Kotlin name`() {
        val views = KotlinMembers.viewsFor(
            kmClass(kmFunction("originalName", "renamedForJvm", "(I)I")),
        )
        val view = views[kotlinViewKey("renamedForJvm", "(I)I")]!!
        view.displayName shouldBe "originalName"
        view.markSuspend shouldBe false
        view.stripTrailingContinuation shouldBe false
        view.displayReturn shouldBe null
    }

    @Test
    fun `a mangled internal function maps to its demangled name`() {
        val views = KotlinMembers.viewsFor(
            kmClass(kmFunction("internalHelper", "internalHelper\$testfixtures", "()V")),
        )
        views[kotlinViewKey("internalHelper\$testfixtures", "()V")]?.displayName shouldBe "internalHelper"
    }

    @Test
    fun `a suspend function strips the Continuation and renders the metadata return`() {
        val views = KotlinMembers.viewsFor(
            kmClass(
                kmFunction(
                    "fetch",
                    "fetch",
                    "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
                    suspend = true,
                    returnType = kmType("kotlin/String"),
                ),
            ),
        )
        val view = views[
            kotlinViewKey(
                "fetch",
                "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
            ),
        ]!!
        view.displayName shouldBe "fetch"
        view.markSuspend shouldBe true
        view.stripTrailingContinuation shouldBe true
        view.displayReturn shouldBe "java.lang.String"
    }

    @Test
    fun `a suspend function with an unrenderable return keeps the strip and the mark`() {
        val views = KotlinMembers.viewsFor(
            kmClass(
                kmFunction(
                    "fetch",
                    "fetch",
                    "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
                    suspend = true,
                    returnType = kmType("kotlin/Mystery"),
                ),
            ),
        )
        val view = views[
            kotlinViewKey("fetch", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
        ]!!
        view.displayReturn shouldBe null
        view.stripTrailingContinuation shouldBe true
        view.markSuspend shouldBe true
    }

    @Test
    fun `an unchanged non-nullable function maps to nothing`() {
        val views = KotlinMembers.viewsFor(
            kmClass(
                kmFunction(
                    "plain",
                    "plain",
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    returnType = kmType("kotlin/String"),
                ).also { function ->
                    function.valueParameters.add(
                        kotlin.metadata.KmValueParameter("input").apply {
                            type = kmType("kotlin/String")
                        },
                    )
                },
            ),
        )
        views shouldBe emptyMap()
    }

    @Test
    fun `a nullable param and return produce a view with display types`() {
        val views = KotlinMembers.viewsFor(
            kmClass(
                kmFunction(
                    "nullable",
                    "nullable",
                    "(Ljava/lang/String;)Ljava/lang/Integer;",
                    returnType = kmType("kotlin/Int", nullable = true),
                ).also { function ->
                    function.valueParameters.add(
                        kotlin.metadata.KmValueParameter("input").apply {
                            type = kmType("kotlin/String", nullable = true)
                        },
                    )
                },
            ),
        )
        val view = views[kotlinViewKey("nullable", "(Ljava/lang/String;)Ljava/lang/Integer;")]!!
        view.displayName shouldBe "nullable"
        view.displayReturn shouldBe "java.lang.Integer?"
        view.displayParameters shouldBe listOf("java.lang.String?")
        view.markSuspend shouldBe false
    }

    @Test
    fun `value-class arity mismatch drops display parameters`() {
        // JVM prepends the unboxed receiver; metadata lists only the Kotlin params.
        // Applying displayParameters by index would paint Object? onto the receiver.
        val views = KotlinMembers.viewsFor(
            kmClass(
                kmFunction(
                    "equals",
                    "equals-impl",
                    "(Ljava/lang/String;Ljava/lang/Object;)Z",
                    returnType = kmType("kotlin/Boolean"),
                ).also { function ->
                    function.valueParameters.add(
                        kotlin.metadata.KmValueParameter("other").apply {
                            type = kmType("kotlin/Any", nullable = true)
                        },
                    )
                },
            ),
        )
        val view = views[kotlinViewKey("equals-impl", "(Ljava/lang/String;Ljava/lang/Object;)Z")]!!
        view.displayName shouldBe "equals"
        view.displayParameters shouldBe emptyList()
    }

    @Test
    fun `nested nullability alone produces a param display`() {
        val listOfNullable = kmType(
            "kotlin/collections/List",
            arguments = listOf(KmTypeProjection(KmVariance.INVARIANT, kmType("kotlin/String", nullable = true))),
        )
        val views = KotlinMembers.viewsFor(
            kmClass(
                kmFunction(
                    "items",
                    "items",
                    "(Ljava/util/List;)V",
                    returnType = kmType("kotlin/Unit"),
                ).also { function ->
                    function.valueParameters.add(
                        kotlin.metadata.KmValueParameter("xs").apply {
                            type = listOfNullable
                        },
                    )
                },
            ),
        )
        val view = views[kotlinViewKey("items", "(Ljava/util/List;)V")]!!
        view.displayParameters shouldBe listOf("java.util.List<java.lang.String?>")
        view.displayReturn shouldBe null
    }

    @Test
    fun `a function without a JVM signature maps to nothing`() {
        val views = KotlinMembers.viewsFor(
            kmClass(KmFunction("orphan")),
        )
        views shouldBe emptyMap()
    }

    @Test
    fun `a malformed descriptor never throws and never strips`() {
        val views = KotlinMembers.viewsFor(
            kmClass(kmFunction("renamed", "jvmName", "not-a-descriptor")),
        )
        val view = views[kotlinViewKey("jvmName", "not-a-descriptor")]!!
        view.displayName shouldBe "renamed"
        view.stripTrailingContinuation shouldBe false
    }

    @Test
    fun `continuation tails are detected by erased type`() {
        KotlinMembers.isContinuationTail("(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;") shouldBe true
        KotlinMembers.isContinuationTail("(Lkotlin/coroutines/Continuation;)V") shouldBe true
        KotlinMembers.isContinuationTail("(ILkotlin/coroutines/Continuation;)V") shouldBe true
        KotlinMembers.isContinuationTail("(Ljava/lang/String;)V") shouldBe false
        KotlinMembers.isContinuationTail("()V") shouldBe false
        KotlinMembers.isContinuationTail("([Lkotlin/coroutines/Continuation;)V") shouldBe false
        KotlinMembers.isContinuationTail("(Lcom/example/kotlin/coroutines/Continuation;)V") shouldBe false
        KotlinMembers.isContinuationTail("not-a-descriptor") shouldBe false
    }

    @Test
    fun `types render with kotlin mappings and nullability`() {
        KotlinMembers.renderType(kmType("kotlin/Int")) shouldBe "int"
        KotlinMembers.renderType(kmType("kotlin/String")) shouldBe "java.lang.String"
        KotlinMembers.renderType(kmType("kotlin/String", nullable = true)) shouldBe "java.lang.String?"
        KotlinMembers.renderType(kmType("kotlin/Int", nullable = true)) shouldBe "java.lang.Integer?"
        KotlinMembers.renderType(kmType("kotlin/Unit")) shouldBe "void"
        KotlinMembers.renderType(kmType("java/lang/String")) shouldBe "java.lang.String"
        KotlinMembers.renderType(kmType("dev/jdx/fixtures/KotlinMembers.Companion")) shouldBe
            "dev.jdx.fixtures.KotlinMembers\$Companion"
    }

    @Test
    fun `carriesNullability walks nested arguments`() {
        KotlinMembers.carriesNullability(kmType("kotlin/String")) shouldBe false
        KotlinMembers.carriesNullability(kmType("kotlin/String", nullable = true)) shouldBe true
        val nested = kmType(
            "kotlin/collections/List",
            arguments = listOf(KmTypeProjection(KmVariance.INVARIANT, kmType("kotlin/String", nullable = true))),
        )
        KotlinMembers.carriesNullability(nested) shouldBe true
        KotlinMembers.carriesNullability(
            kmType(
                "kotlin/collections/List",
                arguments = listOf(KmTypeProjection(KmVariance.INVARIANT, kmType("kotlin/String"))),
            ),
        ) shouldBe false
    }

    @Test
    fun `type arguments render with variance and stars`() {
        val listOfString = kmType(
            "kotlin/collections/List",
            arguments = listOf(KmTypeProjection(KmVariance.INVARIANT, kmType("kotlin/String"))),
        )
        KotlinMembers.renderType(listOfString) shouldBe "java.util.List<java.lang.String>"
        val outString = kmType(
            "kotlin/collections/List",
            arguments = listOf(KmTypeProjection(KmVariance.OUT, kmType("kotlin/String"))),
        )
        KotlinMembers.renderType(outString) shouldBe "java.util.List<? extends java.lang.String>"
        val star = kmType(
            "kotlin/collections/List",
            arguments = listOf(KmTypeProjection.STAR),
        )
        KotlinMembers.renderType(star) shouldBe "java.util.List<?>"
    }

    @Test
    fun `unmapped kotlin classifiers keep the JVM text`() {
        KotlinMembers.renderType(kmType("kotlin/Mystery")) shouldBe null
    }

    @Test
    fun `a type variable keeps the JVM text`() {
        val variable = KmType().apply {
            classifier = KmClassifier.TypeParameter(0)
        }
        KotlinMembers.renderType(variable) shouldBe null
    }

    @Test
    fun `default indices mark only declaresDefaultValue params`() {
        val function = kmFunction("withDefault", "withDefault", "(ILjava/lang/String;)Ljava/lang/String;")
        function.valueParameters.add(
            kotlin.metadata.KmValueParameter("first").apply {
                type = kmType("kotlin/Int")
            },
        )
        function.valueParameters.add(
            kotlin.metadata.KmValueParameter("second").apply {
                type = kmType("kotlin/String")
                declaresDefaultValue = true
            },
        )
        KotlinMembers.defaultArgIndices(function) shouldBe setOf(1)
    }

    @Test
    fun `a function without defaults marks nothing`() {
        val function = kmFunction("nullable", "nullable", "(Ljava/lang/String;)Ljava/lang/Integer;")
        KotlinMembers.defaultArgIndices(function) shouldBe emptySet()
    }

    @Test
    fun `a property with getter and setter folds to var with hidden accessors`() {
        val property = kotlin.metadata.KmProperty("count").apply {
            getterSignature = kotlin.metadata.jvm.JvmMethodSignature("getCount", "()I")
            setterSignature = kotlin.metadata.jvm.JvmMethodSignature("setCount", "(I)V")
            returnType = kmType("kotlin/Int")
        }
        val kmClass = KmClass().apply { properties.add(property) }
        val folded = KotlinMembers.propertiesFor(kmClass)
        val view = folded.properties["count"]!!
        view.isVar shouldBe true
        view.displayType shouldBe "int"
        folded.hiddenMethods shouldBe setOf("getCount()I", "setCount(I)V")
    }

    @Test
    fun `a val property without a setter folds to val`() {
        val property = kotlin.metadata.KmProperty("name").apply {
            getterSignature = kotlin.metadata.jvm.JvmMethodSignature("getName", "()Ljava/lang/String;")
            returnType = kmType("kotlin/String")
        }
        val kmClass = KmClass().apply { properties.add(property) }
        val folded = KotlinMembers.propertiesFor(kmClass)
        folded.properties["name"]?.isVar shouldBe false
        folded.properties["name"]?.constructorDeclared shouldBe false
    }

    @Test
    fun `a primary-constructor property is marked constructorDeclared`() {
        // Issue #14: the sources-mismatch detector pairs these through `<init>`,
        // never as body-declared field rows.
        val property = kotlin.metadata.KmProperty("name").apply {
            getterSignature = kotlin.metadata.jvm.JvmMethodSignature("getName", "()Ljava/lang/String;")
            returnType = kmType("kotlin/String")
        }
        val primary = kotlin.metadata.KmConstructor().apply {
            valueParameters.add(
                kotlin.metadata.KmValueParameter("name").apply {
                    type = kmType("kotlin/String")
                },
            )
        }
        val kmClass = KmClass().apply {
            properties.add(property)
            constructors.add(primary)
        }
        val folded = KotlinMembers.propertiesFor(kmClass)
        folded.properties["name"]?.constructorDeclared shouldBe true
    }

    @Test
    fun `a property without JVM signatures maps to nothing`() {
        val kmClass = KmClass().apply { properties.add(kotlin.metadata.KmProperty("orphan")) }
        val folded = KotlinMembers.propertiesFor(kmClass)
        folded.properties shouldBe emptyMap()
        folded.hiddenMethods shouldBe emptySet()
    }
}
