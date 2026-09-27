package dev.jdx.diffapi;

/**
 * Target for {@code TYPE_KIND_CHANGED}: the same binary name is a class in v1 and an
 * interface here, so the verifier and the class-file layout both change.
 *
 * <p>Never loaded into a test JVM (D-017).
 */
public interface Convertible {

    /** The member v1's class declared, now an interface method. */
    String value();
}
