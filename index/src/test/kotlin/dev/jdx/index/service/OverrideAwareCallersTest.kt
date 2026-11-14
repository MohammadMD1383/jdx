package dev.jdx.index.service

import dev.jdx.index.service.JdxService.CallOptions
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Override-aware `callers` (issue #6): virtual-dispatch expansion finds call
 * sites through interfaces/supertypes and through concrete overrides. Exact
 * name+descriptor matching stays available behind `--exact`.
 *
 * `calls` stays bytecode-exact by design — an invoke instruction names one
 * owner; expanding to the override set would invent edges the class file does
 * not contain.
 */
@Tag("tier2")
class OverrideAwareCallersTest {

    private val tempDir = Files.createTempDirectory("override-callers-test")

    private fun caseRoots(): RootsSpec =
        RootsSpec(jarSpecs = listOf(buildOverrideCallsCaseJar(tempDir).toString()), includeJdk = false)

    private fun callGraphOf(outcome: ServiceOutcome): dev.jdx.core.render.CallListing =
        (outcome as? ServiceOutcome.CallGraph)?.listing
            ?: error("expected CallGraph, got $outcome")

    private fun callers(query: String, exact: Boolean = false): ServiceOutcome =
        JdxService.callers(query, caseRoots(), CallOptions(exact = exact))

    private fun callerRefs(query: String, exact: Boolean = false): List<String> =
        callGraphOf(callers(query, exact)).rows.map { it.ref }

    // -- interface / implementation -------------------------------------------

    @Test
    fun `callers of an interface method include call sites through implementations`() {
        callerRefs("o.Iface#act()") shouldContainExactlyInAnyOrder listOf(
            "o.ViaIface#run()",
            "o.ViaImpl#run()",
        )
    }

    @Test
    fun `callers of an override include polymorphic call sites through the interface`() {
        callerRefs("o.Impl#act()") shouldContainExactlyInAnyOrder listOf(
            "o.ViaIface#run()",
            "o.ViaImpl#run()",
        )
    }

    @Test
    fun `exact mode keeps interface callers owner-faithful`() {
        callerRefs("o.Iface#act()", exact = true) shouldBe listOf("o.ViaIface#run()")
        callerRefs("o.Impl#act()", exact = true) shouldBe listOf("o.ViaImpl#run()")
    }

    // -- class override -------------------------------------------------------

    @Test
    fun `callers of a base method include call sites through overrides`() {
        callerRefs("o.Base#hook()") shouldContainExactlyInAnyOrder listOf(
            "o.ViaBase#run()",
            "o.ViaChild#run()",
        )
    }

    @Test
    fun `callers of a child override include polymorphic call sites through the base`() {
        callerRefs("o.Child#hook()") shouldContainExactlyInAnyOrder listOf(
            "o.ViaBase#run()",
            "o.ViaChild#run()",
        )
    }

    // -- static / generative --------------------------------------------------

    @Test
    fun `static methods stay exact even without --exact`() {
        callerRefs("o.StaticHost#util()") shouldBe listOf("o.ViaStatic#run()")
    }

    @Test
    fun `override-aware callers are a superset of exact callers`() {
        val queries = listOf(
            "o.Iface#act()",
            "o.Impl#act()",
            "o.Base#hook()",
            "o.Child#hook()",
            "o.StaticHost#util()",
        )
        for (query in queries) {
            val exact = callerRefs(query, exact = true).toSet()
            val aware = callerRefs(query, exact = false).toSet()
            (aware.containsAll(exact)) shouldBe true
        }
    }

    @Test
    fun `calls stay bytecode-exact through a supertype-typed receiver`() {
        // ViaIface invokes o.Iface#act — not the override. Documented: calls
        // does not invent override targets (issue #6 acceptance).
        val listing = callGraphOf(
            JdxService.calls("o.ViaIface#run()", caseRoots(), CallOptions()),
        )
        listing.rows.map { it.ref } shouldBe listOf("o.Iface#act()")
        listing.rows.map { it.ref }.none { it.startsWith("o.Impl#") } shouldBe true
    }

    @Test
    fun `run twice is deterministic`() {
        val first = callers("o.Iface#act()")
        val second = callers("o.Iface#act()")
        first.renderText(false) shouldBe second.renderText(false)
        first.toJson("callers") shouldBe second.toJson("callers")
        first.toJson("callers") shouldContain "o.ViaImpl#run()"
    }
}
