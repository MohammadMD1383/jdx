package dev.jdx.core.resolve

import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.ArrayTypeSignature
import dev.jdx.core.model.BaseTypeSignature
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.ClassTypeSignature
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.FieldSignature
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.MethodSignature
import dev.jdx.core.model.ReferenceTypeSignature
import dev.jdx.core.model.ThrowsSignature
import dev.jdx.core.model.TypeArgument
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.TypeSignature
import dev.jdx.core.model.TypeVariableSignature
import dev.jdx.core.model.Visibility
import dev.jdx.core.model.VoidSignature

/**
 * Member resolution with inheritance and generic substitution (PROPOSAL.md §9.3, T-009).
 *
 * This is the `--inherited` core: what can an agent call on this type without walking the
 * hierarchy by hand. The algorithm follows the spec step by step — linearise, substitute,
 * filter by visibility, collapse overrides, drop synthetics — and every step below is
 * numbered after the spec step it implements. No cleverness: breadth-first, first visit
 * wins, everything deterministic.
 *
 * Pure function of [target] plus [lookup]; no IO. [lookup] maps a type name to its
 * [ClassInfo] (the index in production, an in-memory map in tests) and returns `null`
 * for unknown supertypes, which are skipped and reported in
 * [ResolvedMembers.missingSupertypes] — degrade, don't fail.
 */
public object MemberResolver {

    /**
     * Resolves the full member set of [target]: its declared members plus every visible,
     * non-overridden inherited member, with generic type variables substituted for the
     * target's position in the hierarchy.
     */
    public fun resolve(
        target: ClassInfo,
        lookup: (TypeName) -> ClassInfo?,
        options: MemberResolutionOptions = MemberResolutionOptions(),
    ): ResolvedMembers {
        val linearisation = linearise(target, lookup)
        val targetPackage = target.name.packageName
        val seenMethods = LinkedHashMap<MethodKey, ResolvedMethod>()
        val seenFields = LinkedHashMap<String, ResolvedField>()
        val missing = sortedSetOf<String>()

        for (node in linearisation) {
            val info = node.info
            val isTarget = node.depth == 0
            if (!isTarget && info == null) {
                // Lookup failed for a supertype: the linearisation already skipped its
                // whole subtree (nothing below it is reachable without it).
                missing.add(node.type.binaryName)
                continue
            }
            val declaring = requireNotNull(info) { "linearisation never holds a null target" }
            val declaringPackage = declaring.name.packageName

            for (field in declaring.fields) {
                if (!options.includeSynthetic && isSyntheticField(field)) continue
                if (!isTarget && !isVisible(fieldAccess(field), declaringPackage, targetPackage, isTarget)) {
                    continue
                }
                val existing = seenFields[field.name]
                if (existing == null) {
                    seenFields[field.name] = ResolvedField(
                        declaringType = declaring.name,
                        depth = node.depth,
                        member = field,
                        substitutedSignature = field.genericSignature?.let {
                            substituteField(it, node.environment)
                        },
                        hiddenTypes = emptyList(),
                    )
                } else {
                    // Field hiding (JLS §8.3): the nearer declaration wins; the hidden
                    // type is recorded, mirroring the method override metadata.
                    seenFields[field.name] = existing.copy(
                        hiddenTypes = existing.hiddenTypes + declaring.name,
                    )
                }
            }

            for (method in declaring.methods) {
                if (method.name == "<clinit>") continue // never a callable member
                if (!isTarget && method.name == "<init>") continue // constructors are not inherited
                if (!options.includeSynthetic && isSyntheticMethod(method)) continue
                if (!isTarget && !isVisible(method.access.visibility, declaringPackage, targetPackage, isTarget)) {
                    continue
                }
                val key = MethodKey(method.name, method.descriptor.descriptor)
                val existing = seenMethods[key]
                if (existing == null) {
                    seenMethods[key] = ResolvedMethod(
                        declaringType = declaring.name,
                        depth = node.depth,
                        member = method,
                        substitutedSignature = method.genericSignature?.let {
                            substituteMethod(it, node.environment)
                        },
                        overriddenTypes = emptyList(),
                    )
                } else {
                    // Override collapse (spec step 5): same name + erased descriptor means
                    // the nearer declaration already won; record the overridden type.
                    seenMethods[key] = existing.copy(
                        overriddenTypes = existing.overriddenTypes + declaring.name,
                    )
                }
            }
        }

        val ordered = linearisation.map { SupertypeNode(it.type, it.depth) }
        return ResolvedMembers(
            methods = seenMethods.values.toList(),
            fields = seenFields.values.toList(),
            linearization = ordered,
            missingSupertypes = missing.map { binaryToType(it) },
        )
    }

