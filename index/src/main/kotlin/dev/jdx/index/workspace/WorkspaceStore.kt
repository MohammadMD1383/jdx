package dev.jdx.index.workspace

import dev.jdx.core.paths.JdxPaths
import dev.jdx.index.cache.PlatformMigration
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Persistence for [WorkspaceDefinition]s (T-015).
 *
 * Layout under [configDir] (production: per-OS config default, D-044):
 * `workspaces/<name>.toml` per workspace plus an `active-workspace` file holding the single
 * name set by `jdx ws use` (absent or blank means none). The interface exists so CLI
 * commands stay testable without touching the real home directory: tier-1 tests inject the
 * in-memory fake, tier-2 tests use [FileWorkspaceStore] on a `@TempDir`.
 *
 * All errors surface as [IOException] with a human-readable message — callers map them to
 * exit codes, never stack traces (TESTING.md §7). A file that fails to parse is a
 * [WorkspaceCorruptException] (exit 4: the workspace cannot be resolved); any other IO
 * failure is a plain [IOException] (exit 6).
 */
public interface WorkspaceStore {
    /** Sorted workspace names. Never throws for a missing directory (reads as empty). */
    @Throws(IOException::class)
    public fun listNames(): List<String>

    /** Loads [name], or `null` when no such workspace exists. */
    @Throws(IOException::class)
    public fun load(name: String): WorkspaceDefinition?

    /** Creates or replaces [definition]. Validates the name first. */
    @Throws(IOException::class)
    public fun save(definition: WorkspaceDefinition)

    /** Deletes [name]. Returns false when there was nothing to delete. */
    @Throws(IOException::class)
    public fun delete(name: String): Boolean

    /** The `jdx ws use` selection, or `null` when none (or blank/unreadable — reads as none). */
    public fun activeName(): String?

    /** Sets (or, with `null`, clears) the `jdx ws use` selection. */
    @Throws(IOException::class)
    public fun setActive(name: String?)
}

/**
 * [WorkspaceStore] over the real filesystem. Directories are created on first write —
 * never on read, so `jdx ws list` on a fresh machine reports empty instead of creating
 * config (mirroring `doctor`'s read-only rule, D-027).
 */
public class FileWorkspaceStore(public val configDir: Path) : WorkspaceStore {

    private fun workspacesDir(): Path = configDir.resolve("workspaces")

    private fun fileFor(name: String): Path = workspacesDir().resolve("$name.toml")

    override fun listNames(): List<String> {
        val dir = workspacesDir()
        if (!Files.isDirectory(dir)) return emptyList()
        return try {
            Files.list(dir).use { stream ->
                stream.map { it.fileName.toString() }
                    .filter { it.endsWith(".toml") }
                    .map { it.removeSuffix(".toml") }
                    .filter { validateWorkspaceName(it) == null }
                    .sorted()
                    .toList()
            }
        } catch (e: IOException) {
            throw IOException("cannot list workspaces in $dir: ${e.message}", e)
        }
    }

    override fun load(name: String): WorkspaceDefinition? {
        validateWorkspaceName(name)?.let { throw IOException("invalid workspace name '$name': $it") }
        val file = fileFor(name)
        if (!Files.isRegularFile(file)) return null
        val text = try {
            Files.readString(file)
        } catch (e: IOException) {
            throw IOException("cannot read workspace '$name' ($file): ${e.message}", e)
        }
        val decoded = WorkspaceToml.decode(text, file.fileName.toString())
        return decoded.getOrElse { failure ->
            throw WorkspaceCorruptException("workspace '$name' is corrupt: ${failure.message}", failure)
        }
    }

    override fun save(definition: WorkspaceDefinition) {
        validateWorkspaceName(definition.name)?.let {
            throw IOException("invalid workspace name '${definition.name}': $it")
        }
        try {
            Files.createDirectories(workspacesDir())
            // Write-then-rename is overkill for a single-writer config file, but a plain
            // overwrite of a human-editable file is still done atomically enough here:
            // one `writeString` call, no partial-line states across processes under WAL-free IO.
            Files.writeString(fileFor(definition.name), WorkspaceToml.encode(definition))
        } catch (e: IOException) {
            throw IOException("cannot save workspace '${definition.name}': ${e.message}", e)
        }
    }

