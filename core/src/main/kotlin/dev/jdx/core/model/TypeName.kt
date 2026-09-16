package dev.jdx.core.model

/**
 * A JVM type name in one of its three shapes: a class/interface type, an array type, or a
 * primitive. Produced by the `index` module (from bytecode descriptors) and by `core`'s own
 * parsers; consumed everywhere.
 *
 * The three name forms:
 *  - [binaryName] — JVMS §4.2.1 form, `java.util.Map$Entry`; for arrays, the field descriptor.
 *  - [fqn] — fully-qualified dotted form, `java.util.Map.Entry`; for arrays, `T[]`.
 *  - [simpleName] — the last name segment without package or nesting, e.g. `Entry`, `int[]`.
 *
 * Note on `$`: a `$` in a binary name is treated as a nesting separator. Top-level class
 * names that themselves contain `$` (obfuscated jars) are indistinguishable from nested
 * classes at the name level — an accepted, documented limitation.
 */
public sealed interface TypeName {
    /** The internal/binary name (JVMS §4.2.1). For arrays this is the field descriptor. */
    public val binaryName: String

    /** The fully-qualified dotted name. For arrays, `element[]` per dimension. */
    public val fqn: String

    /** The last name segment, without package or enclosing classes. */
    public val simpleName: String

    /** The package name (`""` for the default package, primitives and arrays of them). */
    public val packageName: String

    /**
     * A class, interface, enum, record or annotation type. [nestedNames] is never empty:
     * the first entry is the top-level class, later entries are nested classes
     * (`Map.Entry` is `["Map", "Entry"]`). Package names are dotted (`java.util`).
     */
    public data class ClassType(
        override val packageName: String,
        public val nestedNames: List<String>,
    ) : TypeName {
        init {
            // Invariant: every name segment is a bare identifier — no separators sneak in via
            // a constructor call. Parsers that meet `$`/`.` must split before constructing.
            require(nestedNames.isNotEmpty()) { "a ClassType needs at least one name segment" }
            nestedNames.forEach { segment ->
                require(segment.isNotEmpty()) { "empty name segment in $nestedNames" }
                require(segment.none { it in ".\$;[/" }) { "name segment '$segment' contains a separator" }
            }
        }

        override val binaryName: String
            get() = joinNames(separator = '$')

        override val fqn: String
            get() = joinNames(separator = '.')

        override val simpleName: String
            get() = nestedNames.last()

        private fun joinNames(separator: Char): String =
            (if (packageName.isEmpty()) "" else "$packageName.") +
                nestedNames.joinToString(separator.toString())
    }

    /**
     * An array type. Invariant: [elementType] is never itself an [ArrayType] — dimensions
     * accumulate on a single instance. Use [arrayTypeName] to construct, it enforces this.
     */
    public data class ArrayType(
        public val elementType: TypeName,
        public val dimensions: Int,
    ) : TypeName {
        init {
            require(dimensions >= 1) { "array dimensions must be >= 1" }
            require(elementType !is ArrayType) { "nest arrays via arrayTypeName(), not ArrayType(ArrayType(..))" }
        }

        override val binaryName: String
            get() = descriptor

        override val fqn: String
            get() = elementType.fqn + "[]".repeat(dimensions)

        override val simpleName: String
            get() = elementType.simpleName + "[]".repeat(dimensions)

        override val packageName: String
            get() = elementType.packageName
    }

    /** One of the nine JVM primitive types, including `void`. */
    public data class PrimitiveType(public val primitive: JvmPrimitive) : TypeName {
        override val binaryName: String get() = primitive.keyword
        override val fqn: String get() = primitive.keyword
        override val simpleName: String get() = primitive.keyword
        override val packageName: String get() = ""
    }

    /** The field descriptor (JVMS §4.3.2): `Ljava/lang/String;`, `I`, `[[J`. */
    public val descriptor: String
        get() = when (this) {
            is ClassType -> "L" + binaryName.replace('.', '/') + ";"
            is ArrayType -> "[".repeat(dimensions) + elementType.descriptor
            is PrimitiveType -> primitive.descriptor.toString()
        }
}

