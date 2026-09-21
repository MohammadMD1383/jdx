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
 * Behaviour of `usages` against a crafted corpus (T-030, tier 2): ASM-built
 * classes whose every edge is placed by hand ([buildUsagesCaseJar]), so the
 * expected hit sets are exact.
 *
 * Rendering itself is pinned by `UsagesGoldenTest` in the render package;
 * here the assertions are structural — resolution, kind filtering, overload
 * narrowing, artifact filters, truncation, exit codes, determinism and
 * text⊆JSON.
 */
@Tag("tier2")
class UsagesServiceTest {

    private val tempDir: java.nio.file.Path = Files.createTempDirectory("usages-service-test")

    private fun caseRoots(): RootsSpec =
        RootsSpec(jarSpecs = listOf(buildUsagesCaseJar(tempDir).toString()), includeJdk = false)

    private fun textOf(outcome: ServiceOutcome): String = outcome.renderText(false)

    private fun usageListOf(outcome: ServiceOutcome): dev.jdx.core.render.UsageListing =
        (outcome as? ServiceOutcome.UsageList)?.listing
            ?: error("expected UsageList, got $outcome")

    // -- type queries ---------------------------------------------------------

    @Test
    fun `type query lists every edge kind grouped by artifact`() {
        val outcome = JdxService.usages("u.Lib", caseRoots())
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text.lines().first() shouldBe "usages of 'u.Lib'"
        // u.App#run contributes call + read + write + ref (NEW/CHECKCAST deduped);
        // u.Other#work contributes the second overload's call. Type queries name
        // the touched member per row; pure type edges name the bare owner.
        text shouldContain "usages-case.jar (5)"
        text shouldContain "  call u.App#run() -> #greet(java.lang.String)"
        text shouldContain "  read u.App#run() -> #count"
        text shouldContain "  write u.App#run() -> #count"
        text shouldContain "  ref u.App#run() -> u.Lib"
        text shouldContain "  call u.Other#work() -> #greet(java.lang.String, int)"
    }

    @Test
    fun `member query without params matches every overload`() {
        val outcome = JdxService.usages("u.Lib#greet", caseRoots())
        outcome.exitCode shouldBe 0
        val listing = usageListOf(outcome)
        listing.hits.map { it.fromRef }.sorted() shouldBe listOf("u.App#run()", "u.Other#work()")
    }

    @Test
    fun `parameterised ref narrows to one overload`() {
        val outcome = JdxService.usages("u.Lib#greet(java.lang.String,int)", caseRoots())
        outcome.exitCode shouldBe 0
        val listing = usageListOf(outcome)
        listing.hits.map { it.fromRef } shouldBe listOf("u.Other#work()")
    }

    @Test
    fun `field query finds reads and writes`() {
        val outcome = JdxService.usages("u.Lib#count", caseRoots())
        outcome.exitCode shouldBe 0
        val listing = usageListOf(outcome)
        listing.hits.map { it.kind + " " + it.fromRef }.sorted() shouldBe listOf(
            "read u.App#run()",
            "write u.App#run()",
        )
    }

    // -- kind filtering --------------------------------------------------------

    @Test
    fun `kind call hides field and type edges`() {
        val outcome = JdxService.usages("u.Lib", caseRoots(), UsageOptions(kind = UsageKindFilter.CALL))
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "  call u.App#run() -> #greet(java.lang.String)"
        text shouldContain "  call u.Other#work() -> #greet(java.lang.String, int)"
        text shouldNotContain "read u.App"
        text shouldNotContain "write u.App"
        text shouldNotContain "ref u.App"
    }

    @Test
    fun `kind ref finds only type mentions`() {
        val outcome = JdxService.usages("u.Lib", caseRoots(), UsageOptions(kind = UsageKindFilter.REF))
        outcome.exitCode shouldBe 0
        usageListOf(outcome).hits.map { it.fromRef } shouldBe listOf("u.App#run()")
    }

