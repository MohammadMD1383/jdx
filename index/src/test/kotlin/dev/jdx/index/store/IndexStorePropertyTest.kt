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
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.index.store.sqlite.SqliteIndexStore
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Generative tests for [IndexStore] (T-013, TESTING.md §4/§6).
 *
 * Hand-written round-trips plateau at what the author imagined; these keep
 * inventing classes — nested generics, wildcards, unicode names, hostile
 * annotation strings — and demand the store be a fixed point for all of them.
 * The determinism property pins the D-007 promise (`index twice ⟹ identical
 * rows`) at the store level, where T-014's parallel indexer will rely on it.
 *
 * Tier 2: each case writes to a real SQLite file. One store per test method
 * (not per case) keeps 500 cases inside seconds.
 */
@Tag("tier2")
class IndexStorePropertyTest {

    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `store then load is a fixed point for generated classes`() = runBlocking<Unit> {
        SqliteIndexStore.open(tempDir.resolve("props.db")).use { store ->
            val artifact = store.upsertArtifact(NewArtifact(hash = "p".repeat(32), path = "/props.jar"))
            checkAll(500, StoreGenerators.arbClassInfo()) { expected ->
                store.replaceClasses(artifact.id, listOf(expected))
                store.loadClass(artifact.id, expected.name.binaryName) shouldBe expected
            }
        }
    }

    @Test
    fun `storing twice yields identical reads`() = runBlocking<Unit> {
        SqliteIndexStore.open(tempDir.resolve("idem.db")).use { store ->
            val artifact = store.upsertArtifact(NewArtifact(hash = "i".repeat(32), path = "/idem.jar"))
            checkAll(200, Arb.list(StoreGenerators.arbClassInfo(), 1..4)) { classes ->
                // Duplicate binary names would collide on the UNIQUE key — keep
                // the first of each, mirroring what an artifact really holds.
                val distinct = classes.distinctBy { it.name.binaryName }
                store.replaceClasses(artifact.id, distinct)
                val firstReads = distinct.map { store.loadClass(artifact.id, it.name.binaryName) }
                val firstListing = store.listClassFqns(artifact.id)
                store.replaceClasses(artifact.id, distinct)
                val secondReads = distinct.map { store.loadClass(artifact.id, it.name.binaryName) }
                secondReads shouldBe firstReads
                store.listClassFqns(artifact.id) shouldBe firstListing
                // And the reads are the writes: idempotence plus round-trip together.
                firstReads shouldBe distinct
            }
        }
    }
}

/**
 * Generators for store properties. Small pools with sharp edges: every segment
 * may be unicode, every string may carry quotes and backslashes, every
 * signature is drawn from valid-but-nasty shapes (the store must preserve them,
 * never interpret them). The seed of T-055's shared generator library.
 */
internal object StoreGenerators {

    private val packages = listOf("", "p", "com.example", "ünï")
    private val tops = listOf("A", "B", "Outer", "Nästy", "λ")
    private val nesteds = listOf(null, "Inner", "Deep\$Deeper", "λ")
    private val members = listOf("foo", "bar", "x", "<init>", "ünï", "with space")

    private val fieldDescriptors = listOf(
        "I", "Z", "J", "Ljava/lang/String;", "Ljava/lang/Object;", "[I", "[[Ljava/lang/String;",
    )
    private val methodDescriptors = listOf(
        "()V", "()I", "(I)V", "(Ljava/lang/Object;)Z", "()Ljava/lang/Object;",
        "(Ljava/lang/String;[I)Ljava/util/List;", "(JJ)D",
    )

    /** Valid generic signatures covering nesting, wildcards, variables and throws. */
    private val classSignatures = listOf(
        null,
        "<T:Ljava/lang/Object;>Ljava/lang/Object;",
        "Ljava/util/ArrayList<Ljava/lang/String;>;",
        "Ljava/lang/Object;",
    )
    private val fieldSignatures = listOf(
        null,
        "Ljava/util/Map<Ljava/lang/String;Ljava/util/List<+Ljava/lang/Number;>;>;",
        "TT;",
        "[Ljava/lang/String;",
    )
    private val methodSignatures = listOf(
        null,
        "<T:Ljava/lang/Object;>(TT;)TT;",
        "<T::Ljava/lang/Comparable<TT;>;>(TT;)TT;^Ljava/io/IOException;",
        "(Ljava/util/List<*>;)V",
        "()Ljava/util/Map<Ljava/lang/String;[I>;",
    )

    private val hostileStrings = listOf(
        "",
        "plain",
        "say \"hi\"",
        "back\\slash",
        "new\nline",
        "tab\there",
        "comma, brace{, semi;, eq=",
        "λ→€",
        "a\"b\\c\nd",
    )

    private fun arbBinaryName(): Arb<String> =
        Arb.bind(
            Arb.element(packages),
            Arb.element(tops),
            Arb.element(nesteds),
        ) { pkg, top, nested ->
            val dotted = (if (pkg.isEmpty()) "" else "$pkg.") + top
            if (nested == null) dotted else "$dotted\$$nested"
        }

