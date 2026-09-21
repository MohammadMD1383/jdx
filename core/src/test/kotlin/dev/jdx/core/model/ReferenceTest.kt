package dev.jdx.core.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Example tests for the reference-edge model (T-029, TESTING.md §3).
 *
 * `core` is test-first (D-020): these pin the vocabulary the extractor
 * (index) and the graph queries (M4) share — closed kind set, canonical
 * ordering, value equality.
 */
class ReferenceTest {

    private fun callEdge(
        fromMember: String = "run",
        toOwner: String = "com.example.Service",
        toMember: String? = "execute",
        toDescriptor: String? = "()V",
    ): ReferenceEdge = ReferenceEdge(
        fromClass = "com.example.Client",
        fromMember = fromMember,
        fromDescriptor = "()V",
        toOwner = toOwner,
        toMember = toMember,
        toDescriptor = toDescriptor,
        kind = ReferenceKind.METHOD_CALL,
    )

    @Test
    fun `the kind set is exactly the four documented edge kinds`() {
        ReferenceKind.entries.map { it.name } shouldBe
            listOf("METHOD_CALL", "FIELD_READ", "FIELD_WRITE", "TYPE_REFERENCE")
    }

    @Test
    fun `a pure type edge carries no member`() {
        val edge = ReferenceEdge(
            fromClass = "com.example.Client",
            fromMember = "<init>",
            fromDescriptor = "()V",
            toOwner = "com.example.Service",
            toMember = null,
            toDescriptor = null,
            kind = ReferenceKind.TYPE_REFERENCE,
        )
        edge.toMember shouldBe null
        edge.toDescriptor shouldBe null
    }

    @Test
    fun `edges are values, equal when every field matches`() {
        callEdge() shouldBe callEdge()
    }

    @Test
    fun `sortedEdges orders by source then kind then target`() {
        val read = callEdge().copy(kind = ReferenceKind.FIELD_READ, toMember = "count")
        val write = callEdge().copy(kind = ReferenceKind.FIELD_WRITE, toMember = "count")
        val call = callEdge()
        val otherClass = callEdge().copy(toOwner = "com.example.Other")
        listOf(write, otherClass, call, read).sortedEdges() shouldBe
            listOf(otherClass, call, read, write)
    }

    @Test
    fun `sortedEdges is idempotent`() {
        val edges = listOf(
            callEdge(fromMember = "b"),
            callEdge(fromMember = "a"),
            callEdge().copy(kind = ReferenceKind.TYPE_REFERENCE, toMember = null, toDescriptor = null),
        )
        edges.sortedEdges().sortedEdges() shouldBe edges.sortedEdges()
    }

    @Test
    fun `null member sorts before a named member within the same kind and owner`() {
        val named = callEdge().copy(
            kind = ReferenceKind.TYPE_REFERENCE,
            toMember = "Nested",
            toDescriptor = null,
        )
        val unnamed = callEdge().copy(
            kind = ReferenceKind.TYPE_REFERENCE,
            toMember = null,
            toDescriptor = null,
        )
        listOf(named, unnamed).sortedEdges() shouldBe listOf(unnamed, named)
    }
}
