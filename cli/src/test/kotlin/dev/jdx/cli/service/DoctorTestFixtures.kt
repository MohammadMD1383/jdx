package dev.jdx.cli.service

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared builders for doctor tests: fake home/JDK layouts and stub `javap` runners. Every
 * environment is assembled under a fresh temp dir, so tests never touch the real home
 * directory and never spawn a real `javap`.
 */
internal fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix)

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
    /** OS name for `.exe` tool probing; tests inject `"Windows 11"`. */
    osName: String = System.getProperty("os.name", ""),
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
    val cacheRoot = home.resolve(".cache/jdx").also { Files.createDirectories(it) }
    cacheSetup(cacheRoot)
    if (kotlinSidecarPresent) {
        val sidecar = dev.jdx.sources.kotlinSidecarJar(home)
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
    return DoctorEnvironment(
        userHome = home,
        javaHome = javaHome,
        javaHomeEnv = envJavaHome,
        pathDirs = listOf(pathBin),
        runtimeDir = runtimeDir,
        workingDir = cwd,
        workspaceEnv = workspaceEnv,
        osName = osName,
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
