package dev.jdx.index.artifact

import dev.jdx.testsupport.fixtures.FixtureJars
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Shared helpers for the tier-2 artifact tests (T-007). Kept separate from the pure
 * tier-1 suites: everything here touches real jars on disk.
 */
internal object ArtifactTestJars {

    /**
     * The compiled corpus jar. Delegates to the shared [FixtureJars] rather than carrying a
     * fifth copy of the walk-up resolver (#66): a resolver duplicated four ways is four
     * chances to drift away from the build wiring that feeds it, and that drift is exactly
     * what made the mutation tier fail on a clean checkout for a long time.
     */
    fun binaryJar(dir: File = FixtureJars.fixturesDir()): File = FixtureJars.binaryJar(dir)

    /** Raw bytes of one fixture class — a zip read, never a class load (D-017). */
    fun fixtureClassBytes(jar: File, entry: String): ByteArray {
        val zip = java.util.zip.ZipFile(jar)
        return zip.use {
            val zipEntry = it.getEntry(entry) ?: error("no such fixture class: $entry")
            it.getInputStream(zipEntry).readBytes()
        }
    }

    /**
     * Writes a jar of literal [entries] (`name to bytes`). Entry names are written
     * verbatim — including `../` traversal names — so hostile jars can be built exactly.
     */
    fun craftJar(jar: Path, entries: Map<String, ByteArray>): Path {
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

    /** Minimal manifest bytes; [multiRelease] adds the `Multi-Release: true` attribute. */
    fun manifestBytes(multiRelease: Boolean): ByteArray {
        val body = buildString {
            append("Manifest-Version: 1.0\r\n")
            if (multiRelease) append("Multi-Release: true\r\n")
            append("\r\n")
        }
        return body.toByteArray(Charsets.UTF_8)
    }
}
