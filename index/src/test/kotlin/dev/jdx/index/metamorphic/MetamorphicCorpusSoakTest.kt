package dev.jdx.index.metamorphic

import dev.jdx.core.model.Visibility
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.render.MemberKind
import dev.jdx.index.artifact.ArtifactLoader
import dev.jdx.index.index.ArtifactIndexer
import dev.jdx.index.service.JdxService
import dev.jdx.index.service.JdxService.MemberFilters
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.index.store.sqlite.SqliteIndexStore
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.random.Random
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tier-3 metamorphic testing over the real local jar corpus (T-058, docs/TESTING.md §6, §8).
 *
 * Runs metamorphic relations over a seeded sample of real jars and classes from
 * the local Gradle cache (or `-Pcorpus=<dir>`):
 *
 * 1. `members(T, --inherited) ⊇ members(T, --declared)`
 * 6. `search(exact-fqn-of(T)) returns T`
 * 7. `show(T) member counts == |members(T, --declared, --access all, --include-synthetic)|`
 * 9. `index(jar) twice ⟹ byte-identical index rows`
 * 10. `run(cmd) twice ⟹ byte-identical stdout`
 *
 * Classes that cannot be read honestly (corrupt bytecodes, class versions newer
 * than the running JDK, or JFR-style `$$` names unrepresentable in the model per
 * T-065) are counted as skips, matching the tool's honest degradation laws.
 *
 * Tagged `soak` (never `tier2`): runs only via `./gradlew soak` or `soakTest`.
 */
@Tag("soak")
class MetamorphicCorpusSoakTest {

    private companion object {
        const val DEFAULT_SEED: Long = 20260917L
        const val MAX_JARS: Int = 25
        const val CLASSES_PER_JAR: Int = 4
        const val MAX_DETERMINISM_INDEX_JARS: Int = 3
    }

    private val allVisibilities: Set<Visibility> = Visibility.entries.toSet()

    @Test
    fun `seeded corpus sample upholds metamorphic relations across commands`(@TempDir tempDir: Path) {
        val corpusDir = File(
            System.getProperty("jdx.corpusDir")
                ?: "${System.getProperty("user.home")}/.gradle/caches",
        )
        assumeTrue(
            corpusDir.isDirectory,
            "corpus dir ${corpusDir.absolutePath} absent: skipping corpus soak (TESTING.md §8); point it at real jars with -Pcorpus=<dir>",
        )

        val seed = System.getProperty("jdx.soakSeed")?.toLongOrNull() ?: DEFAULT_SEED
        val random = Random(seed)

        val jars = collectJars(corpusDir, random)
        assumeTrue(
            jars.isNotEmpty(),
            "no readable jars under ${corpusDir.absolutePath}: skipping corpus soak (-Pcorpus=<dir>)",
        )

        val failures = mutableListOf<String>()
        val skipReasons = mutableMapOf<String, Int>()
        var compared = 0
        var indexedCount = 0

        for (jar in jars) {
            val roots = RootsSpec(jarSpecs = listOf(jar.absolutePath), includeJdk = true)

            // Test Relation 9: index(jar) twice ⟹ byte-identical index rows on a subset of jars
            if (indexedCount < MAX_DETERMINISM_INDEX_JARS) {
                try {
                    testIndexDeterminism(jar, tempDir, indexedCount)
                    indexedCount++
                } catch (thrown: Exception) {
                    failures.add("INDEX DETERMINISM ${jar.name}: ${thrown.javaClass.simpleName}: ${thrown.message}")
                }
            }

            for (binaryName in sampleClasses(jar, random)) {
                // Pre-check for T-065: classes with $$ or names that typeNameFromBinaryName rejects
                if (binaryName.contains("$$") || isUnnameable(binaryName)) {
                    skipReasons["unnameable-name (T-065)"] = (skipReasons["unnameable-name (T-065)"] ?: 0) + 1
                    continue
                }

                try {
                    val showOutcome = JdxService.show(binaryName, roots)
                    if (showOutcome is ServiceOutcome.Failure) {
                        if (showOutcome.exitCode == 5) {
                            skipReasons["unreadable (exit 5)"] = (skipReasons["unreadable (exit 5)"] ?: 0) + 1
                            continue
                        } else {
                            failures.add("JDX SHOW ERROR $binaryName (exit ${showOutcome.exitCode}): ${showOutcome.renderText()}")
                            continue
                        }
                    }
                    val card = (showOutcome as ServiceOutcome.Card).card

                    val fullDeclaredOutcome = JdxService.members(
                        rawRef = binaryName,
                        roots = roots,
                        filters = MemberFilters(access = allVisibilities),
                        declaredOnly = true,
                        includeSynthetic = true,
                        maxMembers = 1_000_000,
                    )
                    if (fullDeclaredOutcome is ServiceOutcome.Failure) {
                        if (fullDeclaredOutcome.exitCode == 5) {
                            skipReasons["unreadable (exit 5)"] = (skipReasons["unreadable (exit 5)"] ?: 0) + 1
                            continue
                        } else {
                            failures.add("JDX DECLARED MEMBERS ERROR $binaryName (exit ${fullDeclaredOutcome.exitCode})")
                            continue
                        }
                    }
                    val fullDeclared = (fullDeclaredOutcome as ServiceOutcome.MemberList).listing

                    val fullInheritedOutcome = JdxService.members(
                        rawRef = binaryName,
                        roots = roots,
                        filters = MemberFilters(access = allVisibilities),
                        declaredOnly = false,
                        includeSynthetic = true,
                        maxMembers = 1_000_000,
                    )
                    if (fullInheritedOutcome is ServiceOutcome.Failure) {
                        if (fullInheritedOutcome.exitCode == 5) {
                            skipReasons["unreadable (exit 5)"] = (skipReasons["unreadable (exit 5)"] ?: 0) + 1
                            continue
                        } else {
                            failures.add("JDX INHERITED MEMBERS ERROR $binaryName (exit ${fullInheritedOutcome.exitCode})")
                            continue
                        }
                    }
                    val fullInherited = (fullInheritedOutcome as ServiceOutcome.MemberList).listing

                    // Relation 1: declared ⊆ inherited
                    val declaredRows = fullDeclared.groups.flatMap { it.rows }
                    val inheritedRows = fullInherited.groups.flatMap { it.rows }
                    for (declRow in declaredRows) {
                        val found = inheritedRows.any {
                            it.declaringType == declRow.declaringType &&
                                it.kind == declRow.kind &&
                                it.signature == declRow.signature
                        }
                        if (!found) {
                            failures.add("RELATION 1 MISMATCH in $binaryName: declared row not in inherited: ${declRow.textLine()}")
                        }
                    }

                    // Relation 7: show member counts == |members(T, --declared, --access all)|
                    val ctors = fullDeclared.groups.filter { it.kind == MemberKind.CONSTRUCTOR }.sumOf { it.rows.size }
                    val methods = fullDeclared.groups.filter { it.kind == MemberKind.METHOD }.sumOf { it.rows.size }
                    val fields = fullDeclared.groups.filter { it.kind == MemberKind.FIELD }.sumOf { it.rows.size }
                    if (card.counts.constructors != ctors || card.counts.methods != methods || card.counts.fields != fields) {
                        failures.add("RELATION 7 MISMATCH in $binaryName: card counts ${card.counts} != declared ($ctors, $methods, $fields)")
                    }

                    // Relation 6: search exact FQN finds T
                    val searchOutcome = JdxService.search(binaryName, RootsSpec(jarSpecs = listOf(jar.absolutePath), includeJdk = false))
                    if (searchOutcome is ServiceOutcome.SearchList) {
                        val hits = searchOutcome.listing.hits
                        val matched = hits.any { it.ref == binaryName }
                        if (!matched) {
                            failures.add("RELATION 6 MISMATCH in $binaryName: search did not return the queried type")
                        }
                    }

                    // Relation 10: run twice determinism
                    val showRepeat = JdxService.show(binaryName, roots)
                    if (showOutcome.renderText(false) != showRepeat.renderText(false) ||
                        showOutcome.toJson("show") != showRepeat.toJson("show")
                    ) {
                        failures.add("RELATION 10 DETERMINISM MISMATCH for show $binaryName")
                    }

                    compared++
                } catch (thrown: Exception) {
                    failures.add("THREW $binaryName: ${thrown.javaClass.simpleName}: ${thrown.message}")
                }
            }
        }

        println("SOAK metamorphic seed=$seed jars=${jars.size} compared=$compared skipped=${skipReasons.values.sum()} failed=${failures.size}")
        skipReasons.entries.sortedByDescending { it.value }.forEach { (reason, count) ->
            println("SOAK skip [$count x] $reason")
        }

        (compared > 0) shouldBe true
        failures shouldBe emptyList()
    }

