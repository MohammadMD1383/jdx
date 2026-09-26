package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.jdx.cli.bench.BenchRunner
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.effectiveWorkspace
import dev.jdx.cli.render.benchResult
import dev.jdx.cli.render.renderText
import dev.jdx.cli.render.toJson
import dev.jdx.core.render.ErrorResult
import dev.jdx.index.artifact.ArtifactReadException
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.system.exitProcess

/**
 * `jdx bench` (T-050; PROPOSAL.md §15).
 *
 * Measures the fixed [BenchRunner.CASES] workload against the resolved roots
 * and prints the timing table (text) or the same rows in the standard JSON
 * envelope. A thin adapter (D-004): flag parsing, root resolution and exit
 * codes live here; every measurement lives in [BenchRunner].
 *
 * Roots resolve exactly like the read commands (`--jars`, `--src`, `-w`,
 * `--no-jdk`, `--coord`, `--repo`, `--fetch`). When no explicit roots and no
 * workspace are selected, the `minecraft-client.jar` probe fills in `--jars`
 * (newest `minecraft-client.jar` under `~/.gradle/caches/fabric-loom/`);
 * a missed probe with no roots exits 4 naming `--jars`.
 *
 * §15 targets are advisory: rows print `ok`/`OVER` but success always exits
 * 0 — machine variance gates nothing (the `verifyTier1Budget` precedent).
 * Always runs in-process (no daemon path — the point is cold timing).
 *
 * Exits: 0 measured · 1 no classes to benchmark · 3 usage error (bad
 * `--iterations`) · 4 no roots · 5 artifact read error · 6 internal error.
 */
