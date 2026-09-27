package dev.jdx.diffapi;

/**
 * Target for {@code INTERFACE_METHOD_ADDED}: {@link #alsoRequired()} is abstract here and
 * absent in v1, so every implementor compiled against v1 has no body for it.
 *
 * <p>Never loaded into a test JVM (D-017).
 */
public interface Contract {

    /** Declared on both sides, so it must report nothing. */
    String required();

    /** Only in v2 — the implementor-breaking addition. */
    String alsoRequired();

    /** Only in v2, and concrete, so it is additive rather than breaking. */
    default String defaulted() {
        return "defaulted";
    }
}
