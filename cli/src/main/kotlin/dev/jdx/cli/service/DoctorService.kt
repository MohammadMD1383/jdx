package dev.jdx.cli.service

import dev.jdx.index.artifact.JdkLayout
import dev.jdx.server.DaemonProbe
import dev.jdx.server.DaemonStatusSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The `jdx doctor` result model. One row per check; severities follow D-027:
 * FAIL means jdx cannot do its core job in this environment, WARN means degraded or
 * not-yet-available, OK means fine (including correctly-absent: no daemon, no workspace).
 */
@Serializable
enum class DoctorStatus {
    @SerialName("ok")
    OK,

    @SerialName("warn")
    WARN,

    @SerialName("fail")
    FAIL,
}

/** One `jdx doctor` row. Details are single-line by construction (see [DoctorService]). */
@Serializable
data class DoctorCheck(val name: String, val status: DoctorStatus, val detail: String)

/** The full `jdx doctor` report: the one model both renderers read (D-007). */
@Serializable
data class DoctorReport(val checks: List<DoctorCheck>) {
    val hasFailures: Boolean get() = checks.any { it.status == DoctorStatus.FAIL }
}

/** Outcome of running an external tool, e.g. `javap -version`. */
data class ProcessOutcome(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Runs an external tool. A `fun interface` so tests inject fakes (absent/crashing/garbage
 * `javap`); production uses [RealProcessRunner]. May throw [IOException] — the service turns
 * that into a FAIL row, never a crash (T-005 acceptance).
 */
fun interface ProcessRunner {
    @Throws(IOException::class)
    fun run(executable: Path, args: List<String>): ProcessOutcome
}

/** [ProcessRunner] over [ProcessBuilder]. Tool output is tiny; sequential reads are safe. */
val RealProcessRunner: ProcessRunner = ProcessRunner { executable, args ->
    val process = ProcessBuilder(listOf(executable.toString()) + args).start()
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    ProcessOutcome(process.waitFor(), stdout, stderr)
}

/** Facts about the running JVM that tests must be able to fake (notably jrt-fs). */
data class RuntimeInfo(val version: String, val jrtReachable: Boolean, val jrtReason: String? = null) {
    companion object {
        /** Probes the real runtime. Never throws: an unreachable jrt becomes data. */
        fun current(): RuntimeInfo {
            val version = System.getProperty("java.version", "unknown")
            return try {
                FileSystems.getFileSystem(URI.create("jrt:/"))
                RuntimeInfo(version, jrtReachable = true)
            } catch (e: Exception) {
                RuntimeInfo(version, jrtReachable = false, jrtReason = e.message ?: e.javaClass.simpleName)
            }
        }
    }
}

/**
 * Everything `doctor` reads from the outside world, in one injectable value. Production uses
 * [system]; tests construct arbitrary environments (missing/unreadable dirs, fake `javap`,
 * unreachable jrt) — that is what makes the never-throws and severity contracts testable.
 */
data class DoctorEnvironment(
    val userHome: Path,
    val javaHome: Path,
    /** `$JAVA_HOME` when set — the second `src.zip` candidate (T-012). Null means unset. */
    val javaHomeEnv: Path? = null,
    val pathDirs: List<Path>,
    val runtimeDir: Path?,
    val workingDir: Path,
    val runtime: RuntimeInfo,
    val processRunner: ProcessRunner,
    /** `$JDX_WORKSPACE` when set — the named-workspace override (T-015). Null means unset. */
    val workspaceEnv: String? = null,
) {
    companion object {
        /**
         * The real environment. Cache/config roots are the literal `~/.cache/jdx` and
         * `~/.config/jdx` from D-013/T-015 — no XDG fallback (D-027).
         */
        fun system(): DoctorEnvironment {
            val userHome = Paths.get(System.getProperty("user.home"))
            val javaHome = Paths.get(System.getProperty("java.home"))
            val pathDirs = System.getenv("PATH")
                ?.split(File.pathSeparator)
                ?.filter { it.isNotEmpty() }
                ?.map { Paths.get(it) }
                ?: emptyList()
            val runtimeDir = System.getenv("XDG_RUNTIME_DIR")
                ?.takeIf { it.isNotEmpty() }
                ?.let { Paths.get(it) }
            val workingDir = Paths.get("").toAbsolutePath()
            return DoctorEnvironment(
                userHome = userHome,
                javaHome = javaHome,
                javaHomeEnv = JdkLayout.envJavaHome(),
                pathDirs = pathDirs,
                runtimeDir = runtimeDir,
                workingDir = workingDir,
                runtime = RuntimeInfo.current(),
                processRunner = RealProcessRunner,
                workspaceEnv = System.getenv("JDX_WORKSPACE"),
            )
        }
    }
}

/**
 * Runs every `jdx doctor` check and returns the report. Read-only by construction — no check
 * writes anything. Never throws: each check is isolated, and any exception becomes a FAIL
 * row naming the check (T-005 acceptance).
 *
 * @param daemonProbe answers `health` for one socket path, or null when no daemon answers.
 * Defaults to the real [DaemonProbe.health]; tests inject fakes (running/stale/throwing).
 * A throwing probe reads as stale, never as a crash (T-084).
 */
class DoctorService(
    private val environment: DoctorEnvironment,
    private val daemonProbe: (Path) -> DaemonStatusSnapshot? = { socket -> DaemonProbe.health(socket) },
) {

    fun probe(): DoctorReport {
        val checks = listOf(
            "jdk" to ::jdkCheck,
            "jrt" to ::jrtCheck,
            "javap" to ::javapCheck,
            "jdk-sources" to ::jdkSourcesCheck,
            "cache" to ::cacheCheck,
            "config" to ::configCheck,
            "index" to ::indexCheck,
            "kotlin" to ::kotlinCheck,
            "daemon" to ::daemonCheck,
            "workspace" to ::workspaceCheck,
        )
        return DoctorReport(checks.map { (name, check) ->
            try {
                check()
            } catch (e: Exception) {
                check(name, DoctorStatus.FAIL, "check failed: ${e.message ?: e.javaClass.simpleName}")
            }
        })
    }

    private fun jdkCheck(): DoctorCheck {
        val environment = environment
        return check("jdk", DoctorStatus.OK, "${environment.runtime.version} (${environment.javaHome})")
    }

    private fun jrtCheck(): DoctorCheck {
        val runtime = environment.runtime
        return if (runtime.jrtReachable) {
            check("jrt", DoctorStatus.OK, "jrt:/ filesystem reachable")
        } else {
            check("jrt", DoctorStatus.FAIL, "jrt:/ filesystem unreachable: ${runtime.jrtReason ?: "unknown reason"}")
        }
    }

    private fun javapCheck(): DoctorCheck {
        val environment = environment
        val candidates = listOf(environment.javaHome.resolve("bin/javap")) +
            environment.pathDirs.map { it.resolve("javap") }
        val executable = candidates.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
            ?: return check(
                "javap",
                DoctorStatus.FAIL,
                "not found (looked in ${environment.javaHome.resolve("bin")} and on PATH) — install a full JDK",
            )
        val outcome = environment.processRunner.run(executable, listOf("-version"))
        if (outcome.exitCode != 0) {
            return check(
                "javap",
                DoctorStatus.FAIL,
                "$executable exits ${outcome.exitCode}: ${(outcome.stdout + "\n" + outcome.stderr).oneLine()}",
            )
        }
        val versionText = (outcome.stdout + "\n" + outcome.stderr).trim()
        val major = parseToolVersion(versionText)
            ?: return check("javap", DoctorStatus.FAIL, "unparseable -version output: ${versionText.oneLine()}")
        return if (major < MINIMUM_JAVAP_MAJOR) {
            check(
                "javap",
                DoctorStatus.WARN,
                "$executable is version $versionText, older than $MINIMUM_JAVAP_MAJOR — " +
                    "the javap differential oracle needs $MINIMUM_JAVAP_MAJOR+",
            )
        } else {
            check("javap", DoctorStatus.OK, "$versionText ($executable)")
        }
    }

    private fun jdkSourcesCheck(): DoctorCheck {
        // One shared lookup with the `jrt:/` root (T-012): found sources read OK, a
        // missing src.zip is a WARN — some distributions omit it — never an error.
        val found = JdkLayout.findSrcZip(environment.javaHome, environment.javaHomeEnv)
        return if (found != null) {
            check("jdk-sources", DoctorStatus.OK, "present ($found)")
        } else {
            val searched = listOfNotNull(
                environment.javaHome.resolve("lib/src.zip"),
                environment.javaHomeEnv?.resolve("lib/src.zip"),
            ).distinct().joinToString(" and ")
            check("jdk-sources", DoctorStatus.WARN, "absent ($searched) — JDK sources unavailable")
        }
    }

    private fun cacheCheck(): DoctorCheck {
        val root = environment.userHome.resolve(".cache/jdx")
        if (!Files.exists(root)) {
            return check("cache", DoctorStatus.WARN, "absent ($root) — created on first use")
        }
        if (!Files.isWritable(root)) {
            return check("cache", DoctorStatus.FAIL, "$root is not writable")
        }
        val size = Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
        }
        return check("cache", DoctorStatus.OK, "present ($root, ${formatBytes(size)}, writable)")
    }

    private fun configCheck(): DoctorCheck {
        val root = environment.userHome.resolve(".config/jdx")
        return if (Files.exists(root)) {
            check("config", DoctorStatus.OK, "present ($root)")
        } else {
            check("config", DoctorStatus.WARN, "absent ($root) — created on first use")
        }
    }

    private fun indexCheck(): DoctorCheck {
        // The index itself lands in M2 (T-013); until then this row only reports presence.
        val db = environment.userHome.resolve(".cache/jdx/index/v1.db")
        return if (Files.isRegularFile(db)) {
            check("index", DoctorStatus.WARN, "present ($db) — schema check lands with the index (M2)")
        } else {
            check("index", DoctorStatus.WARN, "no database ($db) — the index lands in M2")
        }
    }

    private fun kotlinCheck(): DoctorCheck {
        // T-038: the side-loaded compiler (D-008) is a versioned sidecar under the cache
        // root. Present reads OK; absent is a WARN — Kotlin binaries still render via
        // @Metadata (T-035), only `.kt` sources need the sidecar (T-039).
        return when (val status = dev.jdx.sources.probeKotlinToolchain(environment.userHome)) {
            is dev.jdx.sources.KotlinToolchainStatus.Installed -> check(
                "kotlin",
                DoctorStatus.OK,
                "kotlin-compiler-embeddable ${dev.jdx.sources.KOTLIN_COMPILER_VERSION} " +
                    "present (${status.jar})",
            )
            is dev.jdx.sources.KotlinToolchainStatus.Missing -> check(
                "kotlin",
                DoctorStatus.WARN,
                "side-loaded compiler not installed (${status.jar}) — " +
                    "run `jdx kotlin install` to fetch it; Kotlin sources unavailable (D-008)",
            )
        }
    }

    private fun daemonCheck(): DoctorCheck {
        // T-084: every `.sock` file is probed with `health` (T-041). An answering socket is
        // a running daemon (OK); a silent one is stale (WARN — housekeeping, never FAIL:
        // a stale file blocks no query). Details name file names for stale sockets (the
        // hash carries no workspace name back) and workspace names for running ones.
        val runtimeDir = environment.runtimeDir
            ?: return check("daemon", DoctorStatus.OK, "not running (XDG_RUNTIME_DIR is unset)")
        val socketDir = runtimeDir.resolve("jdx")
        if (!Files.isDirectory(socketDir)) {
            return check("daemon", DoctorStatus.OK, "not running")
        }
        val sockets = Files.list(socketDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".sock") }.sorted().toList()
        }
        if (sockets.isEmpty()) {
            return check("daemon", DoctorStatus.OK, "not running")
        }
        val running = mutableListOf<String>()
        val stale = mutableListOf<String>()
        for (socket in sockets) {
            val snapshot = try {
                daemonProbe(socket)
            } catch (_: Exception) {
                null
            }
            if (snapshot != null) {
                running.add(snapshot.workspace)
            } else {
                stale.add(socket.fileName.toString())
            }
        }
        running.sort()
        stale.sort()
        return when {
            stale.isEmpty() -> check(
                "daemon",
                DoctorStatus.OK,
                "${running.size} running (${running.joinToString(", ")})",
            )
            running.isEmpty() -> check(
                "daemon",
                DoctorStatus.WARN,
                "${stale.size} stale socket(s) (${stale.joinToString(", ")}) " +
                    "— no daemon answers (delete the file(s) or restart the daemon)",
            )
            else -> check(
                "daemon",
                DoctorStatus.WARN,
                "${running.size} running (${running.joinToString(", ")}); " +
                    "${stale.size} stale (${stale.joinToString(", ")}) " +
                    "— no daemon answers on the stale socket(s) (delete or restart)",
            )
        }
    }

    private fun workspaceCheck(): DoctorCheck {
        // Named workspaces (T-015) plus project auto-discovery (T-016): one line, three
        // facts — the §13 selection, the project root, the stored count. Read-only: a
        // garbage active file reads as none, a missing dir as zero, and discovery never
        // writes the auto-cache from here (queries own that).
        val store = dev.jdx.index.workspace.FileWorkspaceStore(environment.userHome.resolve(".config/jdx"))
        val envName = environment.workspaceEnv?.trim().orEmpty().ifEmpty { null }
        val activeName = try {
            store.activeName()
        } catch (e: Exception) {
            null
        }
        val count = try {
            store.listNames().size
        } catch (e: Exception) {
            null
        }
        val selection = when {
            envName != null -> "workspace '$envName' (JDX_WORKSPACE)"
            activeName != null -> "workspace '$activeName' (default, jdx ws use)"
            else -> "no named workspace"
        }
        val hit = try {
            dev.jdx.index.workspace.ProjectDiscovery.findProjectRoot(environment.workingDir)
        } catch (e: Exception) {
            null
        }
        val project = if (hit != null) {
            "project root: ${hit.root} (nearest build file: ${hit.marker})"
        } else {
            "no project files"
        }
        val stored = if (count == null) "workspaces: unreadable" else "$count workspace(s)"
        return check("workspace", DoctorStatus.OK, "$selection; $project; $stored")
    }

    private fun check(name: String, status: DoctorStatus, detail: String): DoctorCheck =
        DoctorCheck(name, status, detail.oneLine())

    companion object {
        /** `javap` must read our fixture classes (bytecode 65): older is a WARN, absent is a FAIL. */
        const val MINIMUM_JAVAP_MAJOR: Int = 21
    }
}

/** D-015 mapping for doctor: 0 when no check failed, 6 on any FAIL. Pure — tested directly. */
internal fun exitCodeFor(report: DoctorReport): Int = if (report.hasFailures) 6 else 0

/**
 * First dotted number group of a `*-version` tool output (`26.0.2.1` → 26,
 * `1.8.0_292` → 8). Null when the output contains no digits at all. Pure — property-tested.
 */
internal fun parseToolVersion(output: String): Int? {
    val match = Regex("\\d+(\\.\\d+)*").find(output) ?: return null
    val parts = match.value.split(".")
    return if (parts[0] == "1" && parts.size > 1) parts[1].toIntOrNull() else parts[0].toIntOrNull()
}

/** Whole-unit human sizes for the cache row (`0 B`, `4 KB`, `12 MB`). Pure — example-tested. */
internal fun formatBytes(bytes: Long): String {
    require(bytes >= 0) { "negative size: $bytes" }
    if (bytes < 1024) {
        return "$bytes B"
    }
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return "$value ${units[unit]}"
}

private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()
