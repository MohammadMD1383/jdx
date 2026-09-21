package dev.jdx.index.service

import dev.jdx.index.service.JdxService.RootsSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `--with-doc` enrichment (T-072, tier 2): `members`/`outline` carry the
 * first javadoc sentence per row (inherited for methods, never for fields);
 * `body` carries the rendered member doc. Docs come from the paired
 * `-sources.jar` via the T-025 seam; rows without docs render unchanged.
 */
@Tag("tier2")
class WithDocServiceTest {

    private fun roots(jars: DocCaseJars): RootsSpec =
        RootsSpec(jarSpecs = listOf(jars.binary.toString()), includeJdk = false)

    @Test
    fun `members with-doc carries first sentences`(@TempDir tempDir: Path) {
        val jars = buildDocCaseJars(tempDir)
        val outcome = JdxService.members(
            "doc.Base",
            roots(jars),
            JdxService.MemberFilters(withDoc = true),
            declaredOnly = true,
        )
        outcome.exitCode shouldBe 0
        val text = outcome.renderText()
        text shouldContain "Greets warmly."
        text shouldContain "The shared name."
        text shouldContain "Builds a Base."
    }

    @Test
    fun `members without the flag stays silent`(@TempDir tempDir: Path) {
        val jars = buildDocCaseJars(tempDir)
        val outcome = JdxService.members(
            "doc.Base",
            roots(jars),
            JdxService.MemberFilters(withDoc = false),
            declaredOnly = true,
        )
        outcome.exitCode shouldBe 0
        outcome.renderText() shouldNotContain "Greets warmly."
    }

    @Test
    fun `child inherits the method sentence but not the field`(@TempDir tempDir: Path) {
        val jars = buildDocCaseJars(tempDir)
        val outcome = JdxService.members(
            "doc.Child",
            roots(jars),
            JdxService.MemberFilters(withDoc = true),
            declaredOnly = true,
        )
        outcome.exitCode shouldBe 0
        val text = outcome.renderText()
        // `greet` is undocumented on Child: the Base sentence fills in.
        text shouldContain "Greets warmly."
        // `name` hides (D-037): its declared row must not carry Base's sentence.
        val nameLine = text.lines().first { it.trimStart().startsWith("field") && it.contains("name") }
        nameLine shouldNotContain "The shared name."
        nameLine shouldNotContain "—"
        val greetLine = text.lines().first { it.contains("greet(") }
        greetLine shouldContain "— Greets warmly."
    }

    @Test
    fun `outline with-doc matches members declared`(@TempDir tempDir: Path) {
        val jars = buildDocCaseJars(tempDir)
        val members = JdxService.members(
            "doc.Traffic",
            roots(jars),
            JdxService.MemberFilters(withDoc = true),
            declaredOnly = true,
        )
        val outline = JdxService.outline(
            "doc.Traffic",
            roots(jars),
            JdxService.MemberFilters(withDoc = true),
        )
        members.exitCode shouldBe 0
        outline.exitCode shouldBe 0
        outline.renderText() shouldContain "Stop now."
        outline.renderText() shouldBe members.renderText()
    }

    @Test
    fun `body with-doc carries the rendered doc`(@TempDir tempDir: Path) {
        val jars = buildDocCaseJars(tempDir)
        val outcome = JdxService.body(
            "doc.Base#greet(java.lang.String)",
            roots(jars),
            JdxService.BodyOptions(withDoc = true),
        )
        outcome.exitCode shouldBe 0
        val text = outcome.renderText()
        text shouldContain "  doc:"
        text shouldContain "Greets warmly."
        val json = outcome.toJson("body")
        json shouldContain "\"doc\":\"Greets warmly."
    }

    @Test
    fun `body without the flag stays silent`(@TempDir tempDir: Path) {
        val jars = buildDocCaseJars(tempDir)
        val outcome = JdxService.body(
            "doc.Base#greet(java.lang.String)",
            roots(jars),
            JdxService.BodyOptions(withDoc = false),
        )
        outcome.exitCode shouldBe 0
        outcome.renderText() shouldNotContain "  doc:"
    }

    @Test
    fun `with-doc is deterministic and text-subset-json`(@TempDir tempDir: Path) {
        val jars = buildDocCaseJars(tempDir)
        val first = JdxService.members(
            "doc.Base", roots(jars),
            JdxService.MemberFilters(withDoc = true), declaredOnly = true,
        )
        val second = JdxService.members(
            "doc.Base", roots(jars),
            JdxService.MemberFilters(withDoc = true), declaredOnly = true,
        )
        first.renderText() shouldBe second.renderText()
        first.toJson("members") shouldBe second.toJson("members")
        val body = JdxService.body(
            "doc.Base#greet(java.lang.String)", roots(jars),
            JdxService.BodyOptions(withDoc = true),
        )
        val bodyBlock = (body as JdxService.ServiceOutcome.Body).block
        for (line in bodyBlock.doc.orEmpty().filter { it.isNotBlank() }) {
            body.toJson("body") shouldContain line.trim().take(20)
        }
    }
}


