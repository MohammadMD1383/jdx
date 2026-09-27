package dev.jdx.diffapi;

/**
 * Target for {@code ENUM_CONSTANT_REMOVED}: {@code RED} is gone, so {@code valueOf("RED")}
 * and any switch on it break.
 *
 * <p>Never loaded into a test JVM (D-017).
 */
public enum Signal {
    GREEN,
}
