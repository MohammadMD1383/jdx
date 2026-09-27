package dev.jdx.diffapi;

/**
 * Target for {@code MEMBER_MOVED_TO_SUPERTYPE}: in v1 {@code dev.jdx.diffapi.Relocated}
 * declared {@code shared()} itself; here it lives on {@link Base}, which is inside this
 * same artifact, and the subclass no longer declares it.
 *
 * <p>The member is still reachable through the superclass, so a diff that reported a
 * removal would make an agent skip an upgrade for nothing.
 *
 * <p>Never loaded into a test JVM (D-017).
 */
public class Base {

    /** The member that moved up here. */
    public String shared() {
        return "relocated";
    }
}
