package dev.jdx.index.artifact

import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import dev.jdx.sources.SourceRoot
import dev.jdx.sources.openSourceRoot
import java.io.InputStream
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/**
 * An [ArtifactRoot] over the running JDK, read through the `jrt:/` filesystem
 * (PROPOSAL.md §5.1 `JrtModules`).
 *
 * Entries are module-stripped slash paths (`java/lang/Object.class`), so JDK classes list
 * and open exactly like jar classes. When two modules ship the same path (split packages),
 * the alphabetically first module wins and one `DUPLICATE_FQN` warning names them all —
 * the same shadowing rule workspaces apply to jars.
 *
 * [javaHome] is `java.home`; [envJavaHome] is `$JAVA_HOME` when it names a different
 * directory. [jdkSources] is the first `$home/lib/src.zip` found across the two
 * ([JdkLayout.findSrcZip]), `null` when neither ships it (some distributions omit it —
 * routine, surfaced as a `doctor` WARN row, never an error here).
 */
public class JrtArtifact private constructor(
    private val javaHome: Path,
    envJavaHome: Path?,
) : ArtifactRoot {

    private val moduleByPath: Map<String, String>

    override val displayName: String = "jrt:/"

    override val kind: ArtifactKind = ArtifactKind.JRT

    override val warnings: List<Warning>

    /** `$home/lib/src.zip` when present, `null` when the distribution omits it. */
    public val jdkSources: Path? =
        JdkLayout.findSrcZip(javaHome, envJavaHome)

    /**
     * Opens `src.zip` as a [SourceRoot] (T-022), or `null` when the distribution
     * ships none (routine — surfaced as a `doctor` WARN row, never an error
     * here). The caller closes the root.
     */
    public fun openSources(): SourceRoot? =
        jdkSources?.let { runCatching { openSourceRoot(it) }.getOrNull() }

    init {
        val fileSystem = FileSystems.getFileSystem(URI.create("jrt:/"))
        val found = mutableMapOf<String, String>()
        val duplicates = mutableMapOf<String, MutableSet<String>>()
        Files.newDirectoryStream(fileSystem.getPath("/modules")).use { modules ->
            modules.sortedBy { it.fileName.toString() }.forEach { module ->
                val moduleName = module.fileName.toString()
                Files.walk(module).use { walk ->
                    walk.filter { Files.isRegularFile(it) }.forEach { file ->
                        val relative = module.relativize(file).toString().replace('\\', '/')
                        val normal = ZipSafety.normalizeEntryName(relative) ?: return@forEach
                        if (!normal.endsWith(".class") || normal == "module-info.class") {
                            return@forEach
                        }
                        val previous = found.putIfAbsent(normal, moduleName)
                        if (previous != null && previous != moduleName) {
                            duplicates.getOrPut(normal) { mutableSetOf(previous) }.add(moduleName)
                        }
                    }
                }
            }
        }
        ZipSafety.checkEntryCount(found.size, displayName)
        moduleByPath = found.toSortedMap()
        warnings = buildList {
            if (duplicates.isNotEmpty()) {
                val sample = duplicates.entries.sortedBy { it.key }.take(5)
                    .joinToString("; ") { (path, mods) -> "$path in ${mods.sorted()}" }
                add(
                    Warning(
                        code = WarningCode.DUPLICATE_FQN,
                        message = "${duplicates.size} JDK classes are split across modules " +
                            "(e.g. $sample); first module wins",
                        subject = displayName,
                    ),
                )
            }
        }.sortedBy { it.code }
    }

    override fun classEntryPaths(): List<String> = moduleByPath.keys.sorted()

    /**
     * The JDK module serving [path], or `null` when the path is not in the JDK.
     * T-012 reports this as the artifact (e.g. `java.base`).
     */
    public fun moduleForClass(path: String): String? {
        val normal = ZipSafety.normalizeEntryName(path) ?: return null
        return moduleByPath[normal]
    }

    override fun openClass(path: String): InputStream {
        val normal = ZipSafety.normalizeEntryName(path)
            ?: throw ArtifactReadException("artifact read error: rejected unsafe entry name: $path")
        val module = moduleByPath[normal]
            ?: throw ArtifactReadException("artifact read error: jrt:/ has no class $path")
        val fileSystem = FileSystems.getFileSystem(URI.create("jrt:/"))
        val file = fileSystem.getPath("/modules", module, *normal.split('/').toTypedArray())
        if (!Files.isRegularFile(file)) {
            throw ArtifactReadException("artifact read error: jrt:/$module!$normal vanished")
        }
        ZipSafety.checkDeclaredSize("jrt:/$module!$normal", Files.size(file), displayName)
        val bytes = Files.newInputStream(file).use { ZipSafety.readCapped(it, normal) }
        return bytes.inputStream()
    }

    override fun stableId(): String =
        "jrt-" + System.getProperty("java.runtime.version", Runtime.version().toString())

    override fun close(): Unit = Unit

    internal companion object {
        /** Called from [ArtifactLoader]; kept here so roots share one dispatch point. */
        internal fun open(javaHome: Path, envJavaHome: Path? = JdkLayout.envJavaHome()): JrtArtifact {
            try {
                return JrtArtifact(javaHome.toAbsolutePath().normalize(), envJavaHome)
            } catch (ex: ArtifactReadException) {
                throw ex
            } catch (ex: Exception) {
                throw ArtifactReadException("artifact read error: cannot open jrt:/", ex)
            }
        }
    }
}
