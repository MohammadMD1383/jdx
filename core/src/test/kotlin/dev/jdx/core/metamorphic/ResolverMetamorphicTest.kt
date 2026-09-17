package dev.jdx.core.metamorphic

import dev.jdx.core.gen.arbClassGraph
import dev.jdx.core.gen.arbSyntheticToggle
import dev.jdx.core.gen.graphLookup
import dev.jdx.core.gen.graphTarget
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.Visibility
import dev.jdx.core.resolve.MemberResolutionOptions
import dev.jdx.core.resolve.MemberResolver
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative metamorphic relations for member resolution (T-058, docs/TESTING.md §6).
 *
 * Metamorphic testing asserts relations between distinct queries or between subgraphs
 * rather than hardcoded expected values. The resolver relations here test:
 *
 * 1. `members(T, --inherited) ⊇ members(T, --declared)`:
 *    every declared member of T appears in T's inherited listing under its own declaring type.
 * 2. `members(T, --inherited) ⊇ members(super(T), --inherited) minus private/overridden`:
 *    every accessible, non-overridden member inherited by T's superclass is also inherited by T.
 * 3. Determinism: resolving T twice over the same graph yields identical [dev.jdx.core.resolve.ResolvedMembers].
 *
 * Relations from TESTING.md §6 that require later milestone components are catalogued below:
 * - `hierarchy(T, --down) ⟺ hierarchy(S, --up)`: M4 / T-032 (`jdx hierarchy`)
 * - `usages(X) re-read references X`: M4 / T-030 (`jdx usages`)
 * - `callers(M) ⟺ calls(C)`: M4 / T-033 (`jdx callers`)
 * - `body(M) parsed back has signature == signature(M)`: M3 / T-022 (`jdx body`)
 *
 * The end-to-end service and index metamorphic relations over fixture jars and the real corpus
 * live in `index/.../metamorphic/MetamorphicTest.kt` (tier 2) and
 * `index/.../metamorphic/MetamorphicCorpusSoakTest.kt` (tier 3 / soak).
 */
class ResolverMetamorphicTest {

    @Test
    fun `declared members are always a subset of inherited members`() = runBlocking<Unit> {
        // Relation 1 (TESTING.md §6): members(T, --inherited) ⊇ members(T, --declared)
        checkAll(1000, arbClassGraph(), arbSyntheticToggle()) { graph, includeSynthetic ->
            val target = graphTarget(graph)
            val lookup = graphLookup(graph)
            val options = MemberResolutionOptions(includeSynthetic = includeSynthetic)
            val resolved = MemberResolver.resolve(target, lookup, options)

            val declaredMethods = target.methods
                .filter { it.name != "<clinit>" }
                .filter { includeSynthetic || !isSynthetic(it) }
                .map { it.name to it.descriptor.descriptor }
                .toSet()

            val resolvedDeclaredMethods = resolved.methods
                .filter { it.declaringType == target.name && it.depth == 0 }
                .map { it.member.name to it.member.descriptor.descriptor }
                .toSet()

            resolvedDeclaredMethods shouldBe declaredMethods

            val declaredFields = target.fields
                .filter { includeSynthetic || !it.access.has(AccessFlag.SYNTHETIC) }
                .map { it.name }
                .toSet()

            val resolvedDeclaredFields = resolved.fields
                .filter { it.declaringType == target.name && it.depth == 0 }
                .map { it.member.name }
                .toSet()

            resolvedDeclaredFields shouldBe declaredFields
        }
    }

    @Test
    fun `inherited members include supertype members minus private and overridden`() = runBlocking<Unit> {
        // Relation 2 (TESTING.md §6):
        // members(T, --inherited) ⊇ members(super(T), --inherited) minus private/overridden
        checkAll(1000, arbClassGraph(), arbSyntheticToggle()) { graph, includeSynthetic ->
            val target = graphTarget(graph)
            val superRef = target.superclass ?: return@checkAll
            val superClass = graphLookup(graph)(superRef) ?: return@checkAll
            // Exclude trivial direct self-cycles where super is target
            if (superClass.name.binaryName == target.name.binaryName) return@checkAll

            val lookup = graphLookup(graph)
            val options = MemberResolutionOptions(includeSynthetic = includeSynthetic)
            val superResolved = MemberResolver.resolve(superClass, lookup, options)
            val targetResolved = MemberResolver.resolve(target, lookup, options)

            for (superMethod in superResolved.methods) {
                val m = superMethod.member
                if (m.name == "<init>") continue // ctors never inherited
                if (m.access.visibility == Visibility.PRIVATE) continue // private never inherited
                if (m.access.visibility == Visibility.PACKAGE_PRIVATE &&
                    target.name.packageName != superMethod.declaringType.packageName
                ) continue // package-private not accessible across packages

                // Must be present in target's inherited methods (or collapsed via override)
                val found = targetResolved.methods.any {
                    it.member.name == m.name &&
                        it.member.descriptor.descriptor == m.descriptor.descriptor &&
                        (it.declaringType == superMethod.declaringType || superMethod.declaringType in it.overriddenTypes)
                }
                found shouldBe true
            }

            for (superField in superResolved.fields) {
                val f = superField.member
                if (f.access.visibility == Visibility.PRIVATE) continue
                if (f.access.visibility == Visibility.PACKAGE_PRIVATE &&
                    target.name.packageName != superField.declaringType.packageName
                ) continue

                // Must be present in target's inherited fields (or hidden by a nearer declaration)
                val found = targetResolved.fields.any {
                    it.member.name == f.name &&
                        (it.declaringType == superField.declaringType || superField.declaringType in it.hiddenTypes)
                }
                found shouldBe true
            }
        }
    }

    @Test
    fun `resolution is strictly deterministic across runs`() = runBlocking<Unit> {
        // Relation 10 (TESTING.md §6): run twice ⟹ identical result
        checkAll(1000, arbClassGraph(), arbSyntheticToggle()) { graph, includeSynthetic ->
            val target = graphTarget(graph)
            val lookup = graphLookup(graph)
            val options = MemberResolutionOptions(includeSynthetic = includeSynthetic)
            val run1 = MemberResolver.resolve(target, lookup, options)
            val run2 = MemberResolver.resolve(target, lookup, options)
            run1 shouldBe run2
        }
    }

    private fun isSynthetic(m: MethodInfo): Boolean =
        m.access.has(AccessFlag.SYNTHETIC) || m.access.has(AccessFlag.BRIDGE)
}
