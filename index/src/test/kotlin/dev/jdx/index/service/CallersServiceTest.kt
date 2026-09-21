package dev.jdx.index.service

import dev.jdx.core.render.CallDirection
import dev.jdx.index.service.JdxService.CallOptions
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Behaviour of `callers` against a crafted corpus (T-033, tier 2): ASM-built
 * classes whose every call edge is placed by hand ([buildCallsCaseJar]), so
 * the expected trees are exact.
 *
 * Rendering itself is pinned by `CallGraphGoldenTest` in the render package;
 * here the assertions are structural — resolution, overload narrowing, depth,
 * cycles, diamonds, artifact filters, exit codes, determinism and text⊆JSON.
 */
@Tag("tier2")
class CallersServiceTest {

    private val tempDir: java.nio.file.Path = Files.createTempDirectory("callers-service-test")

    private fun caseRoots(): RootsSpec =
        RootsSpec(jarSpecs = listOf(buildCallsCaseJar(tempDir).toString()), includeJdk = false)

    private fun textOf(outcome: ServiceOutcome): String = outcome.renderText(false)

    private fun callGraphOf(outcome: ServiceOutcome): dev.jdx.core.render.CallListing =
        (outcome as? ServiceOutcome.CallGraph)?.listing
            ?: error("expected CallGraph, got $outcome")

    private fun callers(query: String, depth: Int = 1, limit: Int = 50): ServiceOutcome =
        JdxService.callers(query, caseRoots(), CallOptions(depth = depth, limit = limit))

    // -- resolution ------------------------------------------------------------

    @Test
    fun `depth-1 callers list direct callers sorted by ref`() {
        val outcome = callers("c.Lib#greet(java.lang.String)")
        outcome.exitCode shouldBe 0
        val listing = callGraphOf(outcome)
        listing.direction shouldBe CallDirection.CALLERS
        listing.rows.map { it.ref } shouldBe listOf("c.App#run()", "c.Util#help()")
        listing.rows.all { it.depth == 1 } shouldBe true
        textOf(outcome).lines().first() shouldBe "callers of 'c.Lib#greet(java.lang.String)'"
    }

    @Test
    fun `member query without params matches every overload`() {
        val listing = callGraphOf(callers("c.Lib#greet"))
        listing.rows.map { it.ref }.sorted() shouldBe listOf("c.App#run()", "c.Other#work()", "c.Util#help()")
    }

    @Test
    fun `parameterised ref narrows to one overload`() {
        val listing = callGraphOf(callers("c.Lib#greet(java.lang.String,int)"))
        listing.rows.map { it.ref } shouldBe listOf("c.Other#work()")
    }

    @Test
    fun `depth-2 tree nests callers of callers and repeats diamonds`() {
        val listing = callGraphOf(callers("c.Lib#greet(java.lang.String)", depth = 2))
        listing.rows.map { it.depth to it.ref } shouldBe listOf(
            1 to "c.App#run()",
            2 to "c.Main#main(java.lang.String[])",
            2 to "c.Top#go()",
            1 to "c.Util#help()",
            // c.Util#help is called from three methods: the diamond repeats
            // c.Top#go(), and c.App#run + c.Bean#<init> join at this level.
            2 to "c.App#run()",
            2 to "c.Bean#<init>()",
            2 to "c.Top#go()",
        )
    }

    @Test
    fun `depth-1 hides transitive callers`() {
        val listing = callGraphOf(callers("c.Lib#greet(java.lang.String)", depth = 1))
        listing.rows.map { it.ref } shouldBe listOf("c.App#run()", "c.Util#help()")
    }

    @Test
    fun `two-cycle marks the re-entry and stops`() {
        // The marker lands exactly at the re-entry: level 2 is c.Loop#a
        // itself (plain — no displayed ancestor repeats yet), level 3
        // re-enters c.Loop#b and stops.
        val listing = callGraphOf(callers("c.Loop#a()", depth = 3))
        listing.rows.map { it.depth to it.ref } shouldBe listOf(
            1 to "c.Loop#b()",
            2 to "c.Loop#a()",
            3 to "c.Loop#b()",
        )
        listing.rows.last().cycle shouldBe true
        listing.rows.take(2).all { !it.cycle } shouldBe true
        textOf(callers("c.Loop#a()", depth = 3)) shouldContain "…(cycle)"
    }

    @Test
    fun `self-recursion shows one plain row at depth 1 and a cycle at depth 2`() {
        val shallow = callGraphOf(callers("c.Self#tick()", depth = 1))
        shallow.rows.map { it.ref } shouldBe listOf("c.Self#tick()")
        shallow.rows.single().cycle shouldBe false
        val deep = callGraphOf(callers("c.Self#tick()", depth = 2))
        deep.rows.map { it.depth to it.ref } shouldBe listOf(
            1 to "c.Self#tick()",
            2 to "c.Self#tick()",
        )
        deep.rows.last().cycle shouldBe true
    }

    @Test
    fun `constructor callers resolve through invokespecial`() {
        val listing = callGraphOf(callers("c.Bean#<init>()"))
        listing.rows.map { it.ref } shouldBe listOf("c.Factory#make()")
    }

