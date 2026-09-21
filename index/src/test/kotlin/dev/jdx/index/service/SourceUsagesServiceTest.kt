package dev.jdx.index.service

import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.index.service.JdxService.UsageKindFilter
import dev.jdx.index.service.JdxService.UsageOptions
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Source-dir usages (T-031, tier 2): the T-030 case jar gives the bytecode
 * target (`u.Lib`, `u.Lib#greet`) while a fabricated source dir supplies
 * textual mentions — the expected row sets are exact on both sides.
 */
@Tag("tier2")
class SourceUsagesServiceTest {

    private val tempDir: java.nio.file.Path = Files.createTempDirectory("src-usages-service-test")

    private fun writeSources(): java.nio.file.Path {
        val src = Files.createDirectories(tempDir.resolve("proj-src"))
        Files.writeString(src.resolve("App.java"), "import u.Lib;\nclass App { Lib lib; }\n")
        Files.writeString(src.resolve("Calls.java"), "class Calls {\n  void m(u.Lib l) { l.greet(\"x\"); }\n}\n")
        Files.writeString(src.resolve("Noise.txt"), "Lib greet Lib\n")
        return src
    }

    private fun roots(src: java.nio.file.Path?): RootsSpec = RootsSpec(
        jarSpecs = listOf(buildUsagesCaseJar(tempDir).toString()),
        includeJdk = false,
        srcSpecs = if (src == null) emptyList() else listOf(src.toString()),
    )

    private fun textOf(outcome: ServiceOutcome): String = outcome.renderText(false)

    private fun usageListOf(outcome: ServiceOutcome): dev.jdx.core.render.UsageListing =
        (outcome as? ServiceOutcome.UsageList)?.listing
            ?: error("expected UsageList, got $outcome")

    @Test
    fun `type query includes source ref rows under the dir label`() {
        val outcome = JdxService.usages("u.Lib", roots(writeSources()))
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "proj-src (3)"
        text shouldContain "  ref App.java:1"
        text shouldContain "  ref App.java:2"
        text shouldContain "  ref Calls.java:2"
        // Bytecode rows stay.
        text shouldContain "usages-case.jar (5)"
        // JSON carries the same rows.
        val json = outcome.toJson("usages")
        json shouldContain "App.java:1"
        json shouldContain "Calls.java:2"
    }

    @Test
    fun `member query matches the member name in sources`() {
        val outcome = JdxService.usages("u.Lib#greet", roots(writeSources()))
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "  ref Calls.java:2"
        text shouldNotContain "App.java"
    }

    @Test
    fun `kind call shows bytecode edges alone`() {
        val outcome = JdxService.usages(
            "u.Lib",
            roots(writeSources()),
            UsageOptions(kind = UsageKindFilter.CALL),
        )
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "  call u.App#run()"
        text shouldNotContain "App.java"
    }

    @Test
    fun `kind ref shows bytecode refs plus source mentions`() {
        val outcome = JdxService.usages(
            "u.Lib",
            roots(writeSources()),
            UsageOptions(kind = UsageKindFilter.REF),
        )
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "  ref App.java:1"
        text shouldContain "  ref u.App#run()"
    }

    @Test
    fun `in filter narrows to the source label`() {
        val outcome = JdxService.usages(
            "u.Lib",
            roots(writeSources()),
            UsageOptions(inArtifact = "proj-src"),
        )
        outcome.exitCode shouldBe 0
        val listing = usageListOf(outcome)
        listing.hits.all { it.artifact == "proj-src" } shouldBe true
    }

    @Test
    fun `exclude drops the source label`() {
        val outcome = JdxService.usages(
            "u.Lib",
            roots(writeSources()),
            UsageOptions(exclude = "proj-src"),
        )
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldNotContain "App.java"
        text shouldContain "usages-case.jar"
    }

    @Test
    fun `missing source dir exits 5 naming the path`() {
        val outcome = JdxService.usages("u.Lib", roots(tempDir.resolve("nope")))
        outcome.exitCode shouldBe 5
        textOf(outcome) shouldContain "nope"
    }

    @Test
    fun `deterministic and text subset json`() {
        val src = writeSources()
        val first = JdxService.usages("u.Lib", roots(src))
        val second = JdxService.usages("u.Lib", roots(src))
        textOf(first) shouldBe textOf(second)
        first.toJson("usages") shouldBe second.toJson("usages")
        val listing = usageListOf(first)
        val json = first.toJson("usages")
        for (hit in listing.hits) {
            json shouldContain hit.fromRef
            json shouldContain hit.artifact
        }
    }

    @Test
    fun `stored workspace srcs scan without the flag`() {
        val src = writeSources()
        val store = dev.jdx.index.workspace.InMemoryWorkspaceStore()
        store.save(dev.jdx.index.workspace.WorkspaceDefinition("s", emptyList(), includeJdk = false, srcs = listOf(src.toString())))
        val resolved = dev.jdx.index.workspace.WorkspaceResolver.resolve(
            explicitJars = listOf(buildUsagesCaseJar(tempDir).toString()),
            explicitNoJdk = true,
            flagWorkspace = "s",
            loadWorkspace = store::load,
            listNames = store::listNames,
        )
        val value = (resolved as dev.jdx.index.workspace.WorkspaceResolver.Result.success).value
        value.srcSpecs shouldBe listOf(src.toString())
        val outcome = JdxService.usages("u.Lib", RootsSpec.fromResolved(value))
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "App.java:1"
    }
}
