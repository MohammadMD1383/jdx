package dev.jdx.core.diff

import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.Visibility
import dev.jdx.core.diff.CompatRule.ABSTRACT_ADDED
import dev.jdx.core.diff.CompatRule.ABSTRACT_REMOVED
import dev.jdx.core.diff.CompatRule.ANNOTATION_DEFAULT_CHANGED
import dev.jdx.core.diff.CompatRule.CHECKED_EXCEPTION_ADDED
import dev.jdx.core.diff.CompatRule.DEPRECATED_ADDED
import dev.jdx.core.diff.CompatRule.DEPRECATED_REMOVED
import dev.jdx.core.diff.CompatRule.ENUM_CONSTANT_REMOVED
import dev.jdx.core.diff.CompatRule.FIELD_CONSTANT_VALUE_CHANGED
import dev.jdx.core.diff.CompatRule.FIELD_TYPE_CHANGED
import dev.jdx.core.diff.CompatRule.FINAL_REMOVED
import dev.jdx.core.diff.CompatRule.GENERIC_SIGNATURE_CHANGED
import dev.jdx.core.diff.CompatRule.INSTANCE_TO_STATIC
import dev.jdx.core.diff.CompatRule.INTERFACE_METHOD_ADDED
import dev.jdx.core.diff.CompatRule.KOTLIN_DEFAULT_ARG_ADDED
import dev.jdx.core.diff.CompatRule.KOTLIN_DEFAULT_ARG_REMOVED
import dev.jdx.core.diff.CompatRule.KOTLIN_NAME_CHANGED
import dev.jdx.core.diff.CompatRule.KOTLIN_NULLABILITY_CHANGED
import dev.jdx.core.diff.CompatRule.KOTLIN_PARAMETER_NAME_CHANGED
import dev.jdx.core.diff.CompatRule.KOTLIN_PROPERTY_BECAME_MUTABLE
import dev.jdx.core.diff.CompatRule.KOTLIN_SUSPEND_CHANGED
import dev.jdx.core.diff.CompatRule.MEMBER_ADDED
import dev.jdx.core.diff.CompatRule.MEMBER_MADE_FINAL
import dev.jdx.core.diff.CompatRule.MEMBER_MOVED_TO_SUPERTYPE
import dev.jdx.core.diff.CompatRule.MEMBER_REMOVED
import dev.jdx.core.diff.CompatRule.MEMBER_VISIBILITY_NARROWED
import dev.jdx.core.diff.CompatRule.NATIVE_ADDED
import dev.jdx.core.diff.CompatRule.NATIVE_REMOVED
import dev.jdx.core.diff.CompatRule.PARAMETER_NAMES_LOST
import dev.jdx.core.diff.CompatRule.PARAMETER_NAME_CHANGED
import dev.jdx.core.diff.CompatRule.PARAMETER_TYPE_CHANGED
import dev.jdx.core.diff.CompatRule.RETURN_TYPE_CHANGED
import dev.jdx.core.diff.CompatRule.STATIC_TO_INSTANCE
import dev.jdx.core.diff.CompatRule.SUPERTYPE_ADDED
import dev.jdx.core.diff.CompatRule.SUPERTYPE_REMOVED
import dev.jdx.core.diff.CompatRule.SYNCHRONIZED_ADDED
import dev.jdx.core.diff.CompatRule.SYNCHRONIZED_REMOVED
import dev.jdx.core.diff.CompatRule.THROWS_REMOVED
import dev.jdx.core.diff.CompatRule.TRANSIENT_CHANGED
import dev.jdx.core.diff.CompatRule.TYPE_ADDED
import dev.jdx.core.diff.CompatRule.TYPE_KIND_CHANGED
import dev.jdx.core.diff.CompatRule.TYPE_MADE_FINAL
import dev.jdx.core.diff.CompatRule.TYPE_REMOVED
import dev.jdx.core.diff.CompatRule.TYPE_VISIBILITY_NARROWED
import dev.jdx.core.diff.CompatRule.VARARGS_CHANGED

