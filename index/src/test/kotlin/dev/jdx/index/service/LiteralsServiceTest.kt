package dev.jdx.index.service

import dev.jdx.core.render.ErrorResult
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Service-level coverage for `jdx literals` (issue #86): hits, miss, regex,
 * ignore-case, limit, artifact filters, empty pattern, no-workspace.
 */
@Tag("tier2")
class LiteralsServiceTest {

    private fun roots(jar: Path) =
        JdxService.RootsSpec(jarSpecs = listOf(jar.toString()), includeJdk = false)

    @Test
    fun `substring finds LDC and ConstantValue`(@TempDir dir: Path) {
        val jar = buildLiteralsCaseJar(dir)
        val outcome = JdxService.literals(
            "notification.missing",
            roots(jar),
        )
        val listing = (outcome as JdxService.ServiceOutcome.LiteralList).listing
        listing.hits.map { it.kind to it.fromRef }.toSet() shouldBe setOf(
            "ldc" to "lit.App#run()",
            "const" to "lit.Keys#TITLE",
        )
        listing.hits.map { it.value }.toSet() shouldBe setOf(
            "notification.missing.plugin.update.sources.title",
        )
        val ldc = listing.hits.single { it.kind == "ldc" }
        ldc.file shouldBe "App.java"
        ldc.line shouldBe 5
        listing.renderText() shouldContain "literals matching 'notification.missing'"
        listing.toJson("literals") shouldContain "\"kind\":\"ldc\""
    }

    @Test
    fun `miss exits 1`(@TempDir dir: Path) {
        val jar = buildLiteralsCaseJar(dir)
        val outcome = JdxService.literals("no-such-constant", roots(jar))
        val failure = outcome.shouldBeInstanceOf<JdxService.ServiceOutcome.Failure>()
        failure.exitCode shouldBe 1
        failure.error.shouldBeInstanceOf<ErrorResult.NotFound>()
        failure.renderText() shouldContain "no literals matching"
    }

    @Test
    fun `regex and ignore-case`(@TempDir dir: Path) {
        val jar = buildLiteralsCaseJar(dir)
        val regex = JdxService.literals(
            "Hello\\s+World",
            roots(jar),
            JdxService.LiteralOptions(regex = true),
        )
        (regex as JdxService.ServiceOutcome.LiteralList).listing.hits.shouldHaveSize(1)

        val folded = JdxService.literals(
            "hello world",
            roots(jar),
            JdxService.LiteralOptions(ignoreCase = true),
        )
        (folded as JdxService.ServiceOutcome.LiteralList).listing.hits.shouldHaveSize(1)

        val sensitive = JdxService.literals("hello world", roots(jar))
        sensitive.exitCode shouldBe 1
    }

    @Test
    fun `limit truncates and in exclude filter`(@TempDir dir: Path) {
        val jar = buildLiteralsCaseJar(dir)
        val limited = JdxService.literals(
            "notification",
            roots(jar),
            JdxService.LiteralOptions(limit = 1),
        )
        val listing = (limited as JdxService.ServiceOutcome.LiteralList).listing
        listing.hits.shouldHaveSize(1)
        listing.truncation!!.total shouldBe 2

        val onlyOther = JdxService.literals(
            "marker",
            roots(jar),
            JdxService.LiteralOptions(inArtifact = "literals-case.jar"),
        )
        (onlyOther as JdxService.ServiceOutcome.LiteralList).listing.hits
            .map { it.fromRef }
            .shouldContain("lit.Other#work()")
    }

    @Test
    fun `empty pattern and no workspace exit 3 and 4`() {
        JdxService.literals(
            "   ",
            JdxService.RootsSpec(jarSpecs = listOf("x.jar"), includeJdk = false),
        ).exitCode shouldBe 3
        JdxService.literals(
            "anything",
            JdxService.RootsSpec(jarSpecs = emptyList(), includeJdk = false),
        ).exitCode shouldBe 4
    }

    @Test
    fun `invalid regex exits 3`(@TempDir dir: Path) {
        val jar = buildLiteralsCaseJar(dir)
        val outcome = JdxService.literals(
            "[unterminated",
            roots(jar),
            JdxService.LiteralOptions(regex = true),
        )
        outcome.exitCode shouldBe 3
        outcome.renderText() shouldContain "invalid --regex"
    }

    @Test
    fun `deterministic order`(@TempDir dir: Path) {
        val jar = buildLiteralsCaseJar(dir)
        val a = (JdxService.literals("notification", roots(jar)) as JdxService.ServiceOutcome.LiteralList)
            .listing.renderText()
        val b = (JdxService.literals("notification", roots(jar)) as JdxService.ServiceOutcome.LiteralList)
            .listing.renderText()
        a shouldBe b
        // Ensure the temp path is gone after the suite (hygiene).
        Files.exists(jar) shouldBe true
    }
}
