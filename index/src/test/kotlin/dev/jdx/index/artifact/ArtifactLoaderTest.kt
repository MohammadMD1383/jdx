package dev.jdx.index.artifact

import dev.jdx.core.model.WarningCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tier-2 tests for [ArtifactLoader] and the three [ArtifactRoot] shapes (T-007).
 *
 * Tagged `tier2` (docs/TESTING.md §2): every test here reads real jars, class dirs, or the
 * JDK's `jrt:/` off disk. The pure name/hash/MR logic lives in the tier-1 suites beside it.
 */
@Tag("tier2")
class ArtifactLoaderTest {

    private val binaryJar: File by lazy { ArtifactTestJars.binaryJar() }

    @Test
    fun `fixture jar lists sorted normalised classes`(@TempDir temp: Path) {
        ArtifactLoader.openJar(binaryJar.toPath()).use { root ->
            root.kind shouldBe ArtifactKind.BINARY_JAR
            val entries = root.classEntryPaths()
            (entries.isNotEmpty()) shouldBe true
            entries shouldBe entries.sorted()
            entries.forEach { (it.endsWith(".class")) shouldBe true }
            entries shouldNotContain "module-info.class"
            entries shouldContain "dev/jdx/fixtures/Generics.class"
        }
    }

    @Test
    fun `fixture jar classes open with the class-file magic`() {
        ArtifactLoader.openJar(binaryJar.toPath()).use { root ->
            root.openClass("dev/jdx/fixtures/Generics.class").use { stream ->
                val magic = stream.readNBytes(4)
                magic shouldBe byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
            }
        }
    }

    @Test
    fun `fixture binary jar pairs with its sibling sources jar`() {
        ArtifactLoader.openJar(binaryJar.toPath()).use { root ->
            val pair = root.sourcesPair
            (pair is SourcesPair.External) shouldBe true
            ((pair as SourcesPair.External).path.fileName.toString().endsWith("-sources.jar")) shouldBe true
        }
    }

    @Test
    fun `jar hash is stable across opens`() {
        val first = ArtifactLoader.openJar(binaryJar.toPath()).use { it.stableId() }
        val second = ArtifactLoader.openJar(binaryJar.toPath()).use { it.stableId() }
        first shouldBe second
        first shouldBe ArtifactHash.hashFile(binaryJar.toPath())
        first.length shouldBe 32
    }

    @Test
    fun `reading every fixture class never initialises one`() {
        val marker = File(System.getProperty("java.io.tmpdir"), "jdx-fixture-static-init-marker")
        ArtifactLoader.openJar(binaryJar.toPath()).use { root ->
            root.classEntryPaths().forEach { path ->
                root.openClass(path).use { it.readAllBytes() }
            }
        }
        // StaticInitMarker's `<clinit>` writes this file. Bytes were parsed, never loaded
        // (D-017) — so the marker must be absent even after a full-jar read.
        marker.exists().shouldBeFalse()
    }

    @Test
    fun `class dir reads uniformly with its jar`(@TempDir temp: Path) {
        val dir = temp.resolve("classes")
        listOf("dev/jdx/fixtures/Generics.class", "dev/jdx/fixtures/Nesting.class").forEach { entry ->
            val target = dir.resolve(entry)
            Files.createDirectories(target.parent)
            Files.write(target, ArtifactTestJars.fixtureClassBytes(binaryJar, entry))
        }
        ArtifactLoader.openDir(dir).use { root ->
            root.kind shouldBe ArtifactKind.CLASS_DIR
            root.classEntryPaths() shouldBe
                listOf("dev/jdx/fixtures/Generics.class", "dev/jdx/fixtures/Nesting.class")
            val magic = root.openClass("dev/jdx/fixtures/Generics.class").use { it.readNBytes(4) }
            magic shouldBe byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        }
        // And the generic dispatch agrees: dirs open as dirs, jars as jars.
        ArtifactLoader.open(dir).use { (it is DirArtifact) shouldBe true }
        ArtifactLoader.open(binaryJar.toPath()).use { (it is JarArtifact) shouldBe true }
    }

    @Test
    fun `missing artifact names itself in the error`() {
        val missing = Path.of("/no/such/jdx-artifact.jar")
        val ex = shouldThrow<ArtifactReadException> { ArtifactLoader.open(missing) }
        (ex.message?.contains("/no/such/jdx-artifact.jar") ?: false) shouldBe true
        // No stack trace reaches the caller unasked — the contract is message-only (T-057
        // asserts the same over stdout/stderr once commands exist).
        (ex.message?.contains("at dev.jdx") ?: false).shouldBeFalse()
    }

