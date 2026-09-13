package dev.jdx.core.ref

import dev.jdx.core.model.JvmPrimitive
import dev.jdx.core.model.MavenCoordinate
import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.PackageSymbolRef
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.TypeSymbolRef
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Tests for [SymbolRefParser] — one behaviour each, covering every row of the
 * "Accepted forms" table in PROPOSAL.md §6 plus the error contract: failures are
 * structured values carrying a position, never exceptions.
 */
class SymbolRefParserTest {

    // ---------------------------------------------------------------- type refs

    @Test
    fun `a short type name parses to an unresolved default-package type`() {
        parseOk("Gson") shouldBe TypeSymbolRef(TypeName.ClassType("", listOf("Gson")))
    }

    @Test
    fun `a fully qualified type splits package from class`() {
        parseOk("com.google.gson.Gson") shouldBe
            TypeSymbolRef(TypeName.ClassType("com.google.gson", listOf("Gson")))
    }

    @Test
    fun `a primitive keyword parses as a primitive type ref`() {
        parseOk("int") shouldBe TypeSymbolRef(TypeName.PrimitiveType(JvmPrimitive.INT))
    }

    @Test
    fun `an anonymous class parses as a digit nesting segment`() {
        parseOk("com.google.gson.Gson\$1") shouldBe
            TypeSymbolRef(TypeName.ClassType("com.google.gson", listOf("Gson", "1")))
    }

    @Test
    fun `dollar and dot nesting normalise identically for short names`() {
        parseOk("Map.Entry") shouldBe parseOk("Map\$Entry")
        parseOk("Map.Entry") shouldBe TypeSymbolRef(TypeName.ClassType("", listOf("Map", "Entry")))
    }

    @Test
    fun `dollar and dot nesting normalise identically for qualified names`() {
        parseOk("java.util.Map.Entry") shouldBe parseOk("java.util.Map\$Entry")
        parseOk("java.util.Map.Entry") shouldBe
            TypeSymbolRef(TypeName.ClassType("java.util", listOf("Map", "Entry")))
    }

    @Test
    fun `a maven coordinate prefix scopes the type to one artifact`() {
        parseOk("com.google.code.gson:gson:2.14.0/com.google.gson.Gson") shouldBe
            TypeSymbolRef(
                TypeName.ClassType("com.google.gson", listOf("Gson")),
                MavenCoordinate("com.google.code.gson", "gson", "2.14.0"),
            )
    }

    @Test
    fun `a trailing star makes a package glob`() {
        parseOk("com.google.gson.*") shouldBe PackageSymbolRef("com.google.gson.*")
    }

    @Test
    fun `a lone star is a package glob`() {
        parseOk("*") shouldBe PackageSymbolRef("*")
    }

    @Test
    fun `surrounding whitespace is forgiven`() {
        parseOk("  Gson#toJson  ") shouldBe parseOk("Gson#toJson")
    }

    // ---------------------------------------------------------------- members

    @Test
    fun `a bare member name means all overloads`() {
        parseOk("Gson#toJson") shouldBe
            MemberSymbolRef(TypeName.ClassType("", listOf("Gson")), "toJson", parameterTypes = null)
    }

    @Test
    fun `a field reference is a member without a parameter list`() {
        parseOk("Gson#excluder") shouldBe
            MemberSymbolRef(TypeName.ClassType("", listOf("Gson")), "excluder", parameterTypes = null)
    }

    @Test
    fun `simple parameter names parse as unresolved types`() {
        parseOk("Gson#toJson(Object)") shouldBe
            MemberSymbolRef(
                TypeName.ClassType("", listOf("Gson")),
                "toJson",
                listOf(TypeName.ClassType("", listOf("Object"))),
            )
    }

    @Test
    fun `fully qualified parameters parse with their package`() {
        parseOk("Gson#toJson(java.lang.Object)") shouldBe
            MemberSymbolRef(
                TypeName.ClassType("", listOf("Gson")),
                "toJson",
                listOf(TypeName.ClassType("java.lang", listOf("Object"))),
            )
    }

    @Test
    fun `a return type suffix is recorded`() {
        parseOk("Gson#toJson(Object):String") shouldBe
            MemberSymbolRef(
                TypeName.ClassType("", listOf("Gson")),
                "toJson",
                listOf(TypeName.ClassType("", listOf("Object"))),
                returnType = TypeName.ClassType("", listOf("String")),
            )
    }

