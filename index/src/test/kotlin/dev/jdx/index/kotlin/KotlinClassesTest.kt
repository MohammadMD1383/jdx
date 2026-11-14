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
        // Multi-file facades need sibling part bytes (issue #12).
        val partResolver: (String) -> ByteArray? = { partInternal ->
            try {
                ArtifactTestJars.fixtureClassBytes(binaryJar, "$partInternal.class")
            } catch (_: Exception) {
                null
            }
        }
        return AsmClassReader.read(bytes, entry, partResolver)
            .shouldBeInstanceOf<ClassReadResult.Ok>().info
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
        metadata?.kmClass shouldBe null
        // Issue #12: facades carry KmPackage, not KmClass.
        (metadata?.kmPackage != null) shouldBe true

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
    fun `constructor properties are marked constructorDeclared on ClassInfo`() {
        // Issue #14: primary-ctor props (`name`/`count`) pair through `<init>`;
        // body-declared ones (`greeting`/`nickname`) must warn on removal.
        val info = readFixture("dev.jdx.fixtures.KotlinData")
        info.kotlinProperties["name"]?.constructorDeclared shouldBe true
        info.kotlinProperties["count"]?.constructorDeclared shouldBe true
        info.kotlinProperties["greeting"]?.constructorDeclared shouldBe false
        info.kotlinProperties["nickname"]?.constructorDeclared shouldBe false
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
    fun `a file facade carries KmPackage functions and properties`() {
        val metadata = requireNotNull(decodeFixture("dev.jdx.fixtures.KotlinShapesKt"))
        metadata.kmClass shouldBe null
        val kmPackage = requireNotNull(metadata.kmPackage) { "FILE_FACADE carries KmPackage" }
        val functions = kmPackage.functions.map { it.name }
        (functions.contains("facadeOriginal")) shouldBe true
        (functions.contains("facadeFetch")) shouldBe true
        (functions.contains("facadeInternal")) shouldBe true
        (functions.contains("extensionGreeting")) shouldBe true
        val properties = kmPackage.properties.map { it.name }
        (properties.contains("facadeGreeting")) shouldBe true
        (properties.contains("facadeNickname")) shouldBe true
    }

    @Test
    fun `file facade ClassInfo maps JvmName suspend internal and properties`() {
        val info = readFixture("dev.jdx.fixtures.KotlinShapesKt")
        // @JvmName: JVM key → Kotlin declaration name
        val renamed = info.kotlinMethodViews[
            dev.jdx.core.model.kotlinViewKey("facadeRenamed", "(I)I"),
        ]
        renamed?.displayName shouldBe "facadeOriginal"
        // suspend: Continuation strip + mark
        val suspendView = info.kotlinMethodViews.entries
            .firstOrNull { it.key.startsWith("facadeFetch") }
            ?.value
        requireNotNull(suspendView)
        suspendView.markSuspend shouldBe true
        suspendView.stripTrailingContinuation shouldBe true
        suspendView.displayReturn shouldBe "java.lang.String"
        // Top-level `internal` keeps its JVM name (no `$module` mangle on facades);
        // still present in the package, but no rename view is needed.
        (info.methods.any { it.name == "facadeInternal" }) shouldBe true
        // properties folded
        info.kotlinProperties["facadeGreeting"]?.isVar shouldBe false
        info.kotlinProperties["facadeNickname"]?.isVar shouldBe true
        (info.kotlinHiddenMethods.isNotEmpty()) shouldBe true
    }

    @Test
    fun `a multi-file facade umbrella merges part packages into views`() {
        val metadata = decodeFixture("dev.jdx.fixtures.KotlinMultifile")
        metadata?.metadataKind shouldBe KotlinMetadataKind.MULTI_FILE_FACADE
        metadata?.kmClass shouldBe null
        metadata?.kmPackage shouldBe null
        metadata?.declarationContainer() shouldBe null
        (metadata?.partClassNames?.isNotEmpty() == true) shouldBe true

        val info = readFixture("dev.jdx.fixtures.KotlinMultifile")
        info.isKotlin shouldBe true
        info.kind shouldBe TypeKind.CLASS
        // Merged from parts: @JvmName + suspend
        info.kotlinMethodViews[
            dev.jdx.core.model.kotlinViewKey("multiRenamed", "(I)I"),
        ]?.displayName shouldBe "multiOriginal"
        val suspendView = info.kotlinMethodViews.entries
            .firstOrNull { it.key.startsWith("multiSuspend") }
            ?.value
        requireNotNull(suspendView)
        suspendView.markSuspend shouldBe true
        suspendView.stripTrailingContinuation shouldBe true
    }

    @Test
    fun `a multi-file part carries KmPackage and Kotlin views`() {
        // Part binary names: KotlinMultifile__KotlinMultifileAKt / …BKt
        val partA = decodeFixture("dev.jdx.fixtures.KotlinMultifile__KotlinMultifileAKt")
        partA?.metadataKind shouldBe KotlinMetadataKind.MULTI_FILE_PART
        val packageA = requireNotNull(partA?.kmPackage)
        (packageA.functions.map { it.name }.contains("multiOriginal")) shouldBe true

        val infoA = readFixture("dev.jdx.fixtures.KotlinMultifile__KotlinMultifileAKt")
        infoA.kotlinMethodViews[
            dev.jdx.core.model.kotlinViewKey("multiRenamed", "(I)I"),
        ]?.displayName shouldBe "multiOriginal"

        val partB = decodeFixture("dev.jdx.fixtures.KotlinMultifile__KotlinMultifileBKt")
        partB?.metadataKind shouldBe KotlinMetadataKind.MULTI_FILE_PART
        val infoB = readFixture("dev.jdx.fixtures.KotlinMultifile__KotlinMultifileBKt")
        val suspendView = infoB.kotlinMethodViews.entries
            .firstOrNull { it.key.startsWith("multiSuspend") }
            ?.value
        requireNotNull(suspendView)
        suspendView.markSuspend shouldBe true
        suspendView.stripTrailingContinuation shouldBe true
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