    @Test
    fun `multi-release jar serves the newest applicable variant and warns`(@TempDir temp: Path) {
        val base = ArtifactTestJars.fixtureClassBytes(binaryJar, "dev/jdx/fixtures/Generics.class")
        val jar = ArtifactTestJars.craftJar(
            temp.resolve("mr.jar"),
            mapOf(
                "META-INF/MANIFEST.MF" to ArtifactTestJars.manifestBytes(multiRelease = true),
                "com/acme/Thing.class" to base,
                "META-INF/versions/9/com/acme/Thing.class" to "variant-9".toByteArray(),
                "META-INF/versions/17/com/acme/Thing.class" to "variant-17".toByteArray(),
                "META-INF/versions/99/com/acme/Thing.class" to "variant-99".toByteArray(),
            ),
        )
        // Toolchain JDK is 21+, so 17 is the newest applicable variant on any machine
        // running this suite — never 99, never the base.
        ArtifactLoader.openJar(jar).use { root ->
            root.classEntryPaths() shouldBe listOf("com/acme/Thing.class")
            val served = root.openClass("com/acme/Thing.class").use { it.readAllBytes() }
            served shouldBe "variant-17".toByteArray()
            root.warnings.map { it.code } shouldContain WarningCode.MULTI_RELEASE_VARIANT
        }
    }

    @Test
    fun `versioned tree without the manifest flag is ignored silently`(@TempDir temp: Path) {
        val base = ArtifactTestJars.fixtureClassBytes(binaryJar, "dev/jdx/fixtures/Generics.class")
        val jar = ArtifactTestJars.craftJar(
            temp.resolve("plain.jar"),
            mapOf(
                "META-INF/MANIFEST.MF" to ArtifactTestJars.manifestBytes(multiRelease = false),
                "com/acme/Thing.class" to base,
                "META-INF/versions/21/com/acme/Thing.class" to "variant-21".toByteArray(),
            ),
        )
        ArtifactLoader.openJar(jar).use { root ->
            val served = root.openClass("com/acme/Thing.class").use { it.readAllBytes() }
            served shouldBe base
            root.warnings.map { it.code } shouldNotContain WarningCode.MULTI_RELEASE_VARIANT
        }
    }

    @Test
    fun `zip-slip entries are never listed and never open`(@TempDir temp: Path) {
        val real = ArtifactTestJars.fixtureClassBytes(binaryJar, "dev/jdx/fixtures/Generics.class")
        val jar = ArtifactTestJars.craftJar(
            temp.resolve("slip.jar"),
            mapOf(
                "../../evil.class" to "evil".toByteArray(),
                "/abs.class" to "evil".toByteArray(),
                "com/ok/Real.class" to real,
            ),
        )
        ArtifactLoader.openJar(jar).use { root ->
            root.classEntryPaths() shouldBe listOf("com/ok/Real.class")
            shouldThrow<ArtifactReadException> { root.openClass("../../evil.class") }
            shouldThrow<ArtifactReadException> { root.openClass("/abs.class") }
            root.openClass("com/ok/Real.class").use { it.readAllBytes() } shouldBe real
        }
    }

    @Test
    fun `empty jar lists nothing and stays healthy`(@TempDir temp: Path) {
        val jar = ArtifactTestJars.craftJar(temp.resolve("empty.jar"), emptyMap())
        ArtifactLoader.openJar(jar).use { root ->
            root.classEntryPaths() shouldBe emptyList()
            shouldThrow<ArtifactReadException> { root.openClass("com/acme/Missing.class") }
        }
    }

    @Test
    fun `jdk root serves object from java-base`(@TempDir temp: Path) {
        ArtifactLoader.openJdk().use { root ->
            root.kind shouldBe ArtifactKind.JRT
            root.classEntryPaths() shouldContain "java/lang/Object.class"
            root.moduleForClass("java/lang/Object.class") shouldBe "java.base"
            root.moduleForClass("com/acme/Nope.class") shouldBe null
            val magic = root.openClass("java/lang/Object.class").use { it.readNBytes(4) }
            magic shouldBe byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
            (root.stableId().startsWith("jrt-")) shouldBe true
            // src.zip may or may not ship with this distribution — either is routine.
            root.jdkSources?.let { (it.fileName.toString()) shouldBe "src.zip" }
            // Listing is deterministic: two walks agree exactly.
            root.classEntryPaths() shouldBe root.classEntryPaths().sorted()
        }
    }
}