/**
 * Compares two [ApiSnapshot]s and reports every difference as a [DiffFinding].
 *
 * Pure function of its arguments — no IO, no classpath, nothing loaded (D-017) —
 * so it is exhaustively testable with hand-built snapshots and is the whole of the
 * behaviour `jdx diff` has. It never guesses: where a verdict would need a type
 * that is not in the artifact (the supertype lookup behind
 * [CompatRule.MEMBER_MOVED_TO_SUPERTYPE]) it reports the removal as
 * [CompatRule.MEMBER_REMOVED] and says in the detail that inheritance could not be
 * checked, rather than downgrading a breaking change on a maybe.
 *
 * The three passes over a type's members, in order, are what make the output
 * readable rather than a remove/add avalanche:
 *
 * 1. **Pair by shape** — a member gone and another appeared under the same name and
 *    arity is one retyped signature ([CompatRule.PARAMETER_TYPE_CHANGED],
 *    [CompatRule.FIELD_TYPE_CHANGED]), not two findings.
 * 2. **Pair by Kotlin name** — what is left over the same Kotlin-visible name and
 *    erased parameters is a JVM rename ([CompatRule.KOTLIN_NAME_CHANGED]): an
 *    `@JvmName` appearing or disappearing, or an `internal` member's mangling.
 * 3. **Report the remainder** — removals and additions, with the supertype check.
 *
 * Only unique-to-unique buckets are paired. An ambiguous bucket (two removals
 * sharing a shape) is left as plain removals, because pairing one of them would be
 * a guess.
 */
public object ApiDiffer {

    /** Compares [old] to [new] and returns every difference found. */
    public fun diff(old: ApiSnapshot, new: ApiSnapshot): ApiDiff {
        val findings = mutableListOf<DiffFinding>()
        for ((binaryName, oldType) in old.types) {
            val newType = new.types[binaryName]
            if (newType == null) {
                // The whole type is gone; its members are not interesting one by one.
                findings.add(DiffFinding(TYPE_REMOVED, binaryName, oldType.canonicalRef))
            } else {
                findings.addAll(typeFindings(oldType, newType))
                findings.addAll(memberFindings(oldType, newType, new))
            }
        }
        for ((binaryName, newType) in new.types) {
            if (binaryName !in old.types) {
                findings.add(DiffFinding(TYPE_ADDED, binaryName, newType.canonicalRef))
            }
        }
        return ApiDiff(
            oldArtifact = old.artifact,
            newArtifact = new.artifact,
            oldTypeCount = old.typeCount,
            newTypeCount = new.typeCount,
            findings = findings,
        )
    }

    // -- types ------------------------------------------------------------------

    private fun typeFindings(old: ApiType, new: ApiType): List<DiffFinding> {
        val findings = mutableListOf<DiffFinding>()
        val ref = old.canonicalRef
        fun emit(rule: CompatRule, detail: String = "") {
            findings.add(DiffFinding(rule, old.binaryName, ref, detail))
        }

        if (old.kind != new.kind) {
            emit(TYPE_KIND_CHANGED, "${old.kind.name.lowercase()} -> ${new.kind.name.lowercase()}")
        }
        accessFindings(
            before = old.access,
            after = new.access,
            narrowedRule = TYPE_VISIBILITY_NARROWED,
            madeFinalRule = TYPE_MADE_FINAL,
            emit = { rule, detail -> emit(rule, detail) },
        )
        val oldSupertypes = supertypeNames(old)
        val newSupertypes = supertypeNames(new)
        for (supertype in oldSupertypes) {
            if (supertype !in newSupertypes) emit(SUPERTYPE_REMOVED, supertype)
        }
        for (supertype in newSupertypes) {
            if (supertype !in oldSupertypes) emit(SUPERTYPE_ADDED, supertype)
        }
        findings.addAll(annotationFindings(old.annotations, new.annotations, old.binaryName, ref))
        if (!old.deprecated && new.deprecated) emit(DEPRECATED_ADDED)
        if (old.deprecated && !new.deprecated) emit(DEPRECATED_REMOVED)
        return findings
    }

    // -- members ----------------------------------------------------------------

