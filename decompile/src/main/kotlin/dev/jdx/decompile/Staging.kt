package dev.jdx.decompile

import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared single-class staging behind both engines (T-026 Vineflower, T-027
 * javap): the class bytes an engine runs over always enter through a temp
 * dir, never through the workspace itself.
 */

/**
 * Maps a binary name to its staged class-file path (`dev/jdx/Foo.class`),
 * or `null` when the name cannot name a file safely. `$`-nesting, `$`-runs
 * (JFR `$$` shapes) and inner digits are all safe file names — only
 * separators, parents and blanks are rejected, so this never throws and
 * never escapes the staging dir.
 */
internal fun stagedEntryPath(binaryName: String): String? {
    if (binaryName.isBlank()) return null
    if ('/' in binaryName || '\\' in binaryName || binaryName.contains("..")) return null
    if (binaryName.startsWith(".") || binaryName.endsWith(".")) return null
    val entry = binaryName.replace('.', '/') + ".class"
    if (entry.split('/').any { it.isEmpty() }) return null
    return entry
}

/**
 * Writes the class bytes into the staging dir at [entryPath]. The path comes
 * from [stagedEntryPath] (no separators from the caller), and the normalised
 * result must stay inside the staging dir — belt and braces against
 * adversarial binary names.
 */
internal fun stageClassFile(staged: Path, entryPath: String, classBytes: ByteArray) {
    val target = staged.resolve(entryPath).normalize()
    check(target.startsWith(staged)) { "staging escape for $entryPath" }
    target.parent?.let { Files.createDirectories(it) }
    Files.write(target, classBytes)
}

/** Best-effort recursive delete of a staging dir. Never throws. */
internal fun deleteStagedDir(root: Path) {
    runCatching {
        Files.walk(root).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
