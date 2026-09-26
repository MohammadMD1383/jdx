package dev.jdx.core.paths

import java.nio.file.Path

/**
 * The OS family `jdx` branches on (D-044, issue #44; table in `docs/PROPOSAL.md` §17.1).
 * Detected from `os.name` in exactly one place — [JdxPaths.detectOs] — so every
 * cache/config/runtime default agrees on what "Windows" means.
 */
public enum class JdxOs {
    LINUX,
    MACOS,
    WINDOWS,
}

/**
 * Per-OS platform directories (D-044; `docs/PROPOSAL.md` §17.1).
 *
 * Pure path math, no IO: every function takes an explicit `home`, `os` and `env`
 * map, so tests pin Linux/macOS/Windows defaults with fakes and never touch the
 * real home. Production callers pass `Path.of(System.getProperty("user.home"))`,
 * `detectOs(System.getProperty("os.name", ""))` and `System.getenv()`.
 *
 * Precedence (first match wins): explicit CLI flag > `JDX_*` env > platform env
 * (`XDG_*` / `LOCALAPPDATA` / `APPDATA` / `TMPDIR`) > OS default from the table.
 */
public object JdxPaths {
    /** Overrides the cache root (`--cache-dir` covers the index DB, `m2/`, sidecar, `auto/`). */
    public const val ENV_CACHE_DIR: String = "JDX_CACHE_DIR"

    /** Overrides the config root (no CLI flag yet; env only). */
    public const val ENV_CONFIG_DIR: String = "JDX_CONFIG_DIR"

    /** Overrides the daemon socket directory (no CLI flag; env only). */
    public const val ENV_RUNTIME_DIR: String = "JDX_RUNTIME_DIR"

    /**
     * The single `os.name` branch. Matches `win` anywhere (covers `Windows 10`,
     * `Windows 11`), `mac`/`darwin` for macOS, everything else reads as Linux
     * (including other Unixes — they share the XDG defaults).
     */
    public fun detectOs(osName: String): JdxOs {
        val lower = osName.lowercase()
        return when {
            "win" in lower -> JdxOs.WINDOWS
            "mac" in lower || "darwin" in lower -> JdxOs.MACOS
            else -> JdxOs.LINUX
        }
    }

    /** The resolved cache root (index DB, `m2/`, Kotlin sidecar, `auto/` workspaces). */
    public fun cacheRoot(
        home: Path,
        os: JdxOs,
        env: Map<String, String> = emptyMap(),
        explicit: Path? = null,
    ): Path {
        explicit?.let { return it }
        env.nonBlank(ENV_CACHE_DIR)?.let { return Path.of(it) }
        return when (os) {
            JdxOs.LINUX -> env.nonBlank("XDG_CACHE_HOME")?.let { Path.of(it).resolve("jdx") }
                ?: home.resolve(".cache/jdx")
            JdxOs.MACOS -> home.resolve("Library/Caches/jdx")
            JdxOs.WINDOWS -> env.nonBlank("LOCALAPPDATA")?.let { Path.of(it).resolve("jdx/cache") }
                ?: env.nonBlank("USERPROFILE")?.let { Path.of(it).resolve("AppData/Local/jdx/cache") }
                ?: home.resolve(".cache/jdx")
        }
    }

    /** The resolved config root (workspaces, `active-workspace`). */
    public fun configRoot(
        home: Path,
        os: JdxOs,
        env: Map<String, String> = emptyMap(),
        explicit: Path? = null,
    ): Path {
        explicit?.let { return it }
        env.nonBlank(ENV_CONFIG_DIR)?.let { return Path.of(it) }
        return when (os) {
            JdxOs.LINUX -> env.nonBlank("XDG_CONFIG_HOME")?.let { Path.of(it).resolve("jdx") }
                ?: home.resolve(".config/jdx")
            JdxOs.MACOS -> home.resolve("Library/Application Support/jdx")
            JdxOs.WINDOWS -> env.nonBlank("APPDATA")?.let { Path.of(it).resolve("jdx") }
                ?: env.nonBlank("LOCALAPPDATA")?.let { Path.of(it).resolve("jdx/config") }
                ?: home.resolve(".config/jdx")
        }
    }

    /**
     * The daemon socket directory (parent of the version-stamped `<hash>-v1.sock`).
     * Linux prefers `$XDG_RUNTIME_DIR/jdx`, then `$TMPDIR/jdx-$UID`, then
     * `<cache>/run`; macOS prefers `$TMPDIR/jdx-$UID`, then `<cache>/run`;
     * Windows uses `%LOCALAPPDATA%/jdx/run` (AF_UNIX, Win10 17063+ floor).
     * A missing `XDG_RUNTIME_DIR` is never an error — it falls back per table.
     *
     * @param cacheRoot the resolved cache root (its `run` child is the last fallback).
     * @param uid the Unix user id for the `$TMPDIR/jdx-$UID` row; callers pass
     *   `UID` from the environment else `user.name`, tests pass fakes.
     */
    public fun runtimeDir(
        home: Path,
        os: JdxOs,
        env: Map<String, String> = emptyMap(),
        cacheRoot: Path,
        uid: String? = null,
    ): Path {
        env.nonBlank(ENV_RUNTIME_DIR)?.let { return Path.of(it) }
        return when (os) {
            JdxOs.LINUX -> env.nonBlank("XDG_RUNTIME_DIR")?.let { Path.of(it).resolve("jdx") }
                ?: tmpSocketDir(env, uid)?.let { it }
                ?: cacheRoot.resolve("run")
            JdxOs.MACOS -> tmpSocketDir(env, uid)
                ?: cacheRoot.resolve("run")
            JdxOs.WINDOWS -> env.nonBlank("LOCALAPPDATA")?.let { Path.of(it).resolve("jdx/run") }
                ?: cacheRoot.resolve("run")
        }
    }

    /** The pre-D-044 cache location (`~/.cache/jdx` on every OS) — the migration source. */
    public fun legacyCacheRoot(home: Path): Path = home.resolve(".cache/jdx")

    /** The pre-D-044 config location (`~/.config/jdx` on every OS) — the migration source. */
    public fun legacyConfigRoot(home: Path): Path = home.resolve(".config/jdx")

    /**
     * True when the resolved root differs from the legacy dot-dir, i.e. a move or a
     * both-exist warning applies on this OS. Linux defaults never migrate.
     */
    public fun needsMigration(legacy: Path, resolved: Path): Boolean = legacy != resolved

    private fun tmpSocketDir(env: Map<String, String>, uid: String?): Path? {
        val tmp = env.nonBlank("TMPDIR") ?: return null
        val id = uid?.takeIf { it.isNotBlank() }
            ?: env.nonBlank("UID")
            ?: env.nonBlank("USER")
            ?: return Path.of(tmp).resolve("jdx")
        return Path.of(tmp).resolve("jdx-$id")
    }

    private fun Map<String, String>.nonBlank(key: String): String? =
        this[key]?.takeIf { it.isNotBlank() }
}
