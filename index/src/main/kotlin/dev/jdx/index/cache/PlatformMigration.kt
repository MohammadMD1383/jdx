package dev.jdx.index.cache

import dev.jdx.core.paths.JdxPaths
import java.nio.file.Files
import java.nio.file.Path

/**
 * One-shot move of a legacy dot-dir to its
 * D-044 per-OS location (`docs/PROPOSAL.md` §17.1).
 *
 * Move, never merge: only fires when the new location is absent (or empty) and
 * the old one exists with content. When both exist, the new location wins and
 * the caller warns naming the old one. Linux defaults never migrate
 * ([JdxPaths.needsMigration] is false there). Best-effort — any IO problem
 * reads as a warning string, never a throw — so production factories can call
 * it on every `system()` without breaking the cold path.
 */
public object PlatformMigration {
    /**
     * Moves [legacy] to [resolved] when the migration rule fires, returning a
     * human-readable note (`moved …`, `both exist …`) or null when nothing
     * applies. Never throws.
     */
    public fun migrateOnce(legacy: Path, resolved: Path, kind: String): String? {
        if (!JdxPaths.needsMigration(legacy, resolved)) return null
        return try {
            val legacyExists = Files.exists(legacy)
            if (!legacyExists) return null
            val resolvedExists = Files.exists(resolved)
            val resolvedEmpty = resolvedExists && Files.isDirectory(resolved) &&
                Files.list(resolved).use { it.findAny().isEmpty }
            if (!resolvedExists || resolvedEmpty) {
                if (resolvedExists && resolvedEmpty) runCatching { Files.delete(resolved) }
                Files.createDirectories(resolved.parent)
                Files.move(legacy, resolved)
                "moved old $kind cache ${display(legacy)} to ${display(resolved)} (D-044 per-OS dirs)"
            } else {
                "both old ${display(legacy)} and new ${display(resolved)} exist — " +
                    "using the new $kind location; remove the old one when ready"
            }
        } catch (e: Exception) {
            "could not migrate old $kind location ${display(legacy)} to " +
                "${display(resolved)}: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun display(path: Path): String {
        val home = runCatching { Path.of(System.getProperty("user.home")) }.getOrNull()
        return if (home != null && path.startsWith(home)) {
            "~/" + home.relativize(path).toString()
        } else {
            path.toString()
        }
    }
}
