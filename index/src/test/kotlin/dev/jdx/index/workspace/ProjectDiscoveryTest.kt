package dev.jdx.index.workspace

import dev.jdx.core.model.WarningCode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Project auto-discovery against fabricated projects (T-016, tier 2).
 *
 * Everything here runs over `@TempDir` trees — real Gradle/Maven layouts in miniature —
 * so a regression in marker precedence, coordinate parsing or cache invalidation fails
 * here, not in a user's checkout.
 */
@Tag("tier2")
class ProjectDiscoveryTest {

    private fun write(root: Path, relative: String, text: String = ""): Path {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return file
    }

    // -- findProjectRoot ------------------------------------------------------

    @Test
    fun `finds the nearest marker walking up`(@TempDir temp: Path) {
        write(temp, "settings.gradle.kts", "")
        val start = temp.resolve("a/b/c").also { Files.createDirectories(it) }
        val found = ProjectDiscovery.findProjectRoot(start)
        found?.root shouldBe temp.toAbsolutePath().normalize()
        found?.marker shouldBe "settings.gradle.kts"
    }

    @Test
    fun `nearest project wins over an outer one`(@TempDir temp: Path) {
        write(temp, "settings.gradle", "")
        write(temp, "sub/build.gradle", "")
        val inner = temp.resolve("sub/deep").also { Files.createDirectories(it) }
        val found = ProjectDiscovery.findProjectRoot(inner)
        found?.root shouldBe temp.resolve("sub").toAbsolutePath().normalize()
        found?.marker shouldBe "build.gradle"
    }

    @Test
    fun `marker precedence follows the documented order`(@TempDir temp: Path) {
        write(temp, "pom.xml", "<project/>")
        write(temp, "build.gradle.kts", "")
        val found = ProjectDiscovery.findProjectRoot(temp)
        found?.marker shouldBe "build.gradle.kts"
    }

    @Test
    fun `an idea directory marks a project`(@TempDir temp: Path) {
        temp.resolve(".idea").also { Files.createDirectories(it) }
        val found = ProjectDiscovery.findProjectRoot(temp)
        found?.marker shouldBe ".idea"
    }

    @Test
    fun `no marker reads as no project`(@TempDir temp: Path) {
        val bare = temp.resolve("bare").also { Files.createDirectories(it) }
        ProjectDiscovery.findProjectRoot(bare) shouldBe null
    }

    @Test
    fun `a file start resolves from its parent`(@TempDir temp: Path) {
        write(temp, "pom.xml", "<project/>")
        val file = write(temp, "src/Main.java", "class Main {}")
        ProjectDiscovery.findProjectRoot(file)?.marker shouldBe "pom.xml"
    }

    // -- coordinatesOf --------------------------------------------------------

    @Test
    fun `lockfile and scripts unite into one sorted set`(@TempDir temp: Path) {
        write(temp, "settings.gradle", "")
        write(temp, "gradle.lockfile", "com.example:lib:1.0=runtimeClasspath\n")
        write(temp, "build.gradle", "implementation(\"org.example:other:2.0\")\ncom.example:lib:1.0\n")
        ProjectDiscovery.coordinatesOf(temp) shouldBe listOf(
            ProjectDiscovery.MavenCoordinate("com.example", "lib", "1.0"),
            ProjectDiscovery.MavenCoordinate("org.example", "other", "2.0"),
        )
    }

    @Test
    fun `pom dependencies join the coordinate set`(@TempDir temp: Path) {
        write(temp, "pom.xml", POM_WITH_ONE_DEPENDENCY)
        ProjectDiscovery.coordinatesOf(temp) shouldBe listOf(
            ProjectDiscovery.MavenCoordinate("com.example", "app", "2.0"),
        )
    }

    @Test
    fun `pom property versions are skipped, never resolved`(@TempDir temp: Path) {
        write(temp, "pom.xml", POM_WITH_PROPERTY_VERSION)
        ProjectDiscovery.coordinatesOf(temp) shouldBe emptyList()
    }

    @Test
    fun `an invalid pom reads as no coordinates`(@TempDir temp: Path) {
        write(temp, "pom.xml", "this is <not xml")
        ProjectDiscovery.coordinatesOf(temp) shouldBe emptyList()
    }

    // -- deriveBinaryRoots ----------------------------------------------------

    @Test
    fun `class dirs keep the deepest package roots`(@TempDir temp: Path) {
        write(temp, "settings.gradle", "")
        temp.resolve("build/classes/java/main").also { Files.createDirectories(it) }
        temp.resolve("build/classes/kotlin/main").also { Files.createDirectories(it) }
        val derived = ProjectDiscovery.deriveBinaryRoots(temp)
        // `build/classes` also exists (as a parent) but is not a package root —
        // opening it would list `java/main/…`-prefixed entries no binary matches.
        derived.jars shouldBe listOf(
            temp.resolve("build/classes/java/main").toString(),
            temp.resolve("build/classes/kotlin/main").toString(),
        )
        derived.fallbackWarning?.code shouldBe WarningCode.PROJECT_DISCOVERY_FALLBACK
    }

