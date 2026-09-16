package dev.jdx.core.model

/**
 * A Java generic signature (JVMS §4.7.9.1) — the `Signature` attribute of a class, method
 * or field. Unlike [JvmDescriptor], signatures carry generics: type variables, type
 * arguments and wildcards. Produced by the `index` module from bytecode; parsing lives in
 * `core` because it is pure string work.
 *
 * The top-level entry point is [GenericSignature.parse]; every implementation prints its
 * canonical form via [GenericSignature.signature], and
 * `parse(sig).signature == sig` for every well-formed signature (the round-trip invariant).
 */
public sealed interface GenericSignature {
    /** The signature text, in the exact class-file grammar form. */
    public val signature: String

    public companion object {
        /**
         * Parses a class, method or field signature. Dispatch:
         * leading `(` (after any type parameters) → method; otherwise a field signature
         * when there are no type parameters, a class signature when there are.
         *
         * Ambiguity note: a field signature (`LFoo;`) is also a valid parameterless class
         * signature (a superclass, no interfaces). When there are no type parameters we
         * prefer the field reading and only fall back to the class reading when the field
         * reading leaves input unconsumed (a superclass followed by interfaces).
         * Returns `null` on malformed input — never throws (errors are values).
         */
        public fun parse(text: String): GenericSignature? {
            val parser = SignatureParser(text)
            val typeParameters = parser.parseFormalTypeParameters() ?: return null
            if (parser.peek() == '(') {
                return parser.parseMethodSignature(typeParameters)?.takeIf { parser.isAtEnd }
            }
            if (typeParameters.isEmpty()) {
                val field = parser.parseFieldSignature()
                if (field != null && parser.isAtEnd) return field
                // Retry as a class signature: `<no params> Superclass Interface*`.
                val classParser = SignatureParser(text)
                return classParser.parseClassSignature(emptyList())?.takeIf { classParser.isAtEnd }
            }
            return parser.parseClassSignature(typeParameters)?.takeIf { parser.isAtEnd }
        }

        /**
         * Parses a class-file `Signature` attribute known to belong to a class, interface,
         * enum or record declaration. Unlike [parse], this never prefers the field
         * reading: a lone superclass (`Lt/ArrayList<Ljava/lang/String;>;` — `class
         * StringList extends ArrayList<String>` with no interfaces) is a [ClassSignature]
         * here, because the attribute grammar says so (JVMS §4.7.9.1: ClassSignature).
         * Returns `null` on malformed input — never throws.
         */
        public fun parseClass(text: String): ClassSignature? {
            val parser = SignatureParser(text)
            val typeParameters = parser.parseFormalTypeParameters() ?: return null
            return parser.parseClassSignature(typeParameters)?.takeIf { parser.isAtEnd }
        }
    }
}

/** A class signature: optional type parameters, a superclass, then superinterfaces. */
public data class ClassSignature(
    public val typeParameters: List<FormalTypeParameter>,
    public val superclass: ClassTypeSignature,
    public val superinterfaces: List<ClassTypeSignature>,
) : GenericSignature {
    override val signature: String
        get() = buildString {
            appendFormalTypeParameters(typeParameters)
            append(superclass.signature)
            superinterfaces.forEach { append(it.signature) }
        }
}

/**
 * A method signature: optional type parameters, parameters, return type ([VoidSignature]
 * for `V`), then optional throws clauses. Constructors and static initialisers are
 * modelled as methods named `<init>` / `<clinit>`.
 */
public data class MethodSignature(
    public val typeParameters: List<FormalTypeParameter>,
    public val parameters: List<TypeSignature>,
    public val returnType: TypeSignature,
    public val throwsSignatures: List<ThrowsSignature>,
) : GenericSignature {
    override val signature: String
        get() = buildString {
            appendFormalTypeParameters(typeParameters)
            append('(')
            parameters.forEach { append(it.signature) }
            append(')')
            append(returnType.signature)
            throwsSignatures.forEach { append(it.signature) }
        }
}

/** A field signature: a single reference type (never a primitive, never `V`). */
public data class FieldSignature(public val type: ReferenceTypeSignature) : GenericSignature {
    override val signature: String get() = type.signature
}

/** The `V` return type — only valid as a method return, modelled explicitly. */
public data object VoidSignature : TypeSignature {
    override val signature: String get() = "V"
}

/** Any type position inside a signature: base type, type variable, array, or reference. */
public sealed interface TypeSignature {
    public val signature: String
}

/** One of the eight non-void base types appearing in a method signature, e.g. `I`. */
public data class BaseTypeSignature(public val primitive: JvmPrimitive) : TypeSignature {
    init {
        require(primitive != JvmPrimitive.VOID) { "'V' is only valid as a method return type" }
    }

    override val signature: String get() = primitive.descriptor.toString()
}

/** A type-variable use, `TT;` — the letter `T` is part of the syntax. */
public data class TypeVariableSignature(public val name: String) : TypeSignature, ReferenceTypeSignature {
    override val signature: String get() = "T$name;"
}

