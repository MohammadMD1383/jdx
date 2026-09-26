package dev.jdx.index.workspace

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Deepest-only class-dir filtering behind `Path.startsWith` (phase 5,
 * tier 2): a shallow candidate above a deeper one never survives, and a
 * sibling whose name merely shares a prefix never counts as nested.
 */
@Tag("tier2")
class ProjectDiscoveryNestingTest {

    private fun mkdir(root: Path, relative: String): Path {
        val dir = root.resolve(relative)
        Files.createDirectories(dir)
        return dir
    }

    @Test
    fun `a shallow class dir above a deeper one is dropped`(@TempDir temp: Path) {
        mkdir(temp, "build/classes/java/main")
        mkdir(temp, "build/classes")
        // Both candidates exist: only the package root survives.
        val roots = ProjectDiscovery.deriveBinaryRoots(temp)
        roots.jars shouldBe listOf(temp.resolve("build/classes/java/main").toString())
    }

    @Test
    fun `a lone shallow class dir survives`(@TempDir temp: Path) {
        mkdir(temp, "target/classes")
        val roots = ProjectDiscovery.deriveBinaryRoots(temp)
        roots.jars shouldBe listOf(temp.resolve("target/classes").toString())
    }

    @Test
    fun `path nesting is element-wise, not a string prefix`() {
        // `build/classes-java` shares the string prefix `build/classes` but is
        // not nested under it — the `Path.startsWith` contract the filter relies on.
        Path.of("/proj/build/classes-java").startsWith(Path.of("/proj/build/classes")) shouldBe false
        Path.of("/proj/build/classes/java/main").startsWith(Path.of("/proj/build/classes")) shouldBe true
    }
}
