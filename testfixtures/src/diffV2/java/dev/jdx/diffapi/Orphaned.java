package dev.jdx.diffapi;

/**
 * Target for the un-checkable-inheritance path, twin of {@code dev.jdx.diffapi.Orphaned}:
 * the named supertype is in **neither** artifact, and {@link #gone()} is removed, so the
 * differ must keep the removal BREAKING and name the supertype it could not read rather
 * than assume nothing was inherited (AGENTS.md §2.2).
 *
 * <p>Never loaded into a test JVM (D-017) — the class is deliberately not resolvable.
 */
@SuppressWarnings("unused")
public class Orphaned extends dev.jdx.absent.Supertype {

    /** Kept, so the only finding on this class is the un-checkable one. */
    public String kept() {
        return "kept";
    }
}
