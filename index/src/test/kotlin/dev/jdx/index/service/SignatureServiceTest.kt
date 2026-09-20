package dev.jdx.index.service

import dev.jdx.core.render.MemberKind
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.index.service.JdxService.SignatureOptions
import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Behaviour of `signature` against real artifacts (T-024, tier 2): the
 * fixture corpus binary jar (sources never read — signatures are
 * bytecode-only) plus the running JDK.
 *
 * Rendering itself is pinned by `SignatureGoldenTest` in the render package;
 * here the assertions are structural — resolution, overload listing, exit
 * codes, determinism and text⊆JSON.
 */
@Tag("tier2")
class SignatureServiceTest {

    private fun fixtureRoots(): RootsSpec =
        RootsSpec(jarSpecs = listOf(FixtureJars.binaryJar().absolutePath), includeJdk = false)

    private fun textOf(outcome: ServiceOutcome): String = outcome.renderText(false)

    // -- found -----------------------------------------------------------------

    @Test
    fun `generic method renders with type variables and real parameter names`() {
        val outcome = JdxService.signature("dev.jdx.fixtures.Generics#identity(U)", fixtureRoots())
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text.lines().first() shouldBe "signatures of dev.jdx.fixtures.Generics#identity"
        text shouldContain "public U identity(U value)"
        text shouldContain "next: jdx show dev.jdx.fixtures.Generics"
    }

    @Test
    fun `erased spelling resolves through the descriptor`() {
        // `U` erases to `Object`: the erased query names the same member (D-009).
        val spelled = JdxService.signature("dev.jdx.fixtures.Generics#identity(U)", fixtureRoots())
        val erased = JdxService.signature("dev.jdx.fixtures.Generics#identity(java.lang.Object)", fixtureRoots())
        erased.exitCode shouldBe 0
        textOf(erased) shouldBe textOf(spelled)
    }

