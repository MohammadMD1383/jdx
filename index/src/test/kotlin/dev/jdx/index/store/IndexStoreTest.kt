package dev.jdx.index.store

import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.AnnotationInfo
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.FieldSignature
import dev.jdx.core.model.GenericSignature
import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.MethodSignature
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.index.artifact.ArtifactLoader
import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.asm.AsmClassReader
import dev.jdx.index.asm.ClassReadResult
import dev.jdx.index.store.sqlite.SqliteIndexStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tier-2 example tests for [IndexStore] (T-013, TESTING.md §13).
 *
 * Tagged `tier2`: every test opens a real SQLite file on disk. The fast loop
 * stays pure; nothing here may run in tier 1. The suite speaks only the
 * [IndexStore] interface — that is itself an assertion: everything a caller
 * needs (including the D-013 promises) is reachable without SQL. The two
 * contract checks that inherently need a PRAGMA (WAL mode, newer-schema
 * refusal) live in `sqlite.SqliteContractTest`, next to the code they pin.
 */
@Tag("tier2")
class IndexStoreTest {

    @TempDir
    private lateinit var tempDir: Path

    private fun open(name: String = "t.db"): SqliteIndexStore =
        SqliteIndexStore.open(tempDir.resolve(name))

    // -- store lifecycle --------------------------------------------------------

    @Test
    fun `a fresh store reports the current schema version and no artifacts`() {
        open().use { store ->
            store.schemaVersion() shouldBe SqliteSchemaVersion.CURRENT
            store.listArtifacts() shouldBe emptyList()
            store.findArtifactByHash("deadbeef") shouldBe null
            store.deleteArtifactByHash("deadbeef") shouldBe false
        }
    }

    @Test
    fun `upserting the same hash twice returns the same row`() {
        open().use { store ->
            val first = store.upsertArtifact(NewArtifact(hash = "a".repeat(32), path = "/x/gson.jar"))
            val second = store.upsertArtifact(
                NewArtifact(hash = "a".repeat(32), path = "/y/gson.jar", kind = "BINARY_JAR"),
            )
            second.id shouldBe first.id
            // The latest metadata wins; the identity (hash) is stable.
            second.path shouldBe "/y/gson.jar"
            second.schemaVer shouldBe SqliteSchemaVersion.CURRENT
            second.needsReindex shouldBe false
            store.listArtifacts().map { it.hash } shouldBe listOf("a".repeat(32))
        }
    }

    @Test
    fun `listArtifacts is ordered by hash`() {
        open().use { store ->
            store.upsertArtifact(NewArtifact(hash = "c".repeat(32), path = "/c.jar"))
            store.upsertArtifact(NewArtifact(hash = "a".repeat(32), path = "/a.jar"))
            store.upsertArtifact(NewArtifact(hash = "b".repeat(32), path = "/b.jar"))
            store.listArtifacts().map { it.hash } shouldBe
                listOf("a".repeat(32), "b".repeat(32), "c".repeat(32))
        }
    }

    // -- class round-trips ------------------------------------------------------

    @Test
    fun `an empty class list stores as zero classes`() {
        open().use { store ->
            val artifact = store.upsertArtifact(NewArtifact(hash = "e".repeat(32), path = "/e.jar"))
            store.replaceClasses(artifact.id, emptyList())
            store.classCount(artifact.id) shouldBe 0
            store.listClassFqns(artifact.id) shouldBe emptyList()
        }
    }

    @Test
    fun `every fixture class survives a store round-trip exactly`() {
        val infos = fixtureClassInfos()
        (infos.isNotEmpty()) shouldBe true
        open().use { store ->
            val artifact = store.upsertArtifact(NewArtifact(hash = "f".repeat(32), path = "fixtures.jar"))
            store.replaceClasses(artifact.id, infos)
            store.classCount(artifact.id) shouldBe infos.size
            val failures = mutableListOf<String>()
            for (expected in infos) {
                val actual = store.loadClass(artifact.id, expected.name.binaryName)
                if (actual == null) {
                    failures.add("${expected.name.binaryName}: missing after round-trip")
                } else if (actual != expected) {
                    failures.add("${expected.name.binaryName}: differs after round-trip")
                }
            }
            failures shouldBe emptyList()
        }
    }

    @Test
    fun `member declaration order survives the round-trip`() {
        val infos = fixtureClassInfos()
        open().use { store ->
            val artifact = store.upsertArtifact(NewArtifact(hash = "o".repeat(32), path = "/o.jar"))
            store.replaceClasses(artifact.id, infos)
            for (expected in infos) {
                val actual = store.loadClass(artifact.id, expected.name.binaryName)
                    ?: error("missing ${expected.name.binaryName}")
                actual.fields.map { it.name } shouldBe expected.fields.map { it.name }
                actual.methods.map { it.name to it.descriptor.descriptor } shouldBe
                    expected.methods.map { it.name to it.descriptor.descriptor }
            }
        }
    }