    private fun memberFindings(oldType: ApiType, newType: ApiType, new: ApiSnapshot): List<DiffFinding> {
        val oldMembers = oldType.members
        val newMembers = newType.members
        val removed = oldMembers.keys.filterNot { it in newMembers }.toMutableSet()
        val added = newMembers.keys.filterNot { it in oldMembers }.toMutableSet()
        val findings = mutableListOf<DiffFinding>()
        // A key is only ever in one of the two sets, so this lookup always resolves.
        val memberOf: (ApiMemberKey) -> ApiMember = { oldMembers[it] ?: newMembers.getValue(it) }

        findings.addAll(
            pairUp(removed, added, { shapePairKey(it) }) { oldKey, newKey ->
                val rule = if (oldKey is ApiFieldKey) FIELD_TYPE_CHANGED else PARAMETER_TYPE_CHANGED
                DiffFinding(
                    rule = rule,
                    type = oldType.binaryName,
                    ref = oldMembers.getValue(oldKey).canonicalRef,
                    detail = "now: " + newMembers.getValue(newKey).canonicalRef,
                )
            },
        )
        findings.addAll(
            pairUp(
                removed,
                added,
                { key -> kotlinPairKey(key, memberOf(key)) },
            ) { oldKey, newKey ->
                val oldMember = oldMembers.getValue(oldKey)
                val newMember = newMembers.getValue(newKey)
                val rule = when {
                    // A `suspend` function's JVM descriptor carries a hidden
                    // `Continuation`, so gaining or losing `suspend` changes the key.
                    // Kotlin arity (the one with the Continuation stripped) is what
                    // still matches, which is exactly why this pass exists.
                    isSuspend(oldMember) != isSuspend(newMember) -> KOTLIN_SUSPEND_CHANGED
                    oldKey.name != newKey.name -> KOTLIN_NAME_CHANGED
                    // Same Kotlin name, same arity, same JVM name, yet different keys:
                    // nothing here explains the change, so report it honestly as a
                    // removal and an addition rather than invent a rule for it.
                    else -> null
                }
                rule?.let {
                    DiffFinding(
                        rule = it,
                        type = oldType.binaryName,
                        ref = oldMember.canonicalRef,
                        detail = if (it == KOTLIN_SUSPEND_CHANGED) {
                            addedRemoved(isSuspend(oldMember), isSuspend(newMember), "suspend")
                        } else {
                            "JVM name ${oldKey.name} -> ${newKey.name}"
                        },
                    )
                }
            },
        )

        for (key in removed.sortedBy { apiKeySortText(it) }) {
            val member = oldMembers.getValue(key)
            val ref = member.canonicalRef
            when (val inherited = inheritedFrom(newType, key, member, new)) {
                is Inheritance.Found -> findings.add(
                    DiffFinding(
                        rule = MEMBER_MOVED_TO_SUPERTYPE,
                        type = oldType.binaryName,
                        ref = ref,
                        detail = "declared by ${inherited.declaringType}",
                    ),
                )
                is Inheritance.Unchecked -> findings.add(
                    DiffFinding(
                        rule = MEMBER_REMOVED,
                        type = oldType.binaryName,
                        ref = ref,
                        detail = "supertype ${inherited.missingType} is not in " +
                            "${new.artifact}; inheritance not checked",
                    ),
                )
                is Inheritance.Gone -> findings.add(
                    DiffFinding(
                        rule = if (isEnumConstant(oldType, key, member)) ENUM_CONSTANT_REMOVED else MEMBER_REMOVED,
                        type = oldType.binaryName,
                        ref = ref,
                    ),
                )
            }
        }

        for (key in added.sortedBy { apiKeySortText(it) }) {
            val member = newMembers.getValue(key)
            val rule = if (isNewInterfaceRequirement(newType, key, member)) {
                INTERFACE_METHOD_ADDED
            } else {
                MEMBER_ADDED
            }
            findings.add(DiffFinding(rule, oldType.binaryName, member.canonicalRef))
        }

        for (key in oldMembers.keys) {
            val before = oldMembers.getValue(key)
            val after = newMembers[key] ?: continue
            findings.addAll(
                when {
                    before is ApiMethod && after is ApiMethod -> methodFindings(oldType, before, after)
                    before is ApiField && after is ApiField -> fieldFindings(oldType, before, after)
                    // Unreachable: a key names either a method or a field, and a key is
                    // only shared when both sides built it the same way. Kept total so a
                    // future member kind cannot make this throw.
                    else -> emptyList()
                },
            )
        }
        return findings
    }

