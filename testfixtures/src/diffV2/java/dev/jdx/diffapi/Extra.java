package dev.jdx.diffapi;

/**
 * Target for {@code TYPE_ADDED}: this whole type is new in v2.
 *
 * <p>Also added to {@link Api}'s supertype list, which is what makes
 * {@code SUPERTYPE_ADDED} fire alongside the type-level addition.
 *
 * <p>Never loaded into a test JVM (D-017).
 */
public interface Extra {

    /** One abstract member, so the addition is a real API addition. */
    String extra();
}
