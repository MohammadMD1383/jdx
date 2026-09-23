package dev.jdx.server

import dev.jdx.core.rpc.RPC_VERSION
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Where a daemon lives on disk (T-041; PROPOSAL.md §14.3).
 *
 * A daemon listens on a unix-domain socket at
 * `$XDG_RUNTIME_DIR/jdx/<workspace-hash>-v<rpc-version>.sock`, with a pid file
 * and a log file as siblings. The file name — not the directory — carries the
 * version stamp, so an upgraded `jdx` looks for a different socket and never
 * talks to a stale daemon (D-056 consequences).
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

    /** Sibling of [socketPath] with a `.pid` suffix (holds the daemon's pid). */
    public fun pidPath(socketPath: Path): Path = siblingWithSuffix(socketPath, ".pid")

    /** Sibling of [socketPath] with a `.log` suffix (the spawned daemon's output). */
    public fun logPath(socketPath: Path): Path = siblingWithSuffix(socketPath, ".log")

    private fun siblingWithSuffix(socketPath: Path, suffix: String): Path {
        val name = socketPath.fileName.toString().removeSuffix(".sock")
        return socketPath.resolveSibling(name + suffix)
    }
}
