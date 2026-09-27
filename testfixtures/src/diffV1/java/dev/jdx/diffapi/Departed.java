package dev.jdx.diffapi;

/**
 * Target for {@code TYPE_REMOVED}: this whole type is gone in v2.
 *
 * <p>Never loaded into a test JVM (D-017).
 */
public class Departed {

    /** Never reached, never loaded — the bytes are only read. */
    public String value() {
        return "departed";
    }
}
