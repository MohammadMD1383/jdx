package dev.jdx.index.kotlin

import dev.jdx.core.model.TypeKind
import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.asm.AsmClassReader
import dev.jdx.index.asm.ClassReadResult
import dev.jdx.index.store.NewArtifact
import dev.jdx.index.store.sqlite.SqliteIndexStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Path
import kotlin.metadata.ClassKind
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tier-2 acceptance tests for `@Metadata` decoding (T-035).
 *
 * Tagged `tier2` (docs/TESTING.md §2): every test here reads real Kotlin class files
 * off the fixture jar. The fast in-memory suites live in [KotlinMetadataTest] and
 * [KotlinMetadataPropertyTest]. Classes are read as bytes, never loaded (D-017).
 */
@Tag("tier2")
class KotlinClassesTest {

    private val binaryJar: File by lazy { ArtifactTestJars.binaryJar() }

    @TempDir
    private lateinit var tempDir: Path

    private fun readFixture(binaryName: String): dev.jdx.core.model.ClassInfo {
        val entry = binaryName.replace('.', '/') + ".class"
        val bytes = ArtifactTestJars.fixtureClassBytes(binaryJar, entry)
        return AsmClassReader.read(bytes, entry).shouldBeInstanceOf<ClassReadResult.Ok>().info
    }

    private fun decodeFixture(binaryName: String): KotlinMetadata? {
        val entry = binaryName.replace('.', '/') + ".class"
        val bytes = ArtifactTestJars.fixtureClassBytes(binaryJar, entry)
        val node = org.objectweb.asm.tree.ClassNode()
        org.objectweb.asm.ClassReader(bytes).accept(node, org.objectweb.asm.ClassReader.SKIP_FRAMES)
        return KotlinMetadataReader.read(node.visibleAnnotations, node.invisibleAnnotations)
    }

    @Test
    fun `a kotlin class decodes as class and reads as a kotlin class`() {
        val metadata = decodeFixture("dev.jdx.fixtures.KotlinMembers")
        metadata?.metadataKind shouldBe KotlinMetadataKind.CLASS
        metadata?.classKind shouldBe ClassKind.CLASS

        val info = readFixture("dev.jdx.fixtures.KotlinMembers")
        info.isKotlin shouldBe true
        info.kind shouldBe TypeKind.CLASS
    }

    @Test
    fun `a kotlin object reads as object`() {
        val metadata = decodeFixture("dev.jdx.fixtures.KotlinRegistry")
        metadata?.metadataKind shouldBe KotlinMetadataKind.CLASS
        metadata?.classKind shouldBe ClassKind.OBJECT

        val info = readFixture("dev.jdx.fixtures.KotlinRegistry")
        info.isKotlin shouldBe true
        info.kind shouldBe TypeKind.OBJECT
    }

    @Test
    fun `a companion object reads as companion`() {
        val metadata = decodeFixture("dev.jdx.fixtures.KotlinMembers\$Companion")
        metadata?.metadataKind shouldBe KotlinMetadataKind.CLASS
        metadata?.classKind shouldBe ClassKind.COMPANION_OBJECT

        val info = readFixture("dev.jdx.fixtures.KotlinMembers\$Companion")
        info.isKotlin shouldBe true
        info.kind shouldBe TypeKind.COMPANION
    }

    @Test
    fun `a file facade stays a kotlin class`() {
        val metadata = decodeFixture("dev.jdx.fixtures.KotlinShapesKt")
        metadata?.metadataKind shouldBe KotlinMetadataKind.FILE_FACADE
        metadata?.classKind shouldBe null

        val info = readFixture("dev.jdx.fixtures.KotlinShapesKt")
        info.isKotlin shouldBe true
        info.kind shouldBe TypeKind.CLASS
    }

    @Test
    fun `a data object reads as object`() {
        val info = readFixture("dev.jdx.fixtures.KotlinSealed\$Empty")
        info.isKotlin shouldBe true
        info.kind shouldBe TypeKind.OBJECT
    }

    @Test
    fun `a kotlin class carries its KmClass functions under kotlin names`() {
        val kmClass = requireNotNull(decodeFixture("dev.jdx.fixtures.KotlinMembers")?.kmClass) {
            "CLASS metadata carries KmClass"
        }
        val functions = kmClass.functions.map { it.name }
        // Kotlin declaration names, not JVM names: the source says `originalName`.
        (functions.contains("originalName")) shouldBe true
        (functions.contains("fetch")) shouldBe true
        (functions.contains("withDefault")) shouldBe true
        (functions.contains("renamedForJvm")) shouldBe false
    }

    @Test
    fun `a kotlin data class carries its KmClass properties`() {
        val kmClass = requireNotNull(decodeFixture("dev.jdx.fixtures.KotlinData")?.kmClass) {
            "CLASS metadata carries KmClass"
        }
        val properties = kmClass.properties.map { it.name }
        (properties.contains("name")) shouldBe true
        (properties.contains("count")) shouldBe true
        (properties.contains("greeting")) shouldBe true
        (properties.contains("nickname")) shouldBe true
    }

    @Test
    fun `a companion object carries its KmClass members`() {
        val kmClass = requireNotNull(decodeFixture("dev.jdx.fixtures.KotlinMembers\$Companion")?.kmClass) {
            "CLASS metadata carries KmClass"
        }
        (kmClass.functions.map { it.name }.contains("create")) shouldBe true
        (kmClass.properties.map { it.name }.contains("VERSION")) shouldBe true
    }

    @Test
    fun `a file facade carries no KmClass`() {
        decodeFixture("dev.jdx.fixtures.KotlinShapesKt")?.kmClass shouldBe null
    }

    @Test
    fun `KmClass contents are deterministic across decodes`() {
        // KmClass itself has no value equality — compare contents, never carriers.
        val first = requireNotNull(decodeFixture("dev.jdx.fixtures.KotlinMembers")?.kmClass)
        val second = requireNotNull(decodeFixture("dev.jdx.fixtures.KotlinMembers")?.kmClass)
        first.functions.map { it.name } shouldBe second.functions.map { it.name }
        first.properties.map { it.name } shouldBe second.properties.map { it.name }
    }

    @Test
    fun `a java class is not kotlin and keeps its jvm kind`() {
        decodeFixture("dev.jdx.fixtures.TrafficLight") shouldBe null

        val info = readFixture("dev.jdx.fixtures.TrafficLight")
        info.isKotlin shouldBe false
        info.kind shouldBe TypeKind.ENUM
    }

    @Test
    fun `isKotlin and refined kinds survive the index store round trip`() {
        val stored = listOf(
            readFixture("dev.jdx.fixtures.KotlinMembers"),
            readFixture("dev.jdx.fixtures.KotlinRegistry"),
            readFixture("dev.jdx.fixtures.TrafficLight"),
        )
        SqliteIndexStore.open(tempDir.resolve("kotlin.db")).use { store ->
            val artifact = store.upsertArtifact(NewArtifact(hash = "k".repeat(32), path = "/kotlin.jar"))
            store.replaceClasses(artifact.id, stored)
            for (info in stored) {
                val loaded = store.loadClass(artifact.id, info.name.binaryName)
                loaded?.isKotlin shouldBe info.isKotlin
                loaded?.kind shouldBe info.kind
            }
        }
    }
}
