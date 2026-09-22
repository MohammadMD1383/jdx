package dev.jdx.core.render

import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.ArrayTypeSignature
import dev.jdx.core.model.BaseTypeSignature
import dev.jdx.core.model.ClassTypeSignature
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.FieldSignature
import dev.jdx.core.model.FormalTypeParameter
import dev.jdx.core.model.JvmPrimitive
import dev.jdx.core.model.KotlinMethodView
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.MethodSignature
import dev.jdx.core.model.ReferenceTypeSignature
import dev.jdx.core.model.TypeArgument
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.TypeSignature
import dev.jdx.core.model.TypeVariableSignature
import dev.jdx.core.model.VoidSignature

/**
 * One-line source-style signatures for member rows (T-010, D-028).
 *
 * Every type prints fully qualified with `$`-joined nesting
 * (`java.util.Map$Entry`, `java.lang.String[]`), so a text line plus its group
 * header is mechanically canonical: two types sharing a simple name never print
 * identically, and the agent recovers `Type#name(fqn-params)` without guessing.
 *
 * Preference order per position mirrors the truth model (D-009): the substituted
 * generic signature (inheritance-aware), then the declared generic signature,
 * then the erased descriptor — always present, so rendering is total.
 */
public object SignatureLines {

    /**
     * Renders one method/constructor line, e.g.
     * `public <T> T fromJson(java.lang.String arg0, java.lang.Class<T> arg1)`.
     * Constructors print under [declaringSimpleName] (`<init>` is never user-facing).
     * A `null` parameter name degrades to `argN` per the proposal's ladder (§16).
     *
     * [kotlinView] (T-077) renders the Kotlin declaration instead of the JVM
     * projection: the metadata name, the `suspend` keyword, the metadata return
     * type, and the parameter list without the hidden `Continuation`. `null`
     * (Java, unmapped Kotlin) renders exactly as before. Views never apply to
     * constructors.
     */
    public fun methodLine(
        member: MethodInfo,
        substituted: MethodSignature? = null,
        declaringSimpleName: String? = null,
        kotlinView: KotlinMethodView? = null,
    ): String = buildString {
        appendModifiers(member.access, forMethod = true)
        val view = if (member.name == "<init>") null else kotlinView
        if (view?.markSuspend == true) append("suspend ")
        val signature = substituted ?: member.genericSignature
        appendTypeParameters(signature?.typeParameters ?: emptyList())
        if (member.name == "<init>") {
            append(declaringSimpleName ?: "<init>")
        } else {
            val displayReturn = view?.displayReturn
            if (displayReturn != null) append(displayReturn)
            else appendReturnType(signature?.returnType, member.descriptor.returnType)
            append(' ')
            append(view?.displayName ?: member.name)
        }
        append('(')
        appendParameters(member, signature, view)
        append(')')
        appendThrows(signature, member)
        member.annotationDefault?.let { append(" default ").append(it) }
    }

    /** Renders one field line, e.g. `private static final long serialVersionUID = 1`. */
    public fun fieldLine(member: FieldInfo, substituted: FieldSignature? = null): String = buildString {
        appendModifiers(member.access, forMethod = false)
        val type = substituted?.type ?: member.genericSignature?.type
        if (type != null) append(renderReferenceType(type)) else append(renderTypeName(member.type))
        append(' ')
        append(member.name)
        member.constantValue?.let { append(" = ").append(it) }
    }

    /** Source-style text for an erased type: `$`-joined classes, `[]` per dimension. */
    public fun renderTypeName(name: TypeName): String = when (name) {
        is TypeName.ClassType -> name.binaryName
        is TypeName.PrimitiveType -> name.primitive.keyword
        is TypeName.ArrayType -> renderTypeName(name.elementType) + "[]".repeat(name.dimensions)
    }

    /** Source-style text for a signature type, e.g. `java.util.List<? extends T>`. */
    public fun renderTypeSignature(signature: TypeSignature): String = when (signature) {
        is VoidSignature -> JvmPrimitive.VOID.keyword
        is BaseTypeSignature -> signature.primitive.keyword
        is TypeVariableSignature -> signature.name
        is ArrayTypeSignature -> renderTypeSignature(signature.elementType) + "[]"
        is ClassTypeSignature -> renderReferenceType(signature)
    }

    /** Source-style text for a reference type (no primitives, no `void`). */
    public fun renderReferenceType(signature: ReferenceTypeSignature): String = when (signature) {
        is TypeVariableSignature -> signature.name
        is ArrayTypeSignature -> renderTypeSignature(signature.elementType) + "[]"
        is ClassTypeSignature -> buildString {
            if (signature.packageName.isNotEmpty()) append(signature.packageName).append('.')
            append(signature.simpleName)
            appendTypeArguments(signature.typeArguments)
            signature.innerClasses.forEach { inner ->
                append('.').append(inner.simpleName)
                appendTypeArguments(inner.typeArguments)
            }
        }
    }