/** An array type use, e.g. `[Ljava/lang/String;` or `[I`. */
public data class ArrayTypeSignature(public val elementType: TypeSignature) : TypeSignature, ReferenceTypeSignature {
    override val signature: String get() = "[" + elementType.signature
}

/**
 * A reference type position (class, type variable, or array). Exists so bounds and type
 * arguments can require non-primitive types, per the grammar.
 */
public sealed interface ReferenceTypeSignature : TypeSignature

/**
 * A parameterised class type use, e.g. `Ljava/util/List<Ljava/lang/String;>;`, including
 * inner-class suffixes (`...Map<TKey;TValue;>.Entry;`) which carry their own type arguments.
 * [packageName] is dotted (`java.util`); the class-file form uses `/` and printing converts.
 */
public data class ClassTypeSignature(
    public val packageName: String,
    public val simpleName: String,
    public val typeArguments: List<TypeArgument>,
    public val innerClasses: List<InnerClassType>,
) : ReferenceTypeSignature {
    override val signature: String
        get() = buildString {
            append('L')
            if (packageName.isNotEmpty()) {
                append(packageName.replace('.', '/'))
                append('/')
            }
            append(simpleName)
            appendTypeArguments(typeArguments)
            innerClasses.forEach { inner ->
                append('.')
                append(inner.simpleName)
                appendTypeArguments(inner.typeArguments)
            }
            append(';')
        }
}

/** An inner-class segment of a [ClassTypeSignature], with its own type arguments. */
public data class InnerClassType(
    public val simpleName: String,
    public val typeArguments: List<TypeArgument>,
)

/** A type argument inside `<>`: unbounded `*`, `+` extends, `-` super, or a plain type. */
public sealed interface TypeArgument {
    /** Prints a type argument, shared with the bare (unannotated) form used in `<>`. */
    public val signature: String

    /** `*` — an unbounded wildcard. */
    public data object Unbounded : TypeArgument {
        override val signature: String get() = "*"
    }

    /** `+X` — a wildcard with an upper bound. */
    public data class UpperBounded(public val bound: ReferenceTypeSignature) : TypeArgument {
        override val signature: String get() = "+" + bound.signature
    }

    /** `-X` — a wildcard with a lower bound. */
    public data class LowerBounded(public val bound: ReferenceTypeSignature) : TypeArgument {
        override val signature: String get() = "-" + bound.signature
    }

    /** A bare type argument, printed with no marker. */
    public data class Exact(public val type: ReferenceTypeSignature) : TypeArgument {
        override val signature: String get() = type.signature
    }
}

/**
 * A declared type parameter of a class or method signature. The class bound may be absent
 * (empty in the text, modelled as `null` — this happens when the first bound is an
 * interface, e.g. `<T::Ljava/lang/Comparable<TT;>;>`).
 */
public data class FormalTypeParameter(
    public val name: String,
    public val classBound: ReferenceTypeSignature?,
    public val interfaceBounds: List<ReferenceTypeSignature>,
) {
    public val signature: String
        get() = buildString {
            append(name)
            append(':')
            append(classBound?.signature ?: "")
            interfaceBounds.forEach {
                append(':')
                append(it.signature)
            }
        }
}

/** A `throws` clause of a method signature: `^TT;` or `^Lcom/Example;`. */
public sealed interface ThrowsSignature {
    public val signature: String

    public data class ClassThrows(public val classType: ClassTypeSignature) : ThrowsSignature {
        override val signature: String get() = "^" + classType.signature
    }

    public data class TypeVariableThrows(public val name: String) : ThrowsSignature {
        override val signature: String get() = "^T$name;"
    }
}

private fun StringBuilder.appendFormalTypeParameters(parameters: List<FormalTypeParameter>) {
    if (parameters.isEmpty()) return
    append('<')
    parameters.forEach { append(it.signature) }
    append('>')
}

private fun StringBuilder.appendTypeArguments(arguments: List<TypeArgument>) {
    if (arguments.isEmpty()) return
    append('<')
    arguments.forEach { append(it.signature) }
    append('>')
}

/**
 * Recursive-descent parser over the JVMS §4.7.9.1 grammar. Every parse function returns
 * `null` on the first grammar violation, and the caller additionally requires that the
 * entire input is consumed — trailing garbage is malformed.
 */
private class SignatureParser(private val text: String) {
    private var pos = 0

    val isAtEnd: Boolean get() = pos == text.length

    fun peek(): Char? = if (pos < text.length) text[pos] else null

    private fun advance(): Char = text[pos++]

    private fun expect(expected: Char): Boolean =
        if (peek() == expected) {
            pos++
            true
        } else {
            false
        }

    /** Reads identifier characters — anything not structural in this grammar. */
    private fun readIdentifier(): String? {
        val start = pos
        while (pos < text.length && text[pos] !in ".;[/<>:") pos++
        return if (pos == start) null else text.substring(start, pos)
    }

