package dev.jdx.core.search

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pure name-matching behind `jdx search` (T-017): glob, regex, camel-hump and
 * fuzzy matching over JVM binary names. No disk, no jars — tier 1.
 */
class SymbolSearchTest {

    // -- glob detection -------------------------------------------------------

    @Test
    fun `star question bracket and brace mark a glob`() {
        SymbolSearch.isGlobPattern("*Http*Client") shouldBe true
        SymbolSearch.isGlobPattern("Hash?ap") shouldBe true
        SymbolSearch.isGlobPattern("Hash[MX]ap") shouldBe true
        SymbolSearch.isGlobPattern("com/{google,sun}/**") shouldBe true
    }

    @Test
    fun `plain names and camel humps are not globs`() {
        SymbolSearch.isGlobPattern("HashMap") shouldBe false
        SymbolSearch.isGlobPattern("HMap") shouldBe false
        SymbolSearch.isGlobPattern("com.google.gson.Gson") shouldBe false
        SymbolSearch.isGlobPattern("") shouldBe false
    }

    // -- glob matching --------------------------------------------------------

    @Test
    fun `star glob matches across dots and dollars`() {
        SymbolSearch.matchesGlob("*Http*Client", "com.example.HttpServerClient") shouldBe true
        SymbolSearch.matchesGlob("*Http*Client", "com.example.FtpServer") shouldBe false
    }

    @Test
    fun `glob dots are literal, not regex wildcards`() {
        SymbolSearch.matchesGlob("com.example.*", "comXexampleYFoo") shouldBe false
        SymbolSearch.matchesGlob("com.example.*", "com.example.Foo") shouldBe true
    }

    @Test
    fun `question matches exactly one character`() {
        SymbolSearch.matchesGlob("Hash?ap", "HashMap") shouldBe true
        SymbolSearch.matchesGlob("Hash?ap", "HashAp") shouldBe false
    }

    @Test
    fun `bracket class matches one of the set`() {
        SymbolSearch.matchesGlob("Hash[MS]ap", "HashMap") shouldBe true
        SymbolSearch.matchesGlob("Hash[MS]ap", "HashSap") shouldBe true
        SymbolSearch.matchesGlob("Hash[MS]ap", "HashTap") shouldBe false
    }

    @Test
    fun `degenerate classes never throw and stay literal`() {
        // Found by the never-throws property (T-017): `[^]` is an unclosed
        // class in Java regex, and a leading `^` would negate in regex while
        // globs mean it literally.
        SymbolSearch.matchesGlob("[]", "[]") shouldBe true
        SymbolSearch.matchesGlob("[]", "x") shouldBe false
        SymbolSearch.matchesGlob("[^]", "^") shouldBe true
        SymbolSearch.matchesGlob("[^a]", "^") shouldBe true
        SymbolSearch.matchesGlob("[^a]", "a") shouldBe true
        SymbolSearch.matchesGlob("[^a]", "b") shouldBe false
        SymbolSearch.matchesGlob("[F[ßN]", "F") shouldBe true
        SymbolSearch.matchesGlob("[F[ßN]", "[") shouldBe true
        SymbolSearch.matchesGlob("[F[ßN]", "x") shouldBe false
    }

    @Test
    fun `dashes are ranges only between ascending alphanumerics`() {
        // Found by the never-throws property: `[Q-9]` is an illegal reversed
        // range in Java regex, but a literal set in glob semantics.
        SymbolSearch.matchesGlob("[a-z]", "m") shouldBe true
        SymbolSearch.matchesGlob("[a-z]", "5") shouldBe false
        SymbolSearch.matchesGlob("[z-a]", "-") shouldBe true
        SymbolSearch.matchesGlob("[z-a]", "m") shouldBe false
        SymbolSearch.matchesGlob("[+P8hQ|-9j9]", "-") shouldBe true
        SymbolSearch.matchesGlob("[+P8hQ|-9j9]", "Q") shouldBe true
        SymbolSearch.matchesGlob("[-a]", "-") shouldBe true
        SymbolSearch.matchesGlob("[a-]", "-") shouldBe true
    }

    // -- camel-hump matching --------------------------------------------------

    @Test
    fun `hump initials match in order`() {
        SymbolSearch.camelHumpMatches("HMap", "HashMap") shouldBe true
        SymbolSearch.camelHumpMatches("HMap", "HashSet") shouldBe false
        SymbolSearch.camelHumpMatches("NPE", "NullPointerException") shouldBe true
    }

    @Test
    fun `lowercase prefix matches case-insensitively`() {
        SymbolSearch.camelHumpMatches("gJson", "getJson") shouldBe true
        SymbolSearch.camelHumpMatches("hash", "HashMap") shouldBe true
    }

    @Test
    fun `hump pattern does not match out of order`() {
        SymbolSearch.camelHumpMatches("MapH", "HashMap") shouldBe false
    }

    @Test
    fun `empty pattern matches everything`() {
        SymbolSearch.camelHumpMatches("", "HashMap") shouldBe true
    }

    // -- regex matching -------------------------------------------------------

    @Test
    fun `regex matches against the full name`() {
        SymbolSearch.matchesRegex(".*Http.*Client", "com.example.HttpServerClient") shouldBe true
        SymbolSearch.matchesRegex(".*Http.*Client", "com.example.Ftp") shouldBe false
    }

    @Test
    fun `invalid regex never throws`() {
        SymbolSearch.matchesRegex("([", "HashMap") shouldBe false
    }

    // -- levenshtein ----------------------------------------------------------

    @Test
    fun `levenshtein distances are exact`() {
        SymbolSearch.levenshtein("", "") shouldBe 0
        SymbolSearch.levenshtein("kitten", "sitting") shouldBe 3
        SymbolSearch.levenshtein("Gson", "Gson") shouldBe 0
        SymbolSearch.levenshtein("JsonParse", "JsonParser") shouldBe 1
    }

    @Test
    fun `fuzzy match tolerates two edits on simple names`() {
        SymbolSearch.fuzzyMatches("JsonParsr", "JsonParser") shouldBe true
        SymbolSearch.fuzzyMatches("JsonXyz", "JsonParser") shouldBe false
    }
}
