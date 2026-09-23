package dev.jdx.index.service

import dev.jdx.index.service.JdxService.BodyOptions
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.index.service.JdxService.SourceOptions
import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `@Metadata`-aware `SOURCES_VERSION_MISMATCH` pairing (T-081, tier 2):
 * matched Kotlin jars stay silent across shapes (`@JvmName`, `suspend`,
 * default args, properties, data/value synthetics, companion statics,
 * facades), while crafted stale `.kt` sources warn on success — exit codes
 * unchanged either way.
 */
@Tag("tier2")
class KotlinMismatchServiceTest {

    private fun fixtureRoots(): RootsSpec =
        RootsSpec(jarSpecs = listOf(FixtureJars.binaryJar().absolutePath), includeJdk = false)

    private fun textOf(outcome: ServiceOutcome): String = outcome.renderText(false)

    private fun cachedRuntimeJars(): Map<String, Path> {
        val home = System.getProperty("user.home") ?: return emptyMap()
        val root = File(home, ".gradle/caches/modules-2/files-2.1")
        if (!root.isDirectory) return emptyMap()
        val wanted = mapOf(
            "kotlin-compiler-embeddable-2.4.20.jar" to
                "org.jetbrains.kotlin/kotlin-compiler-embeddable",
            "kotlin-stdlib-2.4.20.jar" to
                "org.jetbrains.kotlin/kotlin-stdlib",
            "kotlin-script-runtime-2.4.20.jar" to
                "org.jetbrains.kotlin/kotlin-script-runtime",
            "kotlin-reflect-1.6.10.jar" to
                "org.jetbrains.kotlin/kotlin-reflect",
            "kotlin-daemon-embeddable-2.4.20.jar" to
                "org.jetbrains.kotlin/kotlin-daemon-embeddable",
            "kotlinx-coroutines-core-jvm-1.8.0.jar" to
                "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm",
            "annotations-13.0.jar" to
                "org.jetbrains/annotations",
        )
        return wanted.mapNotNull { (jarName, groupPath) ->
            val dir = File(root, groupPath)
            val found = if (dir.isDirectory) {
                dir.walkTopDown().firstOrNull { it.isFile && it.name == jarName }?.toPath()
            } else {
                null
            }
            if (found != null) jarName to found else null
        }.toMap()
    }

    /** A temp home with the sidecar set symlinked in (the `kotlinUserHome` seam). */
    private fun kotlinHome(home: Path): Path {
        val jars = cachedRuntimeJars()
        assumeTrue(
            jars.containsKey("kotlin-compiler-embeddable-2.4.20.jar"),
            "kotlin-compiler-embeddable 2.4.20 not in the Gradle cache",
        )
        val dir = home.resolve(".cache/jdx/kotlin")
        Files.createDirectories(dir)
        for ((jarName, cached) in jars) {
            Files.createSymbolicLink(dir.resolve(jarName), cached)
        }
        return home
    }

    private fun realKotlinSourceText(): String =
        ZipFile(FixtureJars.sourcesJar()).use { zip ->
            zip.getInputStream(zip.getEntry("dev/jdx/fixtures/KotlinShapes.kt")).readBytes()
                .toString(StandardCharsets.UTF_8)
        }

    private fun staleKotlinPair(tempDir: Path, sourcesText: String): RootsSpec {
        val binary = tempDir.resolve("case.jar")
        val sources = tempDir.resolve("case-sources.jar")
        val classBytes = ZipFile(FixtureJars.binaryJar()).use { zip ->
            zip.getInputStream(zip.getEntry("dev/jdx/fixtures/KotlinMembers.class")).readBytes()
        }
        writeJar(binary, mapOf("dev/jdx/fixtures/KotlinMembers.class" to classBytes))
        writeJar(sources, mapOf("dev/jdx/fixtures/KotlinShapes.kt" to sourcesText.toByteArray()))
        return RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
    }

