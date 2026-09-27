package dev.jdx.diffapi;

/**
 * Target for the modifier and visibility rules: v2 adds {@code final}, narrows
 * {@code #opened()} to package-private, and makes {@code #guarded()} synchronized.
 *
 * <p>Never loaded into a test JVM (D-017).
 */
public class Tuned {

    /** Target for {@code MEMBER_VISIBILITY_NARROWED}. */
    public String opened() {
        return "opened";
    }

    /** Target for {@code SYNCHRONIZED_ADDED}. */
    public String guarded() {
        return "guarded";
    }

    /** Target for {@code TRANSIENT_CHANGED}. */
    public transient String scratch = "scratch";

    /** Target for {@code TYPE_MADE_FINAL}. */
    public String kept() {
        return "kept";
    }
}