    @Test
    fun `hostile values round-trip byte-for-byte`() {
        val nasty = ClassInfo(
            name = typeNameFromBinaryName("p.Nästy\$Inner") as dev.jdx.core.model.TypeName.ClassType,
            kind = TypeKind.CLASS,
            access = Access.of(AccessFlag.PUBLIC, AccessFlag.FINAL),
            superclass = typeNameFromBinaryName("java.lang.Object"),
            interfaces = listOf(typeNameFromBinaryName("java.io.Serializable")),
            genericSignature = GenericSignature.parseClass("<T:Ljava/lang/Object;>Ljava/lang/Object;"),
            fields = listOf(
                FieldInfo(
                    name = "qu\"ote",
                    type = typeNameFromBinaryName("java.lang.String"),
                    access = Access.of(AccessFlag.PRIVATE),
                    genericSignature = GenericSignature.parse(
                        "Ljava/util/Map<Ljava/lang/String;Ljava/util/List<+Ljava/lang/Number;>;>;",
                    ) as? FieldSignature,
                    annotations = listOf(
                        AnnotationInfo(
                            typeNameFromBinaryName("java.lang.Deprecated"),
                            mapOf(
                                "forRemoval" to "true",
                                "note" to "say \"hi\" \\ bye\nnewline, comma; brace{",
                            ),
                        ),
                    ),
                    constantValue = "\"with quotes\"",
                ),
            ),
            methods = listOf(
                MethodInfo(
                    name = "λ",
                    descriptor = JvmDescriptor.parse("(Ljava/lang/Object;)Ljava/lang/Object;") as JvmDescriptor.Method,
                    access = Access.of(AccessFlag.PUBLIC),
                    genericSignature = GenericSignature.parse(
                        "<T:Ljava/lang/Object;>(TT;)TT;^Ljava/io/IOException;",
                    ) as? MethodSignature,
                    parameterNames = listOf(null, "a\"b", "ünï"),
                    throwsTypes = listOf(typeNameFromBinaryName("java.io.IOException")),
                    annotations = listOf(
                        AnnotationInfo(
                            typeNameFromBinaryName("p.Annot"),
                            mapOf("empty" to "", "unicode" to "λ→€"),
                        ),
                    ),
                    deprecated = true,
                    annotationDefault = "{1, 2}",
                ),
            ),
            annotations = listOf(
                AnnotationInfo(typeNameFromBinaryName("java.lang.SuppressWarnings"), mapOf("value" to "{\"a\", \"b\"}")),
            ),
            outerClass = typeNameFromBinaryName("p.Nästy") as dev.jdx.core.model.TypeName.ClassType,
            sourceFileName = "Nästy.java",
            deprecated = true,
        )
        open().use { store ->
            val artifact = store.upsertArtifact(NewArtifact(hash = "n".repeat(32), path = "/n.jar"))
            store.replaceClasses(artifact.id, listOf(nasty))
            store.loadClass(artifact.id, "p.Nästy\$Inner") shouldBe nasty
        }
    }

    // -- artifact scoping ---------------------------------------------------------

    @Test
    fun `replacing one artifact leaves its neighbour intact`() {
        open().use { store ->
            val first = store.upsertArtifact(NewArtifact(hash = "1".repeat(32), path = "/1.jar"))
            val second = store.upsertArtifact(NewArtifact(hash = "2".repeat(32), path = "/2.jar"))
            val infos = fixtureClassInfos()
            store.replaceClasses(first.id, infos)
            store.replaceClasses(second.id, infos.take(3))
            store.classCount(first.id) shouldBe infos.size
            store.classCount(second.id) shouldBe 3
            // Replacing the second again does not disturb the first.
            store.replaceClasses(second.id, emptyList())
            store.classCount(first.id) shouldBe infos.size
            store.classCount(second.id) shouldBe 0
            store.loadClass(first.id, infos.first().name.binaryName) shouldNotBe null
        }
    }