    private fun StringBuilder.appendModifiers(access: Access, forMethod: Boolean) {
        // JLS declaration order; package-private prints nothing. Context-driven by
        // construction: methods never consult VOLATILE/TRANSIENT bits, fields never
        // consult SYNCHRONIZED/VARARGS — the JVM reuses those masks (see AccessFlag).
        // ACC_SUPER (classes only) is never consulted, so it can never print.
        if (access.has(AccessFlag.PUBLIC)) append("public ")
        else if (access.has(AccessFlag.PROTECTED)) append("protected ")
        else if (access.has(AccessFlag.PRIVATE)) append("private ")
        if (forMethod && access.has(AccessFlag.ABSTRACT)) append("abstract ")
        if (access.has(AccessFlag.STATIC)) append("static ")
        if (access.has(AccessFlag.FINAL)) append("final ")
        if (!forMethod && access.has(AccessFlag.TRANSIENT)) append("transient ")
        if (!forMethod && access.has(AccessFlag.VOLATILE)) append("volatile ")
        if (forMethod && access.has(AccessFlag.SYNCHRONIZED)) append("synchronized ")
        if (forMethod && access.has(AccessFlag.NATIVE)) append("native ")
        if (forMethod && access.has(AccessFlag.STRICTFP)) append("strictfp ")
    }

    private fun StringBuilder.appendTypeParameters(parameters: List<FormalTypeParameter>) {
        if (parameters.isEmpty()) return
        append('<')
        append(parameters.joinToString(", ") { parameter ->
            // A lone `java.lang.Object` class bound is javac's default: omit it.
            val bounds = listOfNotNull(parameter.classBound?.takeUnless { it.isJavaLangObject() }) +
                parameter.interfaceBounds
            if (bounds.isEmpty()) parameter.name else parameter.name + " extends " + bounds.joinToString(" & ") {
                renderReferenceType(it)
            }
        })
        append("> ")
    }

    private fun ReferenceTypeSignature.isJavaLangObject(): Boolean {
        val candidate = this as? ClassTypeSignature ?: return false
        return candidate.packageName == "java.lang" &&
            candidate.simpleName == "Object" &&
            candidate.typeArguments.isEmpty() &&
            candidate.innerClasses.isEmpty()
    }

    private fun StringBuilder.appendReturnType(signatureReturn: TypeSignature?, erased: TypeName) {
        if (signatureReturn != null) append(renderTypeSignature(signatureReturn))
        else append(renderTypeName(erased))
    }

    private fun StringBuilder.appendParameters(
        member: MethodInfo,
        signature: MethodSignature?,
        kotlinView: KotlinMethodView? = null,
    ) {
        val all = member.descriptor.parameters.size
        // A `suspend` view drops the hidden trailing `Continuation`; anything else
        // keeps the JVM list (the view itself guards on the tail type).
        val count = if (kotlinView?.stripAppliesTo(member.descriptor.parameters) == true) all - 1 else all
        val varargs = member.access.has(AccessFlag.VARARGS) && count > 0
        append(
            (0 until count).joinToString(", ") { index ->
                val erased = member.descriptor.parameters[index]
                val generic = signature?.parameters?.getOrNull(index)
                val name = member.parameterNames.getOrNull(index) ?: "arg$index"
                if (varargs && index == count - 1) {
                    appendVarargsElement(generic, erased) + "... " + name
                } else {
                    (if (generic != null) renderTypeSignature(generic) else renderTypeName(erased)) + " " + name
                }
            },
        )
    }

    private fun appendVarargsElement(generic: TypeSignature?, erased: TypeName): String {
        // `...` replaces one array dimension: the declared `X[]` becomes `X...`.
        val fromSignature = (generic as? ArrayTypeSignature)?.let { renderTypeSignature(it.elementType) }
        if (fromSignature != null) return fromSignature
        val array = erased as? TypeName.ArrayType ?: return renderTypeName(erased)
        return renderTypeName(array.elementType) + "[]".repeat(array.dimensions - 1)
    }

    private fun StringBuilder.appendThrows(signature: MethodSignature?, member: MethodInfo) {
        val generic = signature?.throwsSignatures
        if (!generic.isNullOrEmpty()) {
            append(" throws ")
            append(
                generic.joinToString(", ") { clause ->
                    when (clause) {
                        is dev.jdx.core.model.ThrowsSignature.ClassThrows ->
                            renderReferenceType(clause.classType)
                        is dev.jdx.core.model.ThrowsSignature.TypeVariableThrows -> clause.name
                    }
                },
            )
        } else if (member.throwsTypes.isNotEmpty()) {
            append(" throws ")
            append(member.throwsTypes.joinToString(", ") { renderTypeName(it) })
        }
    }

    private fun StringBuilder.appendTypeArguments(arguments: List<TypeArgument>) {
        if (arguments.isEmpty()) return
        append('<')
        append(
            arguments.joinToString(", ") { argument ->
                when (argument) {
                    is TypeArgument.Exact -> renderReferenceType(argument.type)
                    is TypeArgument.UpperBounded -> "? extends " + renderReferenceType(argument.bound)
                    is TypeArgument.LowerBounded -> "? super " + renderReferenceType(argument.bound)
                    is TypeArgument.Unbounded -> "?"
                }
            },
        )
        append('>')
    }
}
