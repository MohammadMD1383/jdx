package dev.jdx.index.service

import dev.jdx.decompile.DecompileCache
import dev.jdx.decompile.DecompileResult
import dev.jdx.decompile.DecompilerEngine
import dev.jdx.decompile.DecompilerId
import dev.jdx.decompile.VineflowerDecompiler
import dev.jdx.index.service.JdxService.BodyOptions
import dev.jdx.index.service.JdxService.DocOptions
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.index.service.JdxService.SourceOptions
import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Behaviour of `body`/`source`/`doc` over `.kt` sources (T-039, tier 2):
 * the fixture corpus binary jar with its sibling `-sources.jar` (which
 * ships `KotlinShapes.kt`) plus a temp-home Kotlin sidecar symlinked from
 * the Gradle cache. Skips when the compiler set is absent.
 *
 * Rendering itself is shared with the Java path (pinned by the render
 * goldens); here the assertions are structural — resolution through the
 * Kotlin spellings, exit codes, degradation without a sidecar, and
 * provenance.
 */
@Tag("tier2")
class KotlinSourcesServiceTest {

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

    private fun tempEngine(tempDir: Path): VineflowerDecompiler =
        VineflowerDecompiler(DecompileCache(tempDir.resolve("decompile-cache")))

    // -- body ------------------------------------------------------------------

    @Test
    fun `suspend body slices the Kotlin declaration with sources provenance`(@TempDir home: Path) {
        val roots = fixtureRoots()
        val outcome = JdxService.body(
            "dev.jdx.fixtures.KotlinMembers#fetch",
            roots,
            BodyOptions(kotlinUserHome = kotlinHome(home)),
        )
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        // The canonical ref carries the Kotlin arity (the hidden
        // `Continuation` is stripped by the T-077 view).
        text.lines().first() shouldBe "dev.jdx.fixtures.KotlinMembers#fetch(java.lang.String)"
        text shouldContain "user:\$id"
        text shouldContain "-sources.jar · dev/jdx/fixtures/KotlinShapes.kt:"
        text shouldContain "next: jdx show dev.jdx.fixtures.KotlinMembers"
    }

    @Test
    fun `JvmName spellings both reach the Kotlin declaration`(@TempDir home: Path) {
        val kotlinHome = kotlinHome(home)
        val jvm = JdxService.body(
            "dev.jdx.fixtures.KotlinMembers#renamedForJvm(int)",
            fixtureRoots(),
            BodyOptions(kotlinUserHome = kotlinHome),
        )
        jvm.exitCode shouldBe 0
        textOf(jvm) shouldContain "value * 2"
        val kotlin = JdxService.body(
            "dev.jdx.fixtures.KotlinMembers#originalName(int)",
            fixtureRoots(),
            BodyOptions(kotlinUserHome = kotlinHome),
        )
        kotlin.exitCode shouldBe 0
        textOf(kotlin) shouldContain "value * 2"
    }

