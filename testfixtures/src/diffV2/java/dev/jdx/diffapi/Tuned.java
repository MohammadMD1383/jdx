package dev.jdx.diffapi;

/**
 * Target for the modifier and visibility rules, twin of {@code dev.jdx.diffapi.Tuned}:
 * the class is {@code final}, {@link #opened()} is package-private,
 * {@link #guarded()} is synchronized, and {@link #scratch} is no longer transient.
 *
 * <p>Never loaded into a test JVM (D-017).
 */
public final class Tuned {

    /** Target for {@code MEMBER_VISIBILITY_NARROWED}. */
    String opened() {
        return "opened";
    }

    /** Target for {@code SYNCHRONIZED_ADDED}. */
    public synchronized String guarded() {
        return "guarded";
    }

    /** Target for {@code TRANSIENT_CHANGED}. */
    public String scratch = "scratch";

    /** Unchanged, so the class must report only the three differences above. */
    public String kept() {
        return "kept";
    }
}
