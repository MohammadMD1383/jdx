package dev.jdx.cli.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Windows tool probing for `doctor` (phase 5, tier 1): `javap.exe` wins on a
 * simulated Windows box, `$JAVA_HOME/bin` participates, and the PATHEXT order
 * is pinned — all over temp dirs, never the real JDK.
 */
class DoctorWindowsProbeTest {

    @Test
    fun `tool file names probe PATHEXT shims on windows only`() {
        toolFileNames("javap", "Windows 11") shouldBe
            listOf("javap.exe", "javap.cmd", "javap.bat", "javap")
        toolFileNames("javap", "Linux") shouldBe listOf("javap")
    }

    @Test
    fun `a windows javap dot exe is found and OK`() {
        val root = tempDir("jdx-doctor-win-")
        val javaHome = root.resolve("jdk")
        emptyExecutable(javaHome.resolve("bin"), "javap.exe")
        val service = DoctorService(fakeEnvironment(root, javaHomeBinJavap = false, osName = "Windows 11"))

        val javap = service.probe().checks.first { it.name == "javap" }

        javap.status shouldBe DoctorStatus.OK
        javap.detail shouldContain "javap.exe"
    }

    @Test
    fun `windows prefers exe over a bare sibling in the same dir`() {
        val root = tempDir("jdx-doctor-win-")
        val javaHome = root.resolve("jdk")
        emptyExecutable(javaHome.resolve("bin"), "javap")
        emptyExecutable(javaHome.resolve("bin"), "javap.exe")
        val service = DoctorService(fakeEnvironment(root, javaHomeBinJavap = false, osName = "Windows 11"))

        val javap = service.probe().checks.first { it.name == "javap" }

        javap.status shouldBe DoctorStatus.OK
        javap.detail shouldContain "javap.exe"
    }

    @Test
    fun `JAVA_HOME bin participates ahead of PATH`() {
        val root = tempDir("jdx-doctor-win-")
        val envHome = root.resolve("envjdk")
        emptyExecutable(envHome.resolve("bin"), "javap")
        val service = DoctorService(
            fakeEnvironment(
                root,
                javaHomeBinJavap = false,
                pathBinJavap = true,
                envJavaHome = envHome,
                runner = cannedOutcome("21.0.0"),
            ),
        )

        val javap = service.probe().checks.first { it.name == "javap" }

        javap.status shouldBe DoctorStatus.OK
        javap.detail shouldContain "envjdk"
        javap.detail shouldNotContain "pathbin"
    }

    @Test
    fun `a missing javap on windows still fails naming a full JDK`() {
        val service = DoctorService(
            fakeEnvironment(tempDir("jdx-doctor-win-"), javaHomeBinJavap = false, osName = "Windows 11"),
        )

        val javap = service.probe().checks.first { it.name == "javap" }

        javap.status shouldBe DoctorStatus.FAIL
        javap.detail shouldContain "full JDK"
    }

    @Test
    fun `windows path-only exe is found`() {
        val root = tempDir("jdx-doctor-win-")
        emptyExecutable(root.resolve("pathbin"), "javap.exe")
        // fakeEnvironment creates pathbin eagerly; the assertion below only
        // needs the javaHome side empty.
        val service = DoctorService(
            fakeEnvironment(root, javaHomeBinJavap = false, osName = "Windows 11"),
        )
        Files.exists(root.resolve("pathbin/javap.exe")) shouldBe true

        val javap = service.probe().checks.first { it.name == "javap" }

        javap.status shouldBe DoctorStatus.OK
        javap.detail shouldContain "pathbin"
    }

    @Test
    fun `suffix order holds for every tool name`() = runBlocking {
        checkAll(Arb.string(1..12).filter { it.all { c -> c.isLetterOrDigit() } }) { base ->
            toolFileNames(base, "Windows 10") shouldBe listOf("$base.exe", "$base.cmd", "$base.bat", base)
            toolFileNames(base, "Linux") shouldBe listOf(base)
        }
    }
}