/** The nine JVM primitives with their keyword and descriptor character (JVMS §4.3.2). */
public enum class JvmPrimitive(
    /** The Java keyword, used as the type's name in source-like output. */
    public val keyword: String,
    /** The single descriptor character. */
    public val descriptor: Char,
) {
    VOID("void", 'V'),
    BOOLEAN("boolean", 'Z'),
    BYTE("byte", 'B'),
    CHAR("char", 'C'),
    SHORT("short", 'S'),
    INT("int", 'I'),
    LONG("long", 'J'),
    FLOAT("float", 'F'),
    DOUBLE("double", 'D'),
}

/**
 * Constructs an array type, flattening nested array dimensions so the invariant
 * "elementType is never an ArrayType" holds however callers build the value.
 */
public fun arrayTypeName(elementType: TypeName, dimensions: Int): TypeName.ArrayType =
    when (elementType) {
        is TypeName.ArrayType -> TypeName.ArrayType(elementType.elementType, elementType.dimensions + dimensions)
        else -> TypeName.ArrayType(elementType, dimensions)
    }

/**
 * Parses a binary name (JVMS §4.2.1, `java.util.Map$Entry`) into a [TypeName].
 * Also accepts array descriptors (`[Ljava/lang/String;`, `[[J`) and primitive keywords.
 *
 * Throws [IllegalArgumentException] on empty names or empty nesting segments — callers
 * produce binary names from trusted sources (class files, the ref parser), so a malformed
 * one is a bug, not an agent-triggerable error.
 */
public fun typeNameFromBinaryName(binary: String): TypeName {
    require(binary.isNotEmpty()) { "empty binary name" }

    if (binary[0] == '[') {
        var dimensions = 0
        var index = 0
        while (index < binary.length && binary[index] == '[') {
            dimensions++
            index++
        }
        val element = parseDescriptorType(binary, index)
            ?: throw IllegalArgumentException("malformed array binary name: $binary")
        if (element.second != binary.length) {
            throw IllegalArgumentException("trailing characters in array binary name: $binary")
        }
        return arrayTypeName(element.first, dimensions)
    }

    JvmPrimitive.entries
        .firstOrNull { it.keyword == binary }
        ?.let { return TypeName.PrimitiveType(it) }

    val firstDollar = binary.indexOf('$')
    val head = if (firstDollar == -1) binary else binary.substring(0, firstDollar)
    val rest = if (firstDollar == -1) emptyList() else binary.substring(firstDollar + 1).split('$')

    val lastDot = head.lastIndexOf('.')
    val packageName = if (lastDot == -1) "" else head.substring(0, lastDot)
    val topLevelName = if (lastDot == -1) head else head.substring(lastDot + 1)

    val nestedNames = buildList {
        add(topLevelName)
        addAll(rest)
    }
    return TypeName.ClassType(packageName, nestedNames)
}

/**
 * Parses one descriptor type starting at [startIndex] in [text]; returns the type and the
 * index just past it. Accepts `L...;` class types and the eight non-void base types —
 * `V` is not a field type, and array prefixes are handled by callers that count `[` first.
 * Shared by [typeNameFromBinaryName] (array binary names) and [JvmDescriptor.parse].
 * Returns `null` on malformed input.
 */
internal fun parseDescriptorType(text: String, startIndex: Int): Pair<TypeName, Int>? {
    if (startIndex >= text.length) return null
    return when (val c = text[startIndex]) {
        'L' -> {
            val end = text.indexOf(';', startIndex + 1)
            if (end == -1) return null
            val internal = text.substring(startIndex + 1, end)
            if (internal.isEmpty()) return null
            // The `L...;` payload is untrusted class-file text: empty segments (`L$Entry;`),
            // separators in segments (`L/;`), or garbage (`L1C)Q.;`) make typeNameFromBinaryName
            // throw, but this function's contract is null-on-malformed (T-055 property).
            try {
                typeNameFromBinaryName(internal.replace('/', '.')) to end + 1
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        else -> {
            val primitive = JvmPrimitive.entries.firstOrNull { it.descriptor == c } ?: return null
            if (primitive == JvmPrimitive.VOID) return null // 'V' is not a field type
            TypeName.PrimitiveType(primitive) to startIndex + 1
        }
    }
}