    override fun delete(name: String): Boolean {
        validateWorkspaceName(name)?.let { throw IOException("invalid workspace name '$name': $it") }
        return try {
            Files.deleteIfExists(fileFor(name))
        } catch (e: IOException) {
            throw IOException("cannot delete workspace '$name': ${e.message}", e)
        }
    }

    override fun activeName(): String? {
        val file = configDir.resolve(ACTIVE_FILE)
        val raw = try {
            if (!Files.isRegularFile(file)) return null
            Files.readString(file)
        } catch (e: IOException) {
            return null
        }
        // Multi-line or garbage content reads as none rather than failing every query —
        // the file is ours, and a stray edit should not break reads (use `ws use` to repair).
        val candidate = raw.lines().firstOrNull()?.trim().orEmpty()
        if (candidate.isEmpty() || validateWorkspaceName(candidate) != null) return null
        return candidate
    }

    override fun setActive(name: String?) {
        try {
            if (name == null) {
                Files.deleteIfExists(configDir.resolve(ACTIVE_FILE))
                return
            }
            validateWorkspaceName(name)?.let { throw IOException("invalid workspace name '$name': $it") }
            Files.createDirectories(configDir)
            Files.writeString(configDir.resolve(ACTIVE_FILE), "$name\n")
        } catch (e: IOException) {
            throw IOException("cannot select workspace '$name': ${e.message}", e)
        }
    }

    public companion object {
        /** File holding the `jdx ws use` selection (one name, trailing newline). */
        public const val ACTIVE_FILE: String = "active-workspace"

        /** The production store: per-OS config root off the given home (D-044). */
        public fun system(home: Path = Path.of(System.getProperty("user.home"))): FileWorkspaceStore {
            val os = JdxPaths.detectOs(System.getProperty("os.name", ""))
            val resolved = JdxPaths.configRoot(home, os, System.getenv())
            runCatching {
                PlatformMigration.migrateOnce(JdxPaths.legacyConfigRoot(home), resolved, "config")
            }
            return FileWorkspaceStore(resolved)
        }

        /** The resolved per-OS config root for this process (env overrides win). */
        public fun defaultConfigDir(home: Path = Path.of(System.getProperty("user.home"))): Path {
            val os = JdxPaths.detectOs(System.getProperty("os.name", ""))
            return JdxPaths.configRoot(home, os, System.getenv())
        }
    }
}

/**
 * A workspace file that exists but does not parse (hand-edit typo, version skew).
 * Callers report exit 4 — the workspace cannot be resolved — and keep going:
 * `jdx ws list` still lists its name, and other workspaces still resolve.
 */
public class WorkspaceCorruptException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * In-memory [WorkspaceStore] for tier-1 CLI tests: no disk, no temp dirs, exact behaviour
 * for selection and CRUD without the filesystem.
 */
public class InMemoryWorkspaceStore : WorkspaceStore {
    private val workspaces: MutableMap<String, WorkspaceDefinition> = mutableMapOf()
    private var active: String? = null

    override fun listNames(): List<String> = workspaces.keys.sorted()

    override fun load(name: String): WorkspaceDefinition? {
        validateWorkspaceName(name)?.let { throw IOException("invalid workspace name '$name': $it") }
        return workspaces[name]
    }

    override fun save(definition: WorkspaceDefinition) {
        validateWorkspaceName(definition.name)?.let {
            throw IOException("invalid workspace name '${definition.name}': $it")
        }
        workspaces[definition.name] = definition
    }

    override fun delete(name: String): Boolean = workspaces.remove(name) != null

    override fun activeName(): String? = active

    override fun setActive(name: String?) {
        if (name != null) validateWorkspaceName(name)?.let { throw IOException("invalid workspace name '$name': $it") }
        active = name
    }
}
