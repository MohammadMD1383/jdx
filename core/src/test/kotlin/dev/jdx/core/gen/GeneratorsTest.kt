package dev.jdx.core.gen

import dev.jdx.core.model.ArrayTypeSignature
import dev.jdx.core.model.ClassTypeSignature
import dev.jdx.core.model.JvmPrimitive
import dev.jdx.core.model.TypeArgument
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.TypeVariableSignature
import io.kotest.matchers.shouldBe
import io.kotest.property.RandomSource
import org.junit.jupiter.api.Test

/**
 * Coverage pins for the shared generators (T-055, TESTING.md §4): the acceptance bar says
 * generators must produce genuinely nasty values, so this suite draws a fixed seed and
 * asserts each nasty shape actually appears — a generator that only *claims* nastiness
 * would fail here on the first run.
 */
class GeneratorsTest {

    private fun <T> draw(arb: io.kotest.property.Arb<T>, count: Int, seed: Long): List<T> =
        arb.samples(RandomSource.seeded(seed)).take(count).map { it.value }.toList()

    @Test
    fun `pinned config carries the seed the docs promise`() {
        // Pins the one-liner in PropertySupport's KDoc: if this API drifts, the documented
        // seed-pinning workflow is a lie.
        pinnedConfig(987654321L).seed shouldBe 987654321L
    }

    @Test
    fun `type names cover empty packages unicode nesting and multidim arrays`() {
        val drawn = draw(arbTypeName(), 2_000, seed = 7L)
        drawn.any { it.packageName.isEmpty() } shouldBe true
        drawn.any { type -> type.simpleName.any { c -> c.code > 127 } } shouldBe true
        drawn.any { it is TypeName.ClassType && it.nestedNames.size >= 2 } shouldBe true
        drawn.any { it is TypeName.ArrayType && it.dimensions >= 2 } shouldBe true
        drawn.any { it is TypeName.PrimitiveType } shouldBe true
    }

    @Test
    fun `field types never produce void`() {
        val drawn = draw(arbFieldTypeName(), 2_000, seed = 11L)
        drawn.none { it == TypeName.PrimitiveType(JvmPrimitive.VOID) } shouldBe true
        drawn.any { it is TypeName.ArrayType } shouldBe true
    }

    @Test
    fun `method descriptors cover empty params void return and array params`() {
        val drawn = draw(arbJvmMethodDescriptor(), 2_000, seed = 13L)
        drawn.any { it.parameters.isEmpty() } shouldBe true
        drawn.any { it.returnType == TypeName.PrimitiveType(JvmPrimitive.VOID) } shouldBe true
        drawn.any { method -> method.parameters.any { it is TypeName.ArrayType } } shouldBe true
    }

    @Test
    fun `signatures cover all wildcard kinds type variables nesting and inner classes`() {
        val drawn = draw(arbGenericSignature(), 3_000, seed = 17L)
        val arguments = drawn.flatMap { it.allTypeArguments() }
        arguments.any { it is TypeArgument.Unbounded } shouldBe true
        arguments.any { it is TypeArgument.UpperBounded } shouldBe true
        arguments.any { it is TypeArgument.LowerBounded } shouldBe true
        arguments.any { it is TypeArgument.Exact } shouldBe true
        drawn.any { it.mentionsTypeVariable() } shouldBe true
        drawn.any { it.isNestedGeneric() } shouldBe true
        drawn.any { it.hasInnerClass() } shouldBe true
        drawn.any { it.hasEmptyClassBound() } shouldBe true
    }

    @Test
    fun `method signatures cover type parameters void return arrays and throws`() {
        val drawn = draw(arbMethodSignature(), 2_000, seed = 19L)
        drawn.any { it.typeParameters.isNotEmpty() } shouldBe true
        drawn.any { it.returnType == dev.jdx.core.model.VoidSignature } shouldBe true
        drawn.any { method -> method.parameters.any { it is ArrayTypeSignature } } shouldBe true
        drawn.any { it.throwsSignatures.isNotEmpty() } shouldBe true
    }

    // ---------------------------------------------------------------- helpers