    @Test
    fun `deferred kinds name their owning task`() {
        val impl = JdxService.usages("u.Lib", caseRoots(), UsageOptions(kind = UsageKindFilter.IMPL))
        impl.exitCode shouldBe 3
        textOf(impl) shouldContain "T-032"
        val thrown = JdxService.usages("u.Lib", caseRoots(), UsageOptions(kind = UsageKindFilter.THROW))
        thrown.exitCode shouldBe 3
        textOf(thrown) shouldContain "T-034"
    }

    @Test
    fun `context is rejected naming samples`() {
        val outcome = JdxService.usages("u.Lib", caseRoots(), UsageOptions(contextLines = 2))
        outcome.exitCode shouldBe 3
        textOf(outcome) shouldContain "T-034"
    }

    // -- artifact filters and truncation ----------------------------------------

    @Test
    fun `in and exclude filter by artifact label`() {
        val roots = caseRoots()
        val included = JdxService.usages("u.Lib", roots, UsageOptions(inArtifact = "usages-case.jar"))
        included.exitCode shouldBe 0
        val missed = JdxService.usages("u.Lib", roots, UsageOptions(inArtifact = "no-such*.jar"))
        missed.exitCode shouldBe 1
        val excluded = JdxService.usages("u.Lib", roots, UsageOptions(exclude = "usages-case*"))
        excluded.exitCode shouldBe 1
    }

    @Test
    fun `limit truncates whole rows with a hint`() {
        val outcome = JdxService.usages("u.Lib", caseRoots(), UsageOptions(limit = 2))
        outcome.exitCode shouldBe 0
        val listing = usageListOf(outcome)
        listing.hits.size shouldBe 2
        textOf(outcome) shouldContain "2 of 5 usages shown (--limit 5 to see more)"
    }

    // -- resolution failures -----------------------------------------------------

    @Test
    fun `unknown type reports did-you-mean`() {
        val outcome = JdxService.usages("u.Libbb", caseRoots())
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "u.Lib"
    }

    @Test
    fun `short name with two providers is ambiguous`() {
        val outcome = JdxService.usages("Lib", caseRoots())
        outcome.exitCode shouldBe 2
        textOf(outcome) shouldContain "u.Lib"
        textOf(outcome) shouldContain "v.Lib"
    }

    @Test
    fun `unknown member reports member suggestions`() {
        val outcome = JdxService.usages("u.Lib#greett", caseRoots())
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "u.Lib#greet"
    }

    @Test
    fun `referenced nowhere is exit 1 naming the target`() {
        val outcome = JdxService.usages("u.App", caseRoots())
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "no usages of 'u.App'"
    }

    @Test
    fun `invalid ref and missing workspace fail fast`() {
        JdxService.usages("u.Lib#(", caseRoots()).exitCode shouldBe 3
        JdxService.usages("u.Lib", RootsSpec(emptyList(), includeJdk = false)).exitCode shouldBe 4
        JdxService.usages("u.Lib", caseRoots(), UsageOptions(limit = -1)).exitCode shouldBe 3
    }

    // -- laws ---------------------------------------------------------------------

    @Test
    fun `run twice is byte-identical in text and json`() {
        val roots = caseRoots()
        val first = JdxService.usages("u.Lib", roots)
        val second = JdxService.usages("u.Lib", roots)
        textOf(first) shouldBe textOf(second)
        first.toJson("usages") shouldBe second.toJson("usages")
    }

    @Test
    fun `every text row appears in json`() {
        val outcome = JdxService.usages("u.Lib", caseRoots())
        val json = outcome.toJson("usages")
        for (hit in usageListOf(outcome).hits) {
            json shouldContain hit.fromRef
            json shouldContain hit.artifact
            json shouldContain hit.kind
        }
    }

    @Test
    fun `jdk smoke test truncates a large hit set`() {
        val outcome = JdxService.usages(
            "java.util.ArrayList",
            RootsSpec(emptyList(), includeJdk = true),
            UsageOptions(limit = 5),
        )
        outcome.exitCode shouldBe 0
        val listing = usageListOf(outcome)
        listing.hits.size shouldBe 5
        textOf(outcome) shouldContain "5 of "
    }
}
