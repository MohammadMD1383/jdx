package dev.jdx.index.kotlin

import dev.jdx.core.model.kotlinViewKey
import io.kotest.matchers.shouldBe
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeProjection
import kotlin.metadata.KmVariance
import kotlin.metadata.isNullable
import kotlin.metadata.isSuspend
import kotlin.metadata.jvm.JvmMethodSignature
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
    fun `an unchanged function maps to nothing`() {
        val views = KotlinMembers.viewsFor(
            kmClass(kmFunction("nullable", "nullable", "(Ljava/lang/String;)Ljava/lang/Integer;")),
        )
        views shouldBe emptyMap()
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
        KotlinMembers.renderType(kmType("kotlin/Unit")) shouldBe "void"
        KotlinMembers.renderType(kmType("java/lang/String")) shouldBe "java.lang.String"
        KotlinMembers.renderType(kmType("dev/jdx/fixtures/KotlinMembers.Companion")) shouldBe
            "dev.jdx.fixtures.KotlinMembers\$Companion"
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
}
