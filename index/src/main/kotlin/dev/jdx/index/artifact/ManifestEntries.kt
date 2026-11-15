package dev.jdx.index.artifact

import java.io.ByteArrayInputStream
import java.util.jar.Attributes
import java.util.jar.Manifest

/**
 * `META-INF/MANIFEST.MF` path helpers and main-attribute parsing (issue #25
 * manifest slice). Pure — no filesystem IO beyond the bytes handed in — so jar
 * and directory roots share one parser and tier-1 tests can feed hostile bytes
 * without opening a zip.
 *
 * Only the **base** `META-INF/MANIFEST.MF` is servable: versioned copies under
 * `META-INF/versions/` are ignored (the JVM reads the jar-root manifest; a
 * multi-release flag lives there). Per-entry (JarEntry) attributes are out of
 * this slice.
 */
public object ManifestEntries {

    /** The single classic jar-manifest path this slice serves. */
    public const val PATH: String = "META-INF/MANIFEST.MF"

    /**
     * `true` when [path] is the base (non-versioned) jar manifest entry.
     */
    public fun isManifestPath(path: String): Boolean =
        path == PATH && !path.startsWith(MultiRelease.VERSIONS_PREFIX)

    /**
     * Parses main-section attributes from [bytes]. Returns `(name, value)`
     * pairs sorted by name (D-007). Never throws — corrupt bytes yield an
     * empty list (degrade, don't fail). Empty values are kept when the
     * attribute is present; the `Manifest-Version` key is included when set.
     *
     * Attribute names are printed as [Attributes.Name] would (`Main-Class`,
     * not `main-class`) so output matches what `jar tf` / `unzip -p` show.
     */
    public fun parseMainAttributes(bytes: ByteArray): List<Pair<String, String>> {
        if (bytes.isEmpty()) return emptyList()
        return try {
            val manifest = Manifest(ByteArrayInputStream(bytes))
            val attrs = manifest.mainAttributes
            val hits = mutableListOf<Pair<String, String>>()
            for ((key, value) in attrs) {
                val name = when (key) {
                    is Attributes.Name -> key.toString()
                    else -> key?.toString() ?: continue
                }
                if (name.isEmpty()) continue
                val text = value?.toString() ?: ""
                hits.add(name to text)
            }
            hits.sortedBy { it.first }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