    @Test
    fun `unused method exits 1 naming no callers`() {
        val outcome = callers("c.Factory#make()")
        outcome.exitCode shouldBe 1
        // The detail names the bare target (the usages convention): the raw
        // query is already in the header line above it.
        textOf(outcome) shouldContain "no callers of 'c.Factory#make'"
    }

    // -- rejections -------------------------------------------------------------

    @Test
    fun `type refs fields and static initialisers are usage errors`() {
        callers("c.Lib").exitCode shouldBe 3
        textOf(callers("c.Lib")) shouldContain "takes a member reference"
        val field = callers("c.Lib#count")
        field.exitCode shouldBe 3
        textOf(field) shouldContain "jdx usages"
        val clinit = JdxService.callers("c.Lib#<clinit>()", caseRoots())
        clinit.exitCode shouldBe 3
        textOf(clinit) shouldContain "never called"
    }

    @Test
    fun `unknown ambiguous and bad flags fail distinctly`() {
        callers("c.Missing#nope()").exitCode shouldBe 1
        callers("Lib#greet").exitCode shouldBe 2
        JdxService.callers("c.Lib#greet", caseRoots(), CallOptions(depth = 0)).exitCode shouldBe 3
        JdxService.callers("c.Lib#greet", caseRoots(), CallOptions(limit = -1)).exitCode shouldBe 3
        val external = JdxService.callers("c.Lib#greet", caseRoots(), CallOptions(externalOnly = true))
        external.exitCode shouldBe 3
        textOf(external) shouldContain "--external-only is a calls flag"
    }

    // -- filters -----------------------------------------------------------------

    @Test
    fun `in and exclude scope rows by artifact label`() {
        val kept = callGraphOf(
            JdxService.callers("c.Lib#greet", caseRoots(), CallOptions(inArtifact = "calls-case.jar")),
        )
        kept.rows.map { it.ref }.sorted() shouldBe listOf("c.App#run()", "c.Other#work()", "c.Util#help()")
        callers("c.Lib#greet").let { _ ->
            JdxService.callers("c.Lib#greet", caseRoots(), CallOptions(inArtifact = "nope*")).exitCode shouldBe 1
        }
        JdxService.callers("c.Lib#greet", caseRoots(), CallOptions(exclude = "calls-case*")).exitCode shouldBe 1
    }

    // -- metamorphic: callers ⟺ calls, depth 1 (TESTING.md §6) --------------------

    @Test
    fun `callers depth-1 duals calls depth-1 over every case method`() {
        val methods = listOf(
            "c.App#run()",
            "c.Util#help()",
            "c.Main#main(java.lang.String[])",
            "c.Top#go()",
            "c.Other#work()",
            "c.Lib#greet(java.lang.String)",
            "c.Lib#greet(java.lang.String,int)",
            "c.Loop#a()",
            "c.Loop#b()",
            "c.Self#tick()",
            "c.Bean#<init>()",
            "c.Factory#make()",
        )
        for (method in methods) {
            val callerOutcome = JdxService.callers(method, caseRoots(), CallOptions(depth = 1, limit = 1000))
            if (callerOutcome is ServiceOutcome.CallGraph) {
                for (caller in callerOutcome.listing.rows.map { it.ref }) {
                    val calleeOutcome = JdxService.calls(caller, caseRoots(), CallOptions(depth = 1, limit = 1000))
                    val callees = callGraphOf(calleeOutcome).rows.map { it.ref }.toSet()
                    // Row refs are the canonical printer form (", " between
                    // params); queries accept the spaceless form, so identity
                    // compares normalised.
                    (norm(method) in callees.map(::norm)) shouldBe true
                }
            }
            val calleeOutcome = JdxService.calls(method, caseRoots(), CallOptions(depth = 1, limit = 1000))
            if (calleeOutcome is ServiceOutcome.CallGraph) {
                for (callee in calleeOutcome.listing.rows.map { it.ref }) {
                    val callerRows = callGraphOf(
                        JdxService.callers(callee, caseRoots(), CallOptions(depth = 1, limit = 1000)),
                    ).rows.map { it.ref }.toSet()
                    (norm(method) in callerRows.map(::norm)) shouldBe true
                }
            }
        }
    }

    /** Canonicalises the printer's ", " separators for identity comparison. */
    private fun norm(ref: String): String = ref.replace(", ", ",")

    // -- determinism and parity ---------------------------------------------------

    @Test
    fun `run twice yields identical bytes`() {
        val first = callers("c.Lib#greet(java.lang.String)", depth = 2)
        val second = callers("c.Lib#greet(java.lang.String)", depth = 2)
        first.renderText(false) shouldBe second.renderText(false)
        first.toJson("callers") shouldBe second.toJson("callers")
    }

    @Test
    fun `every text row appears in json`() {
        val listing = callGraphOf(callers("c.Lib#greet(java.lang.String)", depth = 2))
        val json = listing.toJson("callers")
        json shouldContain "\"direction\":\"callers\""
        for (row in listing.rows) {
            json shouldContain row.ref
            row.artifact?.let { json shouldContain it }
        }
    }
}
