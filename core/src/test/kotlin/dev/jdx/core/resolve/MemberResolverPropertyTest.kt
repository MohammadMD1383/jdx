package dev.jdx.core.resolve

import dev.jdx.core.gen.arbClassGraph
import dev.jdx.core.gen.arbSyntheticToggle
import dev.jdx.core.gen.graphLookup
import dev.jdx.core.gen.graphTarget
import dev.jdx.core.model.Visibility
import io.kotest.property.checkAll
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative invariants for member resolution (T-009, TESTING.md §4 — the family that
 * keeps generating cases after the examples stop). Every property runs 1,000 graphs:
 * cyclic, colliding, dangling hierarchies from [arbClassGraph].
 */
class MemberResolverPropertyTest {

    @Test
    fun `resolution is deterministic across runs`() = runBlocking<Unit> {
        checkAll(1000, arbClassGraph(), arbSyntheticToggle()) { graph, includeSynthetic ->
            val lookup = graphLookup(graph)
            val target = graphTarget(graph)
            val options = MemberResolutionOptions(includeSynthetic = includeSynthetic)
            MemberResolver.resolve(target, lookup, options) shouldBe
                MemberResolver.resolve(target, lookup, options)
        }
    }

    @Test
    fun `resolution of a cyclic hierarchy terminates without duplicates`() = runBlocking<Unit> {
        checkAll(1000, arbClassGraph()) { graph ->
            val resolved = MemberResolver.resolve(graphTarget(graph), graphLookup(graph))
            val binaries = resolved.linearization.map { it.type.binaryName }
            binaries.toSet().size shouldBe binaries.size
        }
    }

    @Test
    fun `declared members survive resolution`() = runBlocking<Unit> {
        // members(T, --inherited) ⊇ members(T, --declared): every non-clinit declared
        // member is present under its own declaring type (TESTING.md §6).
        checkAll(1000, arbClassGraph(), arbSyntheticToggle()) { graph, includeSynthetic ->
            val target = graphTarget(graph)
            val options = MemberResolutionOptions(includeSynthetic = includeSynthetic)
            val resolved = MemberResolver.resolve(target, graphLookup(graph), options)
            val declaredMethods = target.methods
                .filter { it.name != "<clinit>" }
                .filter { includeSynthetic || !isTestSynthetic(it) }
                .map { it.name to it.descriptor.descriptor }.toSet()
            val resolvedOwn = resolved.methods
                .filter { it.declaringType == target.name }
                .map { it.member.name to it.member.descriptor.descriptor }.toSet()
            resolvedOwn shouldBe declaredMethods
        }
    }

    @Test
    fun `no private member of a strict supertype ever leaks through`() = runBlocking<Unit> {
        checkAll(1000, arbClassGraph(), arbSyntheticToggle()) { graph, includeSynthetic ->
            val target = graphTarget(graph)
            val options = MemberResolutionOptions(includeSynthetic = includeSynthetic)
            val resolved = MemberResolver.resolve(target, graphLookup(graph), options)
            resolved.methods
                .filter { it.declaringType != target.name }
                .none { it.member.access.visibility == Visibility.PRIVATE } shouldBe true
            resolved.fields
                .filter { it.declaringType != target.name }
                .none { it.member.access.visibility == Visibility.PRIVATE } shouldBe true
        }
    }

    @Test
    fun `one erased method signature resolves to at most one member`() = runBlocking<Unit> {
        // Override collapse is total: no (name, erased-descriptor) pair appears twice.
        checkAll(1000, arbClassGraph(), arbSyntheticToggle()) { graph, includeSynthetic ->
            val options = MemberResolutionOptions(includeSynthetic = includeSynthetic)
            val resolved = MemberResolver.resolve(graphTarget(graph), graphLookup(graph), options)
            val keys = resolved.methods.map { it.member.name to it.member.descriptor.descriptor }
            keys.toSet().size shouldBe keys.size
        }
    }

    @Test
    fun `including synthetics only ever adds members`() = runBlocking<Unit> {
        checkAll(1000, arbClassGraph()) { graph ->
            val target = graphTarget(graph)
            val lookup = graphLookup(graph)
            val hidden = MemberResolver.resolve(target, lookup, MemberResolutionOptions(false))
            val shown = MemberResolver.resolve(target, lookup, MemberResolutionOptions(true))
            val hiddenKeys = hidden.methods.map { it.member.name to it.member.descriptor.descriptor }.toSet()
            val shownKeys = shown.methods.map { it.member.name to it.member.descriptor.descriptor }.toSet()
            shownKeys.containsAll(hiddenKeys) shouldBe true
        }
    }

    private fun isTestSynthetic(member: dev.jdx.core.model.MethodInfo): Boolean =
        member.access.has(dev.jdx.core.model.AccessFlag.SYNTHETIC) ||
            member.access.has(dev.jdx.core.model.AccessFlag.BRIDGE)
}
