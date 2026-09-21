package dev.jdx.sources

import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.ArrayTypeSignature
import dev.jdx.core.model.BaseTypeSignature
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.ClassTypeSignature
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.TypeSignature
import dev.jdx.core.model.TypeVariableSignature
import dev.jdx.core.model.VoidSignature
import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode

/**
 * `SOURCES_VERSION_MISMATCH` detection (T-028, D-009, PROPOSAL.md §11.1).
 *
 * Bytecode is the skeleton, sources are the flesh: when a `-sources.jar`
 * disagrees with its binary jar about which members a type declares (a member
 * added, removed or renamed — a real and common packaging bug), `jdx` cannot
 * be lied to about the API, but it must say so. This compares one type's
 * bytecode members ([ClassInfo]) against the same type's source declarations
 * ([listJavaMembers]) and returns a structured [Warning] when they disagree,
 * or `null` when they agree.
 *
 * The comparison is deliberately lenient where the compiler legitimately
 * widens bytecode beyond sources, so matched jars never warn:
 *
 * - synthetic/bridge members and `<clinit>` never appear in sources;
 * - an implicit default constructor (no source `<init>` at all, exactly one
 *   bytecode `<init>`) is compiler-generated;
 * - record accessors, `toString`/`hashCode`/`equals` and the compact/canonical
 *   constructor pairing are implicit unless declared;
 * - enum `values()`/`valueOf(String)` are implicit; enum constructors carry
 *   hidden `(String, int)` name/ordinal parameters;
 * - inner-class constructors carry a hidden leading outer-`this` parameter;
 * - generic spellings (`U` vs erased `Object`), varargs (`String...` vs
 *   `String[]`) and annotations/generics on source spellings all normalise to
 *   the same key.
 *
 * Pure and total: hostile names, empty lists and odd spellings return a value,
 * never throw. Callers that cannot list sources ([JavaMemberList] absences)
 * skip the check rather than warning — a missing file is a different,
 * already-labelled degradation.
 */
public fun detectSourcesMismatch(
    target: ClassInfo,
    sourceMembers: List<DeclaredSourceMember>,
    sourcesName: String,
): Warning? {
    val binary = target.name.binaryName
    val bytecode = bytecodeEntriesOf(target)
    // Static initialisers are not declarations in either language — neither
    // side can name one, so neither side is compared on it.
    val remaining = sourceMembers.filterNot { it.name == "<clinit>" }.toMutableList()
    val missingFromSources = mutableListOf<String>()

    // An implicit default constructor: no source `<init>` at all and exactly
    // one bytecode `<init>` (a default, an inner default with its outer
    // parameter, or an enum default with name/ordinal) is compiler output.
    val comparable = if (remaining.none { it.kind == SourceBodyKind.CONSTRUCTOR } &&
        bytecode.count { it.kind == EntryKind.CONSTRUCTOR } == 1
    ) {
        bytecode.filterNot { it.kind == EntryKind.CONSTRUCTOR }
    } else {
        bytecode
    }

    for (entry in comparable) {
        val match = remaining.indexOfFirst { matches(entry, it, target) }
        if (match >= 0) {
            remaining.removeAt(match)
        } else if (!isImplicitMember(target, entry)) {
            missingFromSources.add(renderBytecodeEntry(binary, entry))
        }
    }
    // Source leftovers are always explicit declarations, so every one is
    // evidence — there are no implicit source members to excuse.
    val extraInSources = remaining.map { renderSourceEntry(binary, it) }.sorted()

    if (missingFromSources.isEmpty() && extraInSources.isEmpty()) return null
    val message = buildString {
        if (extraInSources.isNotEmpty()) {
            append(sourcesName)
            append(" declares ")
            append(summarise(extraInSources))
            append(" which is absent from ")
            append(binary)
            append(". ")
        }
        if (missingFromSources.isNotEmpty()) {
            append(sourcesName)
            append(" omits ")
            append(summarise(missingFromSources.sorted()))
            append(" present in ")
            append(binary)
            append(". ")
        }
        append("Structure shown from bytecode.")
    }
    return Warning(code = WarningCode.SOURCES_VERSION_MISMATCH, message = message, subject = binary)
}

