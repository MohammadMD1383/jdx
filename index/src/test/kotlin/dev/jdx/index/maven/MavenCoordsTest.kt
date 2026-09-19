package dev.jdx.index.maven

import dev.jdx.core.model.MavenCoordinate
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Tests for Maven coordinate parsing and layout paths (T-019). Pure string
 * work — tier 1 — plus the task's *generating* family: a thousand-case
 * parse/format round-trip and a never-throws sweep over hostile strings.
 */
class MavenCoordsTest {

    @Test
    fun `parse accepts canonical coordinates and trims whitespace`() {
        MavenCoords.parse("com.google.code.gson:gson:2.14.0") shouldBe
            MavenCoordinate("com.google.code.gson", "gson", "2.14.0")
        MavenCoords.parse("  org.example : lib : 1.0 ") shouldBe
            MavenCoordinate("org.example", "lib", "1.0")
        MavenCoords.parse("a:b:c-d_e.f+g") shouldBe MavenCoordinate("a", "b", "c-d_e.f+g")
    }

    @Test
    fun `parse rejects malformed coordinates`() {
        val bad = listOf(
            "",
            "   ",
            "gson",
            "g:a",
            "g:a:v:extra",
            ":a:v",
            "g::v",
            "g:a:",
            "g:a:1.0*",
            "g:a:1 0",
            "g:a:../evil",
            "g:..:v",
            "com/example:gson:2.14.0",
            "g:a:v/w",
        )
        for (text in bad) {
            (MavenCoords.parse(text) == null) shouldBe true
        }
    }

    @Test
    fun `invalidReason names the expected shape`() {
        MavenCoords.invalidReason("gson") shouldContain "group:artifact:version"
        MavenCoords.invalidReason("g:a:1*") shouldContain "g:a:1*"
    }

    @Test
    fun `layout paths follow the Maven directory conventions`() {
        val coord = MavenCoordinate("com.google.code.gson", "gson", "2.14.0")
        MavenCoords.repositoryPath(coord) shouldBe "com/google/code/gson/gson/2.14.0"
        MavenCoords.binaryFileName(coord) shouldBe "gson-2.14.0.jar"
        MavenCoords.sourcesFileName(coord) shouldBe "gson-2.14.0-sources.jar"
        val root = Path.of("/home/u/.m2/repository")
        MavenCoords.m2BinaryPath(root, coord) shouldBe
            root.resolve("com/google/code/gson/gson/2.14.0/gson-2.14.0.jar")
        MavenCoords.m2SourcesPath(root, coord) shouldBe
            root.resolve("com/google/code/gson/gson/2.14.0/gson-2.14.0-sources.jar")
    }

    @Test
    fun `downloadUrl joins the base, path and file`() {
        val coord = MavenCoordinate("com.google.code.gson", "gson", "2.14.0")
        MavenCoords.downloadUrl(coord, "gson-2.14.0.jar") shouldBe
            "https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar"
        MavenCoords.downloadUrl(coord, "gson-2.14.0.jar", "http://localhost:8081/repo") shouldBe
            "http://localhost:8081/repo/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar"
    }

    @Test
    fun `parse-format is a fixed point for generated coordinates`() = runBlocking<Unit> {
        val part = Arb.list(
            Arb.of(('a'..'z').toList() + ('A'..'Z').toList() + ('0'..'9').toList() + listOf('.', '-', '_', '+')),
            1..12,
        ).map { it.joinToString("") }.filter { it != "." && it != ".." }
        checkAll(1_000, part, part, part) { group, artifact, version ->
            val text = "$group:$artifact:$version"
            MavenCoords.parse(text) shouldBe MavenCoordinate(group, artifact, version)
            MavenCoords.format(MavenCoords.parse(text)!!) shouldBe text
        }
    }

    @Test
    fun `parse and invalidReason never throw on generated strings`() = runBlocking<Unit> {
        checkAll(1_000, Arb.string(0..32)) { text ->
            // Completion without throwing is the assertion; either side is legal.
            val parsed = MavenCoords.parse(text)
            ((parsed == null || parsed.coordinate.isNotEmpty())) shouldBe true
            (MavenCoords.invalidReason(text).isNotEmpty()) shouldBe true
        }
    }

    @Test
    fun `repo URLs accept http and https with optional trailing slash`() {
        val valid = listOf(
            "https://repo.maven.apache.org/maven2/",
            "https://repo.maven.apache.org/maven2",
            "http://localhost:8081/repo",
            "http://127.0.0.1:9/maven2/",
            "https://repo.example.com/artifactory/maven",
            "HTTPS://REPO.EXAMPLE.COM/maven2/",
        )
        for (url in valid) {
            (MavenCoords.isValidRepoUrl(url)) shouldBe true
            (MavenCoords.invalidRepoReason(url) == null) shouldBe true
        }
    }

    @Test
    fun `repo URLs reject non-http schemes and shapeless values`() {
        val bad = listOf(
            "",
            "   ",
            "ftp://repo.example.com/maven2",
            "file:///root/.m2/repository",
            "repo.example.com/maven2",
            "//repo.example.com/maven2",
            "http://",
            "https://?query",
            "https://repo.example.com/maven2?query=1",
            "https://repo.example.com/maven2#fragment",
            "https://repo.example.com/ma ven2",
            " https://repo.example.com/maven2",
            "https://repo.example.com/maven2 ",
        )
        for (url in bad) {
            (MavenCoords.isValidRepoUrl(url)) shouldBe false
            val reason = MavenCoords.invalidRepoReason(url)
            ((reason != null && reason.contains(url.trim().ifEmpty { "--repo" }))) shouldBe true
        }
    }

    @Test
    fun `repo validation never throws and agrees with itself on generated strings`() = runBlocking<Unit> {
        checkAll(1_000, Arb.string(0..48)) { text ->
            val reason = MavenCoords.invalidRepoReason(text)
            (MavenCoords.isValidRepoUrl(text)) shouldBe (reason == null)
            if (reason != null) (reason.contains("--repo")) shouldBe true
        }
    }
}