    private fun writeJar(jar: Path, entries: Map<String, ByteArray>) {
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            entries.entries.sortedBy { it.key }.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    // -- matched jars stay silent ------------------------------------------------

    @Test
    fun `matched kotlin bodies carry no mismatch warning`(@TempDir home: Path) {
        val kotlinHome = kotlinHome(home)
        val bodies = listOf(
            // Suspend + typealias parameter (the T-081 alias leniency).
            "dev.jdx.fixtures.KotlinMembers#fetch",
            // JVM spelling and Kotlin `@JvmName` spelling alike.
            "dev.jdx.fixtures.KotlinMembers#renamedForJvm(int)",
            "dev.jdx.fixtures.KotlinMembers#originalName(int)",
            // Mangled `internal`, default args, properties.
            "dev.jdx.fixtures.KotlinMembers#internalHelper",
            "dev.jdx.fixtures.KotlinData#nickname",
            "dev.jdx.fixtures.KotlinData#greeting",
        )
        for (ref in bodies) {
            val outcome = JdxService.body(ref, fixtureRoots(), BodyOptions(kotlinUserHome = kotlinHome))
            outcome.exitCode shouldBe 0
            textOf(outcome) shouldNotContain "SOURCES_VERSION_MISMATCH"
            outcome.toJson("body") shouldNotContain "SOURCES_VERSION_MISMATCH"
        }
    }

    @Test
    fun `matched kotlin sources carry no mismatch warning`(@TempDir home: Path) {
        val kotlinHome = kotlinHome(home)
        val types = listOf(
            "dev.jdx.fixtures.KotlinMembers",
            "dev.jdx.fixtures.KotlinData",
            "dev.jdx.fixtures.KotlinRegistry",
            "dev.jdx.fixtures.UserIdBox",
            "dev.jdx.fixtures.KotlinShapesKt",
        )
        for (ref in types) {
            val outcome = JdxService.source(ref, fixtureRoots(), SourceOptions(kotlinUserHome = kotlinHome))
            outcome.exitCode shouldBe 0
            textOf(outcome) shouldNotContain "SOURCES_VERSION_MISMATCH"
            outcome.toJson("source") shouldNotContain "SOURCES_VERSION_MISMATCH"
        }
    }

    // -- stale jars warn on success ----------------------------------------------

    @Test
    fun `an added kotlin member warns on body and source`(@TempDir home: Path) {
        val kotlinHome = kotlinHome(home)
        val stale = realKotlinSourceText().replace(
            "    suspend fun fetch(id: UserId): String",
            "    fun brandNew(): Int = 42\n\n    suspend fun fetch(id: UserId): String",
        )
        val roots = staleKotlinPair(home, stale)

        val body = JdxService.body(
            "dev.jdx.fixtures.KotlinMembers#fetch",
            roots,
            BodyOptions(kotlinUserHome = kotlinHome),
        )
        body.exitCode shouldBe 0
        textOf(body) shouldContain "warning SOURCES_VERSION_MISMATCH"
        textOf(body) shouldContain "declares dev.jdx.fixtures.KotlinMembers#brandNew()"
        body.toJson("body") shouldContain "\"code\":\"SOURCES_VERSION_MISMATCH\""

        val source = JdxService.source(
            "dev.jdx.fixtures.KotlinMembers",
            roots,
            SourceOptions(kotlinUserHome = kotlinHome),
        )
        source.exitCode shouldBe 0
        textOf(source) shouldContain "warning SOURCES_VERSION_MISMATCH"
        source.toJson("source") shouldContain "\"code\":\"SOURCES_VERSION_MISMATCH\""
    }

    @Test
    fun `a renamed kotlin member warns on both sides`(@TempDir home: Path) {
        val kotlinHome = kotlinHome(home)
        val stale = realKotlinSourceText().replace("fun originalName(value: Int)", "fun renamedOne(value: Int)")
        val roots = staleKotlinPair(home, stale)

        val outcome = JdxService.body(
            "dev.jdx.fixtures.KotlinMembers#fetch",
            roots,
            BodyOptions(kotlinUserHome = kotlinHome),
        )
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "warning SOURCES_VERSION_MISMATCH"
        text shouldContain "declares dev.jdx.fixtures.KotlinMembers#renamedOne(Int)"
        text shouldContain "omits dev.jdx.fixtures.KotlinMembers#originalName(int)"
    }

    // -- invariants ----------------------------------------------------------------

    @Test
    fun `warned kotlin answers are deterministic with text covered by json`(@TempDir home: Path) {
        val kotlinHome = kotlinHome(home)
        val stale = realKotlinSourceText().replace(
            "    suspend fun fetch(id: UserId): String",
            "    fun brandNew(): Int = 42\n\n    suspend fun fetch(id: UserId): String",
        )
        val roots = staleKotlinPair(home, stale)
        val ref = "dev.jdx.fixtures.KotlinMembers#fetch"
        val options = BodyOptions(kotlinUserHome = kotlinHome)
        textOf(JdxService.body(ref, roots, options)) shouldBe textOf(JdxService.body(ref, roots, options))
        JdxService.body(ref, roots, options).toJson("body") shouldBe
            JdxService.body(ref, roots, options).toJson("body")
        val text = textOf(JdxService.body(ref, roots, options))
        val json = JdxService.body(ref, roots, options).toJson("body")
        text shouldContain "warning SOURCES_VERSION_MISMATCH"
        json shouldContain "\"code\":\"SOURCES_VERSION_MISMATCH\""
        json shouldContain "brandNew"
    }
}
