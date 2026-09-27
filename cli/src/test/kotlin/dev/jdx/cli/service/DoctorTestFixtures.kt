package dev.jdx.cli.service

import dev.jdx.core.paths.JdxPaths
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared builders for doctor tests: fake home/JDK layouts and stub `javap` runners. Every
 * environment is assembled under a fresh temp dir, so tests never touch the real home
 * directory and never spawn a real `javap`.
 */
internal fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix)

/** AppCDS fixture shapes for the `appcds` doctor row (see [fakeEnvironment]). */
internal enum class CdsState { PRESENT, ABSENT, STALE, EMPTY, UNRESOLVABLE }

/** An executable file that is never run — the stub [ProcessRunner] answers instead. */
internal fun emptyExecutable(dir: Path, name: String): Path {
    Files.createDirectories(dir)
    return Files.createFile(dir.resolve(name)).also { it.toFile().setExecutable(true) }
}

internal fun cannedOutcome(output: String, exitCode: Int = 0): ProcessRunner =
    ProcessRunner { _, _ -> ProcessOutcome(exitCode, output, "") }

internal fun throwingRunner(message: String): ProcessRunner =
    ProcessRunner { _, _ -> throw IOException(message) }

/**
 * Assembles a [DoctorEnvironment] under [root]. `javap` resolution order is javaHome/bin
 * first, then [pathBin]: create the executable file in one, the other, both or neither.
 */
