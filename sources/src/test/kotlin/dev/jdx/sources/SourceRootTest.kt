package dev.jdx.sources

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Disk behaviour of [SourceRoot] (T-071, tier 2): crafted hostile jars and
 * dirs plus one real read from the fixture `-sources.jar` (T-006). Everything
 * here touches the filesystem, so it is tagged out of the tier-1 loop.
 */
@Tag("tier2")
class SourceRootTest {

    private fun craftJar(jar: Path, entries: Map<String, ByteArray>): Path {
        Files.createDirectories(jar.parent)
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            entries.entries.sortedBy { it.key }.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return jar
    }

    private fun fixturesDir(): File {
        System.getProperty("jdx.fixturesDir")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testfixtures/build/libs")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("fixture jars not found: build :testfixtures first")
    }

    private fun fixtureSourcesJar(dir: File = fixturesDir()): File {
        val jars = dir.listFiles { file ->
            file.isFile && file.name.endsWith("-sources.jar")
        }?.toList().orEmpty()
        require(jars.size == 1) { "expected exactly one fixture sources jar in $dir, found: $jars" }
        return jars.single()
    }

    @Test
    fun `jar root lists only java and kotlin sources sorted`(@TempDir tmp: Path) {
        val jar = craftJar(
            tmp.resolve("lib-sources.jar"),
            mapOf(
                "com/example/Foo.java" to "class Foo {}".toByteArray(),
                "com/example/Data.kt" to "class Data".toByteArray(),
                "com/example/Foo.class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte()),
                "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\r\n\r\n".toByteArray(),
                "../evil.java" to "evil".toByteArray(),
                "/absolute.java" to "evil".toByteArray(),
            ),
        )
        JarSourceRoot(jar).use { root ->
            root.displayName shouldBe "lib-sources.jar"
            root.sourcePaths() shouldBe listOf("com/example/Data.kt", "com/example/Foo.java")
            // Deterministic across runs (CLAUDE.md §2.5).
            root.sourcePaths() shouldBe root.sourcePaths()
            root.findSource("com.example.Foo") shouldBe "com/example/Foo.java"
            root.findSource("com.example.Foo\$Inner") shouldBe "com/example/Foo.java"
            root.findSource("com.example.Data") shouldBe "com/example/Data.kt"
            root.findSource("com.example.Missing") shouldBe null
            root.openSource("com/example/Foo.java").use { it.readBytes().toString(Charsets.UTF_8) } shouldBe
                "class Foo {}"
            // Traversal and absolute entries are neither listed nor openable.
            shouldThrow<SourceReadException> { root.openSource("../evil.java") }
            shouldThrow<SourceReadException> { root.openSource("/absolute.java") }
            shouldThrow<SourceReadException> { root.openSource("com/example/Foo.class") }
            shouldThrow<SourceReadException> { root.openSource("com/example/Missing.java") }
        }
    }

    @Test
    fun `dir root walks nested trees and contains every read`(@TempDir tmp: Path) {
        val src = tmp.resolve("src")
        Files.createDirectories(src.resolve("com/example"))
        Files.write(src.resolve("com/example/Foo.java"), "class Foo {}".toByteArray())
        Files.write(src.resolve("com/example/Data.kt"), "class Data".toByteArray())
        Files.write(src.resolve("com/example/notes.txt"), "not source".toByteArray())
        DirSourceRoot(src).use { root ->
            root.sourcePaths() shouldBe listOf("com/example/Data.kt", "com/example/Foo.java")
            root.findSource("com.example.Foo\$Inner") shouldBe "com/example/Foo.java"
            root.openSource("com/example/Data.kt").use { it.readBytes().toString(Charsets.UTF_8) } shouldBe
                "class Data"
            shouldThrow<SourceReadException> { root.openSource("../outside.java") }
            shouldThrow<SourceReadException> { root.openSource("com/example/notes.txt") }
        }
    }

    @Test
    fun `dispatch opens jars and dirs and rejects missing paths`(@TempDir tmp: Path) {
        val jar = craftJar(tmp.resolve("a.jar"), mapOf("Foo.java" to "x".toByteArray()))
        openSourceRoot(jar).use { it.displayName shouldBe "a.jar" }
        openSourceRoot(tmp).use { it.sourcePaths() shouldBe emptyList() }
        shouldThrow<SourceReadException> { openSourceRoot(tmp.resolve("absent.jar")) }
            .message shouldContain "no such source root"
    }

    @Test
    fun `fixture sources jar resolves a nested fixture class`() {
        val jar = fixtureSourcesJar()
        JarSourceRoot(jar.toPath()).use { root ->
            root.findSource("dev.jdx.fixtures.Nesting\$Inner") shouldBe "dev/jdx/fixtures/Nesting.java"
            root.findSource("dev.jdx.fixtures.Generics") shouldBe "dev/jdx/fixtures/Generics.java"
            val text = root.openSource("dev/jdx/fixtures/Generics.java").use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            text shouldContain "Generics"
            // The Kotlin shapes ship as sources too (M5 parses them; T-071 only lists).
            root.sourcePaths().any { it.endsWith("KotlinShapes.kt") } shouldBe true
        }
    }
}