    // -- spec step 2: linearisation -------------------------------------------------

    /**
     * Breadth-first supertype walk: the target at depth 0, then its superclass and
     * interfaces (in declaration order), then theirs. First visit wins, so the walk is
     * cycle-safe and deterministic, and nearer declarations always precede farther ones —
     * which is what makes override collapse a single map lookup. `java.lang.Object` is
     * moved last so renderers can collapse it onto one line (PROPOSAL.md §7.1).
     */
    private fun linearise(
        target: ClassInfo,
        lookup: (TypeName) -> ClassInfo?,
    ): List<LinearNode> {
        val visited = LinkedHashMap<String, LinearNode>()
        val queue = ArrayDeque<QueueEntry>()
        val targetType = target.name
        visited[targetType.binaryName] = LinearNode(targetType, 0, target, emptyMap())
        queue.add(QueueEntry(targetType, target, emptyMap(), 0))

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val childInfo = current.info
            for (superRef in directSupertypes(childInfo)) {
                val superType = superRef.toTypeName() ?: continue
                if (superType.binaryName in visited) continue
                val superInfo = lookup(superType)
                val environment = if (superInfo == null) {
                    emptyMap()
                } else {
                    supertypeEnvironment(superInfo, superRef, current.environment)
                }
                visited[superType.binaryName] = LinearNode(superType, current.depth + 1, superInfo, environment)
                if (superInfo != null) {
                    queue.add(QueueEntry(superType, superInfo, environment, current.depth + 1))
                }
            }
        }