internal fun fakeEnvironment(
    root: Path,
    javaHomeBinJavap: Boolean = true,
    pathBinJavap: Boolean = false,
    runner: ProcessRunner = cannedOutcome("26.0.2.1"),
    srcZipPresent: Boolean = true,
    /** A second JDK home standing in for `$JAVA_HOME` (T-012 fallback); null means unset. */
    envJavaHome: Path? = null,
    jrtReachable: Boolean = true,
    cacheSetup: (cacheRoot: Path) -> Unit = {},
    configPresent: Boolean = false,
    indexDbPresent: Boolean = false,
    socketCount: Int = 0,
    workingDir: Path? = null,
    /** `$JDX_WORKSPACE` stand-in for the workspace row (T-015); null means unset. */
    workspaceEnv: String? = null,
    /** Workspace files to pre-store as `name to jars` (T-015); empty means none. */
    workspaces: Map<String, List<String>> = emptyMap(),
    /** `jdx ws use` selection to pre-store (T-015); null means none. */
    activeWorkspace: String? = null,
    /** A stub sidecar jar standing in for the D-008 compiler (T-038); false means absent. */
    kotlinSidecarPresent: Boolean = false,
    /** OpenCode binaries to place on the fake PATH (`opencode`, `opencode2`); empty means absent. */
    opencodeBinaries: List<String> = emptyList(),
    /** Claude Code binaries to place on the fake PATH (`claude`); empty means absent. */
    claudeBinaries: List<String> = emptyList(),
    /** OS name for `.exe` tool probing; tests inject `"Windows 11"`. */
    osName: String = System.getProperty("os.name", ""),
    /**
     * AppCDS fixture shape: PRESENT creates a fresh archive newer than the fat jar (OK);
     * ABSENT passes paths but creates no archive file (WARN); STALE backdates the archive
     * below the fat jar (WARN); EMPTY creates a zero-byte archive (WARN); UNRESOLVABLE
     * passes null paths, i.e. dev layout (WARN).
     */
    cdsState: CdsState = CdsState.PRESENT,
): DoctorEnvironment {
    val home = root.resolve("home").also { Files.createDirectories(it) }
    val javaHome = root.resolve("jdk").also { Files.createDirectories(it) }
    if (javaHomeBinJavap) {
        emptyExecutable(javaHome.resolve("bin"), "javap")
    }
    if (srcZipPresent) {
        val lib = javaHome.resolve("lib").also { Files.createDirectories(it) }
        Files.write(lib.resolve("src.zip"), byteArrayOf(0x50, 0x4b))
    }
    val pathBin = root.resolve("pathbin").also { Files.createDirectories(it) }
    if (pathBinJavap) {
        emptyExecutable(pathBin, "javap")
    }
    for (binary in opencodeBinaries) {
        for (name in toolFileNames(binary, osName)) {
            emptyExecutable(pathBin, name)
        }
    }
    for (binary in claudeBinaries) {
        for (name in toolFileNames(binary, osName)) {
            emptyExecutable(pathBin, name)
        }
    }
    val cacheRoot = home.resolve(".cache/jdx").also { Files.createDirectories(it) }
    cacheSetup(cacheRoot)
    if (kotlinSidecarPresent) {
        // Under the injected cache root (see envVars below), never
        // kotlinSidecarJar(home) — that resolves per ambient OS/env, so on
        // Windows (%LOCALAPPDATA% set) the sidecar would land in the real
        // machine's cache instead of the fixture.
        val sidecar = cacheRoot.resolve("kotlin")
            .resolve(dev.jdx.sources.KOTLIN_COMPILER_JAR)
        Files.createDirectories(sidecar.parent)
        Files.write(sidecar, ByteArray(64) { 0x50 })
    }
    if (configPresent) {
        Files.createDirectories(home.resolve(".config/jdx"))
    }
    if (indexDbPresent) {
        val indexDir = cacheRoot.resolve("index").also { Files.createDirectories(it) }
        Files.write(indexDir.resolve("v1.db"), byteArrayOf(0x53, 0x51, 0x4c))
    }
    val runtimeDir = root.resolve("run").also { Files.createDirectories(it) }
    if (socketCount > 0) {
        repeat(socketCount) { Files.createFile(runtimeDir.resolve("daemon-$it.sock")) }
    }
    val cwd = (workingDir ?: root.resolve("cwd")).also { Files.createDirectories(it) }
    val configDir = home.resolve(".config/jdx")
    if (workspaces.isNotEmpty() || activeWorkspace != null) {
        Files.createDirectories(configDir.resolve("workspaces"))
        for ((name, jars) in workspaces) {
            val quoted = jars.joinToString(", ") { "\"$it\"" }
            Files.writeString(
                configDir.resolve("workspaces/$name.toml"),
                "name = \"$name\"\njars = [$quoted]\ninclude_jdk = true\n",
            )
        }
        if (activeWorkspace != null) {
            Files.writeString(configDir.resolve("active-workspace"), "$activeWorkspace\n")
        }
    }
    val installLibs = root.resolve("install/libs").also { Files.createDirectories(it) }
    val fatJar = installLibs.resolve("jdx-test-all.jar")
    val archive = installLibs.resolve("jdx.jsa")
    val (cdsArchive, cdsFatJar) = when (cdsState) {
        CdsState.UNRESOLVABLE -> null to null
        CdsState.ABSENT -> {
            Files.write(fatJar, ByteArray(128) { 0x4a })
            archive to fatJar
        }
        CdsState.EMPTY -> {
            Files.write(fatJar, ByteArray(128) { 0x4a })
            Files.createFile(archive)
            archive to fatJar
        }
        CdsState.STALE -> {
            Files.write(fatJar, ByteArray(128) { 0x4a })
            Files.write(archive, ByteArray(2048) { 0x43 })
            // Backdate the archive below the fat jar so the mtime comparison reads stale.
            val jarTime = Files.getLastModifiedTime(fatJar)
            Files.setLastModifiedTime(
                archive,
                java.nio.file.attribute.FileTime.fromMillis(jarTime.toMillis() - 60_000),
            )
            archive to fatJar
        }
        CdsState.PRESENT -> {
            Files.write(fatJar, ByteArray(128) { 0x4a })
            Files.write(archive, ByteArray(2048) { 0x43 })
            // Fresh archive: at-or-after the fat jar so the row reads OK deterministically.
            val jarTime = Files.getLastModifiedTime(fatJar)
            Files.setLastModifiedTime(
                archive,
                java.nio.file.attribute.FileTime.fromMillis(jarTime.toMillis() + 1_000),
            )
            archive to fatJar
        }
    }
    return DoctorEnvironment(
        userHome = home,
        javaHome = javaHome,
        javaHomeEnv = envJavaHome,
        pathDirs = listOf(pathBin),
        runtimeDir = runtimeDir,
        workingDir = cwd,
        workspaceEnv = workspaceEnv,
        osName = osName,
        cdsArchive = cdsArchive,
        cdsFatJar = cdsFatJar,
        // Pin cache/config to the fixture on every OS: macOS resolves
        // ~/Library/... unconditionally (XDG_* is a Linux convention the
        // macOS branch ignores by design), and Windows falls back past
        // %LOCALAPPDATA% only when it is unset. JDX_* wins on all three, so
        // the fixture reads identically everywhere.
        envVars = mapOf(
            JdxPaths.ENV_CACHE_DIR to cacheRoot.toString(),
            JdxPaths.ENV_CONFIG_DIR to home.resolve(".config/jdx").toString(),
        ),
        runtime = RuntimeInfo(
            version = "26.0.2.1-test",
            jrtReachable = jrtReachable,
            jrtReason = if (jrtReachable) null else "fake jrt failure",
        ),
        processRunner = runner,
    )
}

/** Writes `sizes` bytes of files into `dir` (deterministic cache content for size rows). */
internal fun writeSizedFiles(dir: Path, vararg sizes: Int) {
    Files.createDirectories(dir)
    sizes.forEachIndexed { index, size ->
        Files.write(dir.resolve("file-$index.bin"), ByteArray(size))
    }
}