    private fun dev.jdx.core.model.GenericSignature.allTypeArguments(): List<TypeArgument> {
        fun argsOf(type: dev.jdx.core.model.TypeSignature): List<TypeArgument> = when (type) {
            is ClassTypeSignature ->
                type.typeArguments + type.innerClasses.flatMap { it.typeArguments } +
                    type.typeArguments.flatMap { (it as? TypeArgument.Exact)?.type?.let(::argsOf) ?: emptyList() } +
                    type.typeArguments.flatMap {
                        when (it) {
                            is TypeArgument.UpperBounded -> argsOf(it.bound)
                            is TypeArgument.LowerBounded -> argsOf(it.bound)
                            else -> emptyList()
                        }
                    }
            is ArrayTypeSignature -> argsOf(type.elementType)
            else -> emptyList()
        }
        return when (this) {
            is dev.jdx.core.model.ClassSignature ->
                superclass.let(::argsOf) + superinterfaces.flatMap(::argsOf)
            is dev.jdx.core.model.MethodSignature ->
                parameters.flatMap(::argsOf) + argsOf(returnType)
            is dev.jdx.core.model.FieldSignature -> argsOf(type)
        }
    }

    private fun dev.jdx.core.model.GenericSignature.mentionsTypeVariable(): Boolean {
        fun hasVar(type: dev.jdx.core.model.TypeSignature): Boolean = when (type) {
            is TypeVariableSignature -> true
            is ArrayTypeSignature -> hasVar(type.elementType)
            is ClassTypeSignature ->
                type.typeArguments.any {
                    when (it) {
                        is TypeArgument.Exact -> hasVar(it.type)
                        is TypeArgument.UpperBounded -> hasVar(it.bound)
                        is TypeArgument.LowerBounded -> hasVar(it.bound)
                        else -> false
                    }
                } || type.innerClasses.any { inner ->
                    inner.typeArguments.any {
                        it is TypeArgument.Exact && hasVar(it.type)
                    }
                }
            else -> false
        }
        return when (this) {
            is dev.jdx.core.model.ClassSignature ->
                typeParameters.isNotEmpty() || hasVar(superclass) || superinterfaces.any(::hasVar)
            is dev.jdx.core.model.MethodSignature ->
                typeParameters.isNotEmpty() || parameters.any(::hasVar) || hasVar(returnType)
            is dev.jdx.core.model.FieldSignature -> hasVar(type)
        }
    }

    private fun dev.jdx.core.model.GenericSignature.isNestedGeneric(): Boolean {
        // A type argument whose own type carries further arguments: Map<String, List<Integer>>.
        fun nested(type: dev.jdx.core.model.TypeSignature): Boolean = when (type) {
            is ClassTypeSignature ->
                type.typeArguments.any {
                    (it as? TypeArgument.Exact)?.type is ClassTypeSignature &&
                        ((it.type as ClassTypeSignature).typeArguments.isNotEmpty() || nested(it.type))
                }
            is ArrayTypeSignature -> nested(type.elementType)
            else -> false
        }
        return when (this) {
            is dev.jdx.core.model.ClassSignature -> nested(superclass) || superinterfaces.any(::nested)
            is dev.jdx.core.model.MethodSignature -> parameters.any(::nested) || nested(returnType)
            is dev.jdx.core.model.FieldSignature -> nested(type)
        }
    }

    private fun dev.jdx.core.model.GenericSignature.hasInnerClass(): Boolean {
        fun inner(type: dev.jdx.core.model.TypeSignature): Boolean = when (type) {
            is ClassTypeSignature -> type.innerClasses.isNotEmpty()
            is ArrayTypeSignature -> inner(type.elementType)
            else -> false
        }
        return when (this) {
            is dev.jdx.core.model.ClassSignature -> inner(superclass) || superinterfaces.any(::inner)
            is dev.jdx.core.model.MethodSignature -> parameters.any(::inner) || inner(returnType)
            is dev.jdx.core.model.FieldSignature -> inner(type)
        }
    }

    private fun dev.jdx.core.model.GenericSignature.hasEmptyClassBound(): Boolean =
        when (this) {
            is dev.jdx.core.model.ClassSignature ->
                typeParameters.any { it.classBound == null }
            is dev.jdx.core.model.MethodSignature ->
                typeParameters.any { it.classBound == null }
            else -> false
        }
}
