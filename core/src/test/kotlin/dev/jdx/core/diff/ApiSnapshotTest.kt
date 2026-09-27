package dev.jdx.core.diff

import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.JvmPrimitive
import dev.jdx.core.model.KotlinMethodView
import dev.jdx.core.model.KotlinPropertyView
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.Visibility
import dev.jdx.core.model.kotlinViewKey
import dev.jdx.core.render.methodRefString
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Snapshot construction (issue #23): which declarations enter a diff, and how each one
 * is spelled.
 *
 * The ref tests deliberately compare against [methodRefString] — the function every
 * other `jdx` command uses — because a diff whose refs differ from `jdx members`' is
 * an agent that has to reconstruct instead of copy-paste (AGENTS.md §2.3).
 */
class ApiSnapshotTest {

    private val string = TypeName.PrimitiveType(JvmPrimitive.INT)

    private fun classInfo(
        name: String = "com.example.Foo",
        kind: TypeKind = TypeKind.CLASS,
        access: Access = PUBLIC,
        fields: List<FieldInfo> = emptyList(),
        methods: List<MethodInfo> = emptyList(),
        superclass: TypeName? = null,
        interfaces: List<TypeName> = emptyList(),
        kotlinViews: Map<String, KotlinMethodView> = emptyMap(),
        kotlinProperties: Map<String, KotlinPropertyView> = emptyMap(),
        deprecated: Boolean = false,
        isKotlin: Boolean = false,
    ): ClassInfo = ClassInfo(
        name = TypeName.ClassType("com.example", listOf(name.substringAfterLast('.'))),
        kind = kind,
        access = access,
        superclass = superclass,
        interfaces = interfaces,
        fields = fields,
        methods = methods,
        deprecated = deprecated,
        isKotlin = isKotlin,
        kotlinMethodViews = kotlinViews,
        kotlinProperties = kotlinProperties,
    )

    private fun methodInfo(
        name: String,
        parameters: List<TypeName> = emptyList(),
        returnType: TypeName = TypeName.PrimitiveType(JvmPrimitive.VOID),
        access: Access = PUBLIC,
    ): MethodInfo = MethodInfo(
        name = name,
        descriptor = JvmDescriptor.Method(parameters = parameters, returnType = returnType),
        access = access,
    )

    private fun fieldInfo(name: String, type: TypeName = string, access: Access = PUBLIC): FieldInfo =
        FieldInfo(name = name, type = type, access = access)

    private fun snapshotOf(
        info: ClassInfo,
        surface: ApiSurface = ApiSurface.ALL,
        includeSynthetic: Boolean = false,
    ) = ApiSnapshot.of("test.jar", listOf(info), surface, includeSynthetic)

    // -- what enters a snapshot -----------------------------------------------------

    @Test
    fun `the class initialiser is never part of an API`() {
        val info = classInfo(methods = listOf(methodInfo(CLASS_INITIALISER), methodInfo("real")))
        val keys = snapshotOf(info).types.values.single().members.keys.map { it.name }
        keys shouldBe listOf("real")
        snapshotOf(info, includeSynthetic = true).types.values.single().members.keys.map { it.name } shouldBe
            listOf("real")
    }

    @Test
    fun `synthetic and bridge members hide unless asked for`() {
        val info = classInfo(
            methods = listOf(
                methodInfo("plain"),
                methodInfo("synth", access = Access.of(AccessFlag.PUBLIC, AccessFlag.SYNTHETIC)),
                methodInfo("bridged", access = Access.of(AccessFlag.PUBLIC, AccessFlag.BRIDGE)),
            ),
        )
        snapshotOf(info).types.values.single().members.keys.map { it.name } shouldBe listOf("plain")
        snapshotOf(info, includeSynthetic = true).types.values.single().members.keys.map { it.name } shouldBe
            listOf("bridged", "plain", "synth")
    }

    @Test
    fun `the bridge bit is read as volatile on a field, so a volatile field is never dropped`() {
        // 0x0040 is `ACC_BRIDGE` on a method and `ACC_VOLATILE` on a field. Reading the
        // bridge bit off a field would silently delete every volatile field.
        val volatileField = fieldInfo("busy", access = Access.of(AccessFlag.PUBLIC, AccessFlag.VOLATILE))
        val info = classInfo(
            fields = listOf(volatileField, fieldInfo("plain")),
            methods = listOf(methodInfo("bridged", access = Access.of(AccessFlag.PUBLIC, AccessFlag.BRIDGE))),
        )
        val withoutSynthetic = snapshotOf(info).types.values.single().members
        withoutSynthetic.keys.map { it.name } shouldBe listOf("busy", "plain")
        // The field is a genuine API member and keeps its access.
        (withoutSynthetic[ApiFieldKey("busy", string)] as ApiField).access.has(AccessFlag.VOLATILE) shouldBe true
    }

    @Test
    fun `the public surface keeps public and protected members, and hides the rest`() {
        val info = classInfo(
            access = Access.of(AccessFlag.PUBLIC),
            fields = listOf(
                fieldInfo("pub", access = PUBLIC),
                fieldInfo("prot", access = Access.of(AccessFlag.PROTECTED)),
                fieldInfo("pkg", access = Access.NONE),
                fieldInfo("priv", access = Access.of(AccessFlag.PRIVATE)),
            ),
            methods = listOf(
                methodInfo("pubM", access = PUBLIC),
                methodInfo("privM", access = Access.of(AccessFlag.PRIVATE)),
            ),
        )
        val members = snapshotOf(info, ApiSurface.PUBLIC).types.values.single().members
        members.keys.map { it.name }.sorted() shouldBe listOf("prot", "pub", "pubM")
    }

    @Test
    fun `the all surface keeps every declared member, whatever the visibility`() {
        val info = classInfo(
            fields = listOf(
                fieldInfo("pub", access = PUBLIC),
                fieldInfo("pkg", access = Access.NONE),
                fieldInfo("priv", access = Access.of(AccessFlag.PRIVATE)),
            ),
        )
        snapshotOf(info, ApiSurface.ALL).types.values.single().members.keys.map { it.name }.sorted() shouldBe
            listOf("pkg", "priv", "pub")
    }

    @Test
    fun `a package-private type is outside the public surface and inside the all surface`() {
        val info = classInfo(access = Access.NONE)
        snapshotOf(info, ApiSurface.PUBLIC).types shouldBe emptyMap()
        snapshotOf(info, ApiSurface.ALL).types.keys shouldBe setOf("com.example.Foo")
    }

    @Test
    fun `the artifact label and type count travel with the snapshot`() {
        val snapshot = ApiSnapshot.of(
            "gson-2.14.0.jar",
            listOf(classInfo(name = "com.example.A"), classInfo(name = "com.example.B")),
        )
        snapshot.artifact shouldBe "gson-2.14.0.jar"
        snapshot.typeCount shouldBe 2
    }

    // -- identity -------------------------------------------------------------------

    @Test
    fun `a method key is the name plus the erased parameter list, not the return type`() {
        val info = classInfo(
            methods = listOf(
                methodInfo("f", listOf(string), returnType = string),
                methodInfo("f", listOf(string), returnType = TypeName.PrimitiveType(JvmPrimitive.LONG)),
            ),
        )
        // Two entries with one key: a class file cannot hold both, and the snapshot
        // collapses them the way a map would, without throwing.
        val members = snapshotOf(info).types.values.single().members
        members.keys.size shouldBe 1
        (members.keys.single() as ApiMethodKey).parameterTypes shouldBe listOf(string)
    }

    @Test
    fun `a field key carries the type, so retyping a field is a different field`() {
        val info = classInfo(fields = listOf(fieldInfo("n", string)))
        val key = snapshotOf(info).types.values.single().members.keys.single()
        key shouldBe ApiFieldKey("n", string)
        key shouldBe ApiFieldKey("n", string)
        (key == ApiFieldKey("n", TypeName.PrimitiveType(JvmPrimitive.LONG))) shouldBe false
    }

    @Test
    fun `members iterate in key order, whatever order the class file listed them`() {
        val forwards = classInfo(
            methods = listOf(methodInfo("a"), methodInfo("b"), methodInfo("c")),
            fields = listOf(fieldInfo("x"), fieldInfo("y")),
        )
        val backwards = classInfo(
            methods = listOf(methodInfo("c"), methodInfo("b"), methodInfo("a")),
            fields = listOf(fieldInfo("y"), fieldInfo("x")),
        )
        val first = snapshotOf(forwards).types.values.single().members.keys.map { it.name }
        val second = snapshotOf(backwards).types.values.single().members.keys.map { it.name }
        first shouldBe second
        // Fields before methods, then by name — the same order `ClassInfo.members` uses,
        // so a diff reads like a member listing.
        first shouldBe listOf("x", "y", "a", "b", "c")
    }

    // -- the spelling of a ref ------------------------------------------------------

    @Test
    fun `a method ref is spelled exactly as every other command spells it`() {
        val info = classInfo(
            methods = listOf(
                methodInfo("f", listOf(string, TypeName.PrimitiveType(JvmPrimitive.LONG))),
                methodInfo(CONSTRUCTOR, listOf(string)),
            ),
        )
        val members = snapshotOf(info).types.values.single().members
        members[ApiMethodKey("f", listOf(string, TypeName.PrimitiveType(JvmPrimitive.LONG)))]!!.canonicalRef shouldBe
            "com.example.Foo#f(int, long)"
        members[ApiMethodKey(CONSTRUCTOR, listOf(string))]!!.canonicalRef shouldBe
            "com.example.Foo#<init>(int)"
    }

    @Test
    fun `a field ref is the type and the name, with no parameter list`() {
        val info = classInfo(fields = listOf(fieldInfo("count")))
        snapshotOf(info).types.values.single().members.values.single().canonicalRef shouldBe
            "com.example.Foo#count"
    }

    @Test
    fun `a Kotlin view puts the Kotlin name and strips the hidden Continuation in the ref`() {
        val continuation = TypeName.ClassType("kotlin.coroutines", listOf("Continuation"))
        val source = methodInfo("f", listOf(continuation), returnType = TypeName.ClassType("java.lang", listOf("Object")))
        val info = classInfo(
            methods = listOf(source),
            kotlinViews = mapOf(
                kotlinViewKey("f", source.descriptor.descriptor) to KotlinMethodView(
                    displayName = "fetch",
                    stripTrailingContinuation = true,
                    markSuspend = true,
                    displayReturn = "kotlin.String",
                ),
            ),
            isKotlin = true,
        )
        val member = snapshotOf(info).types.values.single().members.values.single() as ApiMethod
        member.canonicalRef shouldBe "com.example.Foo#fetch()"
        // The JVM key is untouched by the view: bytecode is still the truth.
        member.key.parameterTypes shouldBe listOf(continuation)
        member.kotlinName shouldBe "fetch"
    }

    @Test
    fun `a constructor never takes a Kotlin view, even when one is offered`() {
        val source = methodInfo(CONSTRUCTOR, listOf(string))
        val info = classInfo(
            methods = listOf(source),
            kotlinViews = mapOf(
                kotlinViewKey(CONSTRUCTOR, source.descriptor.descriptor) to
                    KotlinMethodView(displayName = "made", displayReturn = "com.example.Foo"),
            ),
        )
        val member = snapshotOf(info).types.values.single().members.values.single() as ApiMethod
        member.kotlin shouldBe null
        member.kotlinName shouldBe null
        // `methodRefString` states the invariant; this is the same ref it would print.
        member.canonicalRef shouldBe methodRefString(
            declaring = TypeName.ClassType("com.example", listOf("Foo")),
            member = source,
            view = null,
            disambiguateReturn = false,
        )
    }

    @Test
    fun `a Kotlin property view rides the backing field and names the property`() {
        val info = classInfo(
            fields = listOf(fieldInfo("name", TypeName.ClassType("java.lang", listOf("String")))),
            kotlinProperties = mapOf(
                "name" to KotlinPropertyView(propertyName = "name", isVar = true, displayType = "kotlin.String"),
            ),
            isKotlin = true,
        )
        val member = snapshotOf(info).types.values.single().members.values.single() as ApiField
        member.kotlin?.isVar shouldBe true
        member.kotlinName shouldBe "name"
        member.canonicalRef shouldBe "com.example.Foo#name"
    }

    // -- the facts a diff reads ------------------------------------------------------

    @Test
    fun `every fact the differ compares is read off the model`() {
        val view = KotlinMethodView(displayName = "f", defaultArgIndices = setOf(1))
        val source = methodInfo(
            "f",
            listOf(string),
            returnType = TypeName.ClassType("java.lang", listOf("String")),
            // `varargs` and `synchronized` are access bits on a method, not fields.
            access = Access.of(AccessFlag.PUBLIC, AccessFlag.FINAL, AccessFlag.SYNCHRONIZED, AccessFlag.VARARGS),
        ).copy(
            parameterNames = listOf("a"),
            throwsTypes = listOf(TypeName.ClassType("java.io", listOf("IOException"))),
            annotationDefault = "1",
        )
        val info = classInfo(
            methods = listOf(source),
            kotlinViews = mapOf(kotlinViewKey("f", source.descriptor.descriptor) to view),
            superclass = TypeName.ClassType("java.lang", listOf("Object")),
            interfaces = listOf(TypeName.ClassType("java.io", listOf("Serializable"))),
            deprecated = true,
        )
        val type = snapshotOf(info).types.values.single()
        val method = type.members.values.single() as ApiMethod
        method.access.has(AccessFlag.FINAL) shouldBe true
        method.varargs shouldBe true
        method.synchronized shouldBe true
        method.native shouldBe false
        method.genericSignature shouldBe null
        method.annotationDefault shouldBe "1"
        method.parameterNames shouldBe listOf("a")
        method.throwsTypes.map { it.binaryName } shouldBe listOf("java.io.IOException")
        method.deprecated shouldBe false
        method.kotlin?.defaultArgIndices shouldBe setOf(1)
        type.deprecated shouldBe true
        type.superclass?.binaryName shouldBe "java.lang.Object"
        type.interfaces.map { it.binaryName } shouldBe listOf("java.io.Serializable")
        type.isKotlin shouldBe false
        type.canonicalRef shouldBe "com.example.Foo"
    }

    @Test
    fun `a deprecated member keeps its own deprecation flag`() {
        val info = classInfo(
            methods = listOf(methodInfo("old").copy(deprecated = true)),
            fields = listOf(fieldInfo("stale").copy(deprecated = true)),
        )
        val members = snapshotOf(info).types.values.single().members
        members.values.all { it.deprecated } shouldBe true
    }

    @Test
    fun `an empty snapshot is legal and holds nothing`() {
        val empty = ApiSnapshot.of("empty.jar", emptyList())
        empty.types shouldBe emptyMap()
        empty.typeCount shouldBe 0
        ApiDiffer.diff(empty, empty).findings shouldBe emptyList()
    }

    @Test
    fun `visibility is read through the access mask, never from a stored field`() {
        val info = classInfo(
            access = Access.of(AccessFlag.PUBLIC, AccessFlag.FINAL),
            methods = listOf(
                methodInfo("a", access = Access.of(AccessFlag.PROTECTED)),
                methodInfo("b", access = Access.of(AccessFlag.PRIVATE)),
                methodInfo("c", access = Access.NONE),
            ),
        )
        val members = snapshotOf(info, ApiSurface.ALL).types.values.single().members
        (members[ApiMethodKey("a", emptyList())] as ApiMethod).access.visibility shouldBe Visibility.PROTECTED
        (members[ApiMethodKey("b", emptyList())] as ApiMethod).access.visibility shouldBe Visibility.PRIVATE
        (members[ApiMethodKey("c", emptyList())] as ApiMethod).access.visibility shouldBe
            Visibility.PACKAGE_PRIVATE
    }
}