    @Test
    fun `constructor renders under the simple name`() {
        val outcome = JdxService.signature(
            "dev.jdx.fixtures.PersonRecord#<init>(java.lang.String,int)",
            fixtureRoots(),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "PersonRecord(java.lang.String"
    }

    @Test
    fun `bare name lists the field and the method together`() {
        // `seconds` (no parameter list) names the field *and* the method (D-016):
        // one block lists both, exit 0.
        val outcome = JdxService.signature("dev.jdx.fixtures.TrafficLight#seconds", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "private final int seconds"
        textOf(outcome) shouldContain "public int seconds()"
    }

    @Test
    fun `nested type resolves through dollar nesting`() {
        val outcome = JdxService.signature("dev.jdx.fixtures.Nesting\$Inner#outer()", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "outer()"
    }

    @Test
    fun `bridge methods are hidden by default`() {
        // `Child` declares `Child copy()` plus a synthetic `Base copy()` bridge:
        // the default view shows only the real overload.
        val outcome = JdxService.signature("dev.jdx.fixtures.CovariantOverrides\$Child#copy", fixtureRoots())
        outcome.exitCode shouldBe 0
        val signatureLines = textOf(outcome).lines().filter { it.startsWith("  ") }
        signatureLines.size shouldBe 1
        textOf(outcome) shouldContain "Child copy()"
    }

    @Test
    fun `include synthetic reveals the bridge`() {
        val outcome = JdxService.signature(
            "dev.jdx.fixtures.CovariantOverrides\$Child#copy",
            fixtureRoots(),
            SignatureOptions(includeSynthetic = true),
        )
        outcome.exitCode shouldBe 0
        val signatureLines = textOf(outcome).lines().filter { it.startsWith("  ") }
        signatureLines.size shouldBe 2
    }

    @Test
    fun `bridge return suffixes align with their own signatures`() {
        // Regression: refs were once zipped from a sorted list against
        // declaration-ordered matches, swapping the `:return` suffixes.
        val outcome = JdxService.signature(
            "dev.jdx.fixtures.CovariantOverrides\$Child#copy",
            fixtureRoots(),
            SignatureOptions(includeSynthetic = true),
        )
        outcome.exitCode shouldBe 0
        val block = (outcome as ServiceOutcome.SignatureList).block
        block.signatures.size shouldBe 2
        for (entry in block.signatures) {
            val returns = entry.signature.substringBefore(" copy()").substringAfterLast(' ')
            entry.canonicalRef shouldBe "dev.jdx.fixtures.CovariantOverrides\$Child#copy():$returns"
        }
    }

    @Test
    fun `field and method refs align with their kinds`() {
        val outcome = JdxService.signature("dev.jdx.fixtures.TrafficLight#seconds", fixtureRoots())
        outcome.exitCode shouldBe 0
        val block = (outcome as ServiceOutcome.SignatureList).block
        block.signatures.single { it.kind == MemberKind.METHOD }.canonicalRef shouldBe
            "dev.jdx.fixtures.TrafficLight#seconds()"
        block.signatures.single { it.kind == MemberKind.FIELD }.canonicalRef shouldBe
            "dev.jdx.fixtures.TrafficLight#seconds"
    }

    @Test
    fun `under-specified overloads list every overload with exit 0`() {
        // Unlike `body` (exit 2), a signature block can hold many rows — listing
        // is not guessing (D-016).
        val outcome = JdxService.signature("java.lang.StringBuilder#append", RootsSpec())
        outcome.exitCode shouldBe 0
        val signatureLines = textOf(outcome).lines().filter { it.startsWith("  ") }
        (signatureLines.size > 1) shouldBe true
        textOf(outcome) shouldContain "source: java.base (jrt)"
    }

    @Test
    fun `limit truncates with a footer`() {
        val outcome = JdxService.signature(
            "java.lang.StringBuilder#append",
            RootsSpec(),
            SignatureOptions(maxSignatures = 2),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "2 of "
        textOf(outcome) shouldContain "signatures shown (--limit "
    }

    @Test
    fun `negative limit is a usage error`() {
        val outcome = JdxService.signature(
            "dev.jdx.fixtures.Generics#identity(U)",
            fixtureRoots(),
            SignatureOptions(maxSignatures = -1),
        )
        outcome.exitCode shouldBe 3
        textOf(outcome) shouldContain "--limit"
    }

    // -- misses and misuse -------------------------------------------------------

    @Test
    fun `unknown member exits 1 with did-you-mean`() {
        val outcome = JdxService.signature("dev.jdx.fixtures.Generics#noSuchMethod()", fixtureRoots())
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "not found: dev.jdx.fixtures.Generics#noSuchMethod()"
    }

    @Test
    fun `unknown type exits 1 naming the query`() {
        val outcome = JdxService.signature("com.example.Nope#m()", fixtureRoots())
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "not found: com.example.Nope#m()"
    }

    @Test
    fun `ambiguous short type exits 2 with candidates`() {
        val outcome = JdxService.signature("Map#get", RootsSpec())
        outcome.exitCode shouldBe 2
        textOf(outcome) shouldContain "ambiguous:"
    }

    @Test
    fun `type reference exits 3 naming show and members`() {
        val outcome = JdxService.signature("dev.jdx.fixtures.Generics", fixtureRoots())
        outcome.exitCode shouldBe 3
        textOf(outcome) shouldContain "jdx show"
    }

    @Test
    fun `invalid reference exits 3`() {
        val outcome = JdxService.signature("not a ref ((((", fixtureRoots())
        outcome.exitCode shouldBe 3
        textOf(outcome) shouldContain "usage error:"
    }

    @Test
    fun `clinit exits 3`() {
        val outcome = JdxService.signature("dev.jdx.fixtures.Generics#<clinit>", fixtureRoots())
        outcome.exitCode shouldBe 3
        textOf(outcome) shouldContain "static initialisers"
    }

    @Test
    fun `no roots exits 4`() {
        val outcome = JdxService.signature(
            "dev.jdx.fixtures.Generics#identity(U)",
            RootsSpec(jarSpecs = emptyList(), includeJdk = false),
        )
        outcome.exitCode shouldBe 4
        textOf(outcome) shouldContain "no workspace"
    }

    // -- laws --------------------------------------------------------------------

    @Test
    fun `same query twice renders identical bytes`() {
        val first = JdxService.signature("dev.jdx.fixtures.Generics#identity(U)", fixtureRoots())
        val second = JdxService.signature("dev.jdx.fixtures.Generics#identity(U)", fixtureRoots())
        textOf(first) shouldBe textOf(second)
        first.toJson("signature") shouldBe second.toJson("signature")
    }

    @Test
    fun `every shown text signature is covered by the json`() {
        val outcome = JdxService.signature("dev.jdx.fixtures.TrafficLight#seconds", fixtureRoots())
        outcome.exitCode shouldBe 0
        val block = (outcome as ServiceOutcome.SignatureList).block
        val json = outcome.toJson("signature")
        json shouldContain "\"signatures\":["
        for (entry in block.signatures) {
            json shouldContain entry.signature
            json shouldContain entry.canonicalRef
        }
    }
}
