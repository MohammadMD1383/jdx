package dev.jdx.site

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One finished `jdx` invocation: exit status plus both streams, as the site embeds them. */
data class JdxResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Runs `jdx` for the site generator. Production uses [ProcessJdxRunner] (the real launcher);
 * tests substitute a stub so generator logic is testable without a built distribution.
 */
fun interface JdxRunner {
    fun run(arguments: List<String>, stdin: String?): JdxResult
}

/**
 * Runs the real `jdx` launcher in a hermetic sandbox: private cache/config/runtime dirs under
 * [sandboxDir] (so a developer's own workspaces never leak into published output), an empty
 * working directory in the system temp dir — outside any Gradle/Maven project, so project
 * auto-discovery never picks up this repository — UTF-8 output, and no `JAVA_TOOL_OPTIONS`
 * (whose "Picked up ..." banner breaks the launcher's JDK probe, issue #88).
 *
 * Results are memoised by (arguments, stdin): docs repeat commands, and output is
 * deterministic for a fixed build (AGENTS.md §2.5), so re-running would only cost time.
 */
class ProcessJdxRunner(
    private val launcher: File,
    private val sandboxDir: File,
    private val javaHome: String,
    private val extraEnvironment: Map<String, String> = emptyMap(),
) : JdxRunner {
    private val memo = ConcurrentHashMap<Pair<List<String>, String?>, JdxResult>()

    private val workingDir: File =
        java.nio.file.Files.createTempDirectory("jdx-site-cwd").toFile().also { it.deleteOnExit() }

    override fun run(arguments: List<String>, stdin: String?): JdxResult =
        memo.computeIfAbsent(arguments to stdin) { runUncached(arguments, stdin) }

    private fun runUncached(arguments: List<String>, stdin: String?): JdxResult {
        val builder = ProcessBuilder(listOf(launcher.absolutePath) + arguments)
            .directory(workingDir)
        val environment = builder.environment()
        environment.remove("JAVA_TOOL_OPTIONS")
        environment.remove("_JAVA_OPTIONS")
        environment.remove("JDK_JAVA_OPTIONS")
        environment.remove("JDX_WORKSPACE")
        environment["JAVA_HOME"] = javaHome
        environment["LC_ALL"] = "C.UTF-8"
        environment["LANG"] = "C.UTF-8"
        environment["HOME"] = File(sandboxDir, "home").also { it.mkdirs() }.absolutePath
        environment["XDG_CACHE_HOME"] = File(sandboxDir, "cache").absolutePath
        environment["XDG_CONFIG_HOME"] = File(sandboxDir, "config").absolutePath
        environment["XDG_RUNTIME_DIR"] = File(sandboxDir, "run").also { it.mkdirs() }.absolutePath
        environment.putAll(extraEnvironment)
        val stdoutFile = File.createTempFile("jdx-out", ".txt", sandboxDir)
        val stderrFile = File.createTempFile("jdx-err", ".txt", sandboxDir)
        try {
            builder.redirectOutput(stdoutFile).redirectError(stderrFile)
            val process = builder.start()
            process.outputStream.use { input -> if (stdin != null) input.write(stdin.toByteArray()) }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw SiteBuildException("jdx ${arguments.joinToString(" ")} timed out after ${TIMEOUT_SECONDS}s")
            }
            return JdxResult(
                exitCode = process.exitValue(),
                stdout = scrub(stdoutFile.readText()),
                stderr = scrub(stderrFile.readText()),
            )
        } finally {
            stdoutFile.delete()
            stderrFile.delete()
        }
    }

    /** Published output must never carry a machine path (AGENTS.md §2.5). */
    private fun scrub(text: String): String =
        text.replace(File(sandboxDir, "home").absolutePath, "~")
            .replace(File(sandboxDir, "cache").absolutePath, "~/.cache")
            .replace(sandboxDir.absolutePath, "~")
            .replace(workingDir.absolutePath, ".")

    private companion object {
        const val TIMEOUT_SECONDS = 180L
    }
}

/** A `$ ...` line from a doc, parsed into what the runner needs. */
data class ShellCommand(val arguments: List<String>, val stdin: String?)

/**
 * Minimal POSIX-ish word splitting for the commands docs show: single quotes, double quotes,
 * backslash escapes, and one optional `echo '<text>' | jdx ...` pipe (how `jdx batch` is fed).
 * Anything fancier (redirects, `&&`, subshells) is refused, because the site must execute
 * exactly what a reader would type.
 */
object ShellWords {
    fun split(line: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var inWord = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '\'' -> {
                    val end = line.indexOf('\'', index + 1)
                    if (end < 0) throw SiteBuildException("unterminated single quote in: $line")
                    current.append(line, index + 1, end)
                    inWord = true
                    index = end
                }
                char == '"' -> {
                    index++
                    while (index < line.length && line[index] != '"') {
                        if (line[index] == '\\' && index + 1 < line.length && line[index + 1] in "\"\\$`") {
                            index++
                        }
                        current.append(line[index])
                        index++
                    }
                    if (index >= line.length) throw SiteBuildException("unterminated double quote in: $line")
                    inWord = true
                }
                char == '\\' && index + 1 < line.length -> {
                    current.append(line[index + 1])
                    inWord = true
                    index++
                }
                char.isWhitespace() -> {
                    if (inWord) words += current.toString()
                    current.clear()
                    inWord = false
                }
                char == '#' && !inWord -> break // comment to end of line
                char == '|' || char == '>' || char == '<' || char == '&' || char == ';' -> {
                    if (inWord) words += current.toString()
                    current.clear()
                    inWord = false
                    words += OPERATOR_PREFIX + char
                }
                else -> {
                    current.append(char)
                    inWord = true
                }
            }
            index++
        }
        if (inWord) words += current.toString()
        return words
    }

    /** Parses a doc command line into a `jdx` invocation, or explains why it cannot run. */
    fun parseJdxCommand(line: String): ShellCommand {
        val words = split(line)
        val pipeAt = words.indexOf("$OPERATOR_PREFIX|")
        val operators = words.filter { it.startsWith(OPERATOR_PREFIX) }
        if (operators.size > 1 || (operators.size == 1 && pipeAt < 0)) {
            throw SiteBuildException("only `jdx ...` or `echo '...' | jdx ...` can be executed, got: $line")
        }
        val (stdin, command) = if (pipeAt >= 0) {
            val producer = words.subList(0, pipeAt)
            if (producer.size != 2 || producer[0] != "echo") {
                throw SiteBuildException("the only supported pipe is `echo '<text>' | jdx ...`, got: $line")
            }
            producer[1] + "\n" to words.subList(pipeAt + 1, words.size)
        } else {
            null to words
        }
        if (command.firstOrNull() != "jdx") {
            throw SiteBuildException("executable doc commands must start with `jdx`, got: $line")
        }
        return ShellCommand(command.drop(1), stdin)
    }

    /** Trailing `# exit N` comment on a doc command: the exit status the doc promises. */
    fun expectedExit(line: String): Int? =
        Regex("""#\s*exit\s*=?\s*(\d+)\s*$""").find(line)?.groupValues?.get(1)?.toInt()

    /** The command as displayed: the `# exit N` annotation is metadata, not something to type. */
    fun displayForm(line: String): String = line.replace(Regex("""\s*#\s*exit\s*=?\s*\d+\s*$"""), "")

    private const val OPERATOR_PREFIX = "\u0000op:"
}

/** A site build failure with a message meant for whoever broke the docs. */
class SiteBuildException(message: String) : RuntimeException(message)
