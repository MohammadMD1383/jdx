package dev.jdx.index.service

import dev.jdx.index.maven.MavenResolver
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tests for `g:a:v/`-scoped type queries (T-019): the prefix resolves the
 * coordinate, scopes candidates to its jar, and still answers hierarchy
 * questions from the surrounding roots. Tier 2: fabricated `~/.m2` layouts
 * holding a copy of the fixture corpus jar — no network, no absolute paths.
 */
@Tag("tier2")
class MavenQueryTest {

    private fun fixtureJar(): Path {
        val dir = System.getProperty("jdx.fixturesDir")?.let { File(it) }
            ?: error("jdx.fixturesDir not set (index build wires it; see index/build.gradle.kts)")
        return dir.listFiles { file -> file.name.endsWith(".jar") && !file.name.contains("sources") }
            ?.sortedBy { it.name }?.firstOrNull()?.toPath()
            ?: error("no fixture binary jar in $dir")
    }

    private fun m2WithDemo(root: Path): MavenResolver.Repositories {
        val versionDir = root.resolve("m2/com/example/demo/1.0")
        Files.createDirectories(versionDir)
        Files.copy(fixtureJar(), versionDir.resolve("demo-1.0.jar"))
        return MavenResolver.Repositories(
            gradleFilesRoot = null,
            m2Repo = root.resolve("m2"),
            fetchCacheRoot = null,
        )
    }

    private fun rootsFor(repos: MavenResolver.Repositories, includeJdk: Boolean = true): JdxService.RootsSpec =
        JdxService.RootsSpec(
            jarSpecs = emptyList(),
            includeJdk = includeJdk,
            mavenResolve = { text, fetch -> MavenResolver.resolve(text, fetch, repos) },
        )

    @Test
    fun `a coordinate prefix scopes show to the artifact`(@TempDir root: Path) {
        val repos = m2WithDemo(root)
        val outcome = JdxService.show("com.example:demo:1.0/dev.jdx.fixtures.Generics", rootsFor(repos))
        (outcome.exitCode) shouldBe 0
        outcome.renderText() shouldContain "dev.jdx.fixtures.Generics"
        outcome.renderText() shouldContain "demo-1.0.jar"
    }

    @Test
    fun `a coordinate prefix scopes members to the artifact`(@TempDir root: Path) {
        val repos = m2WithDemo(root)
        val outcome = JdxService.members("com.example:demo:1.0/dev.jdx.fixtures.Generics", rootsFor(repos))
        (outcome.exitCode) shouldBe 0
        outcome.renderText() shouldContain "dev.jdx.fixtures.Generics"
    }

    @Test
    fun `a coordinate alone answers with no workspace and no JDK`(@TempDir root: Path) {
        val repos = m2WithDemo(root)
        val outcome = JdxService.show(
            "com.example:demo:1.0/dev.jdx.fixtures.Generics",
            rootsFor(repos, includeJdk = false),
        )
        (outcome.exitCode) shouldBe 0
    }

    @Test
    fun `a class outside the scoped artifact is not found`(@TempDir root: Path) {
        val repos = m2WithDemo(root)
        val outcome = JdxService.show("com.example:demo:1.0/java.util.HashMap", rootsFor(repos))
        (outcome.exitCode) shouldBe 1
    }

    @Test
    fun `an unresolvable prefix is an artifact error naming the fix`(@TempDir root: Path) {
        val repos = m2WithDemo(root)
        val outcome = JdxService.show("com.example:missing:9.9.9/dev.jdx.fixtures.Generics", rootsFor(repos))
        (outcome.exitCode) shouldBe 5
        outcome.renderText() shouldContain "--fetch"
    }
}
