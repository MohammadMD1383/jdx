package dev.jdx.index.cache

import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.ReferenceEdge
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.index.store.ClassHit
import dev.jdx.index.store.IndexStore
import dev.jdx.index.store.NewArtifact
import dev.jdx.index.store.ReferenceHit
import dev.jdx.index.store.SqliteSchemaVersion
import dev.jdx.index.store.StoredArtifact

/**
 * In-memory [IndexStore] for tier-1 cache tests (T-018): the reference,
 * totals and idempotence policies are provable with no SQLite and no disk.
 * Real persistence stays in tier 2 (`CacheServiceStoreTest`).
 */
internal class FakeIndexStore : IndexStore {
    private var nextId = 1L
    private val artifacts = linkedMapOf<String, StoredArtifact>()
    private val classes = mutableMapOf<Long, List<ClassInfo>>()
    private val references = mutableMapOf<Long, List<ReferenceEdge>>()

    override fun schemaVersion(): Int = SqliteSchemaVersion.CURRENT

    override fun upsertArtifact(newArtifact: NewArtifact): StoredArtifact {
        val kept = artifacts[newArtifact.hash]
        val stored = StoredArtifact(
            id = kept?.id ?: nextId++,
            hash = newArtifact.hash,
            path = newArtifact.path,
            sourcesPath = newArtifact.sourcesPath,
            kind = newArtifact.kind,
            jarMtime = newArtifact.jarMtime,
            jarSize = newArtifact.jarSize,
            indexedAt = 0,
            schemaVer = SqliteSchemaVersion.CURRENT,
        )
        artifacts[newArtifact.hash] = stored
        return stored
    }

    override fun findArtifactByHash(hash: String): StoredArtifact? = artifacts[hash]

    override fun listArtifacts(): List<StoredArtifact> = artifacts.values.sortedBy { it.hash }

    override fun deleteArtifactByHash(hash: String): Boolean {
        val removed = artifacts.remove(hash) ?: return false
        classes.remove(removed.id)
        references.remove(removed.id)
        return true
    }

    override fun replaceClasses(artifactId: Long, classes: List<ClassInfo>) {
        this.classes[artifactId] = classes.toList()
        // Mirrors the SQLite scoped delete: replacing classes drops the edges.
        this.references.remove(artifactId)
    }

    override fun findClassesByFqn(binaryName: String): List<ClassHit> =
        artifacts.values.sortedBy { it.hash }.flatMap { artifact ->
            (classes[artifact.id].orEmpty()).filter { it.name.binaryName == binaryName }
                .map { ClassHit(artifact, it) }
        }

    override fun loadClass(artifactId: Long, binaryName: String): ClassInfo? =
        classes[artifactId].orEmpty().firstOrNull { it.name.binaryName == binaryName }

    override fun listClassFqns(artifactId: Long): List<String> =
        classes[artifactId].orEmpty().map { it.name.binaryName }.sorted()

    override fun classCount(artifactId: Long): Int = classes[artifactId].orEmpty().size

    override fun replaceReferences(artifactId: Long, edges: List<ReferenceEdge>) {
        references[artifactId] = edges.sortedWith(ReferenceEdge::compare)
    }

    override fun findReferencesTo(
        toFqn: String,
        toMember: String?,
        toDescriptor: String?,
    ): List<ReferenceHit> = artifacts.values.sortedBy { it.hash }.flatMap { artifact ->
        references[artifact.id].orEmpty()
            .filter { edge ->
                edge.toOwner == toFqn &&
                    (toMember == null || edge.toMember == toMember) &&
                    (toDescriptor == null || toMember == null || edge.toDescriptor == toDescriptor)
            }
            .map { edge ->
                ReferenceHit(
                    artifact = artifact,
                    fromClass = edge.fromClass,
                    fromMember = edge.fromMember,
                    fromDescriptor = edge.fromDescriptor,
                    toOwner = edge.toOwner,
                    toMember = edge.toMember,
                    toDescriptor = edge.toDescriptor,
                    kind = edge.kind,
                )
            }
    }

    override fun countReferences(artifactId: Long): Int = references[artifactId].orEmpty().size

    override fun close() = Unit
}

/** One distinct class per (artifact, index) pair — names never collide across fakes. */
internal fun fakeClass(artifactTag: String, index: Int): ClassInfo {
    val name = typeNameFromBinaryName("com.example.${artifactTag}Foo$index") as TypeName.ClassType
    return ClassInfo(name = name, kind = TypeKind.CLASS)
}

/** Stores an artifact with [classCount] classes; returns the stored row. */
internal fun FakeIndexStore.addArtifact(hash: String, path: String, classCount: Int, kind: String = "BINARY_JAR"): StoredArtifact {
    val stored = upsertArtifact(NewArtifact(hash = hash, path = path, kind = kind))
    // The tag feeds a binary name, so it must be identifier-safe: hex plus a
    // non-negative disambiguator (a raw hashCode would smuggle in `-`).
    val tag = hash.take(4) + "x" + (path.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
    replaceClasses(stored.id, List(classCount) { i -> fakeClass(tag, i) })
    return stored
}
