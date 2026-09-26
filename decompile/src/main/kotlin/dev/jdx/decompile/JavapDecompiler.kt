package dev.jdx.decompile

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where the `javap` binary comes from (T-027). Resolution mirrors `doctor`'s
 * `javap` check: an explicit override first (tests), then `$JAVA_HOME/bin`,
 * then the running JVM's `java.home/bin`, then `PATH` — so the disassembly
 * always comes from a JDK the user recognises.
 */
public data class JavapEnvironment(
    /** Direct executable path, bypassing every lookup (tests use this). */
    public val explicitExecutable: String? = null,
    /** `$JAVA_HOME`, when set in the caller's environment. */
    public val javaHomeEnv: String? = System.getenv("JAVA_HOME"),
    /** The running JVM's home (`java.home` system property). */
    public val javaHome: String = System.getProperty("java.home"),
    /** `PATH` entries searched last. */
    public val pathDirs: List<String> =
        (System.getenv("PATH") ?: "").split(File.pathSeparatorChar).filter { it.isNotEmpty() },
    /** OS name for executable-suffix probing (tests inject `"Windows 11"`); never read twice. */
    public val osName: String = System.getProperty("os.name", ""),
) {
    /**
     * The `javap` executable to run, or `null` when none resolves. An
     * explicit override is returned as-is (a bogus path fails at spawn with
     * the path named, which is exactly the fault the tests pin); every other
     * candidate must be a runnable file. On Windows each directory is probed
     * for `javap.exe`/`.cmd`/`.bat` first (a bare `javap` has no executable
     * bit there), falling back to the extensionless name. Never throws.
     */
    public fun resolveExecutable(): String? {
        explicitExecutable?.let { return it }
        val homeBases = listOfNotNull(javaHomeEnv, javaHome)
            .map { Path.of(it, "bin").toString() }
        for (base in homeBases) {
            firstRunnable(base)?.let { return it }
        }
        for (dir in pathDirs) {
            firstRunnable(dir)?.let { return it }
        }
        return null
    }

    private fun firstRunnable(dir: String): String? {
        for (name in toolNames("javap", osName)) {
            val candidate = Path.of(dir, name).toString()
            if (isRunnable(candidate)) return candidate
        }
        return null
    }

    public companion object {
        /** The production environment: live `JAVA_HOME`, `java.home`, `PATH`. */
        public fun system(): JavapEnvironment = JavapEnvironment()
    }
}

private fun isRunnable(path: String): Boolean = runCatching {
    val file = Path.of(path)
    Files.isRegularFile(file) && Files.isExecutable(file)
}.getOrDefault(false)

/**
 * Candidate file names for a JDK tool: on Windows the `PATHEXT`
 * executables (`.exe` first, then the script shims) before the bare name,
 * elsewhere the bare name alone. Pure — unit-tested.
 */
internal fun toolNames(base: String, osName: String): List<String> =
    if (osName.lowercase().contains("win")) {
        listOf("$base.exe", "$base.cmd", "$base.bat", base)
    } else {
        listOf(base)
    }

/**
 * The raw-opcode engine (PROPOSAL.md §11.2, T-027): the IDE's *Show Bytecode*
 * action behind `--engine javap`.
 *
 * The winning class's bytes are staged to a temp dir (shared [stagedEntryPath]
 * hardening) and read with `javap -c -p -s -classpath <stagedDir> <binary>`:
 * `-c` prints the disassembled instructions, `-p` shows private members, and
 * `-s` prints the erased descriptors the service matches members by. Output
 * is passed through verbatim — minimal reformatting, per the proposal.
 *
 * Like Vineflower, output is cached on disk keyed by `(class hash, engine,
 * engine version)` — except the version here is the engine's own `javap
 * -version` text, so a JDK upgrade never serves stale disassembly. Cache hits
 * never spawn a process, and the `javap` classes are never loaded (there are
 * none — it is a subprocess, so hostile bytes cannot reach this JVM either).
 *
 * @param cache on-disk text cache; [DecompileCache.system] in production, a
 *   temp dir in tests.
 * @param timeout wall-clock budget per spawn (disassembly and the `-version`
 *   probe alike).
 * @param environment where the `javap` binary comes from; tests inject fakes.
 */
