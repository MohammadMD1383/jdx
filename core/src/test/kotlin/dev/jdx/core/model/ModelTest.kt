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
    }

    @Test
    fun `a warning carries a code a message and an optional subject`() {
        val warning = Warning(WarningCode.DUPLICATE_FQN, "duplicate FQN in two artifacts", "com.example.Foo")
        warning.code shouldBe WarningCode.DUPLICATE_FQN
        warning.subject shouldBe "com.example.Foo"
    }
}