/** Bounded member summary: up to three refs, then a count. Deterministic. */
private fun summarise(refs: List<String>): String {
    if (refs.size <= 3) return refs.joinToString(", ")
    return refs.take(3).joinToString(", ") + " (+${refs.size - 3} more)"
}

/** Member kinds for pairing; source `ENUM_ENTRY` constants are bytecode fields. */
private enum class EntryKind { METHOD, CONSTRUCTOR, FIELD }

/** One bytecode member reduced to its pairing key. */
private data class BytecodeEntry(
    val kind: EntryKind,
    val name: String,
    /** Erased descriptor keys, e.g. `[Object]`, `[String]` (`String...`/`[]` collapsed). */
    val erasedKeys: List<String>,
    /** Generic-signature keys when present (`U` for `U identity(U)`), else null. */
    val genericKeys: List<String>?,
    /** Whether the member is static — record accessors never are. */
    val isStatic: Boolean,
)

/** Declared (non-synthetic, non-`<clinit>`) bytecode members in declaration order. */
private fun bytecodeEntriesOf(target: ClassInfo): List<BytecodeEntry> {
    val fields = target.fields
        .filterNot { it.access.has(AccessFlag.SYNTHETIC) }
        .map { BytecodeEntry(EntryKind.FIELD, it.name, emptyList(), null, isStatic = false) }
    val methods = target.methods.mapNotNull { method ->
        if (method.name == "<clinit>") return@mapNotNull null
        // The bridge bit shares its mask with `VOLATILE`, so it is only
        // meaningful on methods — fields check `SYNTHETIC` alone (as in
        // `MemberResolver` and `JdxService.isSyntheticMember`).
        if (method.access.has(AccessFlag.SYNTHETIC) || method.access.has(AccessFlag.BRIDGE)) {
            return@mapNotNull null
        }
        val kind = if (method.name == "<init>") EntryKind.CONSTRUCTOR else EntryKind.METHOD
        BytecodeEntry(
            kind = kind,
            name = method.name,
            erasedKeys = method.descriptor.parameters.map(::bytecodeTypeKey),
            genericKeys = method.genericSignature?.parameters?.map(::signatureTypeKey),
            isStatic = method.access.has(AccessFlag.STATIC),
        )
    }
    return fields + methods
}

/**
 * Whether one source declaration pairs with one bytecode member: same kind
 * (constructors pair only with constructors, fields — and enum entries — only
 * with fields), same name, and compatible parameters.
 */
private fun matches(entry: BytecodeEntry, source: DeclaredSourceMember, target: ClassInfo): Boolean {
    if (entry.kind != sourceKindOf(source.kind)) return false
    if (entry.name != source.name) return false
    val wanted = source.parameterTypes.map(::sourceTypeKey)
    if (entry.kind == EntryKind.FIELD) return true
    // A record compact constructor declares no parameters for the canonical
    // one the compiler emits — arity cannot judge it.
    if (entry.kind == EntryKind.CONSTRUCTOR && target.kind == TypeKind.RECORD && wanted.isEmpty()) {
        return true
    }
    if (entry.erasedKeys.size == wanted.size && entry.erasedKeys == wanted) return true
    val generic = entry.genericKeys
    if (generic != null && generic.size == wanted.size && generic == wanted) return true
    // Inner-class and enum constructors carry hidden leading parameters the
    // source never spells: the outer `this`, or enum name/ordinal.
    val stripped = strippedParameters(target, entry)
    if (stripped != null && stripped.size == wanted.size && stripped == wanted) return true
    return false
}