    @Test
    fun `a lone classes dir without language layout stays a root`(@TempDir temp: Path) {
        write(temp, "settings.gradle", "")
        temp.resolve("build/classes").also { Files.createDirectories(it) }
        val derived = ProjectDiscovery.deriveBinaryRoots(temp)
        derived.jars shouldBe listOf(temp.resolve("build/classes").toString())
    }

    @Test
    fun `maven target classes are picked up`(@TempDir temp: Path) {
        write(temp, "pom.xml", POM_WITH_ONE_DEPENDENCY)
        temp.resolve("target/classes").also { Files.createDirectories(it) }
        val derived = ProjectDiscovery.deriveBinaryRoots(temp, m2Repo = temp.resolve("m2-missing"))
        derived.jars shouldBe listOf(temp.resolve("target/classes").toString())
        // Coordinates exist (no fallback) but the cache is absent, so no jars resolve.
        derived.fallbackWarning shouldBe null
    }

    @Test
    fun `lockfile coordinates resolve to gradle cache jars`(@TempDir temp: Path) {
        write(temp, "settings.gradle", "")
        write(temp, "gradle.lockfile", "com.example:lib:1.0=runtimeClasspath\n")
        val jar = write(temp, "caches/com.example/lib/1.0/abc123/lib-1.0.jar", "jar")
        write(temp, "caches/com.example/lib/1.0/abc123/lib-1.0-sources.jar", "sources")
        temp.resolve("build/classes/java/main").also { Files.createDirectories(it) }
        val derived = ProjectDiscovery.deriveBinaryRoots(temp, gradleFilesRoot = temp.resolve("caches"))
        derived.jars shouldBe listOf(
            temp.resolve("build/classes/java/main").toString(),
            jar.toAbsolutePath().normalize().toString(),
        )
        derived.fallbackWarning shouldBe null
    }

    @Test
    fun `pom coordinates resolve to m2 jars without sources or javadoc`(@TempDir temp: Path) {
        write(temp, "pom.xml", POM_WITH_ONE_DEPENDENCY)
        val jar = write(temp, "m2/com/example/app/2.0/app-2.0.jar", "jar")
        write(temp, "m2/com/example/app/2.0/app-2.0-sources.jar", "sources")
        write(temp, "m2/com/example/app/2.0/app-2.0-javadoc.jar", "javadoc")
        val derived = ProjectDiscovery.deriveBinaryRoots(temp, m2Repo = temp.resolve("m2"))
        derived.jars shouldBe listOf(jar.toAbsolutePath().normalize().toString())
    }

    @Test
    fun `no coordinates means project classes only plus a labelled warning`(@TempDir temp: Path) {
        write(temp, "build.gradle", "// no dependencies here\n")
        val derived = ProjectDiscovery.deriveBinaryRoots(temp)
        derived.jars shouldBe emptyList()
        val warning = derived.fallbackWarning
        (warning != null) shouldBe true
        warning?.code shouldBe WarningCode.PROJECT_DISCOVERY_FALLBACK
        warning?.message shouldContain temp.toAbsolutePath().normalize().toString()
    }

    // -- hash and fingerprint -------------------------------------------------

    @Test
    fun `project hash is a stable 32-hex workspace stem`(@TempDir temp: Path) {
        val first = ProjectDiscovery.projectHash(temp)
        val second = ProjectDiscovery.projectHash(temp)
        first shouldBe second
        first shouldMatch Regex("[0-9a-f]{32}")
        (validateWorkspaceName(first) == null) shouldBe true
    }

    @Test
    fun `fingerprint changes when a build file changes`(@TempDir temp: Path) {
        write(temp, "settings.gradle", "rootProject.name = \"a\"\n")
        val before = ProjectDiscovery.computeFingerprint(temp)
        write(temp, "settings.gradle", "rootProject.name = \"b\"\n")
        val after = ProjectDiscovery.computeFingerprint(temp)
        (before != after) shouldBe true
        ProjectDiscovery.computeFingerprint(temp) shouldBe after
    }

    @Test
    fun `fingerprint ignores dependency jars and class output`(@TempDir temp: Path) {
        write(temp, "settings.gradle", "")
        val before = ProjectDiscovery.computeFingerprint(temp)
        write(temp, "build/classes/java/main/Foo.class", "bytes")
        ProjectDiscovery.computeFingerprint(temp) shouldBe before
    }

    private companion object {
        const val POM_WITH_ONE_DEPENDENCY: String = """<?xml version="1.0" encoding="UTF-8"?>
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>demo</artifactId>
  <version>1.0</version>
  <dependencies>
    <dependency>
      <groupId>com.example</groupId>
      <artifactId>app</artifactId>
      <version>2.0</version>
    </dependency>
  </dependencies>
</project>
"""
        const val POM_WITH_PROPERTY_VERSION: String = """<?xml version="1.0" encoding="UTF-8"?>
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>demo</artifactId>
  <version>1.0</version>
  <properties><app.version>2.0</app.version></properties>
  <dependencies>
    <dependency>
      <groupId>com.example</groupId>
      <artifactId>app</artifactId>
      <version>${"$"}{app.version}</version>
    </dependency>
  </dependencies>
</project>
"""
    }
}