    /**
     * A method or constructor of an interface that every implementor must now
     * implement: abstract, not static, and not a default. A `default` or `static`
     * interface method is implemented already, so adding one is additive — the same
     * reason `ClassInfo.methods` keeps the flag and this check reads it rather than
     * inferring "interface method" from the type alone.
     */
    private fun isNewInterfaceRequirement(owner: ApiType, key: ApiMemberKey, member: ApiMember): Boolean {
        if (owner.kind != TypeKind.INTERFACE) return false
        if (key !is ApiMethodKey) return false
        return member.access.has(AccessFlag.ABSTRACT) && !member.access.has(AccessFlag.STATIC)
    }

    /** The name an enum constant is stored under: `public static final` of the enum's own type. */
    private fun isEnumConstant(owner: ApiType, key: ApiMemberKey, member: ApiMember): Boolean {
        if (owner.kind != TypeKind.ENUM) return false
        if (key !is ApiFieldKey) return false
        if (!member.access.has(AccessFlag.PUBLIC)) return false
        if (!member.access.has(AccessFlag.STATIC)) return false
        if (!member.access.has(AccessFlag.FINAL)) return false
        return key.type.binaryName == owner.binaryName
    }

    // -- supertype lookup --------------------------------------------------------

    /** Where a removed member ended up, or whether the question could be answered. */
    private sealed interface Inheritance {
        /** A supertype in the snapshot declares the member — it is inherited, not gone. */
        data class Found(val declaringType: String) : Inheritance

        /** The walk hit a supertype the snapshot does not hold, so absence proves nothing. */
        data class Unchecked(val missingType: String) : Inheritance

        /** Nothing left that could declare it: every supertype is accounted for. */
        data object Gone : Inheritance
    }

