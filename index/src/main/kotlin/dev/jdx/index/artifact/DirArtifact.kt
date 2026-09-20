package dev.jdx.index.artifact

import dev.jdx.core.model.Warning
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * An [ArtifactRoot] over a directory of compiled classes (`build/classes/java/main`, …).
 *
 * Entries are slash-separated paths relative to [dir], so a class dir and the jar built
 * from it list identical entry names. Reads go straight to the filesystem — no zip
 * handling, no traversal risk beyond the [openClass] containment check kept as defence in
 * depth.
 */
public class DirArtifact private constructor(
    private val dir: Path,
) : ArtifactRoot {

    private val entries: List<String>

    override val displayName: String = dir.fileName?.toString() ?: dir.toString()

    override val kind: ArtifactKind = ArtifactKind.CLASS_DIR

    /** The class directory itself, for the decompiler's library context (T-026). */
    override val libraryPath: Path = dir

    override val warnings: List<Warning> = emptyList()

    init {
        if (!Files.isDirectory(dir)) {
            throw ArtifactReadException("artifact read error: no such class directory: $dir")
        }
        val found = mutableListOf<String>()
        Files.walk(dir).use { walk ->
            walk.filter { Files.isRegularFile(it) }.forEach { file ->
                val relative = dir.relativize(file).toString().replace('\\', '/')
                val normal = ZipSafety.normalizeEntryName(relative) ?: return@forEach
                if (normal.endsWith(".class") && normal != "module-info.class") {
                    found.add(normal)
                }
            }
        }
        ZipSafety.checkEntryCount(found.size, displayName)
        entries = found.sorted()
    }

    override fun classEntryPaths(): List<String> = entries

    override fun openClass(path: String): InputStream {
        val normal = ZipSafety.normalizeEntryName(path)
            ?: throw ArtifactReadException("artifact read error: rejected unsafe entry name: $path")
        if (normal !in entries) {
            throw ArtifactReadException("artifact read error: $displayName has no class $path")
        }
        // Containment holds by construction (walk + normalisation), re-checked so a
        // future refactor cannot turn this into a path escape.
        val file = dir.resolve(normal).normalize()
        if (!file.startsWith(dir)) {
            throw ArtifactReadException("artifact read error: rejected unsafe entry name: $path")
        }
        if (!Files.isRegularFile(file)) {
            throw ArtifactReadException("artifact read error: $displayName!$normal vanished")
        }
        ZipSafety.checkDeclaredSize(normal, Files.size(file), displayName)
        val bytes = Files.newInputStream(file).use { ZipSafety.readCapped(it, normal) }
        return bytes.inputStream()
    }

    override fun stableId(): String = ArtifactHash.hashDirectory(dir)

    override fun close(): Unit = Unit

    internal companion object {
        /** Called from [ArtifactLoader]; kept here so roots share one dispatch point. */
        internal fun open(dir: Path): DirArtifact {
            try {
                return DirArtifact(dir.toAbsolutePath().normalize())
            } catch (ex: ArtifactReadException) {
                throw ex
            } catch (ex: Exception) {
                throw ArtifactReadException("artifact read error: cannot open class dir $dir", ex)
            }
        }
    }
}
