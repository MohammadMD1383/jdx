package dev.jdx.cli.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class DoctorServiceTest {

    private val expectedNames = listOf(
        "jdk", "jrt", "javap", "jdk-sources", "cache",
        "config", "index", "kotlin", "daemon", "workspace",
    )

    @Test
    fun `a healthy environment reports ten rows and exits zero`() {
        val service = DoctorService(fakeEnvironment(tempDir("jdx-doctor-test-")))

        val report = service.probe()

        report.checks.map { it.name } shouldBe expectedNames
        report.hasFailures shouldBe false
        exitCodeFor(report) shouldBe 0
        report.checks.first { it.name == "jdk" }.detail shouldContain "26.0.2.1-test"
        report.checks.first { it.name == "javap" }.status shouldBe DoctorStatus.OK
    }

    @Test
    fun `a missing javap is a FAIL naming a full JDK`() {
        val service = DoctorService(
            fakeEnvironment(tempDir("jdx-doctor-test-"), javaHomeBinJavap = false),
        )

        val report = service.probe()

        val javap = report.checks.first { it.name == "javap" }
        javap.status shouldBe DoctorStatus.FAIL
        javap.detail shouldContain "full JDK"
        exitCodeFor(report) shouldBe 6
    }

    @Test
    fun `the javaHome javap wins over the PATH javap`() {
        val root = tempDir("jdx-doctor-test-")
        val service = DoctorService(
            fakeEnvironment(
                root,
                pathBinJavap = true,
                runner = cannedOutcome("17.0.0"),
            ),
        )

        val report = service.probe()

        // The stub runner answers for whichever executable is chosen; the detail names it.
        // javaHome/bin is resolved first, so its path must appear, not pathbin's.
        val javap = report.checks.first { it.name == "javap" }
        javap.detail shouldContain "jdk"
        javap.detail shouldNotContain "pathbin"
    }

    @Test
    fun `an old javap is a WARN about the differential oracle`() {
        val service = DoctorService(
            fakeEnvironment(tempDir("jdx-doctor-test-"), runner = cannedOutcome("1.8.0_292")),
        )

        val report = service.probe()

        val javap = report.checks.first { it.name == "javap" }
        javap.status shouldBe DoctorStatus.WARN
        javap.detail shouldContain "older"
        exitCodeFor(report) shouldBe 0
    }

    @Test
    fun `a crashing javap is a FAIL with its exit code`() {
        val service = DoctorService(
            fakeEnvironment(tempDir("jdx-doctor-test-"), runner = cannedOutcome("boom", exitCode = 1)),
        )

        val report = service.probe()

        val javap = report.checks.first { it.name == "javap" }
        javap.status shouldBe DoctorStatus.FAIL
        javap.detail shouldContain "exits 1"
        exitCodeFor(report) shouldBe 6
    }

    @Test
    fun `a throwing process runner becomes a FAIL row, never a crash`() {
        val service = DoctorService(
            fakeEnvironment(tempDir("jdx-doctor-test-"), runner = throwingRunner("fake explosion")),
        )

        val report = service.probe()

        val javap = report.checks.first { it.name == "javap" }
        javap.status shouldBe DoctorStatus.FAIL
        javap.detail shouldContain "fake explosion"
    }

    @Test
    fun `unparseable javap output is a FAIL naming the output`() {
        val service = DoctorService(
            fakeEnvironment(tempDir("jdx-doctor-test-"), runner = cannedOutcome("banana")),
        )

        val report = service.probe()

        val javap = report.checks.first { it.name == "javap" }
        javap.status shouldBe DoctorStatus.FAIL
        javap.detail shouldContain "banana"
    }

    @Test
    fun `an unreachable jrt is a FAIL with the reason`() {
        val root = tempDir("jdx-doctor-test-")
        val base = fakeEnvironment(root)
        val service = DoctorService(
            base.copy(runtime = RuntimeInfo("26-test", jrtReachable = false, jrtReason = "fake jrt down")),
        )

        val report = service.probe()

        val jrt = report.checks.first { it.name == "jrt" }
        jrt.status shouldBe DoctorStatus.FAIL
        jrt.detail shouldContain "fake jrt down"
        exitCodeFor(report) shouldBe 6
    }

    @Test
    fun `an unwritable cache is a FAIL`() {
        assumeTrue(System.getProperty("user.name") != "root", "root can write anywhere")
        val root = tempDir("jdx-doctor-test-")
        val service = DoctorService(
            fakeEnvironment(root, cacheSetup = {
                Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("r-xr-xr-x"))
            }),
        )

        val report = service.probe()

        val cache = report.checks.first { it.name == "cache" }
        cache.status shouldBe DoctorStatus.FAIL
        cache.detail shouldContain "not writable"
        exitCodeFor(report) shouldBe 6
    }

    @Test
    fun `a missing cache is a WARN pointing at first use`() {
        val root = tempDir("jdx-doctor-test-")
        // fakeEnvironment always creates the cache root; remove it to simulate a fresh machine.
        val cacheRoot = root.resolve("home/.cache/jdx")
        val service = DoctorService(fakeEnvironment(root))
        Files.delete(cacheRoot)

        val report = service.probe()

        val cache = report.checks.first { it.name == "cache" }
        cache.status shouldBe DoctorStatus.WARN
        cache.detail shouldContain "created on first use"
        exitCodeFor(report) shouldBe 0
    }

    @Test
    fun `cache size reflects the files inside`() {
        val service = DoctorService(
            fakeEnvironment(
                tempDir("jdx-doctor-test-"),
                cacheSetup = { writeSizedFiles(it, 1024, 2048, 3072) },
            ),
        )

        val report = service.probe()

        val cache = report.checks.first { it.name == "cache" }
        cache.status shouldBe DoctorStatus.OK
        cache.detail shouldContain "6 KB"
    }

    @Test
    fun `jdk sources found via JAVA_HOME fallback is OK`() {
        val root = tempDir("jdx-doctor-test-")
        val envJdk = root.resolve("envjava").also { Files.createDirectories(it) }
        Files.createDirectories(envJdk.resolve("lib"))
        Files.write(envJdk.resolve("lib/src.zip"), byteArrayOf(0x50, 0x4b))
        val service = DoctorService(
            fakeEnvironment(root, srcZipPresent = false, envJavaHome = envJdk),
        )

        val report = service.probe()

        val sources = report.checks.first { it.name == "jdk-sources" }
        sources.status shouldBe DoctorStatus.OK
        sources.detail shouldContain envJdk.resolve("lib/src.zip").toString()
        exitCodeFor(report) shouldBe 0
    }

    @Test
    fun `javaHome src zip wins over JAVA_HOME`() {
        val root = tempDir("jdx-doctor-test-")
        val envJdk = root.resolve("envjava").also { Files.createDirectories(it) }
        Files.createDirectories(envJdk.resolve("lib"))
        Files.write(envJdk.resolve("lib/src.zip"), byteArrayOf(0x50, 0x4b))
        // srcZipPresent = true puts a src.zip under root/jdk, which must win.
        val service = DoctorService(
            fakeEnvironment(root, srcZipPresent = true, envJavaHome = envJdk),
        )

        val report = service.probe()

        val sources = report.checks.first { it.name == "jdk-sources" }
        sources.status shouldBe DoctorStatus.OK
        sources.detail shouldContain root.resolve("jdk/lib/src.zip").toString()
        sources.detail shouldNotContain "envjava"
    }

    @Test
    fun `missing jdk sources is a WARN naming the searched locations`() {
        val service = DoctorService(
            fakeEnvironment(tempDir("jdx-doctor-test-"), srcZipPresent = false),
        )

        val report = service.probe()

        val sources = report.checks.first { it.name == "jdk-sources" }
        sources.status shouldBe DoctorStatus.WARN
        sources.detail shouldContain "lib/src.zip"
        sources.detail shouldContain "JDK sources unavailable"
        exitCodeFor(report) shouldBe 0
    }

    @Test
    fun `workspace detection finds the nearest build file upward`() {
        val root = tempDir("jdx-doctor-test-")
        val outer = root.resolve("outer").also { Files.createDirectories(it) }
        Files.write(outer.resolve("pom.xml"), byteArrayOf(0x3c))
        val cwd = outer.resolve("sub/inner").also { Files.createDirectories(it) }
        val service = DoctorService(fakeEnvironment(root, workingDir = cwd))

        val report = service.probe()

        val workspace = report.checks.first { it.name == "workspace" }
        workspace.status shouldBe DoctorStatus.OK
        workspace.detail shouldContain "project root"
        workspace.detail shouldContain "pom.xml"
        workspace.detail shouldContain outer.toString()
    }

    @Test
    fun `workspace row names the JDX_WORKSPACE selection`() {
        val service = DoctorService(
            fakeEnvironment(tempDir("jdx-doctor-test-"), workspaceEnv = "mc"),
        )

        val report = service.probe()

        val workspace = report.checks.first { it.name == "workspace" }
        workspace.status shouldBe DoctorStatus.OK
        workspace.detail shouldContain "workspace 'mc' (JDX_WORKSPACE)"
    }

    @Test
    fun `workspace row names the use default and counts stored workspaces`() {
        val service = DoctorService(
            fakeEnvironment(
                tempDir("jdx-doctor-test-"),
                workspaces = mapOf("mc" to listOf("a.jar"), "other" to emptyList()),
                activeWorkspace = "other",
            ),
        )

        val report = service.probe()

        val workspace = report.checks.first { it.name == "workspace" }
        workspace.status shouldBe DoctorStatus.OK
        workspace.detail shouldContain "workspace 'other' (default, jdx ws use)"
        workspace.detail shouldContain "2 workspace(s)"
    }

    @Test
    fun `workspace row on a fresh machine reports none and zero`() {
        val service = DoctorService(fakeEnvironment(tempDir("jdx-doctor-test-")))

        val report = service.probe()

        val workspace = report.checks.first { it.name == "workspace" }
        workspace.status shouldBe DoctorStatus.OK
        workspace.detail shouldContain "no named workspace"
        workspace.detail shouldContain "no project files"
        workspace.detail shouldContain "0 workspace(s)"
    }

    @Test
    fun `daemon sockets are reported without claiming the daemon runs`() {
        val service = DoctorService(fakeEnvironment(tempDir("jdx-doctor-test-"), socketCount = 2))

        val report = service.probe()

        val daemon = report.checks.first { it.name == "daemon" }
        daemon.status shouldBe DoctorStatus.OK
        daemon.detail shouldContain "2 socket"
    }

    @Test
    fun `exitCodeFor is 0 without failures and 6 with any FAIL`() {
        exitCodeFor(DoctorReport(emptyList())) shouldBe 0
        val warnOnly = DoctorReport(listOf(DoctorCheck("x", DoctorStatus.WARN, "w")))
        exitCodeFor(warnOnly) shouldBe 0
        val failed = DoctorReport(
            listOf(
                DoctorCheck("a", DoctorStatus.OK, "ok"),
                DoctorCheck("b", DoctorStatus.FAIL, "bad"),
            ),
        )
        exitCodeFor(failed) shouldBe 6
    }

    @Test
    fun `formatBytes uses whole units`() {
        formatBytes(0) shouldBe "0 B"
        formatBytes(512) shouldBe "512 B"
        formatBytes(1023) shouldBe "1023 B"
        formatBytes(1024) shouldBe "1 KB"
        formatBytes(1536) shouldBe "1 KB"
        formatBytes(5 * 1024 * 1024) shouldBe "5 MB"
        formatBytes(3L * 1024 * 1024 * 1024) shouldBe "3 GB"
    }

    @Test
    fun `details never span lines`() {
        val service = DoctorService(
            fakeEnvironment(tempDir("jdx-doctor-test-"), runner = cannedOutcome("line1\nline2\nline3")),
        )

        val report = service.probe()

        for (check in report.checks) {
            check.detail.lines().size shouldBe 1
        }
        // The javap detail leads with the executable path; the folded version follows it.
        report.checks.first { it.name == "javap" }.detail shouldContain "line1 line2 line3"
    }
}
