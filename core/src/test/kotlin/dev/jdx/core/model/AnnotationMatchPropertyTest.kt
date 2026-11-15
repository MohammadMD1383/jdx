package dev.jdx.core.model

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative laws for annotation where-matching (issue #26): a clause built
 * from a rendered value always matches that annotation, and renderUse is
 * deterministic under key-order shuffle.
 */
class AnnotationMatchPropertyTest {

    private fun arbKey(): Arb<String> = Arb.string(1..8, Codepoint.az())

    private fun arbRenderedValue(): Arb<String> = Arb.bind(Arb.boolean(), Arb.string(0..6, Codepoint.az()), Arb.int(0..999)) {
            quoted, text, number ->
        if (quoted) "\"$text\"" else number.toString()
    }

    private fun arbPairs(): Arb<Map<String, String>> =
        Arb.bind(Arb.list(arbKey(), 0..5), Arb.list(arbRenderedValue(), 0..5)) { keys, values ->
            keys.zip(values).toMap()
        }

    @Test
    fun `clause taken from rendered values always matches`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, arbPairs()) { pairs ->
            if (pairs.isEmpty()) return@checkAll
            val annot = AnnotationInfo(typeNameFromBinaryName("com.example.Mark"), pairs)
            val clauses = pairs.entries.map { (key, value) -> AnnotationWhere(key, value) }
            assert(annot.matchesWhere(clauses)) {
                "self-derived clauses must match: $pairs"
            }
            val unquoted = pairs.entries.map { (key, value) ->
                val wanted = if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value.substring(1, value.length - 1)
                } else {
                    value
                }
                AnnotationWhere(key, wanted)
            }
            assert(annot.matchesWhere(unquoted)) {
                "unquoted clauses must match quoted stored values: $pairs"
            }
        }
    }

    @Test
    fun `renderUse is deterministic under key shuffle`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, arbPairs()) { pairs ->
            val a = AnnotationInfo(typeNameFromBinaryName("com.example.Mark"), pairs)
            val shuffled = AnnotationInfo(
                typeNameFromBinaryName("com.example.Mark"),
                pairs.entries.shuffled().associate { it.key to it.value },
            )
            assert(a.renderUse() == shuffled.renderUse()) {
                "renderUse must sort keys: ${a.renderUse()} vs ${shuffled.renderUse()}"
            }
        }
    }
}
