package dev.jdx.cli.service

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Property tests for [parseToolVersion] (TESTING.md §4): generated `*-version` outputs —
 * plain, legacy `1.x`, quoted, suffixed — always parse to their major, and digit-free
 * garbage never parses.
 */
class ToolVersionParserTest {

    private data class GeneratedVersion(val line: String, val major: Int)

    // Note: 1 is deliberately absent from the majors. With legacy=false it would compose
    // "1.0.0"-style strings that are neither real legacy ("1.8.0_292" → 8) nor real modern
    // output — the parser reads the post-"1." component (0), which is correct for input no
    // JDK ever prints, but contradicts the generator's bookkeeping. Don't generate it.
    private val versionArb: Arb<GeneratedVersion> = Arb.bind(
        Arb.of(5, 6, 7, 8, 9, 11, 17, 20, 21, 22, 26, 30, 45),
        Arb.int(0..30),
        Arb.int(0..400),
        Arb.of(true, false),
        Arb.of("", "javap ", "openjdk version ", "java version "),
        Arb.of("", "\""),
        Arb.of("", "-ea", "-internal"),
    ) { major, minor, patch, legacy, prefix, quote, suffix ->
        val version = if (legacy && major < 10) {
            "1.$major.$minor" + "_$patch" + suffix
        } else {
            "$major.$minor.$patch" + suffix
        }
        GeneratedVersion("$prefix$quote$version$quote", major)
    }

    @Test
    fun `generated tool versions parse to their major`() = runBlocking<Unit> {
        checkAll(300, versionArb) { generated ->
            parseToolVersion(generated.line) shouldBe generated.major
        }
    }

    @Test
    fun `digit-free garbage never parses`() = runBlocking<Unit> {
        checkAll(200, Arb.string(0..30).filter { it.none(Char::isDigit) }) { garbage ->
            parseToolVersion(garbage) shouldBe null
        }
    }

    @Test
    fun `real-world outputs parse`() {
        parseToolVersion("26.0.2.1") shouldBe 26
        parseToolVersion("javap 17.0.7") shouldBe 17
        parseToolVersion("1.8.0_292") shouldBe 8
        parseToolVersion("openjdk version \"21.0.3\" 2024-04-16") shouldBe 21
        parseToolVersion("") shouldBe null
    }
}
