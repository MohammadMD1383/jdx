package dev.jdx.core.render

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative invariants for the body renderer (T-022, TESTING.md §4 — the standing
 * bar demands a generating family, not only examples).
 */
class BodyBlockPropertyTest {

    // JSON escapes `"`/`\`/control chars, so generated lines are restricted to
    // plain printable text — otherwise the raw line is not a JSON substring.
    private fun arbLine(): Arb<String> =
        Arb.string(0..40).map { s -> s.filter { c -> c >= ' ' && c != '"' && c != '\\' } }

    @Test
    fun `rendering is byte-identical across runs`() = runBlocking<Unit> {
        checkAll(
            JDX_PROPERTY_ITERATIONS,
            Arb.list(arbLine(), 1..30),
            Arb.int(0..4),
            Arb.boolean(),
            Arb.int(0..40),
        ) { file, context, numbers, max ->
            val end = file.size.coerceAtLeast(1)
            val start = 1
            val first = buildBodyBlock(
                "com.example.A#m()", "com.example.A", "com/example/A.java",
                file, start, end, provenanceOf(), contextLines = context,
                lineNumbers = numbers, maxLines = max,
            )
            val second = buildBodyBlock(
                "com.example.A#m()", "com.example.A", "com/example/A.java",
                file, start, end, provenanceOf(), contextLines = context,
                lineNumbers = numbers, maxLines = max,
            )
            first.renderText() shouldBe second.renderText()
            first.toJson(command = "body") shouldBe second.toJson(command = "body")
        }
    }

    @Test
    fun `every shown text line is covered by the json`() = runBlocking<Unit> {
        checkAll(
            JDX_PROPERTY_ITERATIONS,
            Arb.list(arbLine(), 1..30),
            Arb.int(0..4),
            Arb.boolean(),
            Arb.int(0..40),
        ) { file, context, numbers, max ->
            val block = buildBodyBlock(
                "com.example.A#m()", "com.example.A", "com/example/A.java",
                file, 1, file.size.coerceAtLeast(1), provenanceOf(), contextLines = context,
                lineNumbers = numbers, maxLines = max,
            )
            val json = block.toJson(command = "body")
            for (line in block.lines) json shouldContain line
            json shouldContain block.canonicalRef
            json shouldContain block.file
        }
    }

    @Test
    fun `truncation law holds shown below total with hint exactly when cut`() = runBlocking<Unit> {
        checkAll(
            JDX_PROPERTY_ITERATIONS,
            Arb.list(arbLine(), 1..30),
            Arb.int(0..4),
            Arb.int(0..40),
        ) { file, context, max ->
            val full = buildBodyBlock(
                "com.example.A#m()", "com.example.A", "com/example/A.java",
                file, 1, file.size.coerceAtLeast(1), provenanceOf(),
                contextLines = context, maxLines = Int.MAX_VALUE,
            )
            val cut = buildBodyBlock(
                "com.example.A#m()", "com.example.A", "com/example/A.java",
                file, 1, file.size.coerceAtLeast(1), provenanceOf(),
                contextLines = context, maxLines = max,
            )
            if (cut.truncation == null) {
                cut.lines shouldBe full.lines
                cut.displayStartLine shouldBe full.displayStartLine
            } else {
                val truncation = cut.truncation!!
                (truncation.shown <= truncation.total) shouldBe true
                truncation.hint shouldContain "--max-lines"
                truncation.total shouldBe full.lines.size
                truncation.shown shouldBe cut.lines.size
                full.lines.take(cut.lines.size) shouldBe cut.lines
            }
        }
    }

    @Test
    fun `plain text never contains escapes`() = runBlocking<Unit> {
        checkAll(
            JDX_PROPERTY_ITERATIONS,
            Arb.list(arbLine(), 1..30),
            Arb.int(0..4),
            Arb.int(0..40),
        ) { file, context, max ->
            val text = buildBodyBlock(
                "com.example.A#m()", "com.example.A", "com/example/A.java",
                file, 1, file.size.coerceAtLeast(1), provenanceOf(),
                contextLines = context, maxLines = max,
            ).renderText()
            (text.contains("\u001B")) shouldBe false
        }
    }

    private fun provenanceOf(): List<Provenance> =
        listOf(Provenance(artifact = "test-sources.jar", origin = Origin.SOURCES))
}
