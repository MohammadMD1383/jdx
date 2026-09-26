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

    /** `sun_path` byte cap on Linux (and Windows AF_UNIX): over this `bind` fails. */
    public const val MAX_SOCKET_PATH_BYTES_LINUX: Int = 108

    /** `sun_path` byte cap on macOS: over this `bind` fails. */
    public const val MAX_SOCKET_PATH_BYTES_MACOS: Int = 104

    /** The `sun_path` byte cap for [os]: 104 on macOS, 108 elsewhere. */
    public fun maxSocketPathBytes(os: JdxOs): Int =
        if (os == JdxOs.MACOS) MAX_SOCKET_PATH_BYTES_MACOS else MAX_SOCKET_PATH_BYTES_LINUX

    /** UTF-8 byte length of the full socket path — what `sun_path` enforces. */
    public fun socketPathByteLength(socketPath: Path): Int =
        socketPath.toString().toByteArray(StandardCharsets.UTF_8).size

    /** True when [socketPath] cannot `bind` on [os] (macOS `/var/folders/…` hits this). */
    public fun socketPathTooLong(socketPath: Path, os: JdxOs): Boolean =
        socketPathByteLength(socketPath) > maxSocketPathBytes(os)

    /**
     * Actionable message for an over-long socket path: names the dir, the byte
     * count, the OS cap, and the `JDX_RUNTIME_DIR` escape hatch. Callers report
     * it with exit 3 — never a generic bind failure or a silent cold fallback.
     */
    public fun describeSocketPathTooLong(socketPath: Path, os: JdxOs): String {
        val bytes = socketPathByteLength(socketPath)
        val cap = maxSocketPathBytes(os)
        return "daemon socket path is $bytes bytes (limit $cap on $os): '$socketPath' — " +
            "set JDX_RUNTIME_DIR to a shorter directory"
    }

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

    /**
     * Sibling of [socketPath] with a `.lock` suffix (`<hash>-v1.lock`): the
     * start-mutual-exclusion file held via `FileChannel.tryLock` by the serving
     * daemon. Short by construction — the hashed stem, never the workspace name.
     */
    public fun lockPath(socketPath: Path): Path = siblingWithSuffix(socketPath, ".lock")

    /** `<hash>-v<version>.lock` — the lock-file stem mirrors the socket stamp. */
    public fun lockFileName(workspace: String, version: Int = RPC_VERSION): String =
        "${workspaceHash(workspace)}-v$version.lock"

    private fun siblingWithSuffix(socketPath: Path, suffix: String): Path {
        val name = socketPath.fileName.toString().removeSuffix(".sock")
        return socketPath.resolveSibling(name + suffix)
    }
}
