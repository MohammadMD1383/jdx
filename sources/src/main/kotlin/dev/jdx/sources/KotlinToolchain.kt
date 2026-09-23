package dev.jdx.sources

import java.nio.file.Files
import java.nio.file.Path

/**
 * The pinned `kotlin-compiler-embeddable` version the T-038 sidecar must be.
 * Single source of truth for the runtime path; the build-time pin lives in
 * `gradle/libs.versions.toml#kotlinCompiler` — change both together.
 */
public const val KOTLIN_COMPILER_VERSION: String = "2.4.20"

/** File name of the side-loaded compiler jar for [KOTLIN_COMPILER_VERSION]. */
public const val KOTLIN_COMPILER_JAR: String = "kotlin-compiler-embeddable-2.4.20.jar"

/**
 * Where the side-loaded Kotlin compiler lives (D-008 §3): under the cache
 * root, versioned so an upgrade never talks to a stale jar. Fetched and
 * verified on first use (T-080 wires the fetch); until then its absence is
 * routine, not an error.
 */
public fun kotlinSidecarJar(userHome: Path): Path =
    kotlinSidecarDir(userHome).resolve(KOTLIN_COMPILER_JAR)

/** The directory holding the sidecar plus its runtime jars (T-039). */
public fun kotlinSidecarDir(userHome: Path): Path =
    userHome.resolve(".cache").resolve("jdx").resolve("kotlin")

/**
 * The user-facing hint naming the missing sidecar without absolute paths
 * (T-039): error details must stay deterministic and `~`-relative
 * (CLAUDE.md §2.5), so callers use this instead of the absolute
 * [KotlinSourceParser.detail].
 */
public fun kotlinMissingHint(): String =
    "$KOTLIN_COMPILER_JAR not installed under ~/.cache/jdx/kotlin " +
        "(Kotlin sources unavailable — run `jdx kotlin install` to fetch it, see `jdx doctor`)"

/**
 * Whether the side-loaded Kotlin compiler is installed. A value on every
 * path, never a throw: a missing file, a directory at the path, an unreadable
 * file and a hostile [userHome] all read as [Missing].
 */
public sealed interface KotlinToolchainStatus {
    /** The sidecar jar exists and is a non-empty regular file ([bytes] long). */
    public data class Installed(public val jar: Path, public val bytes: Long) : KotlinToolchainStatus

    /** No usable sidecar jar at [jar] — Kotlin sources degrade, never fail. */
    public data class Missing(public val jar: Path) : KotlinToolchainStatus
}

/**
 * Probes the sidecar jar under [userHome]. Pure filesystem check — no class
 * loading, no network, no parsing — so it is safe on the cold CLI path.
 */
public fun probeKotlinToolchain(userHome: Path): KotlinToolchainStatus {
    val jar = try {
        kotlinSidecarJar(userHome)
    } catch (e: Exception) {
        return KotlinToolchainStatus.Missing(missingJarFallback(userHome))
    }
    return try {
        if (!Files.isRegularFile(jar)) return KotlinToolchainStatus.Missing(jar)
        val bytes = Files.size(jar)
        if (bytes <= 0) KotlinToolchainStatus.Missing(jar) else KotlinToolchainStatus.Installed(jar, bytes)
    } catch (e: Exception) {
        KotlinToolchainStatus.Missing(jar)
    }
}

/** Last-resort path when even resolving the sidecar path throws (never throws itself). */
private fun missingJarFallback(userHome: Path): Path {
    return try {
        userHome.resolve(KOTLIN_COMPILER_JAR)
    } catch (e: Exception) {
        Path.of(KOTLIN_COMPILER_JAR)
    }
}
