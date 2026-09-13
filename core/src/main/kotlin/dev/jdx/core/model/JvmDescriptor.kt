package dev.jdx.core.model

/**
 * A JVM field or method descriptor (JVMS §4.3), e.g. `Ljava/lang/String;` or
 * `(Ljava/lang/String;I[Z)V`. Produced by the `index` module from bytecode;
 * [parse] exists in `core` because descriptor parsing is pure string work.
 *
 * Descriptors are erased — generics never appear in them (that is [GenericSignature]'s job).
 */
public sealed interface JvmDescriptor {
    /** The descriptor text, exactly as it appears in a class file. */
    public val descriptor: String

    /** A field descriptor (JVMS §4.3.2). */
    public data class Field(public val type: TypeName) : JvmDescriptor {
        override val descriptor: String get() = type.descriptor
    }

    /**
     * A method descriptor (JVMS §4.3.3). Constructors and static initialisers are modelled
     * as methods named `<init>` / `<clinit>` with the appropriate descriptor.
     */
    public data class Method(
        public val parameters: List<TypeName>,
        public val returnType: TypeName,
    ) : JvmDescriptor {
        override val descriptor: String
            get() = "(" + parameters.joinToString("") { it.descriptor } + ")" + returnType.descriptor
    }

    public companion object {
        /**
         * Parses a field or method descriptor. Dispatches on the leading `(`.
         * Returns `null` on malformed input — never throws; descriptors arrive from
         * class files we do not control (fault injection, TESTING.md §7).
         */
        public fun parse(text: String): JvmDescriptor? {
            if (text.isEmpty()) return null
            return if (text[0] == '(') parseMethod(text) else parseField(text)
        }

        /** Builds a [Field] descriptor from a [TypeName]. */
        public fun of(type: TypeName): Field = Field(type)

        private fun parseField(text: String): JvmDescriptor? {
            val result = parseFieldTypeWithArrays(text, 0) ?: return null
            if (result.second != text.length) return null
            return Field(result.first)
        }

        private fun parseMethod(text: String): JvmDescriptor? {
            var index = 1 // past '('
            val parameters = mutableListOf<TypeName>()
            while (index < text.length && text[index] != ')') {
                val parsed = parseFieldTypeWithArrays(text, index) ?: return null
                parameters.add(parsed.first)
                index = parsed.second
            }
            if (index >= text.length) return null // unclosed parameter list
            index++ // past ')'
            val returnType: TypeName
            if (index < text.length && text[index] == 'V') {
                // Return type may be void, which is not a valid field type.
                index++
                returnType = TypeName.PrimitiveType(JvmPrimitive.VOID)
            } else {
                val parsed = parseFieldTypeWithArrays(text, index) ?: return null
                returnType = parsed.first
                index = parsed.second
            }
            if (index != text.length) return null
            return Method(parameters, returnType)
        }

        /** Parses one field type, including array dimensions (`[[J`), from [startIndex]. */
        private fun parseFieldTypeWithArrays(text: String, startIndex: Int): Pair<TypeName, Int>? {
            var index = startIndex
            var dimensions = 0
            while (index < text.length && text[index] == '[') {
                dimensions++
                index++
            }
            val parsed = parseDescriptorType(text, index) ?: return null
            val type = if (dimensions == 0) parsed.first else arrayTypeName(parsed.first, dimensions)
            return type to parsed.second
        }
    }
}