        // Object last: a deep interface DAG could otherwise order it before classes the
        // agent cares about; the target itself is never moved (resolving Object stays [Object]).
        val nodes = visited.values.toMutableList()
        if (nodes.size > 1) {
            val objectIndex = nodes.indexOfFirst { it.type.binaryName == "java.lang.Object" }
            if (objectIndex != -1) {
                val objectNode = nodes.removeAt(objectIndex)
                nodes.add(objectNode)
            }
        }
        return nodes
    }

    /**
     * A supertype edge: either the generic [ClassTypeSignature] from the child's
     * `Signature` attribute (carries type arguments) or the erased [TypeName] from the
     * class file's superclass/interfaces (a raw edge — erases to `Object`).
     */
    private sealed interface SuperRef {
        /** The supertype's name, or `null` when the signature names something unmappable. */
        fun toTypeName(): TypeName.ClassType?
    }

    private data class GenericRef(val signature: ClassTypeSignature) : SuperRef {
        override fun toTypeName(): TypeName.ClassType? {
            // Signatures name nested classes two ways: the grammar `.Inner`
            // suffix and the binary `$Inner` form kotlinc emits for supertype
            // edges (e.g. `Landroidx/savedstate/SavedStateRegistry$SavedStateProvider;`).
            // The model splits nesting on `$` exactly like binary names
            // (typeNameFromBinaryName); building the edge without splitting
            // threw and turned the whole query into exit 6 (T-056 soak find).
            // An edge that still will not model (empty segments from hostile
            // `$` shapes) maps to null — the linearisation skips it, like any
            // unmappable signature, instead of failing the query.
            val nested = listOf(signature.simpleName)
                .plus(signature.innerClasses.map { it.simpleName })
                .flatMap { it.split('$') }
                .filter { it.isNotEmpty() }
            if (nested.isEmpty()) return null
            return try {
                TypeName.ClassType(signature.packageName, nested)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    private data class ErasedRef(val type: TypeName) : SuperRef {
        override fun toTypeName(): TypeName.ClassType? = type as? TypeName.ClassType
    }

    /** Superclass first, then interfaces in declaration order (spec step 2). */
    private fun directSupertypes(info: ClassInfo): List<SuperRef> = buildList {
        val generic = info.genericSignature
        if (generic != null) {
            add(GenericRef(generic.superclass))
            generic.superinterfaces.forEach { add(GenericRef(it)) }
        } else {
            info.superclass?.let { add(ErasedRef(it)) }
            info.interfaces.forEach { add(ErasedRef(it)) }
        }
    }

    // -- spec step 3: generic substitution ------------------------------------------

    /**
     * Builds the type-variable environment for [superInfo] as seen from a child whose own
     * variables are bound by [childEnvironment]: each of the supertype's formal type
     * parameters maps to the corresponding type argument on the inheritance edge, itself
     * substituted through the child's environment so transitive chains
     * (`StringList → ArrayList<String> → AbstractList<E>`) resolve transitively.
     *
     * Raw edges (no type arguments, or an argument count the supertype does not declare)
     * erase every parameter to `java.lang.Object`, matching javac's raw-type erasure.
     */
    private fun supertypeEnvironment(
        superInfo: ClassInfo,
        edge: SuperRef,
        childEnvironment: Map<String, ReferenceTypeSignature>,
    ): Map<String, ReferenceTypeSignature> {
        val parameters = superInfo.genericSignature?.typeParameters ?: return emptyMap()
        if (parameters.isEmpty()) return emptyMap()
        val arguments = (edge as? GenericRef)?.signature?.typeArguments
        if (arguments == null || arguments.size != parameters.size) {
            return parameters.associate { it.name to objectType() }
        }
        return parameters.mapIndexed { index, parameter ->
            val resolved = substituteReference(typeArgumentToReference(arguments[index]), childEnvironment)
            parameter.name to resolved
        }.toMap()
    }

    /**
     * A type argument is a *use-site* shape (wildcards included); substitution needs a
     * plain reference type. Unbounded `*` and bounded `? extends`/`? super` have no
     * `TypeSignature` form (JVMS only allows them inside `<>`), so they approximate to
     * `Object`/the bound — documented imprecision for a shape that is vanishingly rare
     * on inheritance edges (`class Foo extends Bar<? extends Number>`).
     */
    private fun typeArgumentToReference(argument: TypeArgument): ReferenceTypeSignature =
        when (argument) {
            is TypeArgument.Exact -> argument.type
            is TypeArgument.Unbounded -> objectType()
            is TypeArgument.UpperBounded -> argument.bound
            is TypeArgument.LowerBounded -> argument.bound
        }

    private fun objectType(): ClassTypeSignature = ClassTypeSignature(
        packageName = "java.lang",
        simpleName = "Object",
        typeArguments = emptyList(),
        innerClasses = emptyList(),
    )

    /**
     * Substitutes a method signature into the resolving context. The method's own type
     * parameters shadow class variables of the same name (JLS §6.5.5.1), so those keys are
     * removed from the environment before substitution — `<T> T identity(T)` keeps its
     * `T` even under `Box<String>`.
     */
    private fun substituteMethod(
        signature: MethodSignature,
        environment: Map<String, ReferenceTypeSignature>,
    ): MethodSignature {
        if (environment.isEmpty()) return signature
        val shadowed = environment - signature.typeParameters.map { it.name }.toSet()
        if (shadowed.isEmpty()) return signature
        return signature.copy(
            parameters = signature.parameters.map { substitute(it, shadowed) },
            returnType = substitute(signature.returnType, shadowed),
            throwsSignatures = signature.throwsSignatures.map { substituteThrows(it, shadowed) },
        )
    }

    private fun substituteField(
        signature: FieldSignature,
        environment: Map<String, ReferenceTypeSignature>,
    ): FieldSignature {
        if (environment.isEmpty()) return signature
        return signature.copy(type = substituteReference(signature.type, environment))
    }

    private fun substitute(
        signature: TypeSignature,
        environment: Map<String, ReferenceTypeSignature>,
    ): TypeSignature = when (signature) {
        is VoidSignature -> signature
        is BaseTypeSignature -> signature
        is TypeVariableSignature -> environment[signature.name] ?: signature
        is ArrayTypeSignature -> ArrayTypeSignature(substitute(signature.elementType, environment))
        is ClassTypeSignature -> substituteClassType(signature, environment)
    }

    private fun substituteReference(
        signature: ReferenceTypeSignature,
        environment: Map<String, ReferenceTypeSignature>,
    ): ReferenceTypeSignature = when (signature) {
        is TypeVariableSignature -> environment[signature.name] ?: signature
        is ArrayTypeSignature -> ArrayTypeSignature(substitute(signature.elementType, environment))
        is ClassTypeSignature -> substituteClassType(signature, environment)
    }

    private fun substituteClassType(
        signature: ClassTypeSignature,
        environment: Map<String, ReferenceTypeSignature>,
    ): ClassTypeSignature = signature.copy(
        typeArguments = signature.typeArguments.map { argument ->
            when (argument) {
                is TypeArgument.Exact -> TypeArgument.Exact(substituteReference(argument.type, environment))
                is TypeArgument.UpperBounded -> TypeArgument.UpperBounded(
                    substituteReference(argument.bound, environment),
                )
                is TypeArgument.LowerBounded -> TypeArgument.LowerBounded(
                    substituteReference(argument.bound, environment),
                )
                is TypeArgument.Unbounded -> argument
            }
        },
        innerClasses = signature.innerClasses.map { inner ->
            inner.copy(
                typeArguments = inner.typeArguments.map { argument ->
                    when (argument) {
                        is TypeArgument.Exact -> TypeArgument.Exact(
                            substituteReference(argument.type, environment),
                        )
                        is TypeArgument.UpperBounded -> TypeArgument.UpperBounded(
                            substituteReference(argument.bound, environment),
                        )
                        is TypeArgument.LowerBounded -> TypeArgument.LowerBounded(
                            substituteReference(argument.bound, environment),
                        )
                        is TypeArgument.Unbounded -> argument
                    }
                },
            )
        },
    )

    private fun substituteThrows(
        throws: ThrowsSignature,
        environment: Map<String, ReferenceTypeSignature>,
    ): ThrowsSignature = when (throws) {
        is ThrowsSignature.ClassThrows -> ThrowsSignature.ClassThrows(
            substituteClassType(throws.classType, environment),
        )
        is ThrowsSignature.TypeVariableThrows -> when (val resolved = environment[throws.name]) {
            null -> throws
            is ClassTypeSignature -> ThrowsSignature.ClassThrows(resolved)
            is TypeVariableSignature -> ThrowsSignature.TypeVariableThrows(resolved.name)
            is ArrayTypeSignature -> throws // cannot be thrown; keep the declared name
        }
    }

    // -- spec step 4: visibility ------------------------------------------------------

    private fun fieldAccess(field: FieldInfo): Visibility = field.access.visibility

    /**
     * JLS visibility from the querying perspective (spec step 4): the target's own members
     * are always visible (even `private` — `members` on the declaring type shows
     * everything); `private` supertype members are never inherited; package-private
     * members cross only into the same package; `public`/`protected` always cross.
     */
    private fun isVisible(
        visibility: Visibility,
        declaringPackage: String,
        targetPackage: String,
        isTarget: Boolean,
    ): Boolean {
        if (isTarget) return true
        return when (visibility) {
            Visibility.PUBLIC, Visibility.PROTECTED -> true
            Visibility.PRIVATE -> false
            Visibility.PACKAGE_PRIVATE -> declaringPackage == targetPackage
        }
    }

    // -- spec step 6: synthetic filtering -----------------------------------------------

    /**
     * Bridge and synthetic members are compiler machinery, hidden by default. Bit-overlap
     * warning ([dev.jdx.core.model.AccessFlag]): `BRIDGE` shares its mask with `VOLATILE`,
     * so the bridge bit is only meaningful on methods — fields check `SYNTHETIC` alone.
     */
    private fun isSyntheticMethod(method: MethodInfo): Boolean =
        method.access.has(AccessFlag.SYNTHETIC) || method.access.has(AccessFlag.BRIDGE)

    private fun isSyntheticField(field: FieldInfo): Boolean =
        field.access.has(AccessFlag.SYNTHETIC)

    // -- plumbing -------------------------------------------------------------------------

    private data class MethodKey(val name: String, val erasedDescriptor: String)

    private data class QueueEntry(
        val type: TypeName.ClassType,
        val info: ClassInfo,
        val environment: Map<String, ReferenceTypeSignature>,
        val depth: Int,
    )

    private data class LinearNode(
        val type: TypeName.ClassType,
        val depth: Int,
        /** `null` when the supertype is unknown to [lookup] — reported, not fatal. */
        val info: ClassInfo?,
        val environment: Map<String, ReferenceTypeSignature>,
    )

    private fun binaryToType(binary: String): TypeName {
        // Missing supertypes arrive as binary names from real class files; parsing cannot
        // fail on them, but a defensive fallback keeps resolution total regardless.
        return try {
            typeNameFromBinary(binary)
        } catch (_: IllegalArgumentException) {
            TypeName.ClassType("", listOf(binary))
        }
    }

    private fun typeNameFromBinary(binary: String): TypeName =
        dev.jdx.core.model.typeNameFromBinaryName(binary)
}

/**
 * Options for [MemberResolver.resolve]. Only one knob today — more (access floors,
 * kind filters, supertype scoping) arrive with the `members` command (T-011), which is
 * why the parameter is a data class and not a bare boolean.
 */
public data class MemberResolutionOptions(
    /** Show bridge/synthetic members (`--include-synthetic`); hidden by default. */
    public val includeSynthetic: Boolean = false,
)

/** One visited supertype: its name and its BFS depth below the target (target = 0). */
public data class SupertypeNode(
    public val type: TypeName.ClassType,
    public val depth: Int,
)

/**
 * A resolved method: the declaration, where it was declared, how deep below the target,
 * its generic signature substituted into the target's context (`null` when the method is
 * not generic), and the supertypes whose same-signature declarations it overrode.
 */
public data class ResolvedMethod(
    public val declaringType: TypeName.ClassType,
    public val depth: Int,
    public val member: MethodInfo,
    public val substitutedSignature: MethodSignature?,
    public val overriddenTypes: List<TypeName.ClassType>,
)

/**
 * A resolved field: like [ResolvedMethod], but hiding is by name (JLS §8.3 — a subclass
 * field hides a same-named superclass field whatever its type), recorded in
 * [hiddenTypes].
 */
public data class ResolvedField(
    public val declaringType: TypeName.ClassType,
    public val depth: Int,
    public val member: FieldInfo,
    public val substitutedSignature: FieldSignature?,
    public val hiddenTypes: List<TypeName.ClassType>,
)

/**
 * The full answer: inherited + declared members, the linearisation that produced them
 * (target first, `java.lang.Object` last), and supertypes the [lookup] could not
 * provide — skipped gracefully, never fatal.
 */
public data class ResolvedMembers(
    public val methods: List<ResolvedMethod>,
    public val fields: List<ResolvedField>,
    public val linearization: List<SupertypeNode>,
    public val missingSupertypes: List<TypeName>,
)
