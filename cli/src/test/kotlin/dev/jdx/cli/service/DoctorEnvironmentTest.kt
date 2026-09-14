package dev.jdx.cli.service

import dev.jdx.cli.render.renderText
import dev.jdx.cli.render.toJson
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Fault-injection family for `jdx doctor` (TESTING.md §7): the environment state space —
 * cache shape × javap behaviour × src.zip × jrt × daemon × workspace — is enumerated
 * exhaustively (576 combinations) instead of hand-picked. Every combination asserts the
 * contract invariants (never throws, exit code law, text↔JSON parity, determinism) plus the
 * targeted severity for the injected fault.
 */
class DoctorEnvironmentTest {

    private enum class CacheState { ABSENT, EMPTY, WITH_FILES, UNREADABLE }

    private enum class JavapState { ABSENT, MODERN, OLD, CRASHING, THROWING, GARBAGE }

    private enum class DaemonState { NONE, SOCKETS }

    private enum class WorkspaceState { NONE, ROOT, NESTED }

    private val expectedNames = listOf(
        "jdk", "jrt", "javap", "jdk-sources", "cache",
        "config", "index", "kotlin", "daemon", "workspace",
    )

    // `root` cannot make a directory unreadable to itself; drop that state there.
    private val cacheStates = if (System.getProperty("user.name") == "root") {
        CacheState.entries - CacheState.UNREADABLE
    } else {
        CacheState.entries
    }

    @Test
    fun `every environment combination upholds the doctor contract`() {
        var combinations = 0
        for (cache in cacheStates) {
            for (javap in JavapState.entries) {
                for (srcZip in listOf(true, false)) {
                    for (jrt in listOf(true, false)) {
                        for (daemon in DaemonState.entries) {
                            for (workspace in WorkspaceState.entries) {
                                checkCombination(cache, javap, srcZip, jrt, daemon, workspace)
                                combinations++
                            }
                        }
                    }
                }
            }
        }
        // Guard against the loops silently shrinking (e.g. a bad filter above).
        combinations shouldBe cacheStates.size * JavapState.entries.size * 2 * 2 *
            DaemonState.entries.size * WorkspaceState.entries.size
    }

    private fun checkCombination(
        cache: CacheState,
        javap: JavapState,
        srcZip: Boolean,
        jrt: Boolean,
        daemon: DaemonState,
        workspace: WorkspaceState,
    ) {
        val root = tempDir("jdx-doctor-matrix-")
        val outer = root.resolve("outer").also { Files.createDirectories(it) }
        val workingDir = when (workspace) {
            WorkspaceState.NONE -> root.resolve("cwd")
            WorkspaceState.ROOT -> outer.also { Files.write(it.resolve("settings.gradle.kts"), byteArrayOf(0x2f)) }
            WorkspaceState.NESTED -> {
                Files.write(outer.resolve("pom.xml"), byteArrayOf(0x3c))
                outer.resolve("sub/inner")
            }
        }
        val environment = fakeEnvironment(
            root,
            javaHomeBinJavap = javap != JavapState.ABSENT,
            runner = when (javap) {
                JavapState.ABSENT -> cannedOutcome("", exitCode = 0)
                JavapState.MODERN -> cannedOutcome("26.0.2.1")
                JavapState.OLD -> cannedOutcome("1.8.0_292")
                JavapState.CRASHING -> cannedOutcome("fake crash", exitCode = 1)
                JavapState.THROWING -> throwingRunner("fake explosion")
                JavapState.GARBAGE -> cannedOutcome("banana")
            },
            srcZipPresent = srcZip,
            jrtReachable = jrt,
            cacheSetup = { cacheRoot ->
                when (cache) {
                    CacheState.ABSENT -> Files.delete(cacheRoot)
                    CacheState.EMPTY -> Unit
                    CacheState.WITH_FILES -> writeSizedFiles(cacheRoot, 1024, 2048, 3072)
                    CacheState.UNREADABLE -> Files.setPosixFilePermissions(
                        cacheRoot,
                        PosixFilePermissions.fromString("r-xr-xr-x"),
                    )
                }
            },
            socketCount = if (daemon == DaemonState.SOCKETS) 2 else 0,
            workingDir = workingDir,
        )

        // 1. Never throws — completion is the assertion.
        val service = DoctorService(environment)
        val report = service.probe()

        // 2. Same ten rows, in order, always.
        report.checks.map { it.name } shouldBe expectedNames

        // 3. The exit-code law: 6 iff some check failed.
        val code = exitCodeFor(report)
        (code == 0 || code == 6) shouldBe true
        (code == 6) shouldBe report.hasFailures

        // 4. Text and JSON carry the same rows (D-007), in the same order.
        textRows(report.renderText()) shouldBe jsonRows(report.toJson())

        // 5. Determinism: the same environment probes to identical bytes (TESTING.md §6).
        val second = service.probe()
        second.renderText() shouldBe report.renderText()
        second.toJson() shouldBe report.toJson()

        // 6. Targeted severities for the injected faults.
        val byName = report.checks.associateBy { it.name }
        byName.getValue("javap").status shouldBe when (javap) {
            JavapState.MODERN -> DoctorStatus.OK
            JavapState.OLD -> DoctorStatus.WARN
            else -> DoctorStatus.FAIL
        }
        byName.getValue("cache").status shouldBe when (cache) {
            CacheState.ABSENT -> DoctorStatus.WARN
            CacheState.UNREADABLE -> DoctorStatus.FAIL
            else -> DoctorStatus.OK
        }
        byName.getValue("jdk-sources").status shouldBe
            if (srcZip) DoctorStatus.OK else DoctorStatus.WARN
        byName.getValue("jrt").status shouldBe
            if (jrt) DoctorStatus.OK else DoctorStatus.FAIL
        if (cache == CacheState.WITH_FILES) {
            byName.getValue("cache").detail shouldContain "6 KB"
        }
        if (daemon == DaemonState.SOCKETS) {
            byName.getValue("daemon").detail shouldContain "2 socket"
        }
        if (workspace == WorkspaceState.NESTED) {
            byName.getValue("workspace").detail shouldContain outer.toString()
        }
    }

    private data class TextRow(val name: String, val status: String, val detail: String)

    private val rowPattern = Regex("^  ([a-z-]+): (ok|warn|fail) \\((.*)\\)$")

    private fun textRows(text: String): List<TextRow> {
        val lines = text.lines()
        lines.first() shouldBe "jdx doctor"
        return lines.drop(1).map { line ->
            val match = rowPattern.matchEntire(line)
                ?: throw AssertionError("doctor text row does not match '<name>: <status> (<detail>)': $line")
            TextRow(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }
    }

    private fun jsonRows(json: String): List<TextRow> {
        val parsed = Json.parseToJsonElement(json).jsonObject
        parsed["jdx"]?.jsonPrimitive?.content shouldBe "1"
        parsed["command"]?.jsonPrimitive?.content shouldBe "doctor"
        return parsed["result"]?.jsonObject?.get("checks")?.jsonArray?.map { element ->
            val check = element.jsonObject
            TextRow(
                check.getValue("name").jsonPrimitive.content,
                check.getValue("status").jsonPrimitive.content,
                check.getValue("detail").jsonPrimitive.content,
            )
        } ?: throw AssertionError("doctor JSON has no result.checks: $json")
    }
}