    @Test
    fun `the same class in two artifacts reports both providers ordered by hash`() {
        val infos = fixtureClassInfos().take(5)
        open().use { store ->
            val lower = store.upsertArtifact(NewArtifact(hash = "a".repeat(32), path = "/lower.jar"))
            val upper = store.upsertArtifact(NewArtifact(hash = "z".repeat(32), path = "/upper.jar"))
            store.replaceClasses(upper.id, infos)
            store.replaceClasses(lower.id, infos)
            val target = infos.first().name.binaryName
            val hits = store.findClassesByFqn(target)
            hits.size shouldBe 2
            hits.map { it.artifact.hash } shouldBe listOf("a".repeat(32), "z".repeat(32))
            hits.forEach { it.clazz shouldBe infos.first() }
            // And a name indexed nowhere reports no hits — a miss, not an error.
            store.findClassesByFqn("com.example.NoSuchThing") shouldBe emptyList()
        }
    }

    @Test
    fun `deleting an artifact removes its classes but keeps the neighbour`() {
        val infos = fixtureClassInfos()
        open().use { store ->
            val doomed = store.upsertArtifact(NewArtifact(hash = "d".repeat(32), path = "/doomed.jar"))
            val spared = store.upsertArtifact(NewArtifact(hash = "s".repeat(32), path = "/spared.jar"))
            store.replaceClasses(doomed.id, infos)
            store.replaceClasses(spared.id, infos.take(2))
            store.deleteArtifactByHash("d".repeat(32)) shouldBe true
            store.deleteArtifactByHash("d".repeat(32)) shouldBe false
            store.findArtifactByHash("d".repeat(32)) shouldBe null
            store.loadClass(doomed.id, infos.first().name.binaryName) shouldBe null
            store.listClassFqns(doomed.id) shouldBe emptyList()
            // The victim's classes vanish from cross-artifact search; the spared
            // artifact still provides the shared first class.
            store.findClassesByFqn(infos.first().name.binaryName).map { it.artifact.hash } shouldBe
                listOf("s".repeat(32))
            store.classCount(spared.id) shouldBe 2
        }
    }

    @Test
    fun `listClassFqns is sorted`() {
        val infos = fixtureClassInfos()
        open().use { store ->
            val artifact = store.upsertArtifact(NewArtifact(hash = "q".repeat(32), path = "/q.jar"))
            // Insert in reverse to prove the read orders, not the write.
            store.replaceClasses(artifact.id, infos.reversed())
            store.listClassFqns(artifact.id) shouldBe infos.map { it.name.binaryName }.sorted()
        }
    }

    @Test
    fun `closing and reopening keeps every row`() {
        val infos = fixtureClassInfos()
        val file = tempDir.resolve("reopen.db")
        val artifactId = SqliteIndexStore.open(file).use { store ->
            val artifact = store.upsertArtifact(
                NewArtifact(hash = "r".repeat(32), path = "/r.jar", sourcesPath = "/r-sources.jar"),
            )
            store.replaceClasses(artifact.id, infos)
            artifact.id
        }
        SqliteIndexStore.open(file).use { store ->
            val artifact = store.findArtifactByHash("r".repeat(32))
            artifact shouldNotBe null
            artifact!!.id shouldBe artifactId
            artifact.sourcesPath shouldBe "/r-sources.jar"
            store.classCount(artifactId) shouldBe infos.size
            // Spot-check first, middle and last: the whole list was already
            // pinned exactly by the round-trip test above.
            for (expected in listOf(infos.first(), infos[infos.size / 2], infos.last())) {
                store.loadClass(artifactId, expected.name.binaryName) shouldBe expected
            }
            // The fixture classes' simple names are searchable in the listing.
            store.listClassFqns(artifactId).toSet() shouldBe infos.map { it.name.binaryName }.toSet()
        }
    }

    // -- helpers ------------------------------------------------------------------

    /**
     * Every fixture class as ASM reads it — real compiler output, not hand-built
     * models (so this suite inherits the corpus's nastiness: bridges, synthetics,
     * records, enums, generics). Bytes are read, never loaded (D-017).
     */
    private fun fixtureClassInfos(): List<ClassInfo> {
        val jar = ArtifactTestJars.binaryJar()
        val infos = mutableListOf<ClassInfo>()
        ArtifactLoader.openJar(jar.toPath()).use { root ->
            for (entry in root.classEntryPaths().sorted()) {
                val result = root.openClass(entry).use { AsmClassReader.read(it, entry) }
                val ok = result as? ClassReadResult.Ok
                    ?: error("fixture class $entry does not parse: $result")
                infos.add(ok.info)
            }
        }
        // Cross-check the key mapping the store relies on: entry path `a/b/C.class`
        // must equal `ClassInfo.name.binaryName` with dots for slashes.
        for (info in infos) {
            val entry = info.name.binaryName.replace('.', '/') + ".class"
            ArtifactTestJars.fixtureClassBytes(jar, entry)
        }
        return infos.sortedBy { it.name.binaryName }
    }
}