    @Test
    fun `property body slices the property declaration`(@TempDir home: Path) {
        val outcome = JdxService.body(
            "dev.jdx.fixtures.KotlinData#nickname",
            fixtureRoots(),
            BodyOptions(kotlinUserHome = kotlinHome(home)),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "nickname"
        textOf(outcome) shouldContain "KotlinShapes.kt:"
    }

    @Test
    fun `internal mangled query reaches the Kotlin declaration`(@TempDir home: Path) {
        val outcome = JdxService.body(
            "dev.jdx.fixtures.KotlinMembers#internalHelper",
            fixtureRoots(),
            BodyOptions(kotlinUserHome = kotlinHome(home)),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "internal fun internalHelper()"
    }

    @Test
    fun `missing member reports no source counterpart`(@TempDir home: Path) {
        val outcome = JdxService.body(
            "dev.jdx.fixtures.KotlinMembers#absent",
            fixtureRoots(),
            BodyOptions(kotlinUserHome = kotlinHome(home)),
        )
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "dev.jdx.fixtures.KotlinMembers#absent"
    }

    // -- doc -------------------------------------------------------------------

    @Test
    fun `facade member KDoc renders with provenance`(@TempDir home: Path) {
        val outcome = JdxService.doc(
            "dev.jdx.fixtures.KotlinShapesKt#extensionGreeting",
            fixtureRoots(),
            DocOptions(kotlinUserHome = kotlinHome(home)),
        )
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "Extension function"
        text shouldContain "KotlinShapes.kt:"
    }

    @Test
    fun `undocumented property names the query without a mismatch`(@TempDir home: Path) {
        val outcome = JdxService.doc(
            "dev.jdx.fixtures.KotlinData#nickname",
            fixtureRoots(),
            DocOptions(kotlinUserHome = kotlinHome(home)),
        )
        outcome.exitCode shouldBe 1
        val text = textOf(outcome)
        text shouldContain "no javadoc comment for 'dev.jdx.fixtures.KotlinData#nickname'"
        text shouldNotContain "SOURCES_VERSION_MISMATCH"
    }

    @Test
    fun `type KDoc renders for documented classes`(@TempDir home: Path) {
        val outcome = JdxService.doc(
            "dev.jdx.fixtures.UserIdBox",
            fixtureRoots(),
            DocOptions(kotlinUserHome = kotlinHome(home)),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "erased to its underlying type"
    }

    // -- source ------------------------------------------------------------------

    @Test
    fun `whole-file source serves the shared Kotlin file`(@TempDir home: Path) {
        val outcome = JdxService.source(
            "dev.jdx.fixtures.KotlinMembers",
            fixtureRoots(),
            SourceOptions(kotlinUserHome = kotlinHome(home)),
        )
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "class KotlinMembers"
        text shouldContain "suspend fun fetch"
    }

    @Test
    fun `facade source serves its file`(@TempDir home: Path) {
        val outcome = JdxService.source(
            "dev.jdx.fixtures.KotlinShapesKt",
            fixtureRoots(),
            SourceOptions(kotlinUserHome = kotlinHome(home)),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "fun KotlinMembers.extensionGreeting()"
    }

    @Test
    fun `around centers on the Kotlin member`(@TempDir home: Path) {
        val outcome = JdxService.source(
            "dev.jdx.fixtures.KotlinMembers",
            fixtureRoots(),
            SourceOptions(
                aroundRef = "dev.jdx.fixtures.KotlinMembers#fetch",
                kotlinUserHome = kotlinHome(home),
            ),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "user:\$id"
    }

    // -- degradation ---------------------------------------------------------------

    @Test
    fun `body without a sidecar degrades to reconstruction`(
        @TempDir home: Path,
        @TempDir cache: Path,
    ) {
        // `home` has no sidecar: the `.kt` flesh is unavailable, so the
        // ladder reconstructs (Vineflower, else the T-073 javap retry — both
        // read `reconstructed`). The temp engine keeps the real cache clean.
        val outcome = JdxService.body(
            "dev.jdx.fixtures.KotlinMembers#fetch",
            fixtureRoots(),
            BodyOptions(kotlinUserHome = home, decompiler = tempEngine(cache)),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "reconstructed"
    }

    @Test
    fun `doc without a sidecar names the install hint`(@TempDir home: Path) {
        // `doc` has no decompiled path (reconstruction carries no KDoc), so
        // the missing sidecar is exit 1 with the `~`-relative hint — never
        // an absolute home path.
        val outcome = JdxService.doc(
            "dev.jdx.fixtures.KotlinShapesKt#extensionGreeting",
            fixtureRoots(),
            DocOptions(kotlinUserHome = home),
        )
        outcome.exitCode shouldBe 1
        val text = textOf(outcome)
        text shouldContain "kotlin-compiler-embeddable-2.4.20.jar not installed under <cache-dir>/kotlin"
        text shouldNotContain home.toString()
    }
}
