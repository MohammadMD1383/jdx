package dev.jdx.index.store

import dev.jdx.core.model.Access
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.ReferenceEdge
import dev.jdx.core.model.ReferenceKind
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.index.store.sqlite.SqliteIndexStore
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Opcodes

/**
 * Tier-2 example tests for the reference-edge store (T-029, TESTING.md §13).
 *
 * Tagged `tier2`: every test opens a real SQLite file on disk. The suite
 * speaks only the [IndexStore] interface — edge persistence is reachable
 * without SQL, and the `ref` table stays an implementation detail.
 */
@Tag("tier2")
class ReferenceStoreTest {

    @TempDir
    private lateinit var tempDir: Path

    private fun open(name: String = "refs.db"): SqliteIndexStore =
        SqliteIndexStore.open(tempDir.resolve(name))

    private fun method(name: String, descriptor: String): MethodInfo =
        MethodInfo(
            name = name,
            descriptor = JvmDescriptor.parse(descriptor) as JvmDescriptor.Method,
            access = Access(Opcodes.ACC_PUBLIC),
        )

    private fun testClass(binary: String, vararg methods: MethodInfo): ClassInfo =
        ClassInfo(
            name = typeNameFromBinaryName(binary) as dev.jdx.core.model.TypeName.ClassType,
            kind = TypeKind.CLASS,
            methods = methods.toList(),
        )

    private fun edge(
        fromMember: String = "run",
        toOwner: String = "com.example.Service",
        toMember: String? = "execute",
        toDescriptor: String? = "()V",
        kind: ReferenceKind = ReferenceKind.METHOD_CALL,
    ): ReferenceEdge = ReferenceEdge(
        fromClass = "com.example.Client",
        fromMember = fromMember,
        fromDescriptor = "()V",
        toOwner = toOwner,
        toMember = toMember,
        toDescriptor = toDescriptor,
        kind = kind,
    )

    private fun storedFixture(store: IndexStore): StoredArtifact {
        val artifact = store.upsertArtifact(NewArtifact(hash = "r".repeat(32), path = "/refs.jar"))
        store.replaceClasses(
            artifact.id,
            listOf(
                testClass("com.example.Client", method("run", "()V"), method("stop", "()V")),
                testClass("com.example.Service", method("execute", "()V"), method("execute", "(I)V")),
            ),
        )
        return artifact
    }

    @Test
    fun `an empty edge list stores as zero references`() {
        open().use { store ->
            val artifact = storedFixture(store)
            store.replaceReferences(artifact.id, emptyList())
            store.countReferences(artifact.id) shouldBe 0
            store.findReferencesTo("com.example.Service") shouldBe emptyList()
        }
    }

    @Test
    fun `a call edge survives the round-trip with its artifact`() {
        open().use { store ->
            val artifact = storedFixture(store)
            store.replaceReferences(artifact.id, listOf(edge()))
            store.countReferences(artifact.id) shouldBe 1
            val hits = store.findReferencesTo("com.example.Service")
            hits.size shouldBe 1
            val hit = hits.single()
            hit.artifact shouldBe artifact
            hit.fromClass shouldBe "com.example.Client"
            hit.fromMember shouldBe "run"
            hit.fromDescriptor shouldBe "()V"
            hit.toOwner shouldBe "com.example.Service"
            hit.toMember shouldBe "execute"
            hit.toDescriptor shouldBe "()V"
            hit.kind shouldBe ReferenceKind.METHOD_CALL
        }
    }

    @Test
    fun `a member query matches every overload, a descriptor query one`() {
        open().use { store ->
            val artifact = storedFixture(store)
            store.replaceReferences(
                artifact.id,
                listOf(
                    edge(toDescriptor = "()V"),
                    edge(toDescriptor = "(I)V"),
                ),
            )
            // The member query is overload-blind by design (usages of the name).
            store.findReferencesTo("com.example.Service", "execute").size shouldBe 2
            // The descriptor query narrows to one overload.
            val narrow = store.findReferencesTo("com.example.Service", "execute", "(I)V")
            narrow.size shouldBe 1
            narrow.single().toDescriptor shouldBe "(I)V"
            // A descriptor without a member is ignored: the type query stands.
            store.findReferencesTo("com.example.Service", null, "(I)V").size shouldBe 2
        }
    }

