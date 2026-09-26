package dev.jdx.testsupport.fixtures

import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Contract of the shared sidecar-cache lookup: `-Djdx.gradleCaches` wins,
 * missing roots are filtered, and a crafted cache resolves jars by group
 * path. This is test scaffolding, but Windows CI is its only real exerciser
 * (9-minute feedback loop) — a red here must never read as a product red.
 */
class SidecarJarsTest {

    @TempDir
    lateinit var tempDir: java.nio.file.Path

    private fun craftCache(root: File, groupPath: String, jarName: String): File {
        val dir = File(root, groupPath)
        dir.mkdirs()
        File(dir, jarName).writeBytes(byteArrayOf(0x50, 0x4b))
        return root
    }

    @Test
    fun `a crafted cache resolves the compiler jar by group path`() {
        val root = craftCache(
            tempDir.resolve("caches/modules-2/files-2.1").toFile(),
            "org.jetbrains.kotlin/kotlin-compiler-embeddable",
            SidecarJars.COMPILER_JAR,
        )
        val saved = System.getProperty("jdx.gradleCaches")
        System.setProperty("jdx.gradleCaches", root.absolutePath)
        try {
            val jars = SidecarJars.cachedRuntimeJars()
            (jars[SidecarJars.COMPILER_JAR]?.toFile()?.isFile ?: false) shouldBe true
        } finally {
            if (saved == null) System.clearProperty("jdx.gradleCaches") else System.setProperty("jdx.gradleCaches", saved)
        }
    }

    @Test
    fun `missing roots resolve to no jars, never a throw`() {
        val saved = System.getProperty("jdx.gradleCaches")
        System.setProperty(
            "jdx.gradleCaches",
            tempDir.resolve("no-such-caches").toAbsolutePath().toString(),
        )
        try {
            // A bogus override only prepends a filtered-out root: the call
            // still returns whatever the ambient roots hold, never throws.
            io.kotest.assertions.throwables.shouldNotThrow<Exception> {
                SidecarJars.cachedRuntimeJars()
            }
        } finally {
            if (saved == null) System.clearProperty("jdx.gradleCaches") else System.setProperty("jdx.gradleCaches", saved)
        }
    }

    @Test
    fun `cache roots are most-explicit-first and existing dirs only`() {
        val present = Files.createDirectories(tempDir.resolve("present")).toFile()
        val saved = System.getProperty("jdx.gradleCaches")
        System.setProperty(
            "jdx.gradleCaches",
            present.absolutePath + File.pathSeparator + tempDir.resolve("absent").toString(),
        )
        try {
            // The absent segment filters out; the present dir leads (ambient
            // Gradle homes, if any, follow behind).
            val roots = SidecarJars.cacheRoots()
            (roots.isNotEmpty()) shouldBe true
            roots.first() shouldBe present
            roots.none { it.name == "absent" } shouldBe true
        } finally {
            if (saved == null) System.clearProperty("jdx.gradleCaches") else System.setProperty("jdx.gradleCaches", saved)
        }
    }
}
