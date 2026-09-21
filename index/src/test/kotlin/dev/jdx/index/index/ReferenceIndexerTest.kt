package dev.jdx.index.index

import dev.jdx.core.model.ReferenceKind
import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.store.sqlite.SqliteIndexStore
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Tier-2 tests for reference-edge indexing (T-029, TESTING.md §6/§7).
 *
 * Tagged `tier2`: every test crafts a real jar on disk and indexes it into a
 * real SQLite file. The generative bar rides on two family members: a
 * metamorphic determinism test (index the same jar into two stores ⟹
 * identical edge rows) and a fault-injection case (a truncated class entry
 * still indexes its healthy neighbours with their edges).
 */
@Tag("tier2")
class ReferenceIndexerTest {

    private fun openStore(dir: Path, name: String = "refs.db"): SqliteIndexStore =
        SqliteIndexStore.open(dir.resolve(name))

    /** `com.example.Service`: a plain target with one method and one field. */
    private fun serviceBytes(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Service", null, "java/lang/Object", null)
        val field = writer.visitField(Opcodes.ACC_PRIVATE, "count", "I", null, null)
        field.visitEnd()
        val body = writer.visitMethod(Opcodes.ACC_PUBLIC, "execute", "()V", null, null)
        body.visitCode()
        body.visitInsn(Opcodes.RETURN)
        body.visitMaxs(0, 0)
        body.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** `com.example.Client#run` calling `Service#execute` and reading `Service#count`. */
    private fun clientBytes(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Client", null, "java/lang/Object", null)
        val body = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null)
        body.visitCode()
        body.visitTypeInsn(Opcodes.NEW, "com/example/Service")
        body.visitInsn(Opcodes.DUP)
        body.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/example/Service", "<init>", "()V", false)
        body.visitVarInsn(Opcodes.ASTORE, 1)
        body.visitVarInsn(Opcodes.ALOAD, 1)
        body.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/example/Service", "execute", "()V", false)
        body.visitVarInsn(Opcodes.ALOAD, 1)
        body.visitFieldInsn(Opcodes.GETFIELD, "com/example/Service", "count", "I")
        body.visitInsn(Opcodes.POP)
        body.visitInsn(Opcodes.RETURN)
        body.visitMaxs(0, 0)
        body.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun craftEdgeJar(dir: Path, name: String = "edges.jar"): Path =
        ArtifactTestJars.craftJar(
            dir.resolve(name),
            mapOf(
                "com/example/Service.class" to serviceBytes(),
                "com/example/Client.class" to clientBytes(),
            ),
        )

    @Test
    fun `indexing stores the call and field edges`(@TempDir temp: Path) {
        openStore(temp).use { store ->
            val jar = craftEdgeJar(temp)
            val result = ArtifactIndexer.indexOne(store, jar)
            result.status shouldBe EntryStatus.INDEXED
            val id = requireNotNull(result.artifactId)
            (store.countReferences(id) > 0) shouldBe true
            val calls = store.findReferencesTo("com.example.Service", "execute", "()V")
            calls.size shouldBe 1
            calls.single().fromClass shouldBe "com.example.Client"
            calls.single().fromMember shouldBe "run"
            calls.single().kind shouldBe ReferenceKind.METHOD_CALL
            val reads = store.findReferencesTo("com.example.Service", "count", "I")
            reads.size shouldBe 1
            reads.single().kind shouldBe ReferenceKind.FIELD_READ
            // The constructor call is a genuine edge too.
            val ctors = store.findReferencesTo("com.example.Service", "<init>", "()V")
            ctors.size shouldBe 1
        }
    }

    @Test
    fun `indexing the same jar into two stores yields identical edge rows`(@TempDir temp: Path) {
        val jar = craftEdgeJar(temp)
        openStore(temp, "first.db").use { first ->
            openStore(temp, "second.db").use { second ->
                ArtifactIndexer.indexOne(first, jar)
                ArtifactIndexer.indexOne(second, jar)
                val firstHits = first.findReferencesTo("com.example.Service")
                val secondHits = second.findReferencesTo("com.example.Service")
                // Artifact rows differ by path/id, so compare the edge payload.
                fun payload(hits: List<dev.jdx.index.store.ReferenceHit>): List<String> =
                    hits.map {
                        "${it.fromClass}#${it.fromMember}${it.fromDescriptor} " +
                            "${it.kind} ${it.toOwner}#${it.toMember}${it.toDescriptor}"
                    }
                secondHits.size shouldBe firstHits.size
                payload(secondHits) shouldBe payload(firstHits)
                second.classCount(second.listArtifacts().single().id) shouldBe
                    first.classCount(first.listArtifacts().single().id)
            }
        }
    }

    @Test
    fun `a short-circuit run leaves the stored edges untouched`(@TempDir temp: Path) {
        openStore(temp).use { store ->
            val jar = craftEdgeJar(temp)
            val first = ArtifactIndexer.indexOne(store, jar)
            first.status shouldBe EntryStatus.INDEXED
            val id = requireNotNull(first.artifactId)
            val before = store.findReferencesTo("com.example.Service")
            (before.isNotEmpty()) shouldBe true
            val second = ArtifactIndexer.indexOne(store, jar)
            second.status shouldBe EntryStatus.SKIPPED
            store.findReferencesTo("com.example.Service") shouldBe before
            store.countReferences(id) shouldBe before.size
        }
    }

    @Test
    fun `a truncated entry warns but keeps its neighbours edges`(@TempDir temp: Path) {
        openStore(temp).use { store ->
            val full = clientBytes()
            val jar = ArtifactTestJars.craftJar(
                temp.resolve("partial.jar"),
                mapOf(
                    "com/example/Service.class" to serviceBytes(),
                    "com/example/Client.class" to full.copyOf(full.size / 2),
                ),
            )
            val result = ArtifactIndexer.indexOne(store, jar)
            result.status shouldBe EntryStatus.INDEXED
            // The truncated client warns; the healthy service still indexes.
            result.warnings.any { it.code == dev.jdx.core.model.WarningCode.CORRUPT_CLASS } shouldBe true
            result.classCount shouldBe 1
            val id = requireNotNull(result.artifactId)
            // No caller was readable, so no edges — but the store is consistent.
            store.countReferences(id) shouldBe 0
            store.findReferencesTo("com.example.Service") shouldBe emptyList()
        }
    }

    @Test
    fun `an empty jar stores zero edges`(@TempDir temp: Path) {
        openStore(temp).use { store ->
            val jar = ArtifactTestJars.craftJar(temp.resolve("empty.jar"), emptyMap())
            val result = ArtifactIndexer.indexOne(store, jar)
            result.status shouldBe EntryStatus.INDEXED
            store.countReferences(requireNotNull(result.artifactId)) shouldBe 0
        }
    }
}
