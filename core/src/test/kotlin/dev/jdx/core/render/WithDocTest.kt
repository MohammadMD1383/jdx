package dev.jdx.core.render

import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * `--with-doc` enrichment (T-072): [firstDocSentence] plus the [MemberRow.doc]
 * and [BodyBlock.doc] renderings in text and JSON alike.
 *
 * Flag-off output stays byte-identical by construction (`doc = null`
 * defaults); these tests pin the flag-on shape and the text⊆JSON law.
 */
class WithDocTest {

    @Test
    fun `first sentence ends at the first terminator`() {
        firstDocSentence(listOf("Greets warmly.", "Second sentence.")) shouldBe "Greets warmly."
        firstDocSentence(listOf("Greets warmly! Second thought.")) shouldBe "Greets warmly!"
        firstDocSentence(listOf("Stop now? Really.")) shouldBe "Stop now?"
    }

    @Test
    fun `first sentence joins the first paragraph only`() {
        firstDocSentence(
            listOf(
                "Builds a widget",
                "across two lines.",
                "",
                "@param name who to greet",
            ),
        ) shouldBe "Builds a widget across two lines."
    }

    @Test
    fun `blank input has no first sentence`() {
        firstDocSentence(emptyList()) shouldBe null
        firstDocSentence(listOf("", "   ")) shouldBe null
        firstDocSentence(listOf("", "@param x y")) shouldBe null
    }

    @Test
    fun `member row without doc renders exactly like before`() {
        val row = memberRow(doc = null)
        row.textLine() shouldBe "  method public java.lang.String greet(java.lang.String)"
        row.toJson() shouldNotContain "\"doc\""
    }

    @Test
    fun `member row with doc suffixes text and carries json`() {
        val row = memberRow(doc = "Greets warmly.")
        row.textLine() shouldBe
            "  method public java.lang.String greet(java.lang.String) — Greets warmly."
        val json = row.toJson()
        json shouldContain "\"doc\":\"Greets warmly.\""
    }

    @Test
    fun `body block without doc renders exactly like before`() {
        val block = bodyBlock(doc = null)
        val text = block.renderText()
        text shouldNotContain "  doc:"
        block.toJson("body") shouldNotContain "\"doc\""
    }

    @Test
    fun `body block with doc renders between signature and slice in both renderers`() {
        val block = bodyBlock(
            signature = "public java.lang.String greet(java.lang.String)",
            doc = listOf("Greets warmly.", "", "@param name who to greet"),
        )
        val text = block.renderText()
        val lines = text.lines()
        lines[2] shouldBe "  signature: public java.lang.String greet(java.lang.String)"
        lines[3] shouldBe "  doc:"
        lines[4] shouldBe "    Greets warmly."
        val json = block.toJson("body")
        json shouldContain "\"signature\":\"public java.lang.String greet(java.lang.String)\""
        json shouldContain "\"doc\":\"Greets warmly.\\n\\n@param name who to greet\""
        // Text⊆JSON: every doc line appears in the JSON payload.
        for (line in block.doc.orEmpty()) json shouldContain line.ifEmpty { "@param" }
    }

    @Test
    fun `first sentence never throws and is deterministic`() = runBlocking<Unit> {
        checkAll(1_000, Arb.list(Arb.string(), 0..5).filter { it.size <= 5 }) { lines ->
            val first = firstDocSentence(lines)
            firstDocSentence(lines) shouldBe first
            if (first != null) {
                (first.isNotBlank()) shouldBe true
                first shouldNotContain "\n"
            }
        }
    }

    private fun memberRow(doc: String?): MemberRow = MemberRow(
        canonicalRef = "doc.Base#greet(java.lang.String)",
        kind = MemberKind.METHOD,
        signature = "public java.lang.String greet(java.lang.String)",
        declaringType = dev.jdx.core.model.typeNameFromBinaryName("doc.Base") as dev.jdx.core.model.TypeName.ClassType,
        depth = 0,
        deprecated = false,
        overriddenTypes = emptyList(),
        hiddenTypes = emptyList(),
        memberName = "greet",
        doc = doc,
    )

    private fun bodyBlock(signature: String? = null, doc: List<String>?): BodyBlock = buildBodyBlock(
        canonicalRef = "doc.Base#greet(java.lang.String)",
        declaringType = "doc.Base",
        file = "doc/Base.java",
        fileLines = listOf("public abstract String greet(String name);"),
        startLine = 1,
        endLine = 1,
        provenance = listOf(
            Provenance(artifact = "case-sources.jar", origin = Origin.SOURCES),
        ),
        signature = signature,
        doc = doc,
    )
}
