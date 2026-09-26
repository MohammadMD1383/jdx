package dev.jdx.sources

import dev.jdx.core.paths.JdxOs
import dev.jdx.core.paths.JdxPaths
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
 * Where the side-loaded Kotlin compiler lives (D-008 §3): under the per-OS
 * cache root (D-044), versioned so an upgrade never talks to a stale jar.
 * Fetched and verified on first use (T-080 wires the fetch); until then its
 * absence is routine, not an error.
 *
 * [os]/[env] default to the ambient machine (production); tests inject an
 * explicit pair (e.g. `LINUX` + empty env) so the resolved dir stays under a
 * temp home on every OS instead of leaking into the real machine's cache
 * (`%LOCALAPPDATA%` wins over any injected home on Windows, `~/Library` on
 * macOS — both ambient, both shared across parallel tests).
 */
public fun kotlinSidecarJar(
    userHome: Path,
    os: JdxOs = ambientOs(),
    env: Map<String, String> = System.getenv(),
): Path = kotlinSidecarDir(userHome, os, env).resolve(KOTLIN_COMPILER_JAR)

/** The directory holding the sidecar plus its runtime jars (T-039). Same [os]/[env] seam. */
public fun kotlinSidecarDir(
    userHome: Path,
    os: JdxOs = ambientOs(),
    env: Map<String, String> = System.getenv(),
): Path = cacheRootFor(userHome, os, env).resolve("kotlin")

/** The sidecar directory under an explicit cache root (`--cache-dir` wins). */
public fun kotlinSidecarDirForCache(cacheRoot: Path): Path = cacheRoot.resolve("kotlin")

/** Resolves the per-OS cache root for [userHome] (D-044; never throws). */
private fun cacheRootFor(userHome: Path, os: JdxOs, env: Map<String, String>): Path = runCatching {
    JdxPaths.cacheRoot(userHome, os, env)
}.getOrDefault(JdxPaths.legacyCacheRoot(userHome))

/** The ambient OS, defaulting to Linux when `os.name` is unreadable (never throws). */
internal fun ambientOs(): JdxOs = runCatching {
    JdxPaths.detectOs(System.getProperty("os.name", ""))
}.getOrDefault(JdxOs.LINUX)

/**
 * The user-facing hint naming the missing sidecar without absolute paths
 * (T-039): error details must stay deterministic and `~`-relative
 * (AGENTS.md), so callers use this instead of the absolute
 * [KotlinSourceParser.detail].
 */
public fun kotlinMissingHint(): String =
    "$KOTLIN_COMPILER_JAR not installed under <cache-dir>/kotlin " +
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
 *
 * The home spelling resolves per OS and ambient environment by default (D-044);
 * inject [os]/[env] to pin it (tests: `LINUX` + empty env stays under a temp
 * home). Callers with an already-resolved root (an injected test env, an
 * explicit `--cache-dir`) probe [probeKotlinToolchainAt] instead, so tests
 * never read the real machine's cache.
 */
public fun probeKotlinToolchain(
    userHome: Path,
    os: JdxOs = ambientOs(),
    env: Map<String, String> = System.getenv(),
): KotlinToolchainStatus {
    val jar = try {
        kotlinSidecarJar(userHome, os, env)
    } catch (e: Exception) {
        return KotlinToolchainStatus.Missing(missingJarFallback(userHome))
    }
    return probeKotlinToolchainAt(jar)
}

/**
 * Probes one sidecar jar path: a non-empty regular file reads [Installed],
 * anything else [Missing]. Never throws.
 */
public fun probeKotlinToolchainAt(jar: Path): KotlinToolchainStatus {
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
