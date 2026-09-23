package dev.jdx.app

import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests of the POSIX launcher script (src/main/scripts/jdx) against a *stub JDK*: a fake
 * `bin/java` shell script that reports a configurable version and records the exec-line
 * arguments. The stub-JDK suites are fast; the two end-to-end tests at the bottom boot a
 * real JVM each, which puts the whole class (~6 s) over what the tier-1 loop should carry —
 * so this is tier 2 (`@Tag("tier2")`, T-053): it runs in `./gradlew check`, never in
 * `./gradlew test`. The version-gate properties are this task's generative
 * family (TESTING.md §2/§4): versions are generated, never hand-picked.
 */
@Tag("tier2")
class LauncherScriptTest {

    private val projectDir = File(System.getProperty("user.dir"))
    private val launcherSource = File(projectDir, "src/main/scripts/jdx")
    private val builtLauncher = File(projectDir, "build/jdx")

    // ------------------------------------------------------------------ helpers

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runCommand(
        command: List<String>,
        workingDir: File,
        environment: MutableMap<String, String>.() -> Unit = {},
    ): ProcessResult {
        val builder = ProcessBuilder(command).directory(workingDir)
        builder.environment().apply(environment)
        val process = builder.start()
        // Outputs here are tiny (a few lines); sequential reads cannot deadlock a pipe.
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return ProcessResult(process.waitFor(), stdout, stderr)
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    /** A minimal `build/jdx` + `build/libs/jdx-*-all.jar` layout; the jar is never opened. */
    private fun newFakeInstall(withCdsArchive: Boolean = false): File {
        val dir = tempDir("jdx-install-")
        val launcher = File(dir, "jdx").apply {
            writeText(launcherSource.readText())
            setExecutable(true, false)
        }
        val libs = File(dir, "libs").apply { mkdirs() }
        File(libs, "jdx-0.0.0-all.jar").writeBytes(byteArrayOf(0x50, 0x4b))
        if (withCdsArchive) {
            // The launcher only checks presence; the stub java never reads it.
            File(libs, "jdx.jsa").writeBytes(byteArrayOf(0x50, 0x4b))
        }
        return dir
    }

    /** A fake JDK whose `bin/java` is a shell stub configured through the environment. */
    private fun newStubJdk(): File {
        val home = tempDir("jdx-stubjdk-")
        val java = File(File(home, "bin").apply { mkdirs() }, "java")
        java.writeText(
            """
            #!/bin/sh
            if [ "${'$'}1" = "-version" ]; then
                printf '%s\n' "${'$'}STUB_VERSION_LINE" >&2
                exit "${'$'}STUB_VERSION_EXIT"
            fi
            printf '%s\n' "${'$'}@" > "${'$'}STUB_ARGS_FILE"
            printf '%s\n' "${'$'}STUB_EXEC_STDOUT"
            printf '%s\n' "${'$'}STUB_EXEC_STDERR" >&2
            exit "${'$'}STUB_EXEC_EXIT"
            """.trimIndent(),
        )
        java.setExecutable(true, false)
        return home
    }

    /** Environment consumed by the stub JDK, inherited through the launcher's exec. */
    private fun stubEnvironment(
        argsFile: File,
        versionLine: String = """openjdk version "26.0.1"""",
        versionExit: Int = 0,
        execExit: Int = 0,
    ): Map<String, String> = mapOf(
        "STUB_ARGS_FILE" to argsFile.absolutePath,
        "STUB_VERSION_LINE" to versionLine,
        "STUB_VERSION_EXIT" to versionExit.toString(),
        "STUB_EXEC_EXIT" to execExit.toString(),
        "STUB_EXEC_STDOUT" to "stub-stdout",
        "STUB_EXEC_STDERR" to "stub-stderr",
    )

    private fun runLauncher(
        launcherFile: File,
        workingDir: File,
        args: List<String>,
        environment: Map<String, String> = emptyMap(),
        removeJavaHome: Boolean = true,
    ): ProcessResult = runCommand(listOf(launcherFile.absolutePath) + args, workingDir) {
        if (removeJavaHome) remove("JAVA_HOME")
        putAll(environment)
    }

    private fun recordedArgs(argsFile: File): List<String> =
        if (argsFile.exists()) argsFile.readLines() else emptyList()

    /**
     * A PATH containing only the external tools the launcher calls (readlink, sed, dirname)
     * and no `java`, so PATH resolution can be exercised or forced to fail deterministically
     * even on machines that always have a JDK.
     */
    private fun toolsOnlyPath(): String {
        val bin = tempDir("jdx-toolsbin-")
        for (tool in listOf("readlink", "sed", "dirname")) {
            val location = runCommand(listOf("sh", "-c", "command -v $tool"), bin)
            assumeTrue(location.exitCode == 0 && location.stdout.isNotBlank(), "$tool not found on PATH")
            File(bin, tool).let { symlink ->
                Files.createSymbolicLink(symlink.toPath(), File(location.stdout.trim()).toPath())
            }
        }
        return bin.absolutePath
    }

    // ------------------------------------------------------------------ happy path

    @Test
    fun `JAVA_HOME JDK execs the fat jar with one-shot flags, jar and args, and passes streams and exit code through`() {
        val install = newFakeInstall()
        val stubJdk = newStubJdk()
        val argsFile = File(install, "stub-args.txt")

        val result = runLauncher(
            File(install, "jdx"), install,
            args = listOf("--version", "extra arg with spaces"),
            environment = mapOf("JAVA_HOME" to stubJdk.absolutePath) + stubEnvironment(argsFile, execExit = 42),
        )

        result.exitCode shouldBe 42
        result.stdout.trim() shouldBe "stub-stdout"
        result.stderr.trim() shouldBe "stub-stderr"
        recordedArgs(argsFile) shouldBe listOf(
            "-XX:TieredStopAtLevel=1",
            "-XX:+UseSerialGC",
            "-Xshare:auto",
            "-jar",
            File(install, "libs/jdx-0.0.0-all.jar").absolutePath,
            "--version",
            "extra arg with spaces",
        )
    }

    @Test
    fun `java on PATH is used when JAVA_HOME is unset`() {
        val install = newFakeInstall()
        val stubJdk = newStubJdk()
        val argsFile = File(install, "stub-args.txt")

        val result = runLauncher(
            File(install, "jdx"), install, args = listOf("members", "java.util.Map"),
            environment = stubEnvironment(argsFile) + mapOf(
                "PATH" to File(stubJdk, "bin").absolutePath + ":" + System.getenv("PATH"),
            ),
        )

        result.exitCode shouldBe 0
        recordedArgs(argsFile).last() shouldBe "java.util.Map"
    }

    @Test
    fun `JDX_JVM_DEFAULT_DIR is the last resort when JAVA_HOME and PATH have no java`() {
        val install = newFakeInstall()
        val stubJdk = newStubJdk()
        val argsFile = File(install, "stub-args.txt")

        val result = runLauncher(
            File(install, "jdx"), install, args = listOf("--version"),
            environment = stubEnvironment(argsFile) + mapOf(
                "PATH" to toolsOnlyPath(),
                "JDX_JVM_DEFAULT_DIR" to stubJdk.absolutePath,
            ),
        )

        result.exitCode shouldBe 0
        recordedArgs(argsFile) shouldContain "-jar"
    }

    @Test
    fun `a symlinked launcher resolves to the real fat jar`() {
        // install.sh symlinks build/jdx into ~/.local/bin; the launcher must locate the jar
        // relative to its resolved path, not the symlink's directory.
        val install = newFakeInstall()
        val stubJdk = newStubJdk()
        val argsFile = File(install, "stub-args.txt")
        val binDir = tempDir("jdx-userbin-")
        val symlink = File(binDir, "jdx")
        Files.createSymbolicLink(symlink.toPath(), File(install, "jdx").toPath())

        val result = runLauncher(
            symlink, binDir, args = listOf("--version"),
            environment = mapOf("JAVA_HOME" to stubJdk.absolutePath) + stubEnvironment(argsFile),
        )

        result.exitCode shouldBe 0
        recordedArgs(argsFile) shouldContain File(install, "libs/jdx-0.0.0-all.jar").absolutePath
    }

    // ------------------------------------------------------------------ failure paths (all must be human-readable, exit 6)

    private fun assertCleanFailure(result: ProcessResult, vararg messageFragments: String) {
        result.exitCode shouldBe 6
        result.stderr.shouldStartWith("jdx: ")
        result.stdout shouldBe ""
        // A stack trace reaching an agent is a test failure (TESTING.md §7).
        result.stderr shouldNotContain "Exception"
        for (fragment in messageFragments) result.stderr shouldContain fragment
    }

    @Test
    fun `missing JDK everywhere is a clean error`() {
        val install = newFakeInstall()

        val result = runLauncher(
            File(install, "jdx"), install, args = listOf("--version"),
            environment = mapOf(
                "PATH" to toolsOnlyPath(),
                "JDX_JVM_DEFAULT_DIR" to "/nonexistent-jdx-test-jvm",
            ),
        )

        assertCleanFailure(result, "no Java runtime found", "JAVA_HOME")
    }

    @Test
    fun `broken JAVA_HOME is a loud error, not a silent fall-through to PATH`() {
        val install = newFakeInstall()

        val result = runLauncher(
            File(install, "jdx"), install, args = listOf("--version"),
            environment = mapOf("JAVA_HOME" to "/nonexistent-jdx-test-home"),
        )

        assertCleanFailure(result, "JAVA_HOME", "not an executable")
    }

    @Test
    fun `a JDK below the minimum is rejected with its version named`() {
        val install = newFakeInstall()
        val stubJdk = newStubJdk()
        val argsFile = File(install, "stub-args.txt")

        val result = runLauncher(
            File(install, "jdx"), install, args = listOf("--version"),
            environment = mapOf("JAVA_HOME" to stubJdk.absolutePath) +
                stubEnvironment(argsFile, versionLine = """openjdk version "17.0.11""""),
        )

        assertCleanFailure(result, "Java 21", "17.0.11")
    }

    @Test
    fun `a legacy 1-dot-8 JDK is rejected as Java 8`() {
        val install = newFakeInstall()
        val stubJdk = newStubJdk()
        val argsFile = File(install, "stub-args.txt")

        val result = runLauncher(
            File(install, "jdx"), install, args = listOf("--version"),
            environment = mapOf("JAVA_HOME" to stubJdk.absolutePath) +
                stubEnvironment(argsFile, versionLine = """java version "1.8.0_452""""),
        )

        assertCleanFailure(result, "1.8.0_452")
    }

    @Test
    fun `an early-access build of a new enough JDK is accepted`() {
        val install = newFakeInstall()
        val stubJdk = newStubJdk()
        val argsFile = File(install, "stub-args.txt")

        val result = runLauncher(
            File(install, "jdx"), install, args = listOf("--version"),
            environment = mapOf("JAVA_HOME" to stubJdk.absolutePath) +
                stubEnvironment(argsFile, versionLine = """openjdk version "22-ea""""),
        )

        result.exitCode shouldBe 0
    }

    @Test
    fun `java -version crashing is a clean error`() {
        val install = newFakeInstall()
        val stubJdk = newStubJdk()
        val argsFile = File(install, "stub-args.txt")

        val result = runLauncher(
            File(install, "jdx"), install, args = listOf("--version"),
            environment = mapOf("JAVA_HOME" to stubJdk.absolutePath) +
                stubEnvironment(argsFile, versionExit = 1),
        )

        assertCleanFailure(result, "could not run")
    }

    @Test
    fun `unparseable version output is a clean error`() {
        val install = newFakeInstall()
        val stubJdk = newStubJdk()
        val argsFile = File(install, "stub-args.txt")

        val result = runLauncher(
            File(install, "jdx"), install, args = listOf("--version"),
            environment = mapOf("JAVA_HOME" to stubJdk.absolutePath) +
                stubEnvironment(argsFile, versionLine = "the quick brown fox"),
        )

        assertCleanFailure(result, "no version string")
    }

    @Test
    fun `missing fat jar names the build command`() {
        val install = newFakeInstall()
        File(install, "libs/jdx-0.0.0-all.jar").delete()

        val result = runLauncher(File(install, "jdx"), install, args = listOf("--version"))

        assertCleanFailure(result, ":app:installDist")
    }

    // ------------------------------------------------------------------ AppCDS archive (T-048)

    @Test
    fun `a jdx-dot-jsa next to the fat jar is passed as SharedArchiveFile after Xshare-auto`() {
        val install = newFakeInstall(withCdsArchive = true)
        val stubJdk = newStubJdk()
        val argsFile = File(install, "stub-args.txt")

        val result = runLauncher(
            File(install, "jdx"), install,
            args = listOf("--version"),
            environment = mapOf("JAVA_HOME" to stubJdk.absolutePath) + stubEnvironment(argsFile),
        )

        result.exitCode shouldBe 0
        recordedArgs(argsFile) shouldBe listOf(
            "-XX:TieredStopAtLevel=1",
            "-XX:+UseSerialGC",
            "-Xshare:auto",
            "-XX:SharedArchiveFile=${File(install, "libs/jdx.jsa").absolutePath}",
            "-jar",
            File(install, "libs/jdx-0.0.0-all.jar").absolutePath,
            "--version",
        )
    }

    @Test
    fun `a missing jdx-dot-jsa keeps the plain exec line`() {
        val install = newFakeInstall(withCdsArchive = false)
        val stubJdk = newStubJdk()
        val argsFile = File(install, "stub-args.txt")

        val result = runLauncher(
            File(install, "jdx"), install,
            args = listOf("--version"),
            environment = mapOf("JAVA_HOME" to stubJdk.absolutePath) + stubEnvironment(argsFile),
        )

        result.exitCode shouldBe 0
        recordedArgs(argsFile) shouldNotContain "-XX:SharedArchiveFile=${File(install, "libs/jdx.jsa").absolutePath}"
        recordedArgs(argsFile) shouldContain "-Xshare:auto"
    }

    @Test
    fun `archive presence never changes the version gate`() = runBlocking<Unit> {
        val versionArb = versionLineArb
        val archiveArb = Arb.of(true, false)

        checkAll(100, versionArb, archiveArb) { generated, withArchive ->
            val install = newFakeInstall(withCdsArchive = withArchive)
            val stubJdk = newStubJdk()
            val argsFile = File(install, "stub-args.txt")

            val result = runLauncher(
                File(install, "jdx"), install, args = listOf("--version"),
                environment = mapOf("JAVA_HOME" to stubJdk.absolutePath) +
                    stubEnvironment(argsFile, versionLine = generated.versionLine),
            )
            val archiveFlag = "-XX:SharedArchiveFile=${File(install, "libs/jdx.jsa").absolutePath}"
            if (generated.effectiveMajor >= 21) {
                result.exitCode shouldBe 0
                if (withArchive) recordedArgs(argsFile) shouldContain archiveFlag
                else recordedArgs(argsFile) shouldNotContain archiveFlag
            } else {
                // Rejected before the exec line: no args recorded at all.
                result.exitCode shouldBe 6
                result.stderr.shouldStartWith("jdx: ")
            }
        }
    }

    // ------------------------------------------------------------------ generative family (TESTING.md §4)

    private data class GeneratedVersion(val versionLine: String, val effectiveMajor: Int)

    /** JDK-style version lines: legacy `1.8.0_392`, plain `21.0.8`, pre-release `22-ea`. */
    private val versionLineArb: Arb<GeneratedVersion> = Arb.bind(
        Arb.of(6, 8, 9, 11, 17, 20, 21, 22, 25, 26, 30, 45),
        Arb.int(0..60),
        Arb.int(0..400),
        Arb.of(true, false),
        Arb.of("", "-ea", "-internal"),
        Arb.of("openjdk version", "java version"),
    ) { major, minor, patch, legacy, suffix, prefix ->
        val version = if (legacy && major < 10) "1.$major.$minor" + "_$patch" + suffix
        else "$major.$minor.$patch" + suffix
        GeneratedVersion("""$prefix "$version"""", major)
    }

    @Test
    fun `version gate accepts exactly the generated JDKs with major 21 or newer`() = runBlocking<Unit> {
        val install = newFakeInstall()
        val stubJdk = newStubJdk()
        val argsFile = File(install, "stub-args.txt")

        checkAll(200, versionLineArb) { generated ->
            val result = runLauncher(
                File(install, "jdx"), install, args = listOf("--version"),
                environment = mapOf("JAVA_HOME" to stubJdk.absolutePath) +
                    stubEnvironment(argsFile, versionLine = generated.versionLine),
            )
            if (generated.effectiveMajor >= 21) {
                result.exitCode shouldBe 0
                recordedArgs(argsFile) shouldContain "-XX:TieredStopAtLevel=1"
            } else {
                result.exitCode shouldBe 6
                result.stderr.shouldStartWith("jdx: ")
            }
        }
    }

    @Test
    fun `garbage version output never yields a stack trace`() = runBlocking<Unit> {
        val install = newFakeInstall()
        val stubJdk = newStubJdk()
        val argsFile = File(install, "stub-args.txt")
        // No quote character -> the sed extraction can never succeed, so every input fails.
        val garbageArb = Arb.string(1..40).filter { '"' !in it && it.isNotBlank() }

        checkAll(100, garbageArb) { garbage ->
            val result = runLauncher(
                File(install, "jdx"), install, args = listOf("--version"),
                environment = mapOf("JAVA_HOME" to stubJdk.absolutePath) +
                    stubEnvironment(argsFile, versionLine = garbage),
            )
            result.exitCode shouldBe 6
            result.stderr.shouldStartWith("jdx: ")
            result.stderr shouldNotContain "Exception"
        }
    }

    // ------------------------------------------------------------------ end to end (real launcher, real fat jar, real JVM)

    @Test
    fun `--version round-trips through the real launcher and fat jar`() {
        assumeTrue(javaIsAvailable(), "no java on PATH or /usr/lib/jvm/default")
        assumeTrue(builtLauncher.isFile, "app/build/jdx missing — run :app:installDist")

        val result = runLauncher(builtLauncher, projectDir, args = listOf("--version"))

        result.exitCode shouldBe 0
        result.stdout.trim() shouldBe "jdx version ${dev.jdx.cli.BuildInfo.version}"
        // Piped output is plain text (CLAUDE.md §2.5): no ANSI escapes.
        result.stdout shouldNotContain "\u001B"
    }

    @Test
    fun `--help round-trips through the real launcher and fat jar`() {
        assumeTrue(javaIsAvailable(), "no java on PATH or /usr/lib/jvm/default")
        assumeTrue(builtLauncher.isFile, "app/build/jdx missing — run :app:installDist")

        val result = runLauncher(builtLauncher, projectDir, args = listOf("--help"))

        result.exitCode shouldBe 0
        result.stdout.shouldContain("Usage: jdx")
    }

    @Test
    fun `doctor round-trips through the real launcher and fat jar`() {
        assumeTrue(javaIsAvailable(), "no java on PATH or /usr/lib/jvm/default")
        assumeTrue(builtLauncher.isFile, "app/build/jdx missing — run :app:installDist")

        val result = runLauncher(builtLauncher, projectDir, args = listOf("doctor"))

        // Machine-dependent by design (doctor reports this machine): only the shape is pinned.
        (result.exitCode == 0 || result.exitCode == 6) shouldBe true
        result.stdout.shouldStartWith("jdx doctor")
        result.stdout.trim().lines().size shouldBe 11
        result.stdout shouldNotContain "\u001B"
    }

    @Test
    fun `doctor --json round-trips through the real launcher and fat jar`() {
        assumeTrue(javaIsAvailable(), "no java on PATH or /usr/lib/jvm/default")
        assumeTrue(builtLauncher.isFile, "app/build/jdx missing — run :app:installDist")

        val result = runLauncher(builtLauncher, projectDir, args = listOf("doctor", "--json"))

        (result.exitCode == 0 || result.exitCode == 6) shouldBe true
        // Compact envelope; string-pinned so the app module needs no JSON library.
        result.stdout shouldContain "\"jdx\":1"
        result.stdout shouldContain "\"command\":\"doctor\""
        result.stdout shouldContain "\"checks\":["
    }

    @Test
    fun `version round-trips through the real launcher and fat jar`() {
        assumeTrue(javaIsAvailable(), "no java on PATH or /usr/lib/jvm/default")
        assumeTrue(builtLauncher.isFile, "app/build/jdx missing — run :app:installDist")

        val result = runLauncher(builtLauncher, projectDir, args = listOf("version"))

        result.exitCode shouldBe 0
        result.stdout.trim() shouldBe "jdx version ${dev.jdx.cli.BuildInfo.version}"
    }

    @Test
    fun `installDist ships a non-empty AppCDS archive next to the fat jar`() {
        assumeTrue(builtLauncher.isFile, "app/build/jdx missing — run :app:installDist")

        val libs = File(projectDir, "build/libs")
        val fatJars = libs.listFiles { file -> file.name.startsWith("jdx-") && file.name.endsWith("-all.jar") }
            .orEmpty().toList()
        assumeTrue(fatJars.isNotEmpty(), "fat jar missing in app/build/libs — run :app:installDist")

        // The launcher resolves this fixed path (T-048); presence is what engages CDS.
        val archive = File(libs, "jdx.jsa")
        archive.isFile shouldBe true
        (archive.length() > 0) shouldBe true
    }

    private fun javaIsAvailable(): Boolean =
        runCommand(
            listOf("sh", "-c", "command -v java >/dev/null 2>&1 || [ -x /usr/lib/jvm/default/bin/java ]"),
            projectDir,
        ).exitCode == 0
}
