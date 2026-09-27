package dev.jdx.diffapi;

/**
 * Deliberately **identical** in both versions of the diff fixture pair.
 *
 * <p>This is the negative control: a rule that fires on an unchanged type is a false
 * alarm, and a false alarm in an upgrade report is how an agent decides not to take an
 * upgrade for nothing. The file is byte-for-byte the same in `src/diffv1` and
 * `src/diffv2`, so the pair's rule-coverage test can assert that this type is never
 * named.
 *
 * <p>Never loaded into a test JVM (D-017).
 */
public class Stable {

    /** A public constant: a `final` static field on both sides. */
    public static final String NAME = "stable";

    /** Unchanged. */
    public String describe() {
        return NAME;
    }

    /** Unchanged, and deliberately not a universal-root override, so it must stay put. */
    public String toString() {
        return NAME;
    }
}