class BenchCommand(
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
    private val minecraftProbe: () -> Path? = ::defaultMinecraftProbe,
    private val bench: (label: String, jarSpecs: List<String>, roots: JdxService.RootsSpec, iterations: Int) -> BenchRunner.Report =
        { label, jarSpecs, roots, iterations -> BenchRunner.run(label, jarSpecs, roots, JdxService, iterations) },
    private val printer: (String) -> Unit = ::println,
) : CoreCliktCommand(name = "bench") {
    override fun help(context: Context): String =
        "Benchmark the read path: run the fixed load/show/members/search/hierarchy workload " +
            "against the resolved roots and print the timing table with PROPOSAL.md §15 targets. " +
            "Roots resolve like the read commands (--jars plus the selected workspace plus the JDK " +
            "unless --no-jdk); with no explicit roots and no workspace selected, the local " +
            "minecraft-client.jar is used when present. Targets are advisory: success always " +
            "exits 0. Example: jdx bench --iterations 1."

    private val jars by option(
        "--jars",
        help = "Binary roots: jar files, class directories or globs (repeatable). " +
            "Merge in front of the selected workspace's roots.",
    ).multiple()

    private val srcs by option(
        "--src",
        help = "Source-dir roots (repeatable). Merge in front of the selected workspace's stored srcs.",
    ).multiple()

    private val workspace by option(
        "-w",
        "--workspace",
        help = "Use a named workspace (see jdx ws). Explicit --jars merge in front of it.",
    )

    private val noJdk by option(
        "--no-jdk",
        help = "Do not include the running JDK's stdlib (included by default).",
    ).flag()

    private val coord by option(
        "--coord",
        help = "Maven coordinate root group:artifact:version (repeatable).",
    ).multiple()

    private val repo by option(
        "--repo",
        help = "Maven repository base URL for --coord fetches (repeatable).",
    ).multiple()

    private val fetch by option(
        "--fetch",
        help = "Allow downloading --coord artifacts from Maven repositories.",
    ).flag()

    private val iterations by option(
        "--iterations",
        help = "Runs per case; the median is reported (default 3).",
    ).int().default(3)

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        if (iterations < 1) {
            fail(3, "usage error: --iterations must be >= 1, got $iterations")
            return
        }
        val effectiveJars = jars.ifEmpty {
            if (hasExplicitRoots() || hasNamedSelection()) emptyList()
            else listOfNotNull(minecraftProbe()?.toString())
        }
        if (effectiveJars.isEmpty() && !hasExplicitRoots() && !hasNamedSelection()) {
            fail(
                4,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use), no minecraft-client.jar found " +
                    "and no other roots (pass --jars <path> or select a workspace)",
            )
            return
        }
        when (
            val resolved = ReadCommandSupport.resolveRoots(
                effectiveJars,
                noJdk,
                effectiveWorkspace(workspace),
                store,
                getenv,
                discover = discover ?: ReadCommandSupport::discoverProject,
                coords = coord,
                allowFetch = fetch,
                repos = repo,
                srcs = srcs,
            )
        ) {
            is ReadCommandSupport.RootsOrFailure.Failed -> {
                val outcome = resolved.outcome
                if (effectiveJson(json)) printer(outcome.toJson("bench")) else printer(outcome.renderText(false))
                terminate(outcome.exitCode)
            }
            is ReadCommandSupport.RootsOrFailure.Ready -> {
                val roots = resolved.roots
                if (roots.jarSpecs.isEmpty()) {
                    fail(4, "no workspace: bench needs jar roots: pass --jars <path> or select a workspace")
                    return
                }
                val label = roots.jarSpecs.map { Path.of(it).fileName?.toString() ?: it }.sorted().joinToString(",")
                try {
                    val report = bench(label, roots.jarSpecs, roots, iterations)
                    val result = benchResult(report)
                    if (effectiveJson(json)) printer(result.toJson()) else printer(result.renderText())
                } catch (e: IllegalArgumentException) {
                    fail(ErrorResult.notFound("", detail = e.message ?: "no classes to benchmark"))
                } catch (e: ArtifactReadException) {
                    fail(5, e.message ?: "artifact read error")
                } catch (e: Exception) {
                    fail(6, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
                }
            }
        }
    }

    /** Prints a bench failure in text or the JSON envelope and exits with [code]. */
    private fun fail(code: Int, message: String) =
        fail(ErrorResult.generic("", exitCode = code, message = message))

    /** Prints a bench [error] in text or the JSON envelope and exits with its code. */
    private fun fail(error: ErrorResult) {
        val outcome = JdxService.ServiceOutcome.Failure(error)
        if (effectiveJson(json)) printer(outcome.toJson("bench")) else printer(outcome.renderText(false))
        terminate(error.exitCode)
    }

    private fun hasExplicitRoots(): Boolean =
        jars.isNotEmpty() || srcs.isNotEmpty() || coord.isNotEmpty()

    private fun hasNamedSelection(): Boolean {
        val env = try {
            getenv("JDX_WORKSPACE")
        } catch (e: SecurityException) {
            null
        }
        val active = try {
            store.activeName()
        } catch (e: Exception) {
            null
        }
        val flag = effectiveWorkspace(workspace)
        return !flag?.trim().orEmpty().isEmpty() ||
            !env?.trim().orEmpty().isEmpty() ||
            !active?.trim().orEmpty().isEmpty()
    }
}

/**
 * Finds the newest `minecraft-client.jar` under the fabric-loom cache
 * (`~/.gradle/caches/fabric-loom/<version>/minecraft-client.jar`), or `null`
 * when absent. Never throws — a missed probe reads as "no default jar".
 * [getenv] is injectable so tests script `HOME`-unset Windows layouts
 * (`USERPROFILE`) without environment surgery.
 */
fun defaultMinecraftProbe(getenv: (String) -> String? = System::getenv): Path? = runCatching {
    val home = getenv("HOME") ?: getenv("USERPROFILE") ?: System.getProperty("user.home")
    val loom = Path.of(home, ".gradle", "caches", "fabric-loom")
    if (!Files.isDirectory(loom)) return@runCatching null
    val hits = ArrayList<Path>()
    Files.list(loom).use { versions ->
        versions.filter { Files.isDirectory(it) }.forEach { version ->
            val candidate = version.resolve("minecraft-client.jar")
            if (candidate.isRegularFile()) hits.add(candidate)
        }
    }
    hits.maxByOrNull { Files.getLastModifiedTime(it) }
}.getOrNull()