    @Test
    fun `an exact jvm descriptor specifies parameters and return type`() {
        parseOk("Gson#toJson(Ljava/lang/Object;)Ljava/lang/String;") shouldBe
            MemberSymbolRef(
                TypeName.ClassType("", listOf("Gson")),
                "toJson",
                listOf(TypeName.ClassType("java.lang", listOf("Object"))),
                returnType = TypeName.ClassType("java.lang", listOf("String")),
            )
    }

    @Test
    fun `a descriptor accepts arrays and primitives`() {
        parseOk("Gson#m([Ljava/lang/String;I[[J)V") shouldBe
            MemberSymbolRef(
                TypeName.ClassType("", listOf("Gson")),
                "m",
                listOf(
                    TypeName.ArrayType(TypeName.ClassType("java.lang", listOf("String")), 1),
                    TypeName.PrimitiveType(JvmPrimitive.INT),
                    TypeName.ArrayType(TypeName.PrimitiveType(JvmPrimitive.LONG), 2),
                ),
                returnType = TypeName.PrimitiveType(JvmPrimitive.VOID),
            )
    }

    @Test
    fun `double colon is accepted as the member separator`() {
        parseOk("Gson::toJson") shouldBe parseOk("Gson#toJson")
    }

    @Test
    fun `dot is accepted as the member separator before a parameter list`() {
        parseOk("Gson.toJson(Object)") shouldBe parseOk("Gson#toJson(Object)")
    }

    @Test
    fun `dot separates member from a qualified declaring type`() {
        parseOk("java.util.Map.get(Object)") shouldBe parseOk("java.util.Map#get(Object)")
    }

    @Test
    fun `dot is accepted before init without a parameter list`() {
        parseOk("Gson.<init>") shouldBe parseOk("Gson#<init>")
    }

    @Test
    fun `a constructor reference uses the angle-bracket name`() {
        parseOk("Gson#<init>(Excluder,FieldNamingStrategy)") shouldBe
            MemberSymbolRef(
                TypeName.ClassType("", listOf("Gson")),
                "<init>",
                listOf(
                    TypeName.ClassType("", listOf("Excluder")),
                    TypeName.ClassType("", listOf("FieldNamingStrategy")),
                ),
            )
    }

    @Test
    fun `a static initialiser reference uses the clinit name`() {
        parseOk("Gson#<clinit>") shouldBe
            MemberSymbolRef(TypeName.ClassType("", listOf("Gson")), "<clinit>", parameterTypes = null)
    }

    @Test
    fun `an explicit empty parameter list is zero parameters not all overloads`() {
        val zeroArg = parseOk("Gson#clear()") as MemberSymbolRef
        val allOverloads = parseOk("Gson#clear") as MemberSymbolRef
        zeroArg.parameterTypes shouldBe emptyList()
        allOverloads.parameterTypes shouldBe null
        zeroArg.shouldBeInstanceOf<MemberSymbolRef>()
        zeroArg shouldBe allOverloads.copy(parameterTypes = emptyList())
    }

    @Test
    fun `an ellipsis parameter list means unspecified parameters`() {
        parseOk("Gson#toJson(...)") shouldBe parseOk("Gson#toJson")
    }

    @Test
    fun `varargs parameters parse as arrays`() {
        parseOk("Gson#m(String...)") shouldBe
            MemberSymbolRef(
                TypeName.ClassType("", listOf("Gson")),
                "m",
                listOf(TypeName.ArrayType(TypeName.ClassType("", listOf("String")), 1)),
            )
    }

    @Test
    fun `array parameters parse with their dimensions`() {
        parseOk("Gson#m(int[][])") shouldBe
            MemberSymbolRef(
                TypeName.ClassType("", listOf("Gson")),
                "m",
                listOf(TypeName.ArrayType(TypeName.PrimitiveType(JvmPrimitive.INT), 2)),
            )
        parseOk("Gson#m(java.lang.String[])") shouldBe
            MemberSymbolRef(
                TypeName.ClassType("", listOf("Gson")),
                "m",
                listOf(TypeName.ArrayType(TypeName.ClassType("java.lang", listOf("String")), 1)),
            )
        parseOk("Gson#m(Map.Entry[])") shouldBe
            MemberSymbolRef(
                TypeName.ClassType("", listOf("Gson")),
                "m",
                listOf(TypeName.ArrayType(TypeName.ClassType("", listOf("Map", "Entry")), 1)),
            )
    }