    private fun testIndexDeterminism(jar: File, tempDir: Path, index: Int) {
        val store1File = tempDir.resolve("soak-store1-$index.db")
        val store2File = tempDir.resolve("soak-store2-$index.db")

        SqliteIndexStore.open(store1File).use { store1 ->
            ArtifactIndexer.indexOne(store1, jar.toPath())
        }
        SqliteIndexStore.open(store2File).use { store2 ->
            ArtifactIndexer.indexOne(store2, jar.toPath())
        }

        SqliteIndexStore.open(store1File).use { store1 ->
            SqliteIndexStore.open(store2File).use { store2 ->
                val a1 = store1.listArtifacts().map { it.copy(indexedAt = 0) }
                val a2 = store2.listArtifacts().map { it.copy(indexedAt = 0) }
                a1 shouldBe a2
                for (artifact in store1.listArtifacts()) {
                    store1.listClassFqns(artifact.id) shouldBe store2.listClassFqns(artifact.id)
                    store1.classCount(artifact.id) shouldBe store2.classCount(artifact.id)
                }
            }
        }

        Files.deleteIfExists(store1File)
        Files.deleteIfExists(store2File)
    }

    private fun isUnnameable(binaryName: String): Boolean =
        runCatching { typeNameFromBinaryName(binaryName) }.isFailure

    private fun collectJars(corpusDir: File, random: Random): List<File> {
        val found = mutableListOf<File>()
        Files.walk(corpusDir.toPath()).use { walk ->
            walk.filter { path ->
                path.isRegularFile() && path.fileName.toString().endsWith(".jar") &&
                    !path.fileName.toString().endsWith("-sources.jar") &&
                    !path.fileName.toString().endsWith("-javadoc.jar")
            }.forEach { found.add(it.toFile()) }
        }
        return found.sortedBy { it.absolutePath }.shuffled(random).take(MAX_JARS)
    }

    private fun sampleClasses(jar: File, random: Random): List<String> {
        return try {
            ArtifactLoader.openJar(jar.toPath()).use { root ->
                root.classEntryPaths()
                    .map { it.removeSuffix(".class").replace('/', '.') }
                    .shuffled(random)
                    .take(CLASSES_PER_JAR)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
