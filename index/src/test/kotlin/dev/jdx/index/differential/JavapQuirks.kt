package dev.jdx.index.differential

/**
 * The single allowlist of known `javap`-vs-`jdx` disagreements (T-056).
 *
 * Every entry here is a *documented, deliberate* difference — not a bug we
 * have not fixed yet. Each entry names the shape, says which side reports it,
 * cites the production code that owns the behaviour, and is applied in exactly
 * one place ([applyToJavapSet]). An unexplained entry in this file is a review
 * blocker: if the difference is not deliberate, it belongs in a bug report,
 * not here.
 */
internal object JavapQuirks {

    /**
     * `javap -p -s` reports the class initialiser as `static {};` with
     * `descriptor: ()V`; `jdx members` never lists `<clinit>` at any access
     * level — it is not a callable member an agent can invoke, so the resolver
     * drops it before filtering ever sees it
     * (`core/.../resolve/MemberResolver.kt`: `if (method.name == "<clinit>")
     * continue // never a callable member`). The reader-level differential
     * (T-008) still compares `<clinit>` exactly — both `javap` and ASM see it —
     * so nothing about the class file goes unverified; only the *listing*
     * comparison excludes it.
     */
    internal val CLINIT = Javap.MemberKey("<clinit>", "()V")

    /**
     * Removes allowlisted members from a parsed `javap` set before it is
     * compared to a `jdx members` listing. Returns the filtered set plus the
     * names of the quirks that actually fired, so mismatch reports — and the
     * soak log — show which allowances were taken for each class.
     */
    internal fun applyToJavapSet(members: Set<Javap.MemberKey>): AppliedQuirks {
        val applied = mutableListOf<String>()
        var remaining = members
        if (CLINIT in remaining) {
            remaining = remaining - CLINIT
            applied.add("<clinit>-excluded (javap-only class initialiser)")
        }
        return AppliedQuirks(remaining, applied)
    }

    internal data class AppliedQuirks(
        val members: Set<Javap.MemberKey>,
        val applied: List<String>,
    )
}