    private fun arbAccess(): Arb<Access> =
        Arb.element(
            listOf(
                Access.NONE,
                Access.of(AccessFlag.PUBLIC),
                Access.of(AccessFlag.PRIVATE),
                Access.of(AccessFlag.PROTECTED),
                Access.of(AccessFlag.PUBLIC, AccessFlag.FINAL),
                Access.of(AccessFlag.PUBLIC, AccessFlag.STATIC),
                Access.of(AccessFlag.PUBLIC, AccessFlag.SYNTHETIC),
                Access.of(AccessFlag.PUBLIC, AccessFlag.BRIDGE, AccessFlag.SYNTHETIC),
                Access.of(AccessFlag.PUBLIC, AccessFlag.VARARGS),
                Access.of(AccessFlag.PUBLIC, AccessFlag.NATIVE),
                Access.of(AccessFlag.PUBLIC, AccessFlag.ABSTRACT),
            ),
        )

    private fun arbAnnotation(): Arb<AnnotationInfo> =
        Arb.bind(
            Arb.element(listOf("java.lang.Deprecated", "p.Annot", "com.example.Outer\$Inner")),
            Arb.list(Arb.bind(Arb.element(listOf("value", "k", "ünï")), Arb.element(hostileStrings)) { k, v -> k to v }, 0..3),
        ) { annot, pairs ->
            AnnotationInfo(typeNameFromBinaryName(annot), pairs.toMap())
        }

    private fun arbField(): Arb<FieldInfo> =
        Arb.bind(
            Arb.element(members.filter { it != "<init>" }),
            Arb.element(fieldDescriptors),
            arbAccess(),
            Arb.element(fieldSignatures),
            Arb.list(arbAnnotation(), 0..2),
            Arb.element(listOf(true, false)),
            Arb.element(hostileStrings).orNull(),
        ) { name, descriptor, access, signature, annots, deprecated, constant ->
            FieldInfo(
                name = name,
                type = (JvmDescriptor.parse(descriptor) as JvmDescriptor.Field).type,
                access = access,
                genericSignature = signature?.let { GenericSignature.parse(it) as? FieldSignature },
                annotations = annots,
                deprecated = deprecated,
                constantValue = constant,
            )
        }

    private fun arbMethod(): Arb<MethodInfo> =
        Arb.bind(
            Arb.element(members),
            Arb.element(methodDescriptors),
            arbAccess(),
            Arb.element(methodSignatures),
            Arb.list(Arb.element(hostileStrings).orNull(), 0..2),
            Arb.list(Arb.element(listOf("java.io.IOException", "java.lang.RuntimeException")), 0..2),
            Arb.list(arbAnnotation(), 0..2),
            Arb.element(listOf(true, false)),
            Arb.element(hostileStrings).orNull(),
        ) { name, descriptor, access, signature, params, throws, annots, deprecated, default ->
            MethodInfo(
                name = name,
                descriptor = JvmDescriptor.parse(descriptor) as JvmDescriptor.Method,
                access = access,
                genericSignature = signature?.let { GenericSignature.parse(it) as? MethodSignature },
                parameterNames = params,
                throwsTypes = throws.map { typeNameFromBinaryName(it) },
                annotations = annots,
                deprecated = deprecated,
                annotationDefault = default,
            )
        }

    fun arbClassInfo(): Arb<ClassInfo> =
        Arb.bind(
            arbBinaryName(),
            Arb.element(TypeKind.entries),
            arbAccess(),
            Arb.element(listOf(null, "java.lang.Object", "p.A", "java.util.ArrayList")),
            Arb.list(Arb.element(listOf("java.io.Serializable", "java.lang.Comparable", "p.A")), 0..2),
            Arb.element(classSignatures),
            Arb.list(arbField(), 0..3),
            Arb.list(arbMethod(), 0..3),
            Arb.list(arbAnnotation(), 0..2),
            Arb.element(listOf(null, "p.Outer")),
            Arb.element(listOf(null, "C.java", "Nästy.java")),
            Arb.element(listOf(true, false)),
            Arb.int(0..1000),
        ) { binary, kind, access, superFqn, ifaces, signature, fields, methods, annots,
            outerFqn, sourceFile, deprecated, salt ->
            // The salt keeps generated classes distinct across cases sharing one
            // artifact table: without it two cases could draw the same binary name
            // and the assertion would compare against a stale row.
            val salted = if (salt == 0) binary else binary + "\$S$salt"
            ClassInfo(
                name = typeNameFromBinaryName(salted) as TypeName.ClassType,
                kind = kind,
                access = access,
                superclass = superFqn?.let { typeNameFromBinaryName(it) },
                interfaces = ifaces.map { typeNameFromBinaryName(it) },
                genericSignature = signature?.let { GenericSignature.parseClass(it) },
                fields = fields,
                methods = methods,
                annotations = annots,
                outerClass = outerFqn?.let { typeNameFromBinaryName(it) as TypeName.ClassType },
                sourceFileName = sourceFile,
                deprecated = deprecated,
            )
        }
}
