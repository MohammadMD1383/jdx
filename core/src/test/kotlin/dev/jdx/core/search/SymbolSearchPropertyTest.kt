package dev.jdx.core.search

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative coverage for the search matcher (T-017, TESTING.md §4): the
 * matcher meets adversarial strings from every compiler and build tool, so
 * its laws are pinned by properties, not only by hand-picked names.
 */
class SymbolSearchPropertyTest {

    private val nastyChars: List<Char> =
        ('a'..'z').toList() + ('A'..'Z').toList() + ('0'..'9').toList() +
            listOf('*', '?', '[', ']', '{', '}', '.', '$', '/', ';', '(', ')', '^', '+', '|', '\\', '-', '_', 'ü', 'ß')

    private fun arbNastyString(maxLength: Int = 24): Arb<String> =
        Arb.list(Arb.of(nastyChars), 0..maxLength).map { it.joinToString("") }

    private fun arbLiteralSegment(): Arb<String> =
        Arb.list(
            Arb.of(('a'..'z').toList() + ('A'..'Z').toList() + ('0'..'9').toList()),
            1..12,
        ).map { it.joinToString("") }

    @Test
    fun `matchers never throw on adversarial input`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, arbNastyString(), arbNastyString()) { pattern, value ->
            SymbolSearch.matchesGlob(pattern, value)
            SymbolSearch.matchesRegex(pattern, value)
            SymbolSearch.matchesSubstring(pattern, value)
            SymbolSearch.camelHumpMatches(pattern, value)
            SymbolSearch.fuzzyMatches(pattern, value)
            SymbolSearch.isGlobPattern(pattern)
            SymbolSearch.isValidRegex(pattern)
        }
    }

    @Test
    fun `a literal glob matches if and only if equal`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, arbLiteralSegment(), arbLiteralSegment()) { pattern, value ->
            (SymbolSearch.matchesGlob(pattern, value)) shouldBeEqual (pattern == value)
        }
    }

    @Test
    fun `uppercase initials of a camel name always hump-match it`(): Unit = runBlocking {
        // Builds `AbcDefGhi`-shaped names, then takes their hump initials (`ADG`).
        val arbCamelName: Arb<String> = Arb.list(arbLiteralSegment().filter { it[0].isLetter() }, 1..4)
            .map { parts -> parts.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } }
        checkAll(JDX_PROPERTY_ITERATIONS, arbCamelName) { name ->
            val initials = name.filter { it.isUpperCase() }
            assert(SymbolSearch.camelHumpMatches(initials, name)) { "initials $initials miss $name" }
        }
    }

    @Test
    fun `levenshtein is symmetric and zero exactly on equality`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, arbNastyString(12), arbNastyString(12)) { first, second ->
            val forward = SymbolSearch.levenshtein(first, second)
            val backward = SymbolSearch.levenshtein(second, first)
            assert(forward == backward) { "$first vs $second asymmetric" }
            assert((forward == 0) == (first == second)) { "zero-distance law fails for $first vs $second" }
        }
    }

    @Test
    fun `glob star is a superset of the literal pattern`(): Unit = runBlocking {
        // `*literal*` contains every occurrence the bare literal has.
        checkAll(JDX_PROPERTY_ITERATIONS, arbLiteralSegment(), arbNastyString()) { literal, value ->
            if (value.contains(literal)) {
                assert(SymbolSearch.matchesGlob("*$literal*", value)) { "*$literal* misses $value" }
            }
        }
    }

    @Test
    fun `regex and glob agree on plain literals`(): Unit = runBlocking {
        val arbDotted: Arb<String> = Arb.list(arbLiteralSegment(), 1..3).map { it.joinToString(".") }
        checkAll(JDX_PROPERTY_ITERATIONS, arbDotted, arbDotted) { pattern, value ->
            // A literal dotted name as a regex still contains-matches like a substring check.
            val viaRegex = SymbolSearch.matchesRegex(Regex.escape(pattern), value)
            (viaRegex) shouldBeEqual value.contains(pattern)
        }
    }

    private infix fun Boolean.shouldBeEqual(expected: Boolean) {
        assert(this == expected) { "expected $expected" }
    }
}
