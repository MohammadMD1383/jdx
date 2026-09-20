package dev.jdx.core.render

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import io.kotest.matchers.shouldBe
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
 * Generative invariants for the doc renderer (T-025, TESTING.md §4 — the standing
 * bar demands a generating family, not only examples).
 */
class DocBlockPropertyTest {

    // JSON escapes `"`/`\`/control chars, so generated lines are restricted to
    // plain printable text — otherwise the raw line is not a JSON substring.
    private fun arbLine(): Arb<String> =
        Arb.string(0..40).map { s -> s.filter { c -> c >= ' ' && c != '"' && c != '\\' } }

    private fun provenance() = listOf(
        Provenance(
            artifact = "fixture-sources.jar",
            origin = Origin.SOURCES,
            file = "com/example/A.java",
            lineRange = 1..3,
        ),
    )

    private fun docOf(rendered: List<String>, maxLines: Int, inherited: Boolean) = buildDocBlock(
        canonicalRef = "com.example.A#doIt()",
        declaringType = "com.example.A",
        subject = DocSubject.METHOD,
        file = "com/example/A.java",
        startLine = 1,
        endLine = 3,
        rendered = rendered,
        provenance = provenance(),
        inheritedFrom = if (inherited) "com.example.Base" else null,
        maxLines = maxLines,
    )

    @Test
    fun `rendering is byte-identical across runs`() = runBlocking<Unit> {
        checkAll(
            JDX_PROPERTY_ITERATIONS,
            Arb.list(arbLine(), 0..20),
            Arb.int(0..30),
            Arb.boolean(),
        ) { rendered, max, inherited ->
            val first = docOf(rendered, max, inherited)
            val second = docOf(rendered, max, inherited)
            first.renderText() shouldBe second.renderText()
            first.toJson(command = "doc") shouldBe second.toJson(command = "doc")
        }
    }

    @Test
    fun `every shown text line is covered by the json`() = runBlocking<Unit> {
        checkAll(
            JDX_PROPERTY_ITERATIONS,
            Arb.list(arbLine(), 1..20),
            Arb.int(0..30),
            Arb.boolean(),
        ) { rendered, max, inherited ->
            val block = docOf(rendered, max, inherited)
            val text = block.renderText()
            val json = block.toJson(command = "doc")
            for (line in block.lines) {
                // Blank padding lines carry no content to cover.
                if (line.isBlank()) continue
                text shouldContainLine line
                json shouldContainJson line
            }
        }
    }

    private infix fun String.shouldContainLine(line: String) {
        if (!lines().contains(line)) throw AssertionError("text is missing line <$line> in:\n$this")
    }

    private infix fun String.shouldContainJson(line: String) {
        // JSON escapes nothing in the printable subset, so coverage is literal.
        if (!contains(line)) throw AssertionError("JSON is missing line <$line> in:\n$this")
    }

    @Test
    fun `truncation never cuts mid-line and always reports shown total hint`() = runBlocking<Unit> {
        checkAll(
            JDX_PROPERTY_ITERATIONS,
            Arb.list(arbLine(), 1..20),
            Arb.int(0..30),
        ) { rendered, max ->
            val block = docOf(rendered, max, false)
            val truncation = block.truncation
            if (rendered.size <= max.coerceAtLeast(0)) {
                truncation shouldBe null
            } else {
                (truncation == null) shouldBe false
                truncation!!.shown shouldBe max.coerceAtLeast(0)
                truncation.total shouldBe rendered.size
            }
            // Shown lines are always whole rendered lines from the top.
            block.lines shouldBe rendered.take(block.lines.size)
        }
    }

    @Test
    fun `rendering javadoc never throws on hostile input`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.string(0..120)) { raw ->
            renderJavadoc(raw)
            renderJavadoc(raw, inheritDocReplacement = "base")
        }
    }
}
