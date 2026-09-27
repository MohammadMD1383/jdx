package dev.jdx.diffapi

/**
 * The Kotlin half of the diff fixture pair (issue #23).
 *
 * <p>A diff is where the Kotlin rules earn their keep: these are exactly the differences
 * that are invisible in the JVM projection and visible in the source. A
 * hand-built snapshot can prove the differ *handles* a `KotlinMethodView`; only real
 * `@Metadata` decoded by `KotlinMembers` proves the reader ever *produces* one — and
 * `KOTLIN_SUSPEND_CHANGED` in particular depends on `stripTrailingContinuation` being
 * set, which nothing else would catch if it stopped being.
 *
 * <p>Against the v2 version this fires: `KOTLIN_NAME_CHANGED` ({@link #renamed}),
 * `KOTLIN_SUSPEND_CHANGED` ({@link #fetch}), `KOTLIN_NULLABILITY_CHANGED`
 * ({@link #label}), `KOTLIN_DEFAULT_ARG_REMOVED` ({@link #repeat}),
 * `KOTLIN_PARAMETER_NAME_CHANGED` ({@link #measure}) and
 * `KOTLIN_DEFAULT_ARG_ADDED` ({@link #describe}).
 *
 * <p>Never loaded into a test JVM (D-017) — only read as bytes from the jar.
 */
class Kotlinish {
    /**
     * The Kotlin declaration is `greet`; the JVM name is `renamedForJvm` because of
     * `@JvmName`. Dropping the annotation in v2 changes the JVM name while the Kotlin
     * declaration stays put, which is a `KOTLIN_NAME_CHANGED` rather than a removal
     * plus an addition.
     */
    @JvmName("renamedForJvm")
    fun greet(name: String): String = name

    /** Non-`suspend` in v1; `suspend` in v2, which adds a hidden `Continuation`. */
    fun fetch(id: String): String = id

    /** Non-null in v1, nullable in v2 — one erasure, two Kotlin types. */
    fun label(): String = "label"

    /** Takes a defaulted parameter in v1, and does not in v2. */
    fun repeat(times: Int = 3): String = times.toString()

    /** Takes a defaulted parameter only in v2. */
    fun describe(text: String): String = text

    /** The parameter is called `count` in v1 and `total` in v2. */
    fun measure(count: Int): Int = count

    /**
     * `suspend` on **both** sides, so the JVM signature is identical apart from the
     * return type's nullability marker. A `suspend` function always carries a Kotlin view
     * (the hidden `Continuation` guarantees one), which is what lets the differ compare
     * the *Kotlin* return type under one erasure — the case `KOTLIN_NULLABILITY_CHANGED`
     * exists for. A plain function has no view here, so its nullability change surfaces
     * as a `@NotNull`/`@Nullable` annotation difference instead.
     */
    suspend fun nullable(): String = "nullable"

    /** A `val` in v1 and a `var` in v2, so a setter appears. */
    val current: String = "current"
}
