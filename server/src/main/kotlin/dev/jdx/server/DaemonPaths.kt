package dev.jdx.server

import dev.jdx.core.paths.JdxOs
import dev.jdx.core.paths.JdxPaths
import dev.jdx.core.rpc.RPC_VERSION
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Where a daemon lives on disk (T-041; PROPOSAL.md §14.3).
 *
 * A daemon listens on a unix-domain socket at
 * `<socket-dir>/<workspace-hash>-v<rpc-version>.sock`, with a pid file
 * and a log file as siblings. The file name — not the directory — carries the
 * version stamp, so an upgraded `jdx` looks for a different socket and never
 * talks to a stale daemon (D-056 consequences).
 *
 * The socket directory resolves per OS (D-044; `docs/PROPOSAL.md` §17.1):
 * `$XDG_RUNTIME_DIR/jdx`, else `$TMPDIR/jdx-$UID`, else `<cache-dir>/run` on
 * Linux; `$TMPDIR/jdx-$UID`, else `<cache-dir>/run` on macOS;
 * `%LOCALAPPDATA%/jdx/run` on Windows. A missing `XDG_RUNTIME_DIR` falls back
 * per table, never `exit 3`.
 *
 * Pure paths, no IO: resolving them never touches the filesystem.
 */
public object DaemonPaths {
    /** The directory directly under `$XDG_RUNTIME_DIR` that holds daemon sockets. */
    public const val DIR_NAME: String = "jdx"

    /** `$XDG_RUNTIME_DIR` from the environment, or `null` when unset or empty. */
    public fun systemRuntimeDir(): Path? =
        System.getenv("XDG_RUNTIME_DIR")
            ?.takeIf { it.isNotEmpty() }
            ?.let { Path.of(it) }

    /**
     * The daemon socket directory (parent of the version-stamped `<hash>-v1.sock`).
     * Pure path math over explicit inputs so tests pin Linux/macOS/Windows
     * defaults with fakes and never touch the real home.
     */
    public fun socketDir(
        home: Path,
        os: JdxOs,
        env: Map<String, String>,
        cacheRoot: Path,
        uid: String?,
    ): Path = JdxPaths.runtimeDir(home, os, env, cacheRoot, uid)

    /**
     * The production socket directory: `JDX_RUNTIME_DIR` > platform env >
     * OS default (D-044). Never null — a missing `XDG_RUNTIME_DIR` falls back
     * per table, never `exit 3`.
     */
    public fun systemSocketDir(): Path {
        val home = Path.of(System.getProperty("user.home"))
        val os = JdxPaths.detectOs(System.getProperty("os.name", ""))
        val env = System.getenv()
        val cache = JdxPaths.cacheRoot(home, os, env)
        val uid = env["UID"]?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.name")?.takeIf { it.isNotBlank() }
        return JdxPaths.runtimeDir(home, os, env, cache, uid)
    }

    /**
     * Stable 16-hex-char identity of a workspace name (SHA-256, truncated).
     * Hashed rather than raw so odd workspace names cannot escape the socket
     * directory via `/` or `..`, and so the socket name stays short.
     */
    public fun workspaceHash(workspace: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(workspace.toByteArray(StandardCharsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** `<hash>-v<version>.sock` — the version stamp refuses stale daemons. */
    public fun socketFileName(workspace: String, version: Int = RPC_VERSION): String =
        "${workspaceHash(workspace)}-v$version.sock"

    /** `$runtimeDir/jdx/<socketFileName>`. */
    public fun socketPath(runtimeDir: Path, workspace: String, version: Int = RPC_VERSION): Path =
        runtimeDir.resolve(DIR_NAME).resolve(socketFileName(workspace, version))

    /** `<socketDir>/<socketFileName>` — [socketDir] is already the resolved OS socket dir. */
    public fun socketPathIn(socketDir: Path, workspace: String, version: Int = RPC_VERSION): Path =
        socketDir.resolve(socketFileName(workspace, version))

    /** Sibling of [socketPath] with a `.pid` suffix (holds the daemon's pid). */
    public fun pidPath(socketPath: Path): Path = siblingWithSuffix(socketPath, ".pid")

    /** Sibling of [socketPath] with a `.log` suffix (the spawned daemon's output). */
    public fun logPath(socketPath: Path): Path = siblingWithSuffix(socketPath, ".log")

    private fun siblingWithSuffix(socketPath: Path, suffix: String): Path {
        val name = socketPath.fileName.toString().removeSuffix(".sock")
        return socketPath.resolveSibling(name + suffix)
    }
}
