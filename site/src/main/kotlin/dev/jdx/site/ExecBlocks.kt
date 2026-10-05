package dev.jdx.site

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Node

/**
 * Executable doc examples — the main defence against docs drifting from the binary.
 *
 * A fenced block whose info string is `console exec` is a list of `$ jdx ...` commands. On
 * GitHub it renders as an ordinary console block (any sample output written under a command
 * is shown there). On the site, every command is *run against the freshly built jdx* and the
 * sample output is replaced by the real one. A command whose exit status differs from the
 * promise (0, or a trailing `# exit N`) fails the site build — so a renamed flag or a changed
 * exit code in the CLI cannot leave a stale example behind.
 *
 * Info-string options: `max-lines=N` caps each command's shown output (the cut is labelled).
 */
object ExecBlocks {
    /** Added to the info string of a block whose output came from a real run. */
    const val EXECUTED_MARKER = "jdx-generated"

    private const val EXEC_WORD = "exec"

    fun isExecBlock(block: FencedCodeBlock): Boolean =
        block.info.orEmpty().trim().split(Regex("\\s+")).let { words ->
            words.firstOrNull() == "console" && EXEC_WORD in words
        }

    /** Every command line of every exec block, so a caller can run them in parallel first. */
    fun commandLines(document: Node): List<String> =
        execBlocks(document).flatMap { block -> commandLinesOf(block.literal) }

    /**
     * Runs every exec block in [document] and substitutes real output. [location] names the
     * doc in error messages.
     */
    fun execute(document: Node, runner: JdxRunner, location: String) {
        for (block in execBlocks(document)) {
            val options = block.info.orEmpty().trim().split(Regex("\\s+"))
            val maxLines = options.firstNotNullOfOrNull { option ->
                option.removePrefix("max-lines=").takeIf { it != option }?.toIntOrNull()
            }
            val rendered = StringBuilder()
            val lines = commandLinesOf(block.literal)
            if (lines.isEmpty()) throw SiteBuildException("$location: `console exec` block has no `$ jdx ...` line")
            for (line in lines) {
                val command = ShellWords.parseJdxCommand(line)
                val expectedExit = ShellWords.expectedExit(line) ?: 0
                val result = runner.run(command.arguments, command.stdin)
                if (result.exitCode != expectedExit) {
                    throw SiteBuildException(
                        "$location: `$line` exited ${result.exitCode}, the doc promises $expectedExit.\n" +
                            "stdout:\n${result.stdout.prependIndent("  ")}\nstderr:\n${result.stderr.prependIndent("  ")}\n" +
                            "Fix the doc (or the CLI) — published examples are real runs.",
                    )
                }
                rendered.append("$ ").append(ShellWords.displayForm(line)).append('\n')
                val output = (result.stdout + result.stderr).trimEnd('\n', ' ')
                rendered.append(capLines(output, maxLines))
                if (output.isNotEmpty()) rendered.append('\n')
                if (expectedExit != 0) rendered.append("[exit $expectedExit]\n")
            }
            block.literal = rendered.toString()
            block.info = "console $EXECUTED_MARKER"
        }
    }

    private fun capLines(output: String, maxLines: Int?): String {
        if (maxLines == null) return output
        val lines = output.lines()
        if (lines.size <= maxLines) return output
        return (lines.take(maxLines) + "… ${lines.size - maxLines} more lines (run it yourself to see them all)")
            .joinToString("\n")
    }

    private fun commandLinesOf(literal: String): List<String> =
        literal.lines().filter { it.startsWith("$ ") }.map { it.removePrefix("$ ").trim() }

    private fun execBlocks(document: Node): List<FencedCodeBlock> {
        val result = mutableListOf<FencedCodeBlock>()
        document.accept(object : AbstractVisitor() {
            override fun visit(fencedCodeBlock: FencedCodeBlock) {
                if (isExecBlock(fencedCodeBlock)) result += fencedCodeBlock
            }
        })
        return result
    }
}