private fun sourceKindOf(kind: SourceBodyKind): EntryKind = when (kind) {
    SourceBodyKind.METHOD -> EntryKind.METHOD
    SourceBodyKind.CONSTRUCTOR -> EntryKind.CONSTRUCTOR
    SourceBodyKind.FIELD, SourceBodyKind.ENUM_ENTRY -> EntryKind.FIELD
}

/**
 * The bytecode parameter keys minus hidden leading parameters, or null when
 * the shape does not apply: enum constructors drop `(String, int)`
 * name/ordinal; nested-class constructors drop a leading outer-`this` when it
 * names the enclosing class. Static-nested constructors match exactly, so the
 * full-keys check above already paired them — this only widens, never narrows.
 */
private fun strippedParameters(target: ClassInfo, entry: BytecodeEntry): List<String>? {
    if (entry.kind != EntryKind.CONSTRUCTOR) return null
    val keys = entry.erasedKeys
    if (target.kind == TypeKind.ENUM && keys.size >= 2 && keys[0] == "String" && keys[1] == "int") {
        return keys.drop(2)
    }
    val outer = target.outerClass
    if (outer != null && keys.isNotEmpty() && keys[0] == outer.simpleName) {
        return keys.drop(1)
    }
    return null
}

/**
 * Compiler-generated leftovers that must not warn: record accessors and the
 * `toString`/`hashCode`/`equals` trio (implicit unless declared — and declared
 * ones already matched above, so leftovers here are implicit), and enum
 * `values()`/`valueOf(String)` (which `javac` forbids redeclaring, so they can
 * never be stale). Record accessors are instance methods sharing a component
 * field's name; a static zero-arg method with a field's name is user code and
 * still mismatches.
 */
private fun isImplicitMember(target: ClassInfo, entry: BytecodeEntry): Boolean {
    if (entry.kind == EntryKind.FIELD) return false
    if (target.kind == TypeKind.RECORD && entry.kind == EntryKind.METHOD && !entry.isStatic) {
        if (entry.erasedKeys.isEmpty() &&
            (entry.name == "toString" || entry.name == "hashCode" || target.fields.any { it.name == entry.name })
        ) {
            return true
        }
        if (entry.name == "equals" && entry.erasedKeys == listOf("Object")) return true
    }
    if (target.kind == TypeKind.ENUM && entry.kind == EntryKind.METHOD) {
        if (entry.name == "values" && entry.erasedKeys.isEmpty()) return true
        if (entry.name == "valueOf" && entry.erasedKeys == listOf("String")) return true
    }
    return false
}

private fun renderBytecodeEntry(binary: String, entry: BytecodeEntry): String {
    val params = entry.erasedKeys.joinToString(", ")
    return if (entry.kind == EntryKind.FIELD) "$binary#${entry.name}" else "$binary#${entry.name}($params)"
}

private fun renderSourceEntry(binary: String, source: DeclaredSourceMember): String {
    val params = source.parameterTypes.map(::sourceTypeKey).joinToString(", ")
    return when (sourceKindOf(source.kind)) {
        EntryKind.FIELD -> "$binary#${source.name}"
        else -> "$binary#${source.name}($params)"
    }
}

/** Erased bytecode type to pairing key: arrays/varargs collapse to the element. */
private fun bytecodeTypeKey(type: TypeName): String = when (type) {
    is TypeName.ArrayType -> bytecodeTypeKey(type.elementType)
    is TypeName.PrimitiveType -> type.simpleName
    is TypeName.ClassType -> typeKeyOf(type.simpleName)
}

/** Generic-signature type to pairing key: type variables keep their name. */
private fun signatureTypeKey(signature: TypeSignature): String = when (signature) {
    is TypeVariableSignature -> signature.name
    is BaseTypeSignature -> signature.primitive.keyword
    is ArrayTypeSignature -> signatureTypeKey(signature.elementType)
    is ClassTypeSignature -> typeKeyOf(signature.simpleName)
    is VoidSignature -> "void"
}
