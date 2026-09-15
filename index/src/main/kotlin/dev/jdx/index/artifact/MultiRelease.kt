package dev.jdx.index.artifact

/**
 * Multi-release jar variant selection (T-007 acceptance).
 *
 * A jar whose manifest carries `Multi-Release: true` may ship version-specific classes under
 * `META-INF/versions/<n>/…`. For each base path present in both places, the JVM loads the
 * highest-numbered variant at or below the running release — so `jdx` must serve the same
 * bytes, or its answers disagree with what the JVM would load. Whenever at least one
 * versioned variant wins, the loader emits `MULTI_RELEASE_VARIANT` (D-009's labelling habit
 * applied to bytecode, not just sources).
 *
 * Pure function of entry names: no IO, tier-1 testable.
 */
public object MultiRelease {

    /** Entry prefix below which versioned variants live. Never listed as classes itself. */
    public const val VERSIONS_PREFIX: String = "META-INF/versions/"

    /**
     * Resolves every servable class path to the actual entry holding its bytes.
     *
     * @param actualEntries every normalised entry name in the jar.
     * @param multiRelease `true` only when the jar manifest says `Multi-Release: true` —
     *   without it a `META-INF/versions/` tree is dead weight and is ignored.
     * @param runtimeVersion the running release, defaulting to this JVM's
     *   (`Runtime.version().feature()`).
     * @return map from servable base path (`com/foo/Bar.class`) to the entry name to read
     *   (the base path itself, or its winning `META-INF/versions/<n>/…` variant).
     */
    public fun resolve(
        actualEntries: Set<String>,
        multiRelease: Boolean,
        runtimeVersion: Int = Runtime.version().feature(),
    ): Map<String, String> {
        val base = mutableMapOf<String, String>()
        // Highest-numbered applicable variant per base path.
        val variants = mutableMapOf<String, Pair<Int, String>>()
        for (entry in actualEntries) {
            if (!entry.endsWith(".class")) continue
            if (entry.startsWith(VERSIONS_PREFIX)) {
                if (!multiRelease) continue
                val rest = entry.removePrefix(VERSIONS_PREFIX)
                val slash = rest.indexOf('/')
                if (slash < 0) continue
                val release = rest.substring(0, slash).toIntOrNull() ?: continue
                // JVMS multi-release floor: versioned classes start at 9.
                if (release < 9 || release > runtimeVersion) continue
                val basePath = rest.substring(slash + 1)
                if (basePath.isEmpty()) continue
                val current = variants[basePath]
                if (current == null || release > current.first) {
                    variants[basePath] = release to entry
                }
            } else {
                if (entry.startsWith("META-INF/")) continue
                base[entry] = entry
            }
        }
        // A variant shadows its base entry only; a variant with no base entry is still
        // servable under the base path (a class that exists solely for newer releases).
        for ((basePath, entry) in base) {
            variants.putIfAbsent(basePath, -1 to entry)
        }
        return variants.mapValues { it.value.second }
    }

    /**
     * `true` when [resolved] serves at least one versioned variant — the condition for
     * emitting `MULTI_RELEASE_VARIANT`.
     */
    public fun hasVersionedSelection(resolved: Map<String, String>): Boolean =
        resolved.values.any { it.startsWith(VERSIONS_PREFIX) }
}
