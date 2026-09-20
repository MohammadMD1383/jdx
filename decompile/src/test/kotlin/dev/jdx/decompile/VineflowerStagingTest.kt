package dev.jdx.decompile

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Pure staging-name math behind both engines (T-026 Vineflower, T-027 javap):
 * binary names map to safe staged paths, hostile names map to `null` — never
 * a throw, never an escape from the staging dir. No IO, no engine.
 */
class VineflowerStagingTest {

    @Test
    fun `nested names map to slash paths with a class suffix`() {
        stagedEntryPath("dev.jdx.fixtures.Generics") shouldBe "dev/jdx/fixtures/Generics.class"
        stagedEntryPath("dev.jdx.fixtures.Nesting\$Inner") shouldBe "dev/jdx/fixtures/Nesting\$Inner.class"
    }

    @Test
    fun `hostile names are rejected, not staged`() {
        stagedEntryPath("").shouldBeNull()
        stagedEntryPath("   ").shouldBeNull()
        stagedEntryPath("../evil").shouldBeNull()
        stagedEntryPath("a/../../b").shouldBeNull()
        stagedEntryPath("a/b").shouldBeNull()
        stagedEntryPath("a\\b").shouldBeNull()
        stagedEntryPath(".a").shouldBeNull()
        stagedEntryPath("a.").shouldBeNull()
        stagedEntryPath("a..b").shouldBeNull()
    }

    @Test
    fun `staging never throws and valid outputs stay inside one dir`() = runBlocking<Unit> {
        checkAll(1_000, Arb.string(0, 40)) { name ->
            val staged = stagedEntryPath(name) ?: return@checkAll
            staged shouldContain ".class"
            staged shouldNotContain ".."
            staged shouldNotContain "\\"
            staged.split('/').forEach { segment ->
                if (segment != "unused") check(segment.isNotEmpty())
            }
        }
    }
}
