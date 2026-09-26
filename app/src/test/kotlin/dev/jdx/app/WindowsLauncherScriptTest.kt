package dev.jdx.app

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests of the Windows launchers (`src/main/scripts/jdx.bat` + `jdx.ps1`, #46).
 *
 * cmd.exe / PowerShell cannot run on a Linux host, so the tier-1 contract here
 * is static: resolution order, flag set and order, exit-6 failure paths, and
 * the installDist outputs are pinned by content. The tier-2 smokes at the
 * bottom exec the real `jdx.bat` and run only on Windows (Windows CI lands
 * with #52); everywhere else they skip with a reason.
 */
class WindowsLauncherScriptTest {

    private val projectDir = File(System.getProperty("user.dir"))
    private val batSource = File(projectDir, "src/main/scripts/jdx.bat")
    private val ps1Source = File(projectDir, "src/main/scripts/jdx.ps1")
    private val builtBat = File(projectDir, "build/jdx.bat")
    private val builtPs1 = File(projectDir, "build/jdx.ps1")
    private val buildFile = File(projectDir, "build.gradle.kts")

    private fun bat(): String {
        assumeTrue(batSource.isFile, "src/main/scripts/jdx.bat missing")
        // CRLF is the committed form (*.bat text eol=crlf); normalise so the
        // pins below read identically on every checkout.
        return batSource.readText().replace("\r\n", "\n")
    }

    private fun ps1(): String {
        assumeTrue(ps1Source.isFile, "src/main/scripts/jdx.ps1 missing")
        return ps1Source.readText()
    }

    private fun assertOrdered(text: String, vararg tokens: String) {
        val indices = tokens.map { token ->
            val index = text.indexOf(token)
            (index >= 0) shouldBe true
            index
        }
        for (i in 1 until indices.size) (indices[i] > indices[i - 1]) shouldBe true
    }

    // ------------------------------------------------------------------ shipping

    @Test
    fun `installDist ships jdx-bat and jdx-ps1 next to the POSIX launcher`() {
        assumeTrue(builtBat.isFile, "app/build/jdx.bat missing — run :app:installDist")
        assumeTrue(builtPs1.isFile, "app/build/jdx.ps1 missing — run :app:installDist")
        builtBat.readText() shouldBe batSource.readText()
        builtPs1.readText() shouldBe ps1Source.readText()
        (builtBat.length() > 0) shouldBe true
        (builtPs1.length() > 0) shouldBe true
    }

    @Test
    fun `the build resolves java-exe on Windows so CDS training runs there`() {
        // java.home layouts are bin/java on POSIX and bin/java.exe on Windows;
        // a literal bin/java breaks :app:installDist on a Windows host (#46).
        buildFile.readText() shouldContain "bin/java.exe"
    }

    @Test
    fun `the bat file is committed CRLF`() {
        assumeTrue(batSource.isFile, "src/main/scripts/jdx.bat missing")
        val bytes = batSource.readBytes()
        (bytes.isNotEmpty()) shouldBe true
        val bareLf = bytes.indices.any { i ->
            bytes[i] == '\n'.code.toByte() && (i == 0 || bytes[i - 1] != '\r'.code.toByte())
        }
        bareLf shouldBe false
    }

    // ------------------------------------------------------------------ discovery

    @Test
    fun `the bat resolves JAVA_HOME then PATH then registry then ProgramFiles`() {
        // Scoped to the discovery body: the header comment names the same
        // roots in prose, which would skew first-occurrence order.
        val body = bat().substringAfter("rem 1. An explicitly set JAVA_HOME")
        assertOrdered(
            body,
            "%JAVA_HOME%\\bin\\java.exe",
            "where java.exe",
            "reg query",
            "%ProgramFiles%\\Java",
        )
    }

    @Test
    fun `the bat probes both the registry and both ProgramFiles roots`() {
        val text = bat()
        text shouldContain "HKLM\\SOFTWARE\\JavaSoft\\JDK"
        text shouldContain "Eclipse Adoptium"
        text shouldContain "%ProgramFiles%\\Java"
        text shouldContain "ProgramFiles(x86)"
    }

    @Test
    fun `a broken JAVA_HOME is a loud exit-6, never a silent fall-through`() {
        val text = bat()
        text shouldContain "if defined JAVA_HOME"
        text shouldContain "not an executable"
        text shouldContain "exit /b 6"
    }

    @Test
    fun `no JDK anywhere is a clean exit-6 naming the remedies`() {
        val text = bat()
        text shouldContain "no Java runtime found"
        text shouldContain "JAVA_HOME"
        text shouldContain "exit /b 6"
    }

    @Test
    fun `the ps1 mirrors the same discovery order`() {
        assertOrdered(
            ps1(),
            "\$env:JAVA_HOME",
            "Get-Command java.exe",
            "HKLM:\\SOFTWARE\\JavaSoft\\JDK",
            "ProgramFiles",
        )
    }

    // ------------------------------------------------------------------ flags + jar layout

    @Test
    fun `the bat passes the one-shot flags in stale-safe order before -jar`() {
        // -Xshare:auto before -XX:SharedArchiveFile is what makes a stale
        // jdx.jsa degrade instead of fail (same pin as the POSIX launcher).
        assertOrdered(
            bat(),
            "-XX:TieredStopAtLevel=1",
            "-XX:+UseSerialGC",
            "-Xshare:auto",
            "-XX:SharedArchiveFile=",
            "-jar",
        )
    }

    @Test
    fun `the ps1 passes the same one-shot flags in the same order`() {
        // Scoped to the exec body: the header NOTE names the flags in prose.
        val body = ps1().substringAfter("\$Flags = @(")
        assertOrdered(
            body,
            "-XX:TieredStopAtLevel=1",
            "-XX:+UseSerialGC",
            "-Xshare:auto",
            "-XX:SharedArchiveFile=",
            "-jar",
        )
    }

    @Test
    fun `the bat locates the fat jar and the CDS archive relative to its own directory`() {
        val text = bat()
        text shouldContain "%~dp0libs\\jdx-*-all.jar"
        text shouldContain "%~dp0libs\\jdx.jsa"
        // The missing-jar error names the build command, like the POSIX launcher.
        text shouldContain ":app:installDist"
        text shouldContain "exit /b 6"
    }

    @Test
    fun `the ps1 locates the fat jar and the CDS archive relative to its own directory`() {
        val text = ps1()
        text shouldContain "libs\\jdx-*-all.jar"
        text shouldContain "libs\\jdx.jsa"
        text shouldContain ":app:installDist"
        text shouldContain "exit 6"
    }

    // ------------------------------------------------------------------ version gate

    @Test
    fun `the bat gates on Java 21 with legacy 1-dot handling`() {
        val text = bat()
        // Minimum major, legacy 1.8 branch, and the modern dash/underscore
        // suffix branch (22-ea) must all exist.
        text shouldContain "21"
        text shouldContain "1\\."
        text shouldContain ".-_"
        text shouldContain "jdx requires Java"
        text shouldContain "exit /b 6"
    }

    @Test
    fun `unparseable version output is a clean exit-6 in both launchers`() {
        bat() shouldContain "no version string"
        bat() shouldContain "could not run"
        ps1() shouldContain "no version string"
        ps1() shouldContain "could not run"
    }

    @Test
    fun `every bat failure is a jdx-prefixed stderr line with exit 6`() {
        val text = bat()
        text shouldContain "1>&2 echo jdx: "
        val exitSixCount = text.split("exit /b 6").size - 1
        // Missing jar, broken JAVA_HOME, no runtime, could-not-run,
        // no-version-string, unrecognised (x2), too-old: at least 4.
        (exitSixCount >= 4) shouldBe true
    }

    // ------------------------------------------------------------------ end to end (Windows only)

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("win")

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runProcess(command: List<String>, environment: Map<String, String> = emptyMap()): ProcessResult {
        val builder = ProcessBuilder(command)
        builder.environment().putAll(environment)
        val process = builder.start()
        // Outputs here are tiny (a few lines); sequential reads cannot deadlock a pipe.
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return ProcessResult(process.waitFor(), stdout, stderr)
    }

    private fun runBat(args: List<String>, environment: Map<String, String> = emptyMap()): ProcessResult =
        runProcess(listOf("cmd.exe", "/c", builtBat.absolutePath) + args, environment)

    @Test
    @Tag("tier2")
    fun `jdx-bat version round-trips on Windows with a real JDK`() {
        assumeTrue(isWindows(), "cmd.exe smoke needs Windows (runs in Windows CI, #52)")
        assumeTrue(builtBat.isFile, "app/build/jdx.bat missing — run :app:installDist")
        val result = runBat(listOf("version"))
        result.exitCode shouldBe 0
        result.stdout.trim() shouldContain "jdx version"
    }

    @Test
    @Tag("tier2")
    fun `a broken JAVA_HOME is exit 6 on Windows`() {
        assumeTrue(isWindows(), "cmd.exe smoke needs Windows (runs in Windows CI, #52)")
        assumeTrue(builtBat.isFile, "app/build/jdx.bat missing — run :app:installDist")
        val result = runBat(
            listOf("version"),
            environment = mapOf("JAVA_HOME" to "C:\\nonexistent-jdx-test-home"),
        )
        result.exitCode shouldBe 6
        result.stderr shouldContain "jdx: "
        result.stderr shouldContain "JAVA_HOME"
    }

    // ------------------------------------------------------------------ tier-2: real pwsh execution
    //
    // pwsh runs on Linux, so the ps1 failure paths AND the happy path execute
    // here for real against a stub `bin/java.exe` (the `.exe` extension means
    // nothing to the Linux exec loader — the shebang runs). The stub cannot
    // execute on Windows itself (a shell script is not a PE), so these tests
    // skip there with a reason; Windows behaviour is covered by the jdx.bat
    // smokes above, which run on Windows CI (#52).

    private fun pwshIsAvailable(): Boolean =
        try {
            runProcess(listOf("pwsh", "-NoProfile", "-Command", "\$PSVersionTable.PSVersion")).exitCode == 0
        } catch (e: Exception) {
            false
        }

    private fun assumePosixPwsh() {
        assumeTrue(!isWindows(), "sh-stub java.exe needs a POSIX exec host (Windows runs the jdx.bat smokes)")
        assumeTrue(pwshIsAvailable(), "pwsh not on PATH")
        assumeTrue(ps1Source.isFile, "src/main/scripts/jdx.ps1 missing")
    }

    /** A minimal install layout: `jdx.ps1` + `libs/jdx-*-all.jar`; the jar is never opened. */
    private fun newPs1FakeInstall(withCdsArchive: Boolean = false): File {
        val dir = Files.createTempDirectory("jdx-ps1install-").toRealPath().toFile()
        File(dir, "jdx.ps1").writeText(ps1Source.readText())
        val libs = File(dir, "libs").apply { mkdirs() }
        File(libs, "jdx-0.0.0-all.jar").writeBytes(byteArrayOf(0x50, 0x4b))
        if (withCdsArchive) {
            // The launcher only checks presence; the stub java never reads it.
            File(libs, "jdx.jsa").writeBytes(byteArrayOf(0x50, 0x4b))
        }
        return dir
    }

    /** A fake JDK whose `bin/java.exe` is a shell stub configured through the environment. */
    private fun newStubJavaExe(): File {
        val home = Files.createTempDirectory("jdx-stubjdkexe-").toRealPath().toFile()
        val javaExe = File(File(home, "bin").apply { mkdirs() }, "java.exe")
        javaExe.writeText(
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
        javaExe.setExecutable(true, false)
        return home
    }

    /** Environment consumed by the stub java.exe, inherited through the launcher. */
    private fun stubExeEnvironment(
        argsFile: File,
        versionLine: String = """openjdk version "21.0.8"""",
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

    private fun runPs1(
        scriptFile: File,
        args: List<String>,
        environment: Map<String, String>,
    ): ProcessResult = runProcess(listOf("pwsh", "-NoProfile", "-File", scriptFile.absolutePath) + args, environment)

    private fun recordedArgs(argsFile: File): List<String> =
        if (argsFile.exists()) argsFile.readLines() else emptyList()

    @Test
    @Tag("tier2")
    fun `ps1 execs the fat jar with one-shot flags, jar and args, passing streams and exit code through`() {
        assumePosixPwsh()
        val install = newPs1FakeInstall()
        val stubJdk = newStubJavaExe()
        val argsFile = File(install, "stub-args.txt")

        val result = runPs1(
            File(install, "jdx.ps1"),
            listOf("--version", "extra arg with spaces"),
            mapOf("JAVA_HOME" to stubJdk.absolutePath) + stubExeEnvironment(argsFile, execExit = 42),
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
    @Tag("tier2")
    fun `ps1 passes SharedArchiveFile after Xshare-auto when jdx-jsa is present`() {
        assumePosixPwsh()
        val install = newPs1FakeInstall(withCdsArchive = true)
        val stubJdk = newStubJavaExe()
        val argsFile = File(install, "stub-args.txt")

        val result = runPs1(
            File(install, "jdx.ps1"),
            listOf("--version"),
            mapOf("JAVA_HOME" to stubJdk.absolutePath) + stubExeEnvironment(argsFile),
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
    @Tag("tier2")
    fun `ps1 rejects a JDK below the minimum with its version named`() {
        assumePosixPwsh()
        val install = newPs1FakeInstall()
        val stubJdk = newStubJavaExe()
        val argsFile = File(install, "stub-args.txt")

        val result = runPs1(
            File(install, "jdx.ps1"),
            listOf("--version"),
            mapOf("JAVA_HOME" to stubJdk.absolutePath) +
                stubExeEnvironment(argsFile, versionLine = """openjdk version "17.0.11""""),
        )

        result.exitCode shouldBe 6
        result.stderr shouldContain "jdx: "
        result.stderr shouldContain "17.0.11"
        recordedArgs(argsFile) shouldBe emptyList()
    }

    @Test
    @Tag("tier2")
    fun `ps1 maps legacy 1-dot-8 to major 8 and accepts early-access suffixes`() {
        assumePosixPwsh()
        val install = newPs1FakeInstall()
        val stubJdk = newStubJavaExe()

        val legacy = runPs1(
            File(install, "jdx.ps1"),
            listOf("--version"),
            mapOf("JAVA_HOME" to stubJdk.absolutePath) +
                stubExeEnvironment(File(install, "legacy-args.txt"), versionLine = """java version "1.8.0_452""""),
        )
        legacy.exitCode shouldBe 6
        legacy.stderr shouldContain "1.8.0_452"

        val earlyAccess = runPs1(
            File(install, "jdx.ps1"),
            listOf("--version"),
            mapOf("JAVA_HOME" to stubJdk.absolutePath) +
                stubExeEnvironment(File(install, "ea-args.txt"), versionLine = """openjdk version "22-ea""""),
        )
        earlyAccess.exitCode shouldBe 0
    }

    @Test
    @Tag("tier2")
    fun `ps1 with a broken JAVA_HOME is exit 6`() {
        assumePosixPwsh()
        val install = newPs1FakeInstall()

        val result = runPs1(
            File(install, "jdx.ps1"),
            listOf("version"),
            environment = mapOf("JAVA_HOME" to "/nonexistent-jdx-test-home"),
        )

        result.exitCode shouldBe 6
        result.stderr shouldContain "jdx: "
        result.stderr shouldContain "JAVA_HOME"
    }

    @Test
    @Tag("tier2")
    fun `ps1 with a missing fat jar names the build command`() {
        assumePosixPwsh()
        val install = newPs1FakeInstall()
        File(install, "libs/jdx-0.0.0-all.jar").delete()

        val result = runPs1(
            File(install, "jdx.ps1"),
            listOf("version"),
            environment = emptyMap(),
        )

        result.exitCode shouldBe 6
        result.stderr shouldContain "jdx: "
        result.stderr shouldContain ":app:installDist"
    }
}
