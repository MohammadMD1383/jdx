package dev.jdx.diffapi;

/**
 * Target for {@code MEMBER_MOVED_TO_SUPERTYPE}: v1 declares {@code shared()} here; in
 * v2 the method moves up to {@link Base}, which is inside the same artifact, and this
 * class stops declaring it. The caller's {@code invokevirtual} still resolves through
 * the chain, so reporting a removal would be a false alarm.
 *
 * <p>Never loaded into a test JVM (D-017).
 */
public class Relocated {

    /** Moves to {@code Base} in v2. */
    public String shared() {
        return "relocated";
    }

    /** Stays put, so the class is not uniformly different. */
    public String stays() {
        return "stays";
    }
}