public data class JavapDecompiler(
    public val cache: DecompileCache = DecompileCache.system(),
    public val timeout: Duration = 30.seconds,
    public val environment: JavapEnvironment = JavapEnvironment.system(),
) : DecompilerEngine {

    override val id: DecompilerId = DecompilerId.JAVAP

    /**
     * The engine's own version text, queried once per instance: part of every
     * cache key. `null` when `javap` cannot answer `-version` (missing binary
     * or a failing probe) — the query then fails honestly instead of caching
     * under a made-up version.
     */
    private val engineVersion: String? by lazy { queryVersion() }

    override fun decompileClass(
        classBytes: ByteArray,
        binaryName: String,
        classpath: List<Path>,
    ): DecompileResult {
        // The workspace library context is meaningless to `javap` (one staged
        // class on `-classpath` is the whole input) — documented, not ignored
        // by accident.
        if (classBytes.isEmpty()) {
            return DecompileResult.Failed("cannot disassemble $binaryName: empty class bytes")
        }
        val entryPath = stagedEntryPath(binaryName)
            ?: return DecompileResult.Failed("invalid class name for disassembly: '$binaryName'")
        val executable = environment.resolveExecutable()
            ?: return DecompileResult.Failed(
                "javap not found (looked in \$JAVA_HOME/bin and on PATH) — install a full JDK",
            )
        val version = engineVersion
            ?: return DecompileResult.Failed(
                "javap -version failed for $executable: cannot version the disassembly",
            )
        cache.read(classBytes, id.flag, version)?.let { cached ->
            return DecompileResult.Decompiled(cached, version)
        }
        return when (val run = runJavap(executable, binaryName, classBytes, entryPath)) {
            is RunOutcome.Ok -> {
                if (run.text.isBlank()) {
                    DecompileResult.Failed("javap produced no output for $binaryName")
                } else {
                    cache.write(classBytes, id.flag, version, run.text)
                    DecompileResult.Decompiled(run.text, version)
                }
            }
            is RunOutcome.Failed -> DecompileResult.Failed(run.message)
        }
    }

    private sealed interface RunOutcome {
        data class Ok(val text: String) : RunOutcome
        data class Failed(val message: String) : RunOutcome
    }

    /**
     * Runs `javap -version` once: the cache-key version. Exit-nonzero, blank
     * output, a timeout and a missing binary all read as `null` (the caller
     * fails honestly); never throws.
     */
    internal fun queryVersion(): String? {
        val executable = environment.resolveExecutable() ?: return null
        return runCatching {
            val process = ProcessBuilder(executable, "-version").redirectErrorStream(true).start()
            val outputFuture = streamPool.submit<ByteArray> { process.inputStream.readBytes() }
            val exited = process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            if (!exited) {
                process.destroyForcibly()
                outputFuture.cancel(true)
                return null
            }
            val output = outputFuture.get(READ_GRACE_SECONDS, TimeUnit.SECONDS)
                .toString(Charsets.UTF_8).trim()
            if (process.exitValue() != 0 || output.isEmpty()) null else output
        }.getOrNull()
    }

    private fun runJavap(
        executable: String,
        binaryName: String,
        classBytes: ByteArray,
        entryPath: String,
    ): RunOutcome {
        val staged = runCatching { Files.createTempDirectory("jdx-javap-") }.getOrNull()
            ?: return RunOutcome.Failed("cannot stage $binaryName for disassembly: no temp dir")
        try {
            runCatching { stageClassFile(staged, entryPath, classBytes) }.onFailure { e ->
                return RunOutcome.Failed(
                    "cannot stage $binaryName for disassembly: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            val process = try {
                ProcessBuilder(executable, "-c", "-p", "-s", "-classpath", staged.toString(), binaryName)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                return RunOutcome.Failed(
                    "cannot run javap for $binaryName ($executable): ${e.message ?: e.javaClass.simpleName}",
                )
            }
            val outputFuture = streamPool.submit<ByteArray> { process.inputStream.readBytes() }
            val exited = try {
                process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                process.destroyForcibly()
                outputFuture.cancel(true)
                return RunOutcome.Failed("javap disassembly of $binaryName was interrupted")
            }
            if (!exited) {
                process.destroyForcibly()
                outputFuture.cancel(true)
                return RunOutcome.Failed(
                    "javap timed out after ${timeout.inWholeSeconds}s disassembling $binaryName",
                )
            }
            val output = try {
                outputFuture.get(READ_GRACE_SECONDS, TimeUnit.SECONDS).toString(Charsets.UTF_8)
            } catch (e: Exception) {
                val cause = (e.cause ?: e).message ?: (e.cause ?: e).javaClass.simpleName
                return RunOutcome.Failed("could not read javap output for $binaryName: $cause")
            }
            val exit = process.exitValue()
            if (exit != 0) {
                val firstLine = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "no output"
                return RunOutcome.Failed("javap exits $exit for $binaryName: $firstLine")
            }
            return RunOutcome.Ok(output)
        } finally {
            deleteStagedDir(staged)
        }
    }

    private companion object {
        /** Grace period for the reader thread after the process has exited. */
        const val READ_GRACE_SECONDS: Long = 10

        val streamPool = Executors.newCachedThreadPool { task ->
            Thread(task, "jdx-javap").apply { isDaemon = true }
        }
    }
}
