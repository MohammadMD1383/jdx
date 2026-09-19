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

    // ---------------------------------------------------------------- T-060: failure positions
    //
    // Every arithmetic term in a `fail(..., base + ...)` position must be pinned:
    // PIT's Math mutants (`+1` to `-1`) survive whenever a test asserts the message
    // but not the exact position. Each test below names the mutant it kills.

    @Test
    fun `a coordinate with nothing after the slash fails at the end`() {
        // `restBase = base + slash + 1`: both additions are pinned.
        parseFail("g:a:v/", "missing type", 6)
    }

    @Test
    fun `a second slash after a coordinate fails naming its position`() {
        // `restBase + secondSlash`.
        parseFail("g:a:v/A/B", "only valid", 7)
    }

    @Test
    fun `an all-blank coordinate is rejected like any other blank part`() {
        // The `parts.any { it.isBlank() }` lambda: negating it still fails honest
        // blanks but lets an all-blank coordinate through — this test pins it.
        // (Blank means whitespace: `::` would be member syntax, not a coordinate;
        // the leading space shifts `base`, pinning `base + slash` too.)
        parseFail(" : : /T", "group:artifact:version", 5)
    }

    @Test
    fun `a leading dot before the paren fails at the paren`() {
        // `lastDot <= 0`: `0 < 0` is false, so `<` proceeds and reports elsewhere.
        parseFail(".foo()", "before '('", 4)
    }

    @Test
    fun `a package pattern rejects structural characters naming the position`() {
        // `base + k` in parsePackagePattern.
        parseFail("com.*;", "invalid character", 5)
    }

    @Test
    fun `a valid package pattern parses`() {
        // Valid input must NOT fail: kills the negated validity conditional.
        parseOk("com.foo.*") shouldBe PackageSymbolRef("com.foo.*")
    }

    @Test
    fun `a padded parameter reports errors past its leading space`() {
        // `paramBase` carries `paramStartInPiece`: zero in every existing test.
        // (`!` is legal in names — the poison character here is `;`.)
        parseFail("Gson#m( vo;d)", "invalid character", 10)
        parseFail("Gson#m(Object, vo;d)", "invalid character", 17)
    }

    @Test
    fun `trailing text after the parameter list counts its leading space`() {
        // `afterLead` in the "unexpected text" position.
        parseFail("Gson#m() x", "unexpected text", 9)
    }

    @Test
    fun `a blank return type counts the space before the colon`() {
        // `afterLead` in the "missing return type" position. (Trailing spaces
        // are trimmed by `parse`, so `returnLead` there is always zero — an
        // equivalent mutant, not a gap.)
        parseFail("Gson#m() :", "missing return type", 10)
    }

    @Test
    fun `a malformed return type counts the colon lead`() {
        // `returnLead` in the nested return-type base.
        parseFail("Gson#m(): vo;d", "invalid character", 12)
    }

    @Test
    fun `an invalid character in a later nesting segment names its position`() {
        // `base + idx` / `idx += segment.length` need two `$` segments to differ.
        parseFail("A\$B\$c;d", "invalid character", 5)
    }

    @Test
    fun `an invalid character in a later dot segment names its position`() {
        // `base + cursor` past the first dot segment.
        parseFail("com.fo;o.Bar", "invalid character", 6)
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