    @Test
    fun `void is accepted as a return type`() {
        parseOk("Gson#m():void") shouldBe
            MemberSymbolRef(
                TypeName.ClassType("", listOf("Gson")),
                "m",
                emptyList(),
                returnType = TypeName.PrimitiveType(JvmPrimitive.VOID),
            )
    }

    @Test
    fun `spaces inside a parameter list are forgiven`() {
        parseOk("Gson#m( Object , int )") shouldBe parseOk("Gson#m(Object,int)")
    }

    @Test
    fun `nested parameter types accept dot and dollar forms identically`() {
        parseOk("Gson#m(Map.Entry)") shouldBe parseOk("Gson#m(Map\$Entry)")
    }

    @Test
    fun `a coordinate prefix combines with a member reference`() {
        parseOk("com.google.code.gson:gson:2.14.0/Gson#toJson(Object)") shouldBe
            MemberSymbolRef(
                TypeName.ClassType("", listOf("Gson")),
                "toJson",
                listOf(TypeName.ClassType("", listOf("Object"))),
                coordinate = MavenCoordinate("com.google.code.gson", "gson", "2.14.0"),
            )
    }

    // ---------------------------------------------------------------- failures

    @Test
    fun `an empty reference fails at position zero`() {
        parseFail("", "empty reference", 0)
        parseFail("   ", "empty reference", 0)
    }

    @Test
    fun `a member without a declaring type fails`() {
        parseFail("#foo", "declaring type", 0)
    }

    @Test
    fun `a separator without a member name fails`() {
        parseFail("Gson#", "member name", 5)
    }

    @Test
    fun `an unclosed parameter list fails at the opening paren`() {
        parseFail("Gson#toJson(", "unclosed", 11)
        parseFail("Gson#toJson(Object", "unclosed", 11)
    }

    @Test
    fun `text after the parameter list fails unless it is a return type`() {
        parseFail("Gson#toJson(Object))", "after", 19)
    }

    @Test
    fun `an empty parameter fails naming its position`() {
        parseFail("Gson#toJson(,)", "empty parameter", 12)
        parseFail("Gson#toJson(,Object)", "empty parameter", 12)
        parseFail("Gson#toJson(Object,)", "empty parameter", 19)
    }

    @Test
    fun `void is rejected as a parameter type`() {
        parseFail("Gson#m(void)", "void is not a parameter type", 7)
    }

    @Test
    fun `a missing return type after the colon fails`() {
        parseFail("Gson#m(Object):", "return type", 15)
    }

    @Test
    fun `a malformed return type names the offending character`() {
        parseFail("Gson#m(Object):int[", "invalid character", 18)
    }

    @Test
    fun `a coordinate prefix needs three non-empty parts`() {
        parseFail("com.google.code.gson:gson/Gson", "coordinate", 25)
        parseFail("com.google.code.gson:gson:/Gson", "coordinate", 26)
        parseFail("/Gson", "coordinate", 0)
    }

    @Test
    fun `a slash outside a coordinate fails`() {
        parseFail("Gson/Foo", "coordinate", 4)
    }

    @Test
    fun `an empty name segment fails naming the offending dot`() {
        parseFail("Map..Entry", "empty name segment", 4)
        parseFail("Gson..toJson(Object)", "empty name segment", 5)
    }

    @Test
    fun `an empty nesting segment fails naming the offending dollar`() {
        parseFail("Map\$", "empty nesting segment", 3)
    }

    @Test
    fun `structural characters are rejected inside member names`() {
        parseFail("Gson#to#json", "invalid character", 7)
        parseFail("Gson#to json", "invalid character", 7)
    }

    @Test
    fun `only init and clinit may start with an angle bracket`() {
        parseFail("Gson#<foo>", "<init>", 5)
    }

    @Test
    fun `a glob pattern cannot carry a member reference`() {
        parseFail("com.google.gson.*#foo", "glob", 0)
    }

    @Test
    fun `a member reference without a declaring type before the paren fails`() {
        parseFail("foo(int)", "declaring type", 3)
    }

    // ---------------------------------------------------------------- helpers

    private fun parseOk(text: String): dev.jdx.core.model.SymbolRef {
        val result = SymbolRefParser.parse(text)
        result.shouldBeInstanceOf<SymbolRefParseResult.Ok>()
        return result.ref
    }

    private fun parseFail(text: String, messagePart: String, position: Int) {
        val result = SymbolRefParser.parse(text)
        result.shouldBeInstanceOf<SymbolRefParseResult.Failure>()
        result.message.lowercase().contains(messagePart.lowercase()) shouldBe true
        result.position shouldBe position
    }
}