    fun parseFormalTypeParameters(): List<FormalTypeParameter>? {
        if (peek() != '<') return emptyList()
        pos++ // past '<'
        val parameters = mutableListOf<FormalTypeParameter>()
        while (peek() != null && peek() != '>') {
            val name = readIdentifier() ?: return null
            if (!expect(':')) return null
            // The class bound may be empty — that happens exactly when the next character
            // is another ':' (an interface bound follows) or the list ends.
            val classBound = when (peek()) {
                'L', 'T', '[' -> parseReferenceTypeSignature() ?: return null
                else -> null
            }
            val interfaceBounds = mutableListOf<ReferenceTypeSignature>()
            while (peek() == ':') {
                pos++
                interfaceBounds.add(parseReferenceTypeSignature() ?: return null)
            }
            parameters.add(FormalTypeParameter(name, classBound, interfaceBounds))
        }
        if (!expect('>')) return null
        return parameters
    }

    fun parseFieldSignature(): FieldSignature? {
        val type = parseReferenceTypeSignature() ?: return null
        return FieldSignature(type)
    }

    fun parseClassSignature(typeParameters: List<FormalTypeParameter>): ClassSignature? {
        val superclass = parseClassTypeSignature() ?: return null
        val interfaces = mutableListOf<ClassTypeSignature>()
        while (peek() == 'L') {
            interfaces.add(parseClassTypeSignature() ?: return null)
        }
        return ClassSignature(typeParameters, superclass, interfaces)
    }

    fun parseMethodSignature(typeParameters: List<FormalTypeParameter>): MethodSignature? {
        if (!expect('(')) return null
        val parameters = mutableListOf<TypeSignature>()
        while (peek() != null && peek() != ')') {
            parameters.add(parseTypeSignature() ?: return null)
        }
        if (!expect(')')) return null
        val returnType = if (peek() == 'V') {
            pos++
            VoidSignature
        } else {
            parseTypeSignature() ?: return null
        }
        val throws = mutableListOf<ThrowsSignature>()
        while (peek() == '^') {
            pos++
            val clause = when (peek()) {
                'T' -> {
                    pos++
                    val name = readIdentifier() ?: return null
                    if (!expect(';')) return null
                    ThrowsSignature.TypeVariableThrows(name)
                }
                'L' -> ThrowsSignature.ClassThrows(parseClassTypeSignature() ?: return null)
                else -> return null
            }
            throws.add(clause)
        }
        return MethodSignature(typeParameters, parameters, returnType, throws)
    }

    /** A type position that also allows base types: parameters and return types. */
    private fun parseTypeSignature(): TypeSignature? =
        when (peek()) {
            'L', 'T', '[' -> parseReferenceTypeSignature()
            else -> {
                val c = peek() ?: return null
                val primitive = JvmPrimitive.entries.firstOrNull { it.descriptor == c } ?: return null
                if (primitive == JvmPrimitive.VOID) return null // 'V' handled by the caller
                pos++
                BaseTypeSignature(primitive)
            }
        }

    private fun parseReferenceTypeSignature(): ReferenceTypeSignature? =
        when (peek()) {
            'L' -> parseClassTypeSignature()
            'T' -> {
                pos++
                val name = readIdentifier() ?: return null
                if (!expect(';')) return null
                TypeVariableSignature(name)
            }
            '[' -> {
                pos++
                ArrayTypeSignature(parseTypeSignature() ?: return null)
            }
            else -> null
        }

    fun parseClassTypeSignature(): ClassTypeSignature? {
        if (!expect('L')) return null
        // Read package segments (slash-separated) up to the class name. Identifiers cannot
        // contain '/', '.', ';' or '<', so the last segment before a terminator is the
        // top-level class name.
        val segments = mutableListOf<String>()
        while (true) {
            val identifier = readIdentifier() ?: return null
            segments.add(identifier)
            if (peek() == '/') {
                pos++
                continue
            }
            break
        }
        val packageName = segments.dropLast(1).joinToString(".")
        val simpleName = segments.last()
        val typeArguments = parseTypeArguments() ?: return null
        val innerClasses = mutableListOf<InnerClassType>()
        while (peek() == '.') {
            pos++
            val innerName = readIdentifier() ?: return null
            val innerArguments = parseTypeArguments() ?: return null
            innerClasses.add(InnerClassType(innerName, innerArguments))
        }
        if (!expect(';')) return null
        return ClassTypeSignature(packageName, simpleName, typeArguments, innerClasses)
    }

    private fun parseTypeArguments(): List<TypeArgument>? {
        if (peek() != '<') return emptyList()
        pos++ // past '<'
        val arguments = mutableListOf<TypeArgument>()
        while (peek() != null && peek() != '>') {
            val argument = when (peek()) {
                '*' -> {
                    pos++
                    TypeArgument.Unbounded
                }
                '+' -> {
                    pos++
                    TypeArgument.UpperBounded(parseReferenceTypeSignature() ?: return null)
                }
                '-' -> {
                    pos++
                    TypeArgument.LowerBounded(parseReferenceTypeSignature() ?: return null)
                }
                'L', 'T', '[' -> TypeArgument.Exact(parseReferenceTypeSignature() ?: return null)
                else -> return null
            }
            arguments.add(argument)
        }
        // Grammar requires at least one argument between '<' and '>'.
        if (arguments.isEmpty() || !expect('>')) return null
        return arguments
    }
}
