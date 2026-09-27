package dev.jdx.diffapi;

/**
 * Target for the un-checkable-inheritance path: this class names a supertype that is in
 * **neither** version of the artifact, so a removal cannot be cleared against it.
 *
 * <p>The supertype is written as a plain name on purpose — {@code dev.jdx.absent.Supertype}
 * is never compiled into either jar, which is what makes the differ's "inheritance not
 * checked" detail reachable. {@link #gone()} is removed in v2.
 *
 * <p>Never loaded into a test JVM (D-017).
 */
@SuppressWarnings("unused")
public class Orphaned extends dev.jdx.absent.Supertype {

    /** Target for {@code MEMBER_REMOVED} with the un-checkable-supertype detail. */
    public String gone() {
        return "gone";
    }
}
