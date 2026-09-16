package dev.jdx.core.ref

import dev.jdx.core.gen.arbRoundTripRef
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Property tests for the symbol-ref parser/printer pair (TESTING.md §4): the
 * round-trip fixed point, normalisation idempotence over generous input variants,
 * and the never-throws guarantee for arbitrary input.
 */
class SymbolRefPropertyTest {

    @Test
    fun `parse of print is a fixed point for generated refs`() = runBlocking<Unit> {
        checkAll(1000, arbRoundTripRef()) { ref ->
            val printed = SymbolRefPrinter.print(ref)
            SymbolRefParser.parse(printed) shouldBeOk ref
        }
    }

    @Test
    fun `generous variants of a canonical ref parse to the same ref`() = runBlocking<Unit> {
        checkAll(1000, arbRoundTripRef()) { ref ->
            val canonical = SymbolRefPrinter.print(ref)
            val variants = buildList {
                add(canonical)
                add(canonical.replace(", ", ","))
                if ('#' in canonical) add(canonical.replaceFirst("#", "::"))
                val isParenForm = ref is dev.jdx.core.model.MemberSymbolRef &&
                    (ref.parameterTypes != null || ref.name.startsWith("<"))
                if (isParenForm) add(canonical.replaceFirst("#", "."))
            }
            variants.forEach { variant ->
                SymbolRefParser.parse(variant) shouldBeOk ref
            }
        }
    }

    @Test
    fun `print of parse is stable for arbitrary input`() = runBlocking<Unit> {
        // Idempotent normalisation (TESTING.md §4): when arbitrary text parses, printing
        // the result and parsing again must yield the identical ref — never a Failure,
        // never a different ref.
        val structuralAlphabet = listOf(
            '#', ':', '/', '.', '$', '(', ')', ',', ';', '[', ']', '<', '>', '*', ' ', 'a', 'B', '1', '-',
        )
        val arbNastyString = Arb.list(Arb.of(structuralAlphabet), 0..40)
            .map { chars -> chars.joinToString("") }
        checkAll(1000, arbNastyString) { text ->
            val first = SymbolRefParser.parse(text)
            if (first is SymbolRefParseResult.Ok) {
                val once = SymbolRefPrinter.print(first.ref)
                val second = SymbolRefParser.parse(once)
                second shouldBeOk first.ref
                SymbolRefPrinter.print((second as SymbolRefParseResult.Ok).ref) shouldBeEqual once
            }
        }
    }

    private infix fun String.shouldBeEqual(expected: String) {
        if (this != expected) {
            fail<Unit>("expected stable print '$expected' but was '$this'")
        }
    }

    @Test
    fun `a malformed reference never throws`() = runBlocking<Unit> {
        // Any string built from the grammar's structural alphabet must parse to
        // Ok or Failure — never an exception (D-015: exit code 3, not a stack trace).
        val structuralAlphabet = listOf(
            '#', ':', '/', '.', '$', '(', ')', ',', ';', '[', ']', '<', '>', '*', ' ', 'a', 'B', '1', '-',
        )
        val arbNastyString = Arb.list(Arb.of(structuralAlphabet), 0..40)
            .map { chars -> chars.joinToString("") }
        checkAll(1000, arbNastyString) { text ->
            parseWithoutThrowing(text)
        }
    }

    @Test
    fun `mutations of a valid reference never throw`() = runBlocking<Unit> {
        val nastyChars = listOf('#', ':', '/', '.', '$', '(', ')', ',', ';', '[', ']', '<', '>', '*', ' ')
        checkAll(
            1000,
            Arb.bind(arbRoundTripRef(), Arb.int(0..3), Arb.int()) { ref, kind, seed -> Triple(ref, kind, seed) },
        ) { (ref, mutationKind, seed) ->
            val text = SymbolRefPrinter.print(ref)
            val position = if (text.isEmpty()) 0 else seed.mod(text.length)
            val mutated = when (mutationKind) {
                0 -> if (text.isEmpty()) text else text.removeRange(position, position + 1)
                1 -> buildString {
                        append(text, 0, position)
                        append(nastyChars[seed.mod(nastyChars.size)])
                        append(text, position, text.length)
                    }
                2 -> text.substring(0, position)
                else -> text.repeat(2)
            }
            parseWithoutThrowing(mutated)
        }
    }

    // ---------------------------------------------------------------- helpers

    private infix fun SymbolRefParseResult.shouldBeOk(expected: dev.jdx.core.model.SymbolRef) {
        if (this !is SymbolRefParseResult.Ok || this.ref != expected) {
            fail<Unit>("expected Ok($expected) but was $this")
        }
    }

    private fun parseWithoutThrowing(text: String) {
        try {
            SymbolRefParser.parse(text)
        } catch (t: Throwable) {
            fail<Unit>("parse threw on ${text.escape()}: $t")
        }
    }

    private fun String.escape(): String = replace("\n", "\\n").replace("\t", "\\t")
}
