package dev.jdx.core.diff

import dev.jdx.core.diff.DiffSeverity.BREAKING
import dev.jdx.core.diff.DiffSeverity.INFO
import dev.jdx.core.diff.DiffSeverity.SUSPICIOUS

/**
 * How much a difference between two artifacts matters to an agent upgrading a
 * dependency. Declaration order **is** severity order, so `BREAKING < SUSPICIOUS < INFO`
 * sorts most-severe-first and `--severity`/`--fail-on` are cumulative thresholds.
 */
public enum class DiffSeverity {
    /** Already-compiled callers can fail to link, fail to run, or lose a guarantee. */
    BREAKING,

    /** Links fine, but recompiling against the new artifact, or the behaviour, can break. */
    SUSPICIOUS,

    /** Shape change with no compatibility consequence (a new member, a deprecation). */
    INFO,
}

/**
 * One named, stable difference between two artifacts — the taxonomy `jdx diff`
 * reports (issue #23). Every finding carries exactly one rule, and **the enum
 * constant name is the rule id**: agents branch on `rule`, never on `detail`
 * prose (the same discipline [dev.jdx.core.model.WarningCode] sets for warnings).
 * A new rule is a public API change — document it in the same commit.
 *
 * Severities follow JLS "Evolving an Interface" (§13.5) and "Incompatible Changes"
 * (§13.4), read at the level of *binary* compatibility first:
 *
 * - **BREAKING** — JLS §13.4 territory, plus the cases where a member's *identity*
 *   changes rather than its body: the caller's compiled descriptor no longer
 *   resolves, the JVM refuses the access, or a subclass contract is violated.
 * - **SUSPICIOUS** — JLS §13.5 territory: binary-compatible by the letter of the
 *   JLS, but source- or Kotlin-incompatible, or a behavioural guarantee moved.
 * - **INFO** — additive or cosmetic. Nothing that links can notice.
 *
 * Two deliberate non-goals, so a reader never has to guess what "breaking" means here:
 * this is **not** a full JLS §13.4 conformance suite (there is no `permits`/
 * record-component rule — see the caveat in `index/AGENTS.md`), and it never
 * *infers* an incompatibility it cannot read. A check that needs a type outside
 * the artifact (supertype lookup) reports the removal as BREAKING and says in
 * [DiffFinding.detail] that inheritance could not be checked, rather than
 * downgrading on a guess.
 */
public enum class CompatRule(public val severity: DiffSeverity) {
    // -- BREAKING: an already-compiled caller can fail to link, fail to run, or lose a
    //    guarantee. Ordered types, then members, then identity changes. ----------------

    /** A type in the old artifact is absent from the new one (JLS §13.5, class removal). */
    TYPE_REMOVED(BREAKING),

    /**
     * A type's kind changed: `class`↔`interface`↔`enum`↔`record`↔`annotation`, or a
     * Kotlin `object`↔`companion object`. The verifier and the class-file layout both
     * change with it, so no caller of the old shape links.
     */
    TYPE_KIND_CHANGED(BREAKING),

    /** A type went `public`/`protected` → package-private/private (JLS §13.5, access control). */
    TYPE_VISIBILITY_NARROWED(BREAKING),

    /** `final` added to a class: existing subclasses stop verifying (JLS §13.4, `final` classes). */
    TYPE_MADE_FINAL(BREAKING),

    /** A superclass or superinterface dropped from a type's list (JLS §13.4, supertypes). */
    SUPERTYPE_REMOVED(BREAKING),

    /**
     * A field, method or constructor is gone and is not declared by any supertype in
     * the new artifact, so nothing can inherit it. `detail` names the supertype that
     * was missing when the inheritance check could not complete.
     *
     * **Known conservative case:** a *public* member that is no longer declared but was
     * an override of a universal root's method (`toString`, `equals`, `hashCode`, …)
     * still links, through the root, and is reported here as a removal. The universal
     * roots are excluded from the inheritance walk — treating `java.lang.Object` as
     * "missing from the artifact" would put a caveat on every removal in every jar —
     * so resolving this case would need their member names, which is a follow-up rather
     * than a guess. A private or package-private removal is never affected: neither is
     * inherited, so `MEMBER_REMOVED` is exactly right.
     */
    MEMBER_REMOVED(BREAKING),

    /** A member went `public`→`protected`→package-private→`private` (JLS §13.5, access control). */
    MEMBER_VISIBILITY_NARROWED(BREAKING),

    /**
     * `final` added to a method or a field (JLS §13.4, modifiers). A subclass override or
     * an existing `putstatic`/`putfield` now fails.
     */
    MEMBER_MADE_FINAL(BREAKING),

    /** `static` dropped from a method or field: callers compiled `invokestatic` (JLS §13.4). */
    STATIC_TO_INSTANCE(BREAKING),

    /** `static` added to a method or field: callers compiled `invokevirtual` (JLS §13.4). */
    INSTANCE_TO_STATIC(BREAKING),

    /** A concrete class became abstract, or a concrete method became abstract (JLS §13.4). */
    ABSTRACT_ADDED(BREAKING),

    /**
     * An abstract method was added to an interface. Implementors compiled against the
     * old interface have no implementation to dispatch to (`AbstractMethodError`).
     */
    INTERFACE_METHOD_ADDED(BREAKING),

    /** An enum constant disappeared: `valueOf`, `values()` and `switch` on it all break. */
    ENUM_CONSTANT_REMOVED(BREAKING),

    /**
     * A method or constructor kept its name and arity but changed an erased parameter
     * type. Reported as one finding rather than a removal plus an addition, because
     * that is what the upgrade actually did. The old canonical ref is the subject;
     * `detail` carries the new one.
     */
    PARAMETER_TYPE_CHANGED(BREAKING),

