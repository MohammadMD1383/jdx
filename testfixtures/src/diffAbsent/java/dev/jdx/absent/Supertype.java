package dev.jdx.absent;

/**
 * A superclass that exists in the world but is in **neither** diff fixture jar.
 *
 * <p>{@code dev.jdx.diffv1.Orphaned} and {@code dev.jdx.diffv2.Orphaned} both extend it,
 * and this module compiles them — `javac` refuses a class whose supertype is missing —
 * but neither `diffV1Jar` nor `diffV2Jar` packages this class. That is precisely the
 * situation `jdx diff` has to cope with in the wild: a real superclass living in
 * *another dependency*, so a diff cannot read it and cannot prove a removed method was
 * not inherited through it. The removal must stay BREAKING and the report must say which
 * supertype it could not read, rather than assume nothing was inherited (AGENTS.md §2.2).
 *
 * <p>Never loaded into a test JVM (D-017) — it is not even on the test classpath.
 */
public class Supertype {

    /** A member, so the class is not empty enough to be dropped by a compiler. */
    public String inherited() {
        return "inherited";
    }
}
