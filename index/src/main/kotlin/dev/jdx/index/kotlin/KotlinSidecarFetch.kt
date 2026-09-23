package dev.jdx.index.kotlin

import dev.jdx.index.maven.MavenCoords
import dev.jdx.index.maven.MavenFetch
import dev.jdx.sources.KOTLIN_COMPILER_VERSION
import dev.jdx.sources.kotlinSidecarDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * One jar of the Kotlin PSI sidecar set (T-080, D-008 §3).
 *
 * Produced by nothing — the set is a fixed table ([KOTLIN_SIDECAR_ARTIFACTS]);
 * consumed by [fetchKotlinSidecar], which downloads each entry from Maven
 * repositories into the sidecar directory.
 */
public data class KotlinSidecarArtifact(
    /** Maven group, e.g. `org.jetbrains.kotlin`. */
    public val group: String,
    /** Maven artifact, e.g. `kotlin-stdlib`. */
    public val artifact: String,
    /** Pinned version, e.g. `2.4.20`. */
    public val version: String,
    /** File name under the sidecar directory, e.g. `kotlin-stdlib-2.4.20.jar`. */
    public val fileName: String,
)

/**
 * The full sidecar set the T-039 PSI loader reads (T-080).
 *
 * `kotlin-compiler-embeddable` does not bundle the Kotlin stdlib (nor its
 * script/reflect/daemon runtimes), so a lone compiler jar cannot initialise —
 * the loader reads the sidecar plus every `.jar` sibling in its directory
 * (D-055 §2). The versions below are the compiler's own dependency closure
 * (from the `kotlin-compiler-embeddable` POM at [KOTLIN_COMPILER_VERSION]),
 * which is also the set the tier-2 PSI suite proves against: the four jars
 * that track the compiler share its version, `kotlin-reflect`,
 * `kotlinx-coroutines-core-jvm` and `annotations` carry the versions the
 * compiler was built against. Change the compiler version and its four
 * followers together (`libs.versions.toml#kotlinCompiler` too).
 */
public val KOTLIN_SIDECAR_ARTIFACTS: List<KotlinSidecarArtifact> = listOf(
    KotlinSidecarArtifact(
        "org.jetbrains.kotlin",
        "kotlin-compiler-embeddable",
        KOTLIN_COMPILER_VERSION,
        "kotlin-compiler-embeddable-$KOTLIN_COMPILER_VERSION.jar",
    ),
    KotlinSidecarArtifact(
        "org.jetbrains.kotlin",
        "kotlin-stdlib",
        KOTLIN_COMPILER_VERSION,
        "kotlin-stdlib-$KOTLIN_COMPILER_VERSION.jar",
    ),
    KotlinSidecarArtifact(
        "org.jetbrains.kotlin",
        "kotlin-script-runtime",
        KOTLIN_COMPILER_VERSION,
        "kotlin-script-runtime-$KOTLIN_COMPILER_VERSION.jar",
    ),
    KotlinSidecarArtifact(
        "org.jetbrains.kotlin",
        "kotlin-reflect",
        "1.6.10",
        "kotlin-reflect-1.6.10.jar",
    ),
    KotlinSidecarArtifact(
        "org.jetbrains.kotlin",
        "kotlin-daemon-embeddable",
        KOTLIN_COMPILER_VERSION,
        "kotlin-daemon-embeddable-$KOTLIN_COMPILER_VERSION.jar",
    ),
    KotlinSidecarArtifact(
        "org.jetbrains.kotlinx",
        "kotlinx-coroutines-core-jvm",
        "1.8.0",
        "kotlinx-coroutines-core-jvm-1.8.0.jar",
    ),
    KotlinSidecarArtifact(
        "org.jetbrains",
        "annotations",
        "13.0",
        "annotations-13.0.jar",
    ),
)

/**
 * What [fetchKotlinSidecar] installed or found (T-080).
 *
 * File names only, in [KOTLIN_SIDECAR_ARTIFACTS] order — never absolute paths,
 * so reports stay deterministic across machines (CLAUDE.md §2.5).
 */
public data class KotlinSidecarReport(
    /** File names downloaded and verified this run. */
    public val installed: List<String>,
    /** File names already present (skipped without network), or re-fetched under `--force`. */
    public val alreadyPresent: List<String>,
)

/**
 * The outcome of [fetchKotlinSidecar]: installed-or-present files, or one
 * message naming the first artifact that could not be fetched. Failures are
 * values, never throws (TESTING.md §7).
 */
public sealed interface KotlinSidecarOutcome {
    /** Every artifact is now present: [report] splits this run's downloads from skips. */
    public data class Ok(public val report: KotlinSidecarReport) : KotlinSidecarOutcome

    /** [message] names the artifact, the repositories tried, and the last cause. */
    public data class Failed(public val message: String) : KotlinSidecarOutcome
}

