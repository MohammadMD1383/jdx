package dev.jdx.server

import java.time.Duration
import java.util.Locale

/** The idle shutdown the proposal promises (PROPOSAL.md §14.3): 5 minutes. */
public val DEFAULT_IDLE: Duration = Duration.ofMinutes(5)

/** The `--idle` default as the user types it. */
public const val DEFAULT_IDLE_TEXT: String = "5m"

private val IDLE_PATTERN: Regex = Regex("^(\\d+)([smh]?)$")

/**
 * Parses a `--idle <duration>` value: `<n>s`, `<n>m`, `<n>h`, or a bare `<n>`
 * meaning seconds. `0` disables idle shutdown. Returns `null` for anything
 * else — the CLI maps that to exit 3. Never throws, for any input.
 */
public fun parseIdleDuration(text: String): Duration? {
    val match = IDLE_PATTERN.matchEntire(text.trim().lowercase(Locale.ROOT)) ?: return null
    return try {
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        when (match.groupValues[2]) {
            "", "s" -> Duration.ofSeconds(amount)
            "m" -> Duration.ofMinutes(amount)
            "h" -> Duration.ofHours(amount)
            else -> null // unreachable: the pattern only admits s/m/h
        }
    } catch (_: ArithmeticException) {
        null // `999999999999h` overflows the Duration encoding: refuse, don't wrap
    }
}
