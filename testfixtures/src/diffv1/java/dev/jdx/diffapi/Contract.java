package dev.jdx.diffapi;

/**
 * Target for {@code INTERFACE_METHOD_ADDED}: v2 adds one more abstract method here,
 * which every implementor compiled against v1 has no body for.
 *
 * <p>Never loaded into a test JVM (D-017).
 */
public interface Contract {

    /** Declared on both sides. */
    String required();

    /** Only in v2 — the implementor-breaking addition. */
    default String defaulted() {
        return "defaulted";
    }
}
