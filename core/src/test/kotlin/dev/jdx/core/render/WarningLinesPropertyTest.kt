package dev.jdx.core.render

import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative family for [WarningLines]: peel is lossless for non-warning
 * lines, and cap never emits more than `maxPerCode` detail lines plus one
 * overflow summary per code.
 */
class WarningLinesPropertyTest {

    private val answerLine: Arb<String> = Arb.string(1..24, Codepoint.az()).map { "answer-$it" }
    private val warningLine: Arb<String> = Arb.string(1..12, Codepoint.az()).map { code ->
        "warning ${code.uppercase()}: detail-$code"
    }

    @Test
    fun `partition round-trips answer lines and collects every warning`(): Unit = runBlocking {
        checkAll(100, Arb.list(answerLine, 0..8), Arb.list(warningLine, 0..8)) { answers, warnings ->
            // Interleave: answers, then warnings, then a provenance footer — the
            // shape renderers actually emit.
            val rendered = (answers + warnings + listOf("source: jar (bytecode)")).joinToString("\n")
            val part = WarningLines.partition(rendered)
            part.answer.lines().filter { it.isNotEmpty() } shouldBe (answers + listOf("source: jar (bytecode)"))
            part.warningLines shouldBe warnings
        }
    }

    @Test
    fun `cap never exceeds maxPerCode detail lines plus one summary per code`(): Unit = runBlocking {
        checkAll(100, Arb.list(warningLine, 0..40), Arb.int(0..8)) { warnings, maxPerCode ->
            val capped = WarningLines.cap(warnings, maxPerCode)
            val byCode = capped.groupBy { line ->
                line.substringAfter("warning ").substringBefore(':')
            }
            for ((_, lines) in byCode) {
                val summaries = lines.count { "… and " in it }
                val details = lines.size - summaries
                details shouldBeLessThanOrEqual maxPerCode
                summaries shouldBeLessThanOrEqual 1
            }
        }
    }

    @Test
    fun `answerOnly drops every warning line`(): Unit = runBlocking {
        checkAll(50, Arb.list(warningLine, 1..10)) { warnings ->
            val rendered = (listOf("header") + warnings + listOf("footer")).joinToString("\n")
            WarningLines.answerOnly(rendered) shouldBe "header\nfooter"
            WarningLines.partition(rendered).warningLines shouldBe warnings
        }
    }
}
