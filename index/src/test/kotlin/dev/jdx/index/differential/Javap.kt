package dev.jdx.index.differential

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The `javap` oracle behind the differential harness (T-056, TESTING.md §5.1).
 *
 * `javap -p -s` is the only readily available program that prints ground truth
 * for an arbitrary class file — member names plus erased descriptors — without
 * anyone writing the truth down. This object finds `javap`, runs it, and parses
 * its output into [MemberKey] sets; [ServiceDifferential] diffs those sets
 * against what `jdx members --declared --access all --include-synthetic`
 * reports for the same class.
 *
 * The parser is shared (not copied per suite): the T-008 reader-level
 * differential in `asm.AsmClassReaderDifferentialTest` delegates here too, so a
 * parsing fix lands everywhere at once.
 *
 * Every spawn has a wall-clock budget (issue #81). Without one, a slow or hung
 * `javap` under parallel PIT minions is cut by the *mutant* timeout and scored
 * as a surviving mutant — a load problem that reads as a test-quality problem.
 * A timed-out oracle must fail the test with a reason, never look like an
 * unreadable class.
 */
internal object Javap {

    /**
     * One declared member as both sides name it: the simple name (`<init>` for
     * constructors, `<clinit>` for the class initialiser) plus the erased JVM
     * descriptor (`()V`, `Ljava/lang/String;`).
     */
    internal data class MemberKey(val name: String, val descriptor: String)

    /**
     * Outcome of one `javap` spawn. [Ok] carries the output lines; [Unreadable]
     * is a soft miss (soak skips); [TimedOut] is a hard failure — callers must
     * not fold it into a skip.
     */
    internal sealed interface RunOutcome {
        data class Ok(val lines: List<String>) : RunOutcome
        data class Unreadable(val detail: String) : RunOutcome
        data class TimedOut(val timeoutMs: Long, val binaryName: String) : RunOutcome
    }

    /**
     * Default wall-clock budget for one oracle spawn. Kept under the local PIT
     * `timeoutConstInMillis` (10 s) so a hung `javap` fails *this* test with a
     * reason before the minion is killed and scored as a surviving mutant
     * (issue #81). Override with `-Djdx.javap.oracleTimeoutMs=<ms>` in tests.
     */
    internal val defaultTimeoutMs: Long =
        System.getProperty("jdx.javap.oracleTimeoutMs")?.toLongOrNull()?.takeIf { it > 0 }
            ?: 8_000L

    /** Short probe budget for `javap -version` / PATH discovery. */
    private const val PROBE_TIMEOUT_MS: Long = 2_000L

    /**
     * Locates `javap`: the test runtime's JDK first, `PATH` as a fallback.
     * Returns `null` when absent — never throws. Callers skip gracefully via
     * `assumeTrue(javap != null, ...)` (TESTING.md §5.1), so a missing JDK tool
     * never fails the build.
     */
    internal fun findJavap(): String? {
        val homeJavap = File(System.getProperty("java.home"), "bin/javap")
        if (homeJavap.canExecute()) return homeJavap.absolutePath
        return try {
            val probe = ProcessBuilder("javap", "-version").redirectErrorStream(true).start()
            val outputFuture = CompletableFuture.supplyAsync { probe.inputStream.readBytes() }
            val exited = probe.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!exited) {
                probe.destroyForcibly()
                outputFuture.cancel(true)
                return null
            }
            outputFuture.get(1, TimeUnit.SECONDS)
            if (probe.exitValue() == 0) "javap" else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Runs `javap -p -s -classpath [classpath] [binaryName]` under
     * [timeoutMs].
     *
     * Flags precede the class name: a trailing `-s` is parsed as a class name
     * (L-018). Returns [RunOutcome.Ok] on exit 0, [RunOutcome.Unreadable] when
     * `javap` itself cannot read the class, and [RunOutcome.TimedOut] when the
     * wall-clock budget is exhausted (issue #81).
     */
    internal fun tryRun(
        javap: String,
        classpath: String,
        binaryName: String,
        timeoutMs: Long = defaultTimeoutMs,
    ): RunOutcome =
        run(
            executable = javap,
            args = listOf("-p", "-s", "-classpath", classpath, binaryName),
            binaryName = binaryName,
            timeoutMs = timeoutMs,
        )

    /**
     * Runs `javap -l -classpath [classpath] [binaryName]` under [timeoutMs]
     * (LineNumberTable oracle for the issue #8 differential).
     */
    internal fun tryRunLines(
        javap: String,
        classpath: String,
        binaryName: String,
        timeoutMs: Long = defaultTimeoutMs,
    ): RunOutcome =
        run(
            executable = javap,
            args = listOf("-l", "-classpath", classpath, binaryName),
            binaryName = binaryName,
            timeoutMs = timeoutMs,
        )

    /**
     * Spawns `[executable] + [args]`, drains stdout+stderr concurrently, and
     * enforces [timeoutMs]. Never throws: every failure mode is an outcome.
     */
    internal fun run(
        executable: String,
        args: List<String>,
        binaryName: String,
        timeoutMs: Long = defaultTimeoutMs,
    ): RunOutcome {
        val process = try {
            ProcessBuilder(listOf(executable) + args).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return RunOutcome.Unreadable(
                "cannot start javap for $binaryName: ${e.message ?: e.javaClass.simpleName}",
            )
        }
        val outputFuture = CompletableFuture.supplyAsync { process.inputStream.readBytes() }
        val exited = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            outputFuture.cancel(true)
            return RunOutcome.Unreadable("javap for $binaryName was interrupted")
        }
        if (!exited) {
            process.destroyForcibly()
            outputFuture.cancel(true)
            return RunOutcome.TimedOut(timeoutMs, binaryName)
        }
        val output = try {
            outputFuture.get(2, TimeUnit.SECONDS).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            val cause = when (e) {
                is TimeoutException -> e
                else -> e.cause ?: e
            }
            return RunOutcome.Unreadable(
                "could not read javap output for $binaryName: " +
                    (cause.message ?: cause.javaClass.simpleName),
            )
        }
        return if (process.exitValue() == 0) {
            RunOutcome.Ok(output.lines())
        } else {
            val firstLine = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?: "exit ${process.exitValue()}"
            RunOutcome.Unreadable("javap cannot read $binaryName: $firstLine")
        }
    }

    /**
     * Parses `javap -p -s` output into `(name, descriptor)` pairs. Each member
     * line is followed by its `descriptor:` line; constructors print as the FQN
     * (they contain a `.`, unlike method names) and `static {};` is the class
     * initialiser. Header lines (`Compiled from`, the class declaration, the
     * closing brace) carry no member and are dropped.
     */
    internal fun parseMembers(output: List<String>): Set<MemberKey> {
        val members = mutableSetOf<MemberKey>()
        var pending: String? = null
        for (line in output) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed == "}" ||
                trimmed.startsWith("Compiled from") || trimmed.endsWith("{")
            ) {
                continue
            }
            if (trimmed.startsWith("descriptor:")) {
                val descriptor = trimmed.removePrefix("descriptor:").trim()
                check(pending != null) { "javap descriptor without a member line: $trimmed" }
                members.add(MemberKey(pending, descriptor))
                pending = null
            } else {
                pending = normaliseMemberLine(trimmed)
            }
        }
        return members
    }

    private fun normaliseMemberLine(line: String): String {
        val bare = line.removeSuffix(";")
        if ('(' in bare) {
            val nameToken = bare.substringBefore('(').trim().substringAfterLast(' ')
            return if ('.' in nameToken) "<init>" else nameToken
        }
        if (bare.trim() == "static {}") return "<clinit>"
        return bare.trim().substringAfterLast(' ')
    }
}