    /** The erased return type changed — the caller's full descriptor no longer resolves. */
    RETURN_TYPE_CHANGED(BREAKING),

    /** A field kept its name but changed type: constant fields also change their inlined value. */
    FIELD_TYPE_CHANGED(BREAKING),

    // -- SUSPICIOUS: binary-compatible by the letter of the JLS, but recompiling, the
    //    Kotlin view, or the behaviour can still break. ---------------------------------

    /**
     * A member is gone from its declaring type but is declared by one of that type's
     * new supertypes. Binary-safe — the caller's `invokevirtual` still resolves through
     * the chain — but source lookups and reflection on the declaring type change.
     */
    MEMBER_MOVED_TO_SUPERTYPE(SUSPICIOUS),

    /**
     * The erased shape is identical but the `Signature` attribute differs — generics,
     * type parameters or a generic `throws`. Binary-safe, source-breaking.
     */
    GENERIC_SIGNATURE_CHANGED(SUSPICIOUS),

    /**
     * A type that is not `RuntimeException`/`Error` was added to `throws`. JLS §13.5
     * keeps this binary-compatible, but a caller that catches the old set must now
     * declare or catch the new one. Subclasses of the two unchecked roots are
     * classified textually, so an unchecked type may be reported here too — this
     * rule errs toward reporting, never toward silence.
     */
    CHECKED_EXCEPTION_ADDED(SUSPICIOUS),

    /** `varargs` added or removed: the same descriptor, a different source-level contract. */
    VARARGS_CHANGED(SUSPICIOUS),

    /** A method moved into native code: its body and JNI registration left the JVM. */
    NATIVE_ADDED(SUSPICIOUS),

    /** A method moved out of native code. */
    NATIVE_REMOVED(SUSPICIOUS),

    /**
     * A Kotlin member kept its Kotlin name and parameters but changed its JVM name:
     * an `@JvmName` appeared or disappeared, or an `internal` member's module-name
     * mangling changed. The Kotlin declaration is unchanged, so Kotlin callers are
     * fine — every already-compiled *JVM* caller of the old name is not.
     */
    KOTLIN_NAME_CHANGED(SUSPICIOUS),

    /** `suspend` added or removed: the JVM descriptor carries the hidden `Continuation`. */
    KOTLIN_SUSPEND_CHANGED(SUSPICIOUS),

    /** Same erased signature, different Kotlin-visible type (usually a nullability change). */
    KOTLIN_NULLABILITY_CHANGED(SUSPICIOUS),

    /**
     * A parameter stopped declaring a default value. Kotlin callers compiled against the
     * old artifact still pass the same arguments and the same mask, so the call
     * silently keeps the old default instead of failing.
     */
    KOTLIN_DEFAULT_ARG_REMOVED(SUSPICIOUS),

    /** A Kotlin member's parameter name changed, which breaks named arguments. */
    KOTLIN_PARAMETER_NAME_CHANGED(SUSPICIOUS),

    // -- INFO: additive or cosmetic. Nothing that links can notice. ---------------------

    /** A type only the new artifact has. Additive — nothing that links can notice. */
    TYPE_ADDED(INFO),

    /** A superclass or superinterface added. Additive for callers, breaking for subclasses. */
    SUPERTYPE_ADDED(INFO),

    /** A field, method or constructor only the new artifact declares. */
    MEMBER_ADDED(INFO),

    /** `final` dropped — a guarantee weakened, nothing that links can notice. */
    FINAL_REMOVED(INFO),

    /** A method became concrete. Callers that relied on it being abstract now link to a body. */
    ABSTRACT_REMOVED(INFO),

    /** A possibly-checked type was dropped from `throws`. */
    THROWS_REMOVED(INFO),

    /** A member's parameter name changed (JLS §13.5, parameter names) — reflection and Kotlin named arguments. */
    PARAMETER_NAME_CHANGED(INFO),

    /** A member's parameter names are no longer in the class file (`-g:none` recompile). */
    PARAMETER_NAMES_LOST(INFO),

    /** A parameter started declaring a default value. */
    KOTLIN_DEFAULT_ARG_ADDED(INFO),

    /** A Kotlin `val` became a `var`: a setter appeared. */
    KOTLIN_PROPERTY_BECAME_MUTABLE(INFO),

    /** An annotation appeared on a type or member. */
    ANNOTATION_ADDED(INFO),

    /** An annotation disappeared from a type or member. */
    ANNOTATION_REMOVED(INFO),

    /** An annotation kept its type but changed an element value. */
    ANNOTATION_VALUES_CHANGED(INFO),

    /** `@Deprecated` appeared (JLS §13.5, deprecated members). */
    DEPRECATED_ADDED(INFO),

    /** `@Deprecated` disappeared. */
    DEPRECATED_REMOVED(INFO),

    /** A `synchronized` modifier was added: a thread-safety guarantee appeared. */
    SYNCHRONIZED_ADDED(INFO),

    /** A `synchronized` modifier was removed: a thread-safety guarantee disappeared. */
    SYNCHRONIZED_REMOVED(INFO),

    /** A field's `transient` modifier changed: its serialisation behaviour changed. */
    TRANSIENT_CHANGED(INFO),

    /** A constant field's `ConstantValue` changed — every caller that inlined it is stale. */
    FIELD_CONSTANT_VALUE_CHANGED(INFO),

    /** An annotation element's `default` changed. */
    ANNOTATION_DEFAULT_CHANGED(INFO),

    ;
}
