package dev.jdx.diffapi

/**
 * The Kotlin half of the diff fixture pair (issue #23) — the candidate version.
 *
 * <p>Every difference from `src/diffv1`'s twin is a *Kotlin-visible* change that the JVM
 * projection alone would either hide or report as an unrelated remove/add pair. That is
 * the whole argument for reading `@Metadata` before diffing.
 *
 * <p>Never loaded into a test JVM (D-017) — only read as bytes from the jar.
 */
class Kotlinish {
    /** The `@JvmName` is gone, so the JVM name is the Kotlin name again. */
    fun greet(name: String): String = name

    /** `suspend` now, so the JVM signature carries a hidden `Continuation`. */
    suspend fun fetch(id: String): String = id

    /** Nullable now, under the same erased signature. */
    fun label(): String? = null

    /** The default value is gone, so a Kotlin caller compiled against v1 keeps passing it. */
    fun repeat(times: Int): String = times.toString()

    /** The parameter is called `total` now, which breaks Kotlin named arguments. */
    fun measure(total: Int): Int = total

    /** Gains a default value on the parameter v1 already declared, so the arity holds. */
    fun describe(text: String = ""): String = text

    /** `suspend` on both sides, so only the Kotlin return type differs. */
    suspend fun nullable(): String? = null

    /** Becomes a `var`, so a setter appears. */
    var current: String = "current"
}
