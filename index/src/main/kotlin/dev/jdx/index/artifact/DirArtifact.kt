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

    /** Base `META-INF/services/<iface>` paths under [dir] (issue #25). */
    private val serviceEntries: List<String>

    /** Whether [dir] holds a root `module-info.class` (issue #25 module-info). */
    private val hasModuleInfo: Boolean

    /** Whether [dir] holds a base `META-INF/MANIFEST.MF` (issue #25 manifest). */
    private val hasManifest: Boolean

    /** Non-class resource paths under [dir] (issue #25 resource-listing). */
    private val resourceEntries: List<String>

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
        val services = mutableListOf<String>()
        val resources = mutableListOf<String>()
        var moduleInfo = false
        var manifest = false
        Files.walk(dir).use { walk ->
            walk.filter { Files.isRegularFile(it) }.forEach { file ->
                val relative = dir.relativize(file).toString().replace('\\', '/')
                val normal = ZipSafety.normalizeEntryName(relative) ?: return@forEach
                when {
                    normal == "module-info.class" -> moduleInfo = true
                    normal.endsWith(".class") -> found.add(normal)
                    else -> {
                        resources.add(normal)
                        if (ServiceEntries.isServicePath(normal)) services.add(normal)
                        if (ManifestEntries.isManifestPath(normal)) manifest = true
                    }
                }
            }
        }
        ZipSafety.checkEntryCount(
            found.size + resources.size + (if (moduleInfo) 1 else 0),
            displayName,
        )
        entries = found.sorted()
        serviceEntries = services.sorted()
        resourceEntries = resources.sorted()
        hasModuleInfo = moduleInfo
        hasManifest = manifest
    }

    override fun classEntryPaths(): List<String> = entries

    override fun openClass(path: String): InputStream {
        val normal = ZipSafety.normalizeEntryName(path)
            ?: throw ArtifactReadException("artifact read error: rejected unsafe entry name: $path")
        if (normal !in entries) {
            throw ArtifactReadException("artifact read error: $displayName has no class $path")
        }
        return openContainedFile(normal, path)
    }

    override fun serviceEntryPaths(): List<String> = serviceEntries

    override fun openResource(path: String): InputStream {
        val normal = ZipSafety.normalizeEntryName(path)
            ?: throw ArtifactReadException("artifact read error: rejected unsafe entry name: $path")
        if (normal !in serviceEntries) {
            throw ArtifactReadException("artifact read error: $displayName has no resource $path")
        }
        return openContainedFile(normal, path)
    }

    override fun moduleInfoPaths(): List<String> =
        if (hasModuleInfo) listOf("module-info.class") else emptyList()

    override fun openModuleInfo(path: String): InputStream {
        val normal = ZipSafety.normalizeEntryName(path)
            ?: throw ArtifactReadException("artifact read error: rejected unsafe entry name: $path")
        if (normal != "module-info.class" || !hasModuleInfo) {
            throw ArtifactReadException("artifact read error: $displayName has no module-info $path")
        }
        return openContainedFile(normal, path)
    }

    override fun manifestPaths(): List<String> =
        if (hasManifest) listOf(ManifestEntries.PATH) else emptyList()

    override fun resourceEntryPaths(): List<String> = resourceEntries

    override fun openManifest(path: String): InputStream {
        val normal = ZipSafety.normalizeEntryName(path)
            ?: throw ArtifactReadException("artifact read error: rejected unsafe entry name: $path")
        if (!ManifestEntries.isManifestPath(normal) || !hasManifest) {
            throw ArtifactReadException("artifact read error: $displayName has no manifest $path")
        }
        return openContainedFile(normal, path)
    }

    /**
     * Opens a normalised relative path that was already proven to sit under
     * [dir] during the walk. Re-checks containment so a future refactor cannot
     * turn this into a path escape.
     */
    private fun openContainedFile(normal: String, requested: String): InputStream {
        val file = dir.resolve(normal).normalize()
        if (!file.startsWith(dir)) {
            throw ArtifactReadException("artifact read error: rejected unsafe entry name: $requested")
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
