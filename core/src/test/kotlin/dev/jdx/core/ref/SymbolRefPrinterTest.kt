package dev.jdx.core.ref

import dev.jdx.core.model.JvmPrimitive
import dev.jdx.core.model.MavenCoordinate
import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.ModuleSymbolRef
import dev.jdx.core.model.PackageSymbolRef
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.TypeSymbolRef
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for [SymbolRefPrinter] — the canonical output form of PROPOSAL.md §6:
 * `$`-joined nesting for types (CLAUDE.md §6), dotted FQ parameter types,
 * `, `-separated, `:`-prefixed return type, `group:artifact:version/` coordinate.
 */
class SymbolRefPrinterTest {

    @Test
    fun `a top-level type prints its binary name`() {
        SymbolRefPrinter.print(TypeSymbolRef(TypeName.ClassType("com.google.gson", listOf("Gson")))) shouldBe
            "com.google.gson.Gson"
    }

    @Test
    fun `a default-package type prints without a leading dot`() {
        SymbolRefPrinter.print(TypeSymbolRef(TypeName.ClassType("", listOf("Gson")))) shouldBe "Gson"
    }

    @Test
    fun `a nested type prints the dollar form`() {
        SymbolRefPrinter.print(
            TypeSymbolRef(TypeName.ClassType("com.google.gson", listOf("Gson", "FutureTypeAdapter"))),
        ) shouldBe "com.google.gson.Gson\$FutureTypeAdapter"
    }

    @Test
    fun `an anonymous class prints its digit segment`() {
        SymbolRefPrinter.print(
            TypeSymbolRef(TypeName.ClassType("com.google.gson", listOf("Gson", "1"))),
        ) shouldBe "com.google.gson.Gson\$1"
    }

    @Test
    fun `a member without a parameter list prints bare`() {
        SymbolRefPrinter.print(member("com.google.gson", "Gson", "toJson")) shouldBe
            "com.google.gson.Gson#toJson"
    }

    @Test
    fun `a zero-parameter member prints empty parens`() {
        SymbolRefPrinter.print(
            member("java.util", "ArrayList", "clear", parameterTypes = emptyList()),
        ) shouldBe "java.util.ArrayList#clear()"
    }

    @Test
    fun `parameters print as dotted fqns separated by comma-space`() {
        SymbolRefPrinter.print(
            member(
                "com.google.gson", "Gson", "toJson",
                parameterTypes = listOf(
                    TypeName.ClassType("java.lang", listOf("Object")),
                    TypeName.ClassType("java.lang.reflect", listOf("Type")),
                ),
            ),
        ) shouldBe "com.google.gson.Gson#toJson(java.lang.Object, java.lang.reflect.Type)"
    }

    @Test
    fun `a simple unresolved parameter prints as written`() {
        SymbolRefPrinter.print(
            member("com.google.gson", "Gson", "toJson", parameterTypes = listOf(TypeName.ClassType("", listOf("Object")))),
        ) shouldBe "com.google.gson.Gson#toJson(Object)"
    }

    @Test
    fun `a return type prints after a colon`() {
        SymbolRefPrinter.print(
            member(
                "com.google.gson", "Gson", "toJson",
                parameterTypes = listOf(TypeName.ClassType("java.lang", listOf("Object"))),
                returnType = TypeName.ClassType("java.lang", listOf("String")),
            ),
        ) shouldBe "com.google.gson.Gson#toJson(java.lang.Object):java.lang.String"
    }

    @Test
    fun `primitive and array parameters print in source form`() {
        SymbolRefPrinter.print(
            member(
                "", "Main", "main",
                parameterTypes = listOf(TypeName.ArrayType(TypeName.ClassType("java.lang", listOf("String")), 1)),
                returnType = TypeName.PrimitiveType(JvmPrimitive.VOID),
            ),
        ) shouldBe "Main#main(java.lang.String[]):void"
    }

    @Test
    fun `multi-dimensional arrays print all dimensions`() {
        SymbolRefPrinter.print(
            member("", "Grid", "at", parameterTypes = listOf(
                dev.jdx.core.model.arrayTypeName(TypeName.PrimitiveType(JvmPrimitive.INT), 2),
            )),
        ) shouldBe "Grid#at(int[][])"
    }

    @Test
    fun `a constructor keeps its angle-bracket name`() {
        SymbolRefPrinter.print(
            member("com.google.gson", "Gson", "<init>", parameterTypes = listOf(
                TypeName.ClassType("", listOf("Excluder")),
                TypeName.ClassType("", listOf("FieldNamingStrategy")),
            )),
        ) shouldBe "com.google.gson.Gson#<init>(Excluder, FieldNamingStrategy)"
    }

    @Test
    fun `a coordinate prefix prints before the type`() {
        SymbolRefPrinter.print(
            MemberSymbolRef(
                TypeName.ClassType("com.google.gson", listOf("Gson")),
                "toJson",
                listOf(TypeName.ClassType("java.lang", listOf("Object"))),
                coordinate = MavenCoordinate("com.google.code.gson", "gson", "2.14.0"),
            ),
        ) shouldBe "com.google.code.gson:gson:2.14.0/com.google.gson.Gson#toJson(java.lang.Object)"
    }

    @Test
    fun `a package glob prints verbatim`() {
        SymbolRefPrinter.print(PackageSymbolRef("com.google.gson.*")) shouldBe "com.google.gson.*"
    }

    @Test
    fun `a module ref prints its name`() {
        SymbolRefPrinter.print(ModuleSymbolRef("java.base")) shouldBe "java.base"
    }

    private fun member(
        pkg: String,
        top: String,
        name: String,
        parameterTypes: List<TypeName>? = null,
        returnType: TypeName? = null,
    ): MemberSymbolRef =
        MemberSymbolRef(TypeName.ClassType(pkg, listOf(top)), name, parameterTypes, returnType)
}