    /**
     * Breadth-first over [owner]'s supertypes, superclass before interfaces, each in
     * declaration order, so both the answer and the "could not check" witness are
     * deterministic. The first supertype missing from the snapshot is the witness:
     * it is the one that would have to be consulted to prove absence.
     */
    private fun inheritedFrom(
        owner: ApiType,
        key: ApiMemberKey,
        member: ApiMember,
        snapshot: ApiSnapshot,
    ): Inheritance {
        if (member.access.has(AccessFlag.PRIVATE)) return Inheritance.Gone
        val queue = ArrayDeque<ApiType>()
        val seen = mutableSetOf<String>()
        var missing: String? = null
        for (supertype in supertypeNames(owner)) {
            if (supertype in UNIVERSAL_SUPERTYPES) continue
            val found = snapshot.types[supertype]
            if (found == null) {
                if (missing == null) missing = supertype
            } else {
                queue.addLast(found)
            }
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current.binaryName)) continue
            if (key in current.members) return Inheritance.Found(current.binaryName)
            for (supertype in supertypeNames(current)) {
                if (supertype in UNIVERSAL_SUPERTYPES) continue
                val found = snapshot.types[supertype]
                if (found == null) {
                    if (missing == null) missing = supertype
                } else {
                    queue.addLast(found)
                }
            }
        }
        return missing?.let { Inheritance.Unchecked(it) } ?: Inheritance.Gone
    }

    // -- per-kind attribute findings ---------------------------------------------

    private fun methodFindings(oldType: ApiType, old: ApiMethod, new: ApiMethod): List<DiffFinding> {
        val findings = mutableListOf<DiffFinding>()
        val ref = old.canonicalRef
        fun emit(rule: CompatRule, detail: String = "") {
            findings.add(DiffFinding(rule, oldType.binaryName, ref, detail))
        }

        val shapeChanged = old.returnType != new.returnType
        if (shapeChanged) {
            emit(RETURN_TYPE_CHANGED, "${old.returnType.binaryName} -> ${new.returnType.binaryName}")
        }
        accessFindings(
            before = old.access,
            after = new.access,
            narrowedRule = MEMBER_VISIBILITY_NARROWED,
            madeFinalRule = MEMBER_MADE_FINAL,
            emit = { rule, detail -> emit(rule, detail) },
        )
        if (old.varargs != new.varargs) emit(VARARGS_CHANGED, addedRemoved(old.varargs, new.varargs, "varargs"))
        if (!old.native && new.native) emit(NATIVE_ADDED)
        if (old.native && !new.native) emit(NATIVE_REMOVED)
        if (!old.synchronized && new.synchronized) emit(SYNCHRONIZED_ADDED)
        if (old.synchronized && !new.synchronized) emit(SYNCHRONIZED_REMOVED)
        emitThrows(old.throwsTypes, new.throwsTypes, ::emit)
        // A generic signature that moved *with* an erased-shape change is already
        // explained by that change; reporting both would be the same news twice.
        if (!shapeChanged && old.genericSignature != new.genericSignature) emit(GENERIC_SIGNATURE_CHANGED)
        if (old.annotationDefault != new.annotationDefault) {
            emit(ANNOTATION_DEFAULT_CHANGED, "${old.annotationDefault ?: "-"} -> ${new.annotationDefault ?: "-"}")
        }
        parameterNameFindings(oldType, old.parameterNames, new.parameterNames) { rule, detail -> emit(rule, detail) }
        kotlinMethodFindings(old, new) { rule, detail -> emit(rule, detail) }
        findings.addAll(annotationFindings(old.annotations, new.annotations, oldType.binaryName, ref))
        if (!old.deprecated && new.deprecated) emit(DEPRECATED_ADDED)
        if (old.deprecated && !new.deprecated) emit(DEPRECATED_REMOVED)
        return findings
    }

    private fun fieldFindings(oldType: ApiType, old: ApiField, new: ApiField): List<DiffFinding> {
        val findings = mutableListOf<DiffFinding>()
        val ref = old.canonicalRef
        fun emit(rule: CompatRule, detail: String = "") {
            findings.add(DiffFinding(rule, oldType.binaryName, ref, detail))
        }

        accessFindings(
            before = old.access,
            after = new.access,
            narrowedRule = MEMBER_VISIBILITY_NARROWED,
            madeFinalRule = MEMBER_MADE_FINAL,
            emit = { rule, detail -> emit(rule, detail) },
        )
        if (old.transient != new.transient) emit(TRANSIENT_CHANGED, addedRemoved(old.transient, new.transient, "transient"))
        if (old.constantValue != new.constantValue) {
            emit(FIELD_CONSTANT_VALUE_CHANGED, "${old.constantValue ?: "-"} -> ${new.constantValue ?: "-"}")
        }
        if (old.genericSignature != new.genericSignature) emit(GENERIC_SIGNATURE_CHANGED, "")
        kotlinFieldFindings(old, new) { rule, detail -> emit(rule, detail) }
        findings.addAll(annotationFindings(old.annotations, new.annotations, oldType.binaryName, ref))
        if (!old.deprecated && new.deprecated) emit(DEPRECATED_ADDED)
        if (old.deprecated && !new.deprecated) emit(DEPRECATED_REMOVED)
        return findings
    }

    /**
     * The flags that mean the same thing on a type and on a member. The two rule ids
     * that differ ([narrowedRule], [madeFinalRule]) are passed in so a type never
     * reports a member rule id, and one implementation keeps the two in step.
     */
    private fun accessFindings(
        before: Access,
        after: Access,
        narrowedRule: CompatRule,
        madeFinalRule: CompatRule,
        emit: (CompatRule, String) -> Unit,
    ) {
        if (visibilityRank(after.visibility) > visibilityRank(before.visibility)) {
            emit(narrowedRule, "${before.visibility.name.lowercase()} -> ${after.visibility.name.lowercase()}")
        }
        if (!before.has(AccessFlag.FINAL) && after.has(AccessFlag.FINAL)) emit(madeFinalRule, "")
        if (before.has(AccessFlag.FINAL) && !after.has(AccessFlag.FINAL)) emit(FINAL_REMOVED, "")
        if (before.has(AccessFlag.STATIC) && !after.has(AccessFlag.STATIC)) emit(STATIC_TO_INSTANCE, "")
        if (!before.has(AccessFlag.STATIC) && after.has(AccessFlag.STATIC)) emit(INSTANCE_TO_STATIC, "")
        if (!before.has(AccessFlag.ABSTRACT) && after.has(AccessFlag.ABSTRACT)) emit(ABSTRACT_ADDED, "")
        if (before.has(AccessFlag.ABSTRACT) && !after.has(AccessFlag.ABSTRACT)) emit(ABSTRACT_REMOVED, "")
    }

    private fun emitThrows(old: List<TypeName>, new: List<TypeName>, emit: (CompatRule, String) -> Unit) {
        val newNames = new.map { it.binaryName }
        val oldNames = old.map { it.binaryName }
        for (type in old) {
            if (type.binaryName !in newNames) emit(THROWS_REMOVED, type.binaryName)
        }
        for (type in new) {
            if (type.binaryName !in oldNames && isPossiblyChecked(type)) {
                emit(CHECKED_EXCEPTION_ADDED, type.binaryName)
            }
        }
    }

    /**
     * Parameter names are kept apart from a rename because losing them is a *compile
     * flag* change (`-g:none`, or a build that stopped shipping debug info), not an
     * API change — lumping it in with a rename would bury real renames.
     */
    private fun parameterNameFindings(
        owner: ApiType,
        old: List<String?>,
        new: List<String?>,
        emit: (CompatRule, String) -> Unit,
    ) {
        if (old == new) return
        if (new.isEmpty() || new.all { it == null }) {
            if (old.any { it != null }) emit(PARAMETER_NAMES_LOST, "${old.count { it != null }} names")
            return
        }
        val changed = old.indices
            .filter { old.getOrNull(it) != new.getOrNull(it) }
            .joinToString(", ") { index ->
                "${old.getOrNull(index) ?: "?"} -> ${new.getOrNull(index) ?: "?"}"
            }
        emit(if (owner.isKotlin) KOTLIN_PARAMETER_NAME_CHANGED else PARAMETER_NAME_CHANGED, changed)
    }

    /**
     * The Kotlin differences a *common* key can still hide. `suspend` is deliberately
     * absent: a `suspend` function's descriptor carries the hidden `Continuation`, so
     * gaining or losing it changes the member's key and is reported by the Kotlin
     * pairing pass instead. Everything checked here is something the erasure cannot
     * see — a default value, a nullability marker, a parameter name.
     */
    private fun kotlinMethodFindings(old: ApiMethod, new: ApiMethod, emit: (CompatRule, String) -> Unit) {
        val oldView = old.kotlin
        val newView = new.kotlin
        val oldReturn = oldView?.displayReturn
        val newReturn = newView?.displayReturn
        if (oldReturn != null && newReturn != null && oldReturn != newReturn) {
            emit(KOTLIN_NULLABILITY_CHANGED, "$oldReturn -> $newReturn")
        }
        val oldDefaults = oldView?.defaultArgIndices ?: emptySet()
        val newDefaults = newView?.defaultArgIndices ?: emptySet()
        for (index in (oldDefaults - newDefaults).sorted()) {
            emit(KOTLIN_DEFAULT_ARG_REMOVED, "parameter ${index + 1}")
        }
        for (index in (newDefaults - oldDefaults).sorted()) {
            emit(KOTLIN_DEFAULT_ARG_ADDED, "parameter ${index + 1}")
        }
    }

    /**
     * A property whose view appears or disappears is not reported here: its accessors
     * are ordinary members and already report as added or removed. Only a view on
     * *both* sides says something about the same property.
     */
    private fun kotlinFieldFindings(old: ApiField, new: ApiField, emit: (CompatRule, String) -> Unit) {
        val oldView = old.kotlin ?: return
        val newView = new.kotlin ?: return
        if (!oldView.isVar && newView.isVar) emit(KOTLIN_PROPERTY_BECAME_MUTABLE, oldView.propertyName)
        val oldType = oldView.displayType
        val newType = newView.displayType
        if (oldType != null && newType != null && oldType != newType) {
            emit(KOTLIN_NULLABILITY_CHANGED, "$oldType -> $newType")
        }
    }

    // -- pairing ----------------------------------------------------------------

    /**
     * Pairs up the entries of [removed] and [added] that share a [keyOf] bucket,
     * **only** where the bucket holds exactly one entry on each side, and removes
     * the paired entries from both sets so the caller never reports them twice.
     *
     * Ambiguous buckets are left alone on purpose: with two removals of one shape
     * there is no way to know which addition replaced which, and picking one would
     * be a guess (AGENTS.md §2.2). A [finding] that returns `null` declines the pair
     * for the same reason — the bucket matched but nothing explains the change, so
     * the members stay a plain removal and addition.
     */
    private inline fun pairUp(
        removed: MutableSet<ApiMemberKey>,
        added: MutableSet<ApiMemberKey>,
        keyOf: (ApiMemberKey) -> String,
        finding: (ApiMemberKey, ApiMemberKey) -> DiffFinding?,
    ): List<DiffFinding> {
        val removedBuckets = removed.groupBy(keyOf)
        val addedBuckets = added.groupBy(keyOf)
        val paired = mutableListOf<DiffFinding>()
        for ((bucket, oldKeys) in removedBuckets) {
            val newKeys = addedBuckets[bucket] ?: continue
            if (oldKeys.size != 1 || newKeys.size != 1) continue
            val oldKey = oldKeys.single()
            val newKey = newKeys.single()
            val result = finding(oldKey, newKey) ?: continue
            removed.remove(oldKey)
            added.remove(newKey)
            paired.add(result)
        }
        return paired
    }

    /**
     * Kind, name and arity. A method and a field may share a name in Java, so the
     * kind prefix is part of the bucket, not a detail.
     */
    private fun shapePairKey(key: ApiMemberKey): String = when (key) {
        is ApiMethodKey -> "m:" + key.name + "/" + key.parameterTypes.size
        is ApiFieldKey -> "f:" + key.name
    }

    /**
     * The Kotlin-visible name plus the arity a Kotlin caller sees — the parameter list
     * with a `suspend` function's hidden `Continuation` removed. Pairing on the JVM
     * parameter list instead would never match a `suspend` change, whose whole point
     * is that the JVM list moved.
     */
    private fun kotlinPairKey(key: ApiMemberKey, member: ApiMember): String = when (key) {
        is ApiMethodKey -> "m:" + (member.kotlinName ?: key.name) + "/" + kotlinArity(key, member)
        is ApiFieldKey -> "f:" + (member.kotlinName ?: key.name)
    }

    /** The arity a Kotlin caller writes, i.e. the erased arity minus a hidden `Continuation`. */
    private fun kotlinArity(key: ApiMethodKey, member: ApiMember): Int {
        val view = (member as? ApiMethod)?.kotlin
        return if (view?.stripAppliesTo(key.parameterTypes) == true) {
            key.parameterTypes.size - 1
        } else {
            key.parameterTypes.size
        }
    }

    /** Whether the member is a Kotlin `suspend` function, from the only place that knows. */
    private fun isSuspend(member: ApiMember): Boolean = (member as? ApiMethod)?.kotlin?.markSuspend == true

    /** The declaring type's supertypes, superclass first, then interfaces, as binary names. */
    private fun supertypeNames(type: ApiType): List<String> = buildList {
        type.superclass?.let { add(it.binaryName) }
        for (interfaceType in type.interfaces) add(interfaceType.binaryName)
    }

    private fun addedRemoved(before: Boolean, after: Boolean, what: String): String =
        if (after) "$what added" else "$what removed"
}

/**
 * How narrow a declaration is, 0 widest. Written out rather than read off
 * [Visibility.ordinal] so that reordering that enum cannot silently invert every
 * visibility verdict in the tool.
 */
private fun visibilityRank(visibility: Visibility): Int = when (visibility) {
    Visibility.PUBLIC -> 0
    Visibility.PROTECTED -> 1
    Visibility.PACKAGE_PRIVATE -> 2
    Visibility.PRIVATE -> 3
}
