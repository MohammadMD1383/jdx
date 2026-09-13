package dev.jdx.core.model

/**
 * The JVM access flags (JVMS tables 4.1-B, 4.6-A, 4.7-A), modelled as an enum of
 * flag constants over an [Access] mask.
 *
 * Beware the documented bit overlaps — the JVM reuses bits per declaration context:
 * `0x0020` is `ACC_SYNCHRONIZED` (methods) / `ACC_SUPER` (classes, never printed);
 * `0x0040` is `ACC_VOLATILE` (fields) / `ACC_BRIDGE` (methods);
 * `0x0080` is `ACC_TRANSIENT` (fields) / `ACC_VARARGS` (methods).
 * [Access] stores the raw mask and every query is context-driven; `has(VOLATILE)` on a
 * method mask reports the bit, and it is the caller who knows the context.
 */
public enum class AccessFlag(public val mask: Int) {
    PUBLIC(0x0001),
    PRIVATE(0x0002),
    PROTECTED(0x0004),
    STATIC(0x0008),
    FINAL(0x0010),
    SYNCHRONIZED(0x0020),
    VOLATILE(0x0040),
    BRIDGE(0x0040),
    TRANSIENT(0x0080),
    VARARGS(0x0080),
    NATIVE(0x0100),
    ABSTRACT(0x0400),
    STRICTFP(0x0800),
    SYNTHETIC(0x1000),
    ANNOTATION(0x2000),
    ENUM(0x4000),
}

/** JLS visibility levels, derived from the access flags with package-private as default. */
public enum class Visibility { PUBLIC, PROTECTED, PACKAGE_PRIVATE, PRIVATE }

/**
 * A set of JVM access flags as a raw mask, produced by the `index` module from bytecode.
 * Query with [has]; construct with [Access.of] or from a class-file mask directly.
 */
@JvmInline
public value class Access(public val mask: Int) {
    /** Whether [flag]'s bit (or its overlapping bit — see [AccessFlag]) is set. */
    public fun has(flag: AccessFlag): Boolean = mask and flag.mask != 0

    /** The JLS visibility implied by the flags; PACKAGE_PRIVATE when none is set. */
    public val visibility: Visibility
        get() = when {
            has(AccessFlag.PUBLIC) -> Visibility.PUBLIC
            has(AccessFlag.PROTECTED) -> Visibility.PROTECTED
            has(AccessFlag.PRIVATE) -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }

    public companion object {
        public val NONE: Access = Access(0)

        public fun of(vararg flags: AccessFlag): Access =
            Access(flags.fold(0) { accumulated, flag -> accumulated or flag.mask })
    }
}
