package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.render.JdxJson
import dev.jdx.cli.render.envelopeJson
import dev.jdx.index.kotlin.KotlinSidecarOutcome
import dev.jdx.index.kotlin.fetchKotlinSidecar
import dev.jdx.index.maven.MavenCoords
import dev.jdx.index.maven.MavenFetch
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.system.exitProcess

/**
 * `jdx kotlin ...` — Kotlin PSI sidecar management (T-080, D-008 §3).
 *
 * `install` downloads the side-loaded compiler set (`kotlin-compiler-embeddable`
 * plus its stdlib/script/reflect/daemon/coroutines/annotations runtimes, at
 * pinned versions with SHA-1 verification) into `~/.cache/jdx/kotlin`, so
 * `body`/`source`/`doc` can serve `.kt` member bodies and KDoc. Thin by rule
 * (D-004): the artifact table and download policy live in
 * `index/.../kotlin/KotlinSidecarFetch.kt`; here is flag parsing, rendering,
 * and exit codes only. Exits 0 ok · 3 usage error · 5 download/checksum/IO failure.
 */
class KotlinCommand : CoreCliktCommand(name = "kotlin") {
    override fun help(context: Context): String =
        "Manage the Kotlin PSI sidecar: install the side-loaded compiler set " +
            "so body/source/doc can serve Kotlin sources. Without it, Kotlin " +
            "binaries still render via @Metadata and .kt queries degrade."

    override fun run() = Unit
}

/**
 * Builds the `kotlin` group. [fetch] installs the sidecar set for the given
 * home (production: [fetchKotlinSidecar] over live HTTP); tests inject a fake
 * over temp dirs, so no test ever touches the network.
 */
fun kotlinGroup(
    fetch: KotlinSidecarFetchFn = ::defaultKotlinSidecarFetch,
    userHome: Path = defaultKotlinUserHome(),
    terminate: (Int) -> Nothing = ::exitProcess,
): KotlinCommand = KotlinCommand().subcommands(
    KotlinInstallCommand(fetch, userHome, terminate),
)

/** The production fetch behind `kotlin install`: [fetchKotlinSidecar] over live HTTP. */
internal fun defaultKotlinSidecarFetch(
    home: Path,
    fetcher: MavenFetch.Fetcher,
    repoBaseUrls: List<String>,
    force: Boolean,
): KotlinSidecarOutcome = fetchKotlinSidecar(home, fetcher, repoBaseUrls, force)

/** Installs the sidecar set for [home] with [fetcher] over [repoBaseUrls]. */
internal typealias KotlinSidecarFetchFn = (
    home: Path,
    fetcher: MavenFetch.Fetcher,
    repoBaseUrls: List<String>,
    force: Boolean,
) -> KotlinSidecarOutcome

/** The production home: the process user's home directory. */
internal fun defaultKotlinUserHome(): Path =
    Path.of(System.getProperty("user.home"))

/** Thrown by the test terminator instead of killing the test JVM (L-026). */
class KotlinExit(val code: Int) : RuntimeException()

@Serializable
private data class KotlinInstallPayload(
    val installed: List<String>,
    val alreadyPresent: List<String>,
    val message: String,
)

private fun KotlinInstallPayload.toJson(ok: Boolean): String =
    envelopeJson("kotlin install", ok, JdxJson.encodeToJsonElement(this))

/** Prints text + optional JSON, then terminates on non-zero exit (L-026). */
private fun finishKotlinInstall(
    text: String,
    jsonText: String,
    json: Boolean,
    exitCode: Int,
    terminate: (Int) -> Nothing,
) {
    if (json) println(jsonText) else println(text)
    if (exitCode != 0) terminate(exitCode)
}

class KotlinInstallCommand(
    private val fetch: KotlinSidecarFetchFn = ::defaultKotlinSidecarFetch,
    private val userHome: Path = defaultKotlinUserHome(),
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val fetcher: MavenFetch.Fetcher? = null,
) : CoreCliktCommand(name = "install") {
    override fun help(context: Context): String =
        "Download and verify the Kotlin PSI sidecar set " +
            "(kotlin-compiler-embeddable plus its runtime jars, at pinned versions " +
            "with SHA-1 verification) into ~/.cache/jdx/kotlin. Jars already present " +
            "are skipped without network; re-run resumes after a failure. " +
            "Exits 5 when a download, checksum or write fails."

    private val repo by option(
        "--repo",
        help = "Maven repository base URL to fetch from (repeatable, e.g. " +
            "--repo https://repo.example.com/maven2). Tried in flag order before Maven Central, " +
            "which stays last as the fallback. Must be an http(s) URL.",
    ).multiple()

    private val force by option(
        "--force",
        help = "Re-download every jar even when present (repairs a corrupt sidecar " +
            "that probes as installed but fails to initialise).",
    ).flag()

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val json = effectiveJson(json)
        for (url in repo) {
            MavenCoords.invalidRepoReason(url)?.let { reason ->
                val message = "usage error: $reason"
                finishKotlinInstall(
                    message,
                    KotlinInstallPayload(emptyList(), emptyList(), message).toJson(ok = false),
                    json, 3, terminate,
                )
                return
            }
        }
        val baseUrls = if (repo.isEmpty()) {
            listOf(MavenCoords.CENTRAL_BASE_URL)
        } else {
            ReadCommandSupport.buildRepoBaseUrls(repo)
        }
        val outcome = try {
            fetch(userHome, fetcher ?: MavenFetch.httpFetcher(), baseUrls, force)
        } catch (e: Exception) {
            KotlinSidecarOutcome.Failed(
                "cannot install the Kotlin sidecar set: ${e.message ?: e.javaClass.simpleName}",
            )
        }
        when (outcome) {
            is KotlinSidecarOutcome.Ok -> {
                val report = outcome.report
                val text = renderInstallText(report.installed, report.alreadyPresent)
                finishKotlinInstall(
                    text,
                    KotlinInstallPayload(report.installed, report.alreadyPresent, "ok").toJson(ok = true),
                    json, 0, terminate,
                )
            }
            is KotlinSidecarOutcome.Failed -> {
                val message = "artifact read error: ${outcome.message}"
                finishKotlinInstall(
                    message,
                    KotlinInstallPayload(emptyList(), emptyList(), message).toJson(ok = false),
                    json, 5, terminate,
                )
            }
        }
    }
}

/** Renders the install outcome: file names only, never absolute paths (CLAUDE.md §2.5). */
internal fun renderInstallText(installed: List<String>, alreadyPresent: List<String>): String = buildString {
    val total = installed.size + alreadyPresent.size
    appendLine("kotlin sidecar installed ($total jar(s) in ~/.cache/jdx/kotlin)")
    for (name in installed) appendLine("  installed: $name")
    for (name in alreadyPresent) appendLine("  present: $name")
    if (installed.isEmpty()) append("  already complete — nothing to download")
    else append("  Kotlin sources are now available (see `jdx doctor`)")
}.trimEnd()
