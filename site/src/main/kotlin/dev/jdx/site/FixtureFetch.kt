package dev.jdx.site

/**
 * Bounded retry for the example-fixture downloads the site build makes with `--fetch`.
 *
 * Maven Central blips (the Pages failure on 6caecae: "network unreachable, artifact
 * missing, or checksum rejected") are transient. A checksum mismatch is not: the
 * resolver reports that case without the "network unreachable" clause, and retrying
 * it cannot succeed. After the last attempt the original failure is returned unchanged
 * so [SiteBuilder] still fails with "could not fetch the example fixture".
 */
internal object FixtureFetch {
    /** First try plus three retries. */
    const val MAX_ATTEMPTS = 4

    /** Sleep between attempts: after the 1st, 2nd, and 3rd failure. Nothing after the last. */
    val BACKOFF_MS: List<Long> = listOf(2_000L, 5_000L, 10_000L)

    /**
     * True when [result] is a fetch that may succeed if tried again.
     * A distinguished checksum rejection is false.
     */
    fun isTransientFetchFailure(result: JdxResult): Boolean {
        if (result.exitCode == 0) return false
        val text = result.stdout + result.stderr
        if (isChecksumRejection(text)) return false
        return text.contains("cannot fetch Maven coordinate") || text.contains("network unreachable")
    }

    /**
     * A checksum mismatch the resolver called out on its own.
     * The combined failure line mentions "checksum rejected" next to "network unreachable";
     * that one is not distinguishable and is treated as transient.
     */
    fun isChecksumRejection(text: String): Boolean {
        if (text.contains("checksum mismatch")) return true
        return text.contains("checksum rejected") && !text.contains("network unreachable")
    }

    /**
     * Runs [attempt] up to [MAX_ATTEMPTS] times. [sleep] is called only between tries,
     * with [BACKOFF_MS]. [onRetry] sees the 1-based attempt that just failed.
     */
    fun retry(
        attempt: () -> JdxResult,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        onRetry: (attempt: Int, delayMs: Long) -> Unit = { _, _ -> },
    ): JdxResult {
        var last: JdxResult? = null
        for (index in 0 until MAX_ATTEMPTS) {
            val result = attempt()
            if (!isTransientFetchFailure(result)) return result
            last = result
            val delay = BACKOFF_MS.getOrNull(index) ?: break
            onRetry(index + 1, delay)
            sleep(delay)
        }
        return checkNotNull(last) { "fixture fetch retry made no attempt" }
    }
}
