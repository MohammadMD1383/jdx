package dev.jdx.testsupport.paths

import java.nio.file.Files
import java.nio.file.Path

/**
 * A short, unique runtime directory for unix-socket tests.
 *
 * Unix sockets cap `sun_path` (104 bytes on macOS, 108 elsewhere including
 * Windows AF_UNIX), while `@TempDir` roots read `/var/folders/...` on macOS —
 * already ~90 chars before the socket name, so every bind there dies with
 * "Unix domain path too long". This helper roots socket dirs at the system
 * temp base (`/tmp` on macOS, `%TEMP%` on Windows, `java.io.tmpdir` elsewhere)
 * with a short unique leaf, keeping binds ~30–70 chars on all three OSes.
 *
 * The directory is `deleteOnExit`-registered; servers under test sweep their
 * socket files on `stop`, so only the (empty) dir lingers until JVM exit.
 * Uniqueness comes from pid + thread + nanotime — parallel Gradle workers on
 * one host never share a dir.
 */
public fun shortSocketDir(scope: String): Path {
    val os = System.getProperty("os.name", "").lowercase()
    val base = when {
        os.contains("mac") -> Path.of("/tmp")
        os.contains("win") -> System.getenv("TEMP")?.let { Path.of(it) }
            ?: Path.of(System.getProperty("java.io.tmpdir"))
        else -> Path.of(System.getProperty("java.io.tmpdir"))
    }
    val leaf = "jdx-sock-$scope-${ProcessHandle.current().pid()}" +
        "-${Thread.currentThread().threadId()}-${System.nanoTime().toString(36)}"
    return Files.createDirectories(base.resolve(leaf)).also { it.toFile().deleteOnExit() }
}
