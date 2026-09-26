package dev.jdx.index.usages

/**
 * Textual source-dir scan for `usages` (T-031, D-010).
 *
 * Bytecode edges (T-029/T-030) know call vs read vs write; a source dir has no
 * edges, only text. This scanner reports whole-word mentions of one simple
 * name (`Lib`, `greet`) as `(relativePath, line)` pairs — the service renders
 * them as `ref` [dev.jdx.core.render.UsageHit] rows (`fromRef =
 * <relpath>:<line>`). Whole-word (not substring): `greet` matches
 * `greet(` and `x.greet`, never `greetings`.
 *
 * Pure over lines (test-first): [mentionsInLines] takes the file's lines and
 * the wanted simple name; directory walking lives in [scanSourceDir]. Neither
 * throws on agent-reachable input — unreadable files read as no mentions.
 */
public object SourceUsages {

    /** Source file kinds scanned: Java and Kotlin (T-039 owns `.kt` bodies; mentions are plain text). */
    public fun isSourceFile(name: String): Boolean = name.endsWith(".java") || name.endsWith(".kt")

    /**
     * Line numbers (1-based) in [lines] holding a whole-word mention of [simpleName].
     * Blank names match nothing; matching is case-sensitive (Java/Kotlin identifiers are).
     */
    public fun mentionsInLines(lines: List<String>, simpleName: String): List<Int> {
        if (simpleName.isEmpty()) return emptyList()
        if (simpleName.any { !it.isLetterOrDigit() && it != '_' && it != '$' }) return emptyList()
        val result = ArrayList<Int>()
        for ((index, line) in lines.withIndex()) {
            if (mentionsInLine(line, simpleName)) result.add(index + 1)
        }
        return result
    }

    /** One line's whole-word test: every match needs a non-identifier char (or edge) on both sides. */
    internal fun mentionsInLine(line: String, simpleName: String): Boolean {
        var from = 0
        while (true) {
            val at = line.indexOf(simpleName, from)
            if (at < 0) return false
            val before = if (at == 0) null else line[at - 1]
            val end = at + simpleName.length
            val after = if (end >= line.length) null else line[end]
            if (isWordChar(before).not() && isWordChar(after).not()) return true
            from = at + 1
        }
    }

    private fun isWordChar(char: Char?): Boolean =
        char != null && (char.isLetterOrDigit() || char == '_' || char == '$')

    /** One textual mention: the path relative to the scanned root plus the 1-based line. */
    public data class SourceMention(val relativePath: String, val line: Int)

    /**
     * Scans [root] for [simpleName] mentions: walks regular `.java`/`.kt` files,
     * reads each as UTF-8 lines (unreadable/undecodable files read as no mentions),
     * returns sorted `(relativePath, line)` pairs. Never throws.
     */
    public fun scanSourceDir(root: java.nio.file.Path, simpleName: String): List<SourceMention> {
        val out = ArrayList<SourceMention>()
        try {
            java.nio.file.Files.walk(root).use { stream ->
                stream.filter { java.nio.file.Files.isRegularFile(it) }.forEach { path ->
                    val name = path.fileName?.toString() ?: return@forEach
                    if (!isSourceFile(name)) return@forEach
                    val lines = runCatching {
                        java.nio.file.Files.readAllLines(path, Charsets.UTF_8)
                    }.getOrNull() ?: return@forEach
                    // Slash-joined, never toString: Windows renders `\`
                    // separators, while jdx paths read `/` everywhere else
                    // (zip entries, class names) — agents get one spelling.
                    val relative = runCatching { root.relativize(path).joinToString("/") }
                        .getOrNull() ?: name
                    for (line in mentionsInLines(lines, simpleName)) {
                        out.add(SourceMention(relative, line))
                    }
                }
            }
        } catch (e: Exception) {
            return out.sortedWith(compareBy({ it.relativePath }, { it.line }))
        }
        return out.sortedWith(compareBy({ it.relativePath }, { it.line }))
    }
}
