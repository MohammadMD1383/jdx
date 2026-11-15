package dev.jdx.index.artifact

import java.io.ByteArrayInputStream
import java.util.jar.Attributes
import java.util.jar.Manifest

/**
 * One per-entry (JarEntry) attribute row from a jar manifest: the entry path
 * (`Name:` section), the attribute name, and its value.
 */
public data class ManifestEntryAttribute(
    public val entry: String,
    public val name: String,
    public val value: String,
)

/**
 * `META-INF/MANIFEST.MF` path helpers and attribute parsing (issue #25
 * manifest + per-entry slices). Pure — no filesystem IO beyond the bytes
 * handed in — so jar and directory roots share one parser and tier-1 tests
 * can feed hostile bytes without opening a zip.
 *
 * Only the **base** `META-INF/MANIFEST.MF` is servable: versioned copies under
 * `META-INF/versions/` are ignored (the JVM reads the jar-root manifest; a
 * multi-release flag lives there).
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
            flattenAttributes(manifest.mainAttributes).sortedBy { it.first }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Parses per-entry (JarEntry) attribute sections from [bytes]. Returns
     * rows sorted by entry path, then attribute name (D-007). Never throws —
     * corrupt bytes yield an empty list. The `Name:` key itself is the map
     * key (Java `Manifest.entries`) and is not repeated as an attribute row.
     * Empty attribute sections are skipped.
     */
    public fun parseEntryAttributes(bytes: ByteArray): List<ManifestEntryAttribute> {
        if (bytes.isEmpty()) return emptyList()
        return try {
            val manifest = Manifest(ByteArrayInputStream(bytes))
            val hits = mutableListOf<ManifestEntryAttribute>()
            for ((entry, attrs) in manifest.entries) {
                if (entry.isNullOrEmpty() || attrs == null) continue
                for ((name, value) in flattenAttributes(attrs)) {
                    hits.add(ManifestEntryAttribute(entry = entry, name = name, value = value))
                }
            }
            hits.sortedWith(compareBy({ it.entry }, { it.name }))
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Flattens one [Attributes] map into `(name, value)` pairs; never throws. */
    private fun flattenAttributes(attrs: Attributes): List<Pair<String, String>> {
        val hits = mutableListOf<Pair<String, String>>()
        for ((key, value) in attrs) {
            val name = when (key) {
                is Attributes.Name -> key.toString()
                else -> key?.toString() ?: continue
            }
            if (name.isEmpty()) continue
            hits.add(name to (value?.toString() ?: ""))
        }
        return hits
    }
}