    @Test
    fun `a class query matches calls to its members as references to the type`() {
        open().use { store ->
            val artifact = storedFixture(store)
            store.replaceReferences(
                artifact.id,
                listOf(
                    edge(kind = ReferenceKind.METHOD_CALL),
                    edge(kind = ReferenceKind.TYPE_REFERENCE, toMember = null, toDescriptor = null),
                ),
            )
            store.findReferencesTo("com.example.Service").size shouldBe 2
        }
    }

    @Test
    fun `an unknown member query finds nothing`() {
        open().use { store ->
            val artifact = storedFixture(store)
            store.replaceReferences(artifact.id, listOf(edge()))
            store.findReferencesTo("com.example.Service", "missing") shouldBe emptyList()
            store.findReferencesTo("com.example.Missing") shouldBe emptyList()
        }
    }

    @Test
    fun `edges naming no stored method are skipped`() {
        open().use { store ->
            val artifact = storedFixture(store)
            store.replaceReferences(
                artifact.id,
                listOf(
                    edge(),
                    // No `ghost` method was stored for Client: defensive skip.
                    edge(fromMember = "ghost"),
                    // No `Ghost` class was stored at all.
                    edge().copy(fromClass = "com.example.Ghost"),
                ),
            )
            store.countReferences(artifact.id) shouldBe 1
        }
    }

    @Test
    fun `replacing classes clears the edges`() {
        open().use { store ->
            val artifact = storedFixture(store)
            store.replaceReferences(artifact.id, listOf(edge()))
            store.countReferences(artifact.id) shouldBe 1
            // The scoped delete rides along: fresh classes, no stale edges.
            store.replaceClasses(artifact.id, listOf(testClass("com.example.Client", method("run", "()V"))))
            store.countReferences(artifact.id) shouldBe 0
            store.findReferencesTo("com.example.Service") shouldBe emptyList()
        }
    }

    @Test
    fun `replacing references twice is idempotent`() {
        open().use { store ->
            val artifact = storedFixture(store)
            val edges = listOf(
                edge(toDescriptor = "()V"),
                edge(toDescriptor = "(I)V", fromMember = "stop"),
                edge(kind = ReferenceKind.FIELD_READ, toMember = "count", toDescriptor = "I"),
            )
            store.replaceReferences(artifact.id, edges)
            val first = store.findReferencesTo("com.example.Service")
            store.replaceReferences(artifact.id, edges)
            store.findReferencesTo("com.example.Service") shouldBe first
            store.countReferences(artifact.id) shouldBe 3
        }
    }

    @Test
    fun `deleting the artifact drops its edges`() {
        open().use { store ->
            val artifact = storedFixture(store)
            store.replaceReferences(artifact.id, listOf(edge()))
            store.deleteArtifactByHash(artifact.hash) shouldBe true
            store.findReferencesTo("com.example.Service") shouldBe emptyList()
        }
    }

    @Test
    fun `hits are ordered by artifact hash then source`() {
        open().use { store ->
            val first = store.upsertArtifact(NewArtifact(hash = "b".repeat(32), path = "/b.jar"))
            store.replaceClasses(first.id, listOf(testClass("com.example.Client", method("run", "()V"))))
            val second = store.upsertArtifact(NewArtifact(hash = "a".repeat(32), path = "/a.jar"))
            store.replaceClasses(second.id, listOf(testClass("com.example.Client", method("run", "()V"))))
            store.replaceReferences(first.id, listOf(edge(fromMember = "run")))
            store.replaceReferences(second.id, listOf(edge(fromMember = "run")))
            // Hash order wins over insertion order (D-007).
            store.findReferencesTo("com.example.Service").map { it.artifact.hash } shouldBe
                listOf("a".repeat(32), "b".repeat(32))
        }
    }
}
