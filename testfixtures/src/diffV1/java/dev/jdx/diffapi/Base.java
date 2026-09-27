package dev.jdx.diffapi;

/**
 * Target for the un-checkable-inheritance path: {@link Orphaned} extends
 * {@code dev.jdx.absent.Supertype}, which is in **neither** version of this artifact, so
 * the differ cannot prove a removed method was not inherited and must say so instead of
 * downgrading a breaking change on a maybe (AGENTS.md §2.2).
 *
 * <p>Never loaded into a test JVM (D-017) — the class is deliberately not resolvable.
 */
public class Base {
}
