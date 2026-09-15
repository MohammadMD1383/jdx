package dev.jdx.index.artifact

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative tests for [ZipSafety.normalizeEntryName] (TESTING.md §4): no generated input
 * may normalise to an unsafe path. Whatever the fuzzer invents — slashes, dots,
 * backslashes, drive letters — the output is either `null` or a rooted-relative path with
 * no escape segments.
 */
class ZipSafetyPropertyTest {

    private val alphabet: List<Char> =
        ('a'..'e').toList() + ('0'..'1').toList() + listOf('/', '\\', '.', ':', '$', ' ', '_')

    private val rawArb: Arb<String> =
        Arb.list(Arb.of(alphabet), 0..24).map { it.joinToString("") }

    @Test
    fun `normalised output is always safe or null`() = runBlocking<Unit> {
        checkAll(1_000, rawArb) { raw ->
            val normalised = ZipSafety.normalizeEntryName(raw)
            if (normalised != null) {
                (normalised.startsWith("/")) shouldBe false
                val segments = normalised.split('/')
                (segments.any { it.isEmpty() }) shouldBe false
                (segments.any { it == "." || it == ".." }) shouldBe false
            }
        }
    }

    @Test
    fun `idempotent on its own output`() = runBlocking<Unit> {
        checkAll(1_000, Arb.list(Arb.int('a'.code..'z'.code), 1..20)) { codes ->
            val raw = "com/foo/" + codes.map { it.toChar() }.joinToString("") + ".class"
            val once = ZipSafety.normalizeEntryName(raw)
            once shouldNotBe null
            ZipSafety.normalizeEntryName(once!!) shouldBe once
        }
    }
}
