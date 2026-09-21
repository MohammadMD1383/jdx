package dev.jdx.core.model

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative invariants for reference edges (T-029, TESTING.md §4).
 *
 * Hand-written orderings plateau at what the author imagined; these keep
 * inventing edges — hostile names, null members, every kind — and demand the
 * canonical order hold for all of them.
 */
class ReferencePropertyTest {

    private fun arbEdge(): Arb<ReferenceEdge> {
        val binary = Arb.stringPattern("[a-z]{1,8}(\\.[a-z]{1,8}){0,2}")
            .map { it.ifEmpty { "a" } }
        val member = Arb.element(listOf("run", "get", "<init>", "<clinit>", "m\$1", "ünïcode"))
        val descriptor = Arb.element(listOf("()V", "(I)V", "(Ljava/lang/String;)I", "()Ljava/lang/Object;"))
        return Arb.bind(binary, member, descriptor, binary, member.orNull(), descriptor.orNull(), Arb.element(ReferenceKind.entries)) {
                fromClass, fromMember, fromDescriptor, toOwner, toMember, toDescriptor, kind ->
            // A pure type edge carries no member by construction (see ReferenceEdge KDoc).
            if (kind == ReferenceKind.TYPE_REFERENCE) {
                ReferenceEdge(fromClass, fromMember, fromDescriptor, toOwner, null, null, kind)
            } else {
                ReferenceEdge(fromClass, fromMember, fromDescriptor, toOwner, toMember, toDescriptor, kind)
            }
        }
    }

    @Test
    fun `sortedEdges is sorted`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbEdge(), 0..12)) { edges ->
            val sorted = edges.sortedEdges()
            val pairs = sorted.zipWithNext()
            pairs.forEach { (a, b) ->
                (ReferenceEdge.compare(a, b) <= 0) shouldBe true
            }
        }
    }

    @Test
    fun `sortedEdges is a fixed point`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbEdge(), 0..12)) { edges ->
            edges.sortedEdges().sortedEdges() shouldBe edges.sortedEdges()
        }
    }

    @Test
    fun `compare is consistent with equality`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, arbEdge(), arbEdge()) { a, b ->
            if (a == b) {
                ReferenceEdge.compare(a, b) shouldBe 0
            }
        }
    }
}
