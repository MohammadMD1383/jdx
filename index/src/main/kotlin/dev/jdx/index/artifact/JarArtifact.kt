package dev.jdx.index.artifact

import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import dev.jdx.sources.SourceRoot
import dev.jdx.sources.openSourceRoot
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Manifest
import java.util.zip.ZipFile

/**
 * An [ArtifactRoot] over one `.jar`/`.zip` file (PROPOSAL.md §5.1 `BinaryJar`).
 *
 * Opened eagerly: entry names are normalised, traversal entries dropped, caps checked,
 * and multi-release variants resolved once, so every later read is a map lookup plus a
 * bounded stream. Holds a [ZipFile] open until [close] — create one per use and close it.
 */
public class JarArtifact private constructor(
    private val jarPath: Path,
    private val explicitSources: Path?,
) : ArtifactRoot {

    private val zip: ZipFile = ZipFile(jarPath.toFile())

    /** Servable base path → actual entry name (a versioned variant when one won). */
    private val resolved: Map<String, String>

    /**
     * Base `META-INF/services/<iface>` path → actual zip entry name (issue #25).
     * Kept separately from [resolved]: multi-release resolution only keeps
     * `.class` entries and drops bare `META-INF/` paths.
     */
    private val serviceEntries: Map<String, String>

    /**
     * Servable `module-info.class` base path → actual zip entry (issue #25
     * module-info slice). Drawn from [resolved] so multi-release selection
     * matches what the JVM would load.
     */
    private val moduleInfoEntry: String?

    /**
     * Whether this jar holds a base `META-INF/MANIFEST.MF` (issue #25
     * manifest slice). Kept separately from [resolved]: multi-release
     * resolution only keeps `.class` entries.
     */
    private val hasManifest: Boolean

    /**
     * Every non-class resource path in this jar (issue #25 resource-listing
     * slice), including versioned `META-INF/versions/…` copies as stored.
     */
    private val resourceEntries: List<String>

    override val displayName: String = jarPath.fileName.toString()

    override val kind: ArtifactKind = ArtifactKind.BINARY_JAR

    /** The jar file itself, for the decompiler's library context (T-026). */
    override val libraryPath: Path = jarPath

    override val warnings: List<Warning>

    /** The sources pairing for this jar (PROPOSAL.md §5.2). Never null, possibly absent. */
    public val sourcesPair: SourcesPair = SourcesPairing.pair(jarPath, explicitSources)

    /**
     * Opens the paired sources as a [SourceRoot] (T-022): the external
     * `-sources.jar` when paired, the binary jar itself when it embeds sources,
     * `null` when absent. A paired file that vanished or fails to open reads as
     * `null` — a missing pair is routine (D-009), never an error. The caller
     * closes the root.
     */
    public fun openSources(): SourceRoot? = when (val pair = sourcesPair) {
        is SourcesPair.External -> runCatching { openSourceRoot(pair.path) }.getOrNull()
        is SourcesPair.Embedded -> runCatching { openSourceRoot(jarPath) }.getOrNull()
        is SourcesPair.Absent -> null
    }

    init {
        val names = mutableSetOf<String>()
        var knownBytes = 0L
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val normal = ZipSafety.normalizeEntryName(entry.name) ?: continue
            names.add(if (entry.isDirectory) "$normal/" else normal)
            if (!entry.isDirectory && entry.size >= 0) knownBytes += entry.size
        }
        ZipSafety.checkEntryCount(names.size, displayName)
        ZipSafety.checkTotalSize(knownBytes, displayName)
        val files = names.filter { !it.endsWith("/") }.toSet()
        resolved = MultiRelease.resolve(
            actualEntries = files,
            multiRelease = isMultiRelease(zip),
        )
        // Classic SPI files only — not META-INF/versions/<n>/META-INF/services/…
        serviceEntries = files
            .filter { ServiceEntries.isServicePath(it) }
            .associateWith { it }
        moduleInfoEntry = resolved["module-info.class"]
        hasManifest = files.any { ManifestEntries.isManifestPath(it) }
        resourceEntries = files.filter { ResourceEntries.isResourcePath(it) }.sorted()
        warnings = buildList {
            if (MultiRelease.hasVersionedSelection(resolved)) {
                add(
                    Warning(
                        code = WarningCode.MULTI_RELEASE_VARIANT,
                        message = "multi-release $displayName serves " +
                            "version-specific variants for this JDK " +
                            "(${Runtime.version().feature()})",
                        subject = displayName,
                    ),
                )
            }
        }.sortedBy { it.code }
    }

    override fun classEntryPaths(): List<String> =
        resolved.keys
            .filter { it.endsWith(".class") && it != "module-info.class" }
            .sorted()

    override fun openClass(path: String): InputStream {
        val normal = ZipSafety.normalizeEntryName(path)
            ?: throw ArtifactReadException("artifact read error: rejected unsafe entry name: $path")
        val actual = resolved[normal]
            ?: throw ArtifactReadException("artifact read error: $displayName has no class $path")
        val entry = zip.getEntry(actual)
            ?: throw ArtifactReadException("artifact read error: $displayName!$actual vanished")
        ZipSafety.checkDeclaredSize(actual, entry.size, displayName)
        val bytes = zip.getInputStream(entry).use { ZipSafety.readCapped(it, actual) }
        return bytes.inputStream()
    }

    override fun serviceEntryPaths(): List<String> = serviceEntries.keys.sorted()

    override fun openResource(path: String): InputStream {
        val normal = ZipSafety.normalizeEntryName(path)
            ?: throw ArtifactReadException("artifact read error: rejected unsafe entry name: $path")
        val actual = serviceEntries[normal]
            ?: throw ArtifactReadException("artifact read error: $displayName has no resource $path")
        val entry = zip.getEntry(actual)
            ?: throw ArtifactReadException("artifact read error: $displayName!$actual vanished")
        ZipSafety.checkDeclaredSize(actual, entry.size, displayName)
        val bytes = zip.getInputStream(entry).use { ZipSafety.readCapped(it, actual) }
        return bytes.inputStream()
    }

    override fun moduleInfoPaths(): List<String> =
        if (moduleInfoEntry != null) listOf("module-info.class") else emptyList()

    override fun openModuleInfo(path: String): InputStream {
        val normal = ZipSafety.normalizeEntryName(path)
            ?: throw ArtifactReadException("artifact read error: rejected unsafe entry name: $path")
        if (normal != "module-info.class") {
            throw ArtifactReadException("artifact read error: $displayName has no module-info $path")
        }
        val actual = moduleInfoEntry
            ?: throw ArtifactReadException("artifact read error: $displayName has no module-info $path")
        val entry = zip.getEntry(actual)
            ?: throw ArtifactReadException("artifact read error: $displayName!$actual vanished")
        ZipSafety.checkDeclaredSize(actual, entry.size, displayName)
        val bytes = zip.getInputStream(entry).use { ZipSafety.readCapped(it, actual) }
        return bytes.inputStream()
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
        val entry = zip.getEntry(ManifestEntries.PATH)
            ?: throw ArtifactReadException("artifact read error: $displayName!${ManifestEntries.PATH} vanished")
        ZipSafety.checkDeclaredSize(ManifestEntries.PATH, entry.size, displayName)
        val bytes = zip.getInputStream(entry).use { ZipSafety.readCapped(it, ManifestEntries.PATH) }
        return bytes.inputStream()
    }

    override fun stableId(): String = ArtifactHash.hashFile(jarPath)

    override fun close(): Unit = zip.close()

    internal companion object {
        private fun isMultiRelease(zip: ZipFile): Boolean {
            val entry = zip.getEntry("META-INF/MANIFEST.MF") ?: return false
            return try {
                zip.getInputStream(entry).use { Manifest(it) }
                    .mainAttributes.getValue("Multi-Release")
                    .equals("true", ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Opens [jar] as an artifact root. Called from [ArtifactLoader]; kept here so
         * every root is created through the one dispatch point.
         */
        internal fun open(jar: Path, explicitSources: Path?): JarArtifact {
            if (!Files.isRegularFile(jar)) {
                throw ArtifactReadException("artifact read error: no such jar: $jar")
            }
            try {
                return JarArtifact(jar.toAbsolutePath().normalize(), explicitSources)
            } catch (ex: ArtifactReadException) {
                throw ex
            } catch (ex: Exception) {
                throw ArtifactReadException("artifact read error: cannot open jar $jar", ex)
            }
        }
    }
}