/**
 * The Maven download URL for [artifact] under [repoBaseUrl] (T-080).
 *
 * Pure: the same `group-dots-to-slashes/artifact/version/fileName` layout
 * [MavenCoords.downloadUrl] serves for `--coord` fetches, so sidecar jars and
 * coordinate jars verify identically.
 */
public fun kotlinSidecarDownloadUrl(artifact: KotlinSidecarArtifact, repoBaseUrl: String): String =
    MavenCoords.downloadUrl(
        dev.jdx.core.model.MavenCoordinate(artifact.group, artifact.artifact, artifact.version),
        artifact.fileName,
        repoBaseUrl,
    )

/**
 * Installs the Kotlin PSI sidecar set under [userHome] (T-080, D-008 §3).
 *
 * Jars already present as non-empty regular files are skipped without network
 * (unless [force] re-fetches everything, repairing the
 * presence-OK/use-degrades split from D-054 §2); missing ones are downloaded
 * from [repoBaseUrls] in order with SHA-1 verification and land atomically, so
 * a killed run never leaves a half-written jar that probes as installed. A
 * re-run resumes where the failure stopped. Never throws: every network,
 * checksum and filesystem failure reads as [KotlinSidecarOutcome.Failed].
 *
 * [fetcher] defaults to live HTTP; tests inject a fake serving canned bytes,
 * so fetch tests never touch the network (T-019 precedent).
 */
public fun fetchKotlinSidecar(
    userHome: Path,
    fetcher: MavenFetch.Fetcher = MavenFetch.httpFetcher(),
    repoBaseUrls: List<String> = listOf(MavenCoords.CENTRAL_BASE_URL),
    force: Boolean = false,
): KotlinSidecarOutcome {
    return try {
        fetchKotlinSidecarOrThrow(userHome, fetcher, repoBaseUrls.ifEmpty {
            listOf(MavenCoords.CENTRAL_BASE_URL)
        }, force)
    } catch (e: Exception) {
        KotlinSidecarOutcome.Failed(
            "cannot install the Kotlin sidecar set: ${e.message ?: e.javaClass.simpleName}",
        )
    }
}

private fun fetchKotlinSidecarOrThrow(
    userHome: Path,
    fetcher: MavenFetch.Fetcher,
    repoBaseUrls: List<String>,
    force: Boolean,
): KotlinSidecarOutcome {
    val dir = try {
        kotlinSidecarDir(userHome)
    } catch (e: Exception) {
        return KotlinSidecarOutcome.Failed(
            "cannot install the Kotlin sidecar set: ${e.message ?: e.javaClass.simpleName}",
        )
    }
    try {
        Files.createDirectories(dir)
    } catch (e: Exception) {
        return KotlinSidecarOutcome.Failed(
            "cannot create the Kotlin sidecar directory ($dir): ${e.message ?: e.javaClass.simpleName}",
        )
    }
    val installed = ArrayList<String>(KOTLIN_SIDECAR_ARTIFACTS.size)
    val alreadyPresent = ArrayList<String>(KOTLIN_SIDECAR_ARTIFACTS.size)
    for (artifact in KOTLIN_SIDECAR_ARTIFACTS) {
        val target = dir.resolve(artifact.fileName)
        if (!force && isPresentJar(target)) {
            alreadyPresent.add(artifact.fileName)
            continue
        }
        var lastReason = "no repositories configured"
        var done = false
        for (repoBaseUrl in repoBaseUrls) {
            val url = kotlinSidecarDownloadUrl(artifact, repoBaseUrl)
            val bytes = when (val fetched = MavenFetch.fetchVerified(url, fetcher)) {
                is MavenFetch.FetchResult.Ok -> fetched.bytes
                is MavenFetch.FetchResult.Failed -> {
                    lastReason = "${fetched.failure.reason} ($url)"
                    continue
                }
            }
            val writeError = MavenFetch.writeAtomically(target, bytes)
            if (writeError != null) {
                lastReason = "cannot write $target: $writeError"
                continue
            }
            done = true
            break
        }
        if (!done) {
            return KotlinSidecarOutcome.Failed(
                "cannot fetch Kotlin sidecar artifact '${artifact.group}:${artifact.artifact}:${artifact.version}' " +
                    "from ${repoBaseUrls.joinToString(", ")} ($lastReason) — " +
                    "nothing was written for ${artifact.fileName}; re-run to resume",
            )
        }
        installed.add(artifact.fileName)
    }
    return KotlinSidecarOutcome.Ok(KotlinSidecarReport(installed, alreadyPresent))
}

/** A present jar: a non-empty regular file — the same signal `probeKotlinToolchain` reads. */
private fun isPresentJar(target: Path): Boolean {
    return try {
        Files.isRegularFile(target) && Files.size(target) > 0
    } catch (e: Exception) {
        false
    }
}
