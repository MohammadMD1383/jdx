package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.jdx.cli.BuildInfo
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.render.JdxJson
import dev.jdx.cli.render.envelopeJson
import dev.jdx.cli.service.UpgradePayload
import dev.jdx.cli.service.UpgradeService
import dev.jdx.cli.service.exitCodeFor
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.system.exitProcess

/**
 * `jdx upgrade [--version TAG] [--check] [--repo OWNER/NAME] [--json]`.
 * Self-update from GitHub Releases. A thin adapter (D-004): [UpgradeService]
 * resolves, verifies and installs; here is flag parsing, rendering, and exit
 * codes only — 0 ok (up to date, available, or upgraded) · 1 release not found ·
 * 3 not a release install · 5 download/checksum/IO failure.
 *
 * Always runs in-process: it replaces the very binary a daemon would serve
 * from, so forwarding it warm would be nonsense. `--check` only resolves the
 * latest tag and reports, never touching the install.
 *
 * The service and the terminator are constructor-injected (defaults: the real
 * release install and [exitProcess]) so tests run fully offline without
 * killing the test JVM.
 */
class UpgradeCommand(
    private val service: UpgradeService = UpgradeService(BuildInfo.version),
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "upgrade") {
    override fun help(context: Context): String =
        "Self-update this jdx from GitHub Releases (latest, or --version TAG). " +
            "Only works on release installs (install-release.sh); source builds are refused. " +
            "--check only reports whether an update exists. " +
            "Exits 1 when the tag has no release, 3 on a non-release install, 5 on download failure."

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    private val checkOnly by option(
        "--check",
        help = "Only report whether an update exists; never download or install.",
    ).flag()

    private val version by option(
        "--version",
        help = "Upgrade (or check) a specific release tag, e.g. --version v1.1.0. " +
            "Defaults to the latest release.",
    )

    private val repo by option(
        "--repo",
        help = "GitHub repo to upgrade from as OWNER/NAME (default MohammadMD1383/jdx).",
    )

    override fun run() {
        val request = UpgradeService.UpgradeRequest(
            checkOnly = checkOnly,
            version = version,
            repo = repo ?: UpgradeService.DEFAULT_REPO,
        )
        val outcome = service.run(request)
        val payload = upgradePayload(outcome)
        if (effectiveJson(json)) {
            println(payload.toJson())
        } else {
            println(payload.message)
        }
        val code = exitCodeFor(outcome)
        if (code != 0) {
            terminate(code)
        }
    }
}

private fun upgradePayload(outcome: UpgradeService.UpgradeOutcome): UpgradePayload = when (outcome) {
    is UpgradeService.UpgradeOutcome.UpToDate ->
        UpgradePayload(
            status = "up-to-date",
            current = outcome.current,
            latest = outcome.latest,
            message = "jdx ${outcome.current} is up to date (latest: ${outcome.latest})",
        )
    is UpgradeService.UpgradeOutcome.Available ->
        UpgradePayload(
            status = "available",
            current = outcome.current,
            latest = outcome.latest,
            message = "update available: jdx ${outcome.current} -> ${outcome.latest} (run `jdx upgrade` to install)",
        )
    is UpgradeService.UpgradeOutcome.Upgraded ->
        UpgradePayload(
            status = "upgraded",
            current = outcome.from,
            latest = outcome.to,
            installDir = outcome.installDir.toString(),
            message = "upgraded jdx ${outcome.from} -> ${outcome.to} (${outcome.installDir})",
        )
    is UpgradeService.UpgradeOutcome.NotReleaseInstall ->
        UpgradePayload(status = "not-release-install", message = "not upgrading: ${outcome.reason}")
    is UpgradeService.UpgradeOutcome.ReleaseNotFound ->
        UpgradePayload(status = "release-not-found", message = "no release '${outcome.tag}' (check the tag name)")
    is UpgradeService.UpgradeOutcome.Failed ->
        UpgradePayload(status = "failed", message = "upgrade failed: ${outcome.reason}")
}

private fun UpgradePayload.toJson(): String =
    envelopeJson("upgrade", status in setOf("up-to-date", "available", "upgraded"), JdxJson.encodeToJsonElement(this))
