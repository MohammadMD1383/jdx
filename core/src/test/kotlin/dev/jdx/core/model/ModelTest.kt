package dev.jdx.core.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for the remaining model types: [ClassInfo], [MemberInfo], [SymbolRef],
 * [Provenance], [Warning]. Mostly structural — the heavy behaviour lives with
 * TypeName/JvmDescriptor/GenericSignature.
 */
class ModelTest {

    @Test
    fun `warning codes are exactly the documented closed set`() {
        WarningCode.entries.map { it.name }.toSet() shouldBe setOf(
            "SOURCES_VERSION_MISMATCH",
            "DUPLICATE_FQN",
            "UNSUPPORTED_CLASS_VERSION",
            "CORRUPT_CLASS",
            "MULTI_RELEASE_VARIANT",
            "UNRESOLVED_SUPERTYPE",
            "PROJECT_DISCOVERY_FALLBACK",
        )
    }

    @Test
    fun `typekind covers the seven documented kinds`() {
        TypeKind.entries.toSet() shouldBe setOf(
            TypeKind.CLASS,
            TypeKind.INTERFACE,
            TypeKind.ENUM,
            TypeKind.RECORD,
            TypeKind.ANNOTATION,
            TypeKind.OBJECT,
            TypeKind.COMPANION,
        )
    }

    private val voidDescriptor =
        JvmDescriptor.Method(emptyList(), TypeName.PrimitiveType(JvmPrimitive.VOID))

    @Test
    fun `classinfo groups members and picks out constructors`() {
        val constructor = MethodInfo("<init>", voidDescriptor, Access.of(AccessFlag.PUBLIC))
        val field = FieldInfo("size", TypeName.PrimitiveType(JvmPrimitive.INT), Access.NONE)
        val method = MethodInfo(
            "get",
            JvmDescriptor.Method(emptyList(), TypeName.PrimitiveType(JvmPrimitive.INT)),
            Access.NONE,
        )
        val info = ClassInfo(
            name = TypeName.ClassType("java.util", listOf("MyList")),
            kind = TypeKind.CLASS,
            fields = listOf(field),
            methods = listOf(constructor, method),
        )
        info.members shouldBe listOf(field, constructor, method)
        info.constructors shouldBe listOf(constructor)
    }

    @Test
    fun `member info exposes the jvm-declared facts`() {
        val method = MethodInfo(
            name = "toJson",
            descriptor = JvmDescriptor.Method(
                listOf(typeNameFromBinaryName("java.lang.Object")),
                typeNameFromBinaryName("java.lang.String"),
            ),
            access = Access.of(AccessFlag.PUBLIC),
            parameterNames = listOf("src"),
            throwsTypes = listOf(typeNameFromBinaryName("java.io.IOException")),
            deprecated = true,
        )
        method.deprecated shouldBe true
        method.parameterNames shouldBe listOf("src")
        method.throwsTypes.single().fqn shouldBe "java.io.IOException"
        method.access.visibility shouldBe Visibility.PUBLIC
    }

    @Test
    fun `provenance names its artifact origin and location`() {
        val provenance = Provenance(
            artifact = "gson-2.14.0.jar",
            origin = Origin.SOURCES,
            file = "com/google/gson/Gson.java",
            lineRange = 24..31,
        )
        provenance.artifact shouldBe "gson-2.14.0.jar"
        provenance.origin shouldBe Origin.SOURCES
        provenance.lineRange shouldBe 24..31
    }

    @Test
    fun `origin covers the documented source-of-truth ladder`() {
        Origin.entries.toSet() shouldBe setOf(
            Origin.BYTECODE,
            Origin.SOURCES,
            Origin.DECOMPILED_VINEFLOWER,
            Origin.DECOMPILED_JAVAP,
            Origin.JRT,
        )
    }

    @Test
    fun `symbol refs distinguish types members packages and modules`() {
        val refs: List<SymbolRef> = listOf(
            TypeSymbolRef(typeNameFromBinaryName("java.util.HashMap")),
            MemberSymbolRef(
                declaringType = typeNameFromBinaryName("java.util.HashMap"),
                name = "put",
                parameterTypes = listOf(typeNameFromBinaryName("java.lang.Object")),
            ),
            PackageSymbolRef("java.util"),
            ModuleSymbolRef("java.base"),
        )
        refs[0] as TypeSymbolRef
        refs[1] as MemberSymbolRef
        refs[2] as PackageSymbolRef
        refs[3] as ModuleSymbolRef
    }

    @Test
    fun `a maven coordinate prints in group-artifact-version form`() {
        val coordinate = MavenCoordinate("com.google.code.gson", "gson", "2.14.0")
        coordinate.coordinate shouldBe "com.google.code.gson:gson:2.14.0"
        coordinate.group shouldBe "com.google.code.gson"
        coordinate.artifact shouldBe "gson"
        coordinate.version shouldBe "2.14.0"
    }

    @Test
    fun `a warning carries a code a message and an optional subject`() {
        val warning = Warning(WarningCode.DUPLICATE_FQN, "duplicate FQN in two artifacts", "com.example.Foo")
        warning.code shouldBe WarningCode.DUPLICATE_FQN
        warning.subject shouldBe "com.example.Foo"
    }

    // -- T-060: model killers -----------------------------------------------------
    //
    // Optional model slots (annotations, outer class, source file, deprecation)
    // were never read in assertions, so every getter mutant survived.

    @Test
    fun `classinfo exposes annotations outer class source file and access`() {
        val info = ClassInfo(
            name = TypeName.ClassType("com.example", listOf("Outer", "Inner")),
            kind = TypeKind.CLASS,
            access = Access.of(AccessFlag.PUBLIC),
            annotations = listOf(
                AnnotationInfo(typeNameFromBinaryName("java.lang.Deprecated"), emptyMap()),
            ),
            outerClass = typeNameFromBinaryName("com.example.Outer") as TypeName.ClassType,
            sourceFileName = "Outer.java",
        )
        info.access shouldBe Access.of(AccessFlag.PUBLIC)
        info.annotations.single().type.fqn shouldBe "java.lang.Deprecated"
        info.outerClass?.binaryName shouldBe "com.example.Outer"
        info.sourceFileName shouldBe "Outer.java"
        ClassInfo(
            name = TypeName.ClassType("", listOf("Bare")),
            kind = TypeKind.CLASS,
        ).let {
            it.annotations shouldBe emptyList()
            it.outerClass shouldBe null
            it.sourceFileName shouldBe null
        }
    }

    @Test
    fun `field info exposes annotations and deprecation both ways`() {
        val plain = FieldInfo("x", TypeName.PrimitiveType(JvmPrimitive.INT), Access.NONE)
        plain.deprecated shouldBe false
        plain.annotations shouldBe emptyList()
        val marked = plain.copy(
            deprecated = true,
            annotations = listOf(
                AnnotationInfo(typeNameFromBinaryName("java.lang.Deprecated"), emptyMap()),
            ),
        )
        marked.deprecated shouldBe true
        marked.annotations.single().type.fqn shouldBe "java.lang.Deprecated"
    }

    @Test
    fun `method info exposes annotations`() {
        val method = MethodInfo("get", voidDescriptor, Access.NONE)
        method.annotations shouldBe emptyList()
        method.copy(
            annotations = listOf(
                AnnotationInfo(
                    typeNameFromBinaryName("java.lang.Override"),
                    mapOf("value" to "x"),
                ),
            ),
        ).annotations.single().values shouldBe mapOf("value" to "x")
    }
}
