package dev.jdx.index.service

import dev.jdx.index.service.JdxService.MemberFilters
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-2 acceptance for the T-077 Kotlin views over the fixture jar: `members`,
 * `outline` and `signature` render Kotlin declarations (`@JvmName`, `suspend`,
 * mangled `internal`), queries accept both spellings, and Java output is
 * byte-identical. Rendering details are pinned by `core`'s `KotlinViewTest`;
 * here the assertions are behavioural — resolution, exit codes, text⊆JSON.
 */
@Tag("tier2")
class KotlinViewsServiceTest {

    private fun fixtureRoots(): RootsSpec =
        RootsSpec(jarSpecs = listOf(FixtureJars.binaryJar().absolutePath), includeJdk = false)

    private fun textOf(outcome: ServiceOutcome): String = outcome.renderText(false)

    private fun membersText(): String {
        val outcome = JdxService.members("dev.jdx.fixtures.KotlinMembers", fixtureRoots(), MemberFilters())
        outcome.exitCode shouldBe 0
        return textOf(outcome)
    }

    @Test
    fun `members renders the JvmName declaration name`() {
        val text = membersText()
        text shouldContain "originalName(int value)"
        text shouldNotContain "renamedForJvm"
    }

    @Test
    fun `members renders suspend without its Continuation`() {
        val text = membersText()
        text shouldContain "suspend java.lang.String fetch(java.lang.String id)"
        text shouldNotContain "Continuation"
        text shouldNotContain "java.lang.Object fetch("
    }

    @Test
    fun `members demangles internal names`() {
        val text = membersText()
        text shouldContain "internalHelper()"
        text shouldNotContain "internalHelper\$testfixtures"
    }

    @Test
    fun `members keeps the JVM view for unmapped Kotlin members`() {
        val text = membersText()
        // `@JvmOverloads` (I) overload and the `@JvmStatic` bridge have no
        // metadata entry — they stay JVM (T-078 owns overloads/properties).
        text shouldContain "withDefault(int first)"
        text shouldContain "create()"
    }

    @Test
    fun `outline renders the same Kotlin view`() {
        val outcome = JdxService.outline("dev.jdx.fixtures.KotlinMembers", fixtureRoots(), MemberFilters())
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "originalName(int value)"
        text shouldContain "suspend java.lang.String fetch(java.lang.String id)"
        text shouldContain "internalHelper()"
    }

    @Test
    fun `signature resolves the Kotlin spelling`() {
        val outcome = JdxService.signature("dev.jdx.fixtures.KotlinMembers#originalName", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "public final int originalName(int value)"
    }

    @Test
    fun `signature still resolves the JVM spelling`() {
        val outcome = JdxService.signature("dev.jdx.fixtures.KotlinMembers#renamedForJvm", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "public final int originalName(int value)"
    }

    @Test
    fun `signature resolves suspend at Kotlin arity`() {
        val kotlin = JdxService.signature(
            "dev.jdx.fixtures.KotlinMembers#fetch(java.lang.String)",
            fixtureRoots(),
        )
        kotlin.exitCode shouldBe 0
        val text = textOf(kotlin)
        text shouldContain "suspend java.lang.String fetch(java.lang.String id)"
        // The JVM arity names the same member (D-009): same answer, same exit.
        val jvm = JdxService.signature(
            "dev.jdx.fixtures.KotlinMembers#fetch(java.lang.String,kotlin.coroutines.Continuation)",
            fixtureRoots(),
        )
        jvm.exitCode shouldBe 0
        textOf(jvm) shouldBe text
    }

    @Test
    fun `signature resolves the demangled internal name`() {
        val outcome = JdxService.signature("dev.jdx.fixtures.KotlinMembers#internalHelper", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "public final void internalHelper()"
    }

    @Test
    fun `did-you-mean suggests the Kotlin spelling`() {
        val outcome = JdxService.signature("dev.jdx.fixtures.KotlinMembers#originalNam", fixtureRoots())
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "dev.jdx.fixtures.KotlinMembers#originalName(int)"
    }

    @Test
    fun `refs use Kotlin names in JSON`() {
        val outcome = JdxService.members("dev.jdx.fixtures.KotlinMembers", fixtureRoots(), MemberFilters())
        outcome.exitCode shouldBe 0
        // Text rows carry kind + signature (refs live in JSON per D-007).
        textOf(outcome) shouldContain "suspend java.lang.String fetch(java.lang.String id)"
        val json = (outcome as ServiceOutcome.MemberList).listing.toJson("members")
        json shouldContain "dev.jdx.fixtures.KotlinMembers#fetch(java.lang.String)"
        json shouldContain "suspend java.lang.String fetch(java.lang.String id)"
        json shouldNotContain "renamedForJvm"
    }

    @Test
    fun `java members are untouched`() {
        val outcome = JdxService.signature("dev.jdx.fixtures.TrafficLight#seconds", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "public int seconds()"
    }

    @Test
    fun `a kotlin data class folds properties and annotates copy defaults`() {
        // T-078 owns properties/`$default`: getters hide, `val`/`var` rows appear,
        // and `copy` (all-defaulted in Kotlin) annotates `= ...` per param.
        val outcome = JdxService.members("dev.jdx.fixtures.KotlinData", fixtureRoots(), MemberFilters())
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "property public final val java.lang.String name"
        text shouldContain "property public final var int count"
        text shouldContain "copy(java.lang.String name = ..., int count = ...)"
        text shouldNotContain "getName()"
        text shouldNotContain "setCount("
    }

    @Test
    fun `signature resolves a property by its Kotlin name`() {
        val outcome = JdxService.signature("dev.jdx.fixtures.KotlinData#name", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "val java.lang.String name"
    }

    @Test
    fun `signature still resolves the JVM getter spelling`() {
        val outcome = JdxService.signature("dev.jdx.fixtures.KotlinData#getName", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "getName()"
    }

    @Test
    fun `members annotates default args with equals-dotdotdot`() {
        val outcome = JdxService.members("dev.jdx.fixtures.KotlinMembers", fixtureRoots(), MemberFilters())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "withDefault(int first, java.lang.String second = ...)"
    }

    @Test
    fun `include-synthetic reveals the folded accessors`() {
        val outcome = JdxService.members(
            "dev.jdx.fixtures.KotlinData",
            fixtureRoots(),
            MemberFilters(),
            includeSynthetic = true,
        )
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "getName()"
        text shouldContain "property public final val java.lang.String name"
    }

    @Test
    fun `kind property lists only properties`() {
        val outcome = JdxService.members(
            "dev.jdx.fixtures.KotlinData",
            fixtureRoots(),
            MemberFilters(kind = JdxService.KindFilter.PROPERTY),
        )
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "property public final val"
        text shouldNotContain "method "
    }
}
