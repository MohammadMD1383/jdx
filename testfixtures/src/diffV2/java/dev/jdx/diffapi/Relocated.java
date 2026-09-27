package dev.jdx.diffapi;

/**
 * Target for {@code MEMBER_MOVED_TO_SUPERTYPE}: extends {@link Base} and no longer
 * declares {@code shared()}, which v1's {@code Relocated} did.
 *
 * <p>Never loaded into a test JVM (D-017).
 */
public class Relocated extends Base {

    /** Stays put, so the class is not uniformly different and must report exactly one move. */
    public String stays() {
        return "stays";
    }
}
