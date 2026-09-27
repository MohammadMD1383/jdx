package dev.jdx.diffapi;

/**
 * Target for {@code TYPE_KIND_CHANGED}: v2 ships this name as an interface, not a class.
 *
 * <p>Never loaded into a test JVM (D-017).
 */
public class Convertible {

    /** Whatever the kind, the class file layout changes with it. */
    public String value() {
        return "convertible";
    }
}
