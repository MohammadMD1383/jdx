package dev.jdx.index.artifact

/**
 * Non-class resource path helpers (issue #25 resource-listing slice). Pure —
 * no IO — so jar and directory roots share one predicate and tier-1 tests can
 * feed hostile names without opening a zip.
 *
 * A **resource** is any normalised file entry that is not a `.class` file.
 * That includes `META-INF/MANIFEST.MF`, `META-INF/services/…`,
 * `.properties`, XML, and versioned copies under `META-INF/versions/` —
 * listing shows the path as stored (multi-release selection is left to
 * class loading; this slice does not collapse variants). Directories and
 * `.class` entries are never resources (use `search` / `ls` / `modules`
 * for those). Content extraction is a later slice — this object only
 * classifies paths.
 */
public object ResourceEntries {

    /**
     * `true` when [path] is a servable non-class file entry. Directories
     * (trailing `/`) and `.class` files are excluded. Empty / blank paths
     * are rejected.
     */
    public fun isResourcePath(path: String): Boolean {
        if (path.isEmpty() || path.endsWith("/")) return false
        if (path.endsWith(".class")) return false
        return true
    }
}
