package dev.jdx.server

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration

/** Tier 1: `--idle` parsing is pure — no disk, no sockets. */
class DaemonIdleTest {

    @Test
    fun `plain zero disables idle shutdown`() {
        parseIdleDuration("0") shouldBe Duration.ZERO
    }

    @Test
    fun `seconds minutes and hours parse`() {
        parseIdleDuration("30s") shouldBe Duration.ofSeconds(30)
        parseIdleDuration("5m") shouldBe Duration.ofMinutes(5)
        parseIdleDuration("2h") shouldBe Duration.ofHours(2)
    }

    @Test
    fun `a bare number means seconds`() {
        parseIdleDuration("90") shouldBe Duration.ofSeconds(90)
    }

    @Test
    fun `surrounding whitespace and case are accepted`() {
        parseIdleDuration("  5M  ") shouldBe Duration.ofMinutes(5)
    }

    @Test
    fun `garbage is refused, not guessed`() {
        for (text in listOf("", " ", "soon", "-5s", "5x", "1d", "5.5m", "m", "99999999999999999999h")) {
            parseIdleDuration(text) shouldBe null
        }
    }

    @Test
    fun `the default text is the five-minute promise`() {
        parseIdleDuration(DEFAULT_IDLE_TEXT) shouldBe DEFAULT_IDLE
        DEFAULT_IDLE shouldBe Duration.ofMinutes(5)
    }

    @Test
    fun `zero is distinct from unset`() {
        // `0` disables; callers map null/zero to "no idle task". The parse layer
        // must preserve the difference between "0" and garbage.
        parseIdleDuration("0") shouldNotBe null
    }

    @Test
    fun `parsing never throws on hostile input`(): Unit = runBlocking {
        checkAll(1_000, Arb.string()) { text ->
            parseIdleDuration(text) // returns a Duration or null — never raises
        }
    }

    @Test
    fun `parsing never throws on hostile numbers with unit suffixes`(): Unit = runBlocking {
        val units = listOf("", "s", "m", "h")
        checkAll(1_000, Arb.int(), Arb.string(0..3)) { amount, suffix ->
            if (suffix in units) {
                parseIdleDuration("$amount$suffix") // may be null (negative) — never raises
            }
        }
    }
}
