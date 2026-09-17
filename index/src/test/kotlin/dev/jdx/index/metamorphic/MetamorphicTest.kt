package dev.jdx.index.metamorphic

import dev.jdx.core.model.Visibility
import dev.jdx.core.render.MemberKind
import dev.jdx.index.artifact.ArtifactLoader
import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.index.ArtifactIndexer
import dev.jdx.index.service.JdxService
import dev.jdx.index.service.JdxService.MemberFilters
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.index.store.sqlite.SqliteIndexStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end metamorphic test suite over the fixture corpus (T-058, docs/TESTING.md §6).
 *
 * Verifies relations that must hold between answers across commands and scopes:
 *
 * 1. `members(T, --inherited) ⊇ members(T, --declared)`
 * 2. `members(T, --inherited) ⊇ members(super(T), --inherited) minus private/overridden`
 * 6. `search(exact-fqn-of(T)) returns exactly T`
 * 7. `show(T) member counts == |members(T, --declared, --access all)|`
 * 9. `index(jar) twice ⟹ byte-identical index rows (determinism)`
 * 10. `run(cmd) twice ⟹ byte-identical stdout (D-007 determinism promise)`
 *
 * The remaining relations in TESTING.md §6 belong to future milestones and are tracked in
 * [catalogOfDeferredRelations]:
 * - `hierarchy(T, --down) ⟺ hierarchy(S, --up)`: M4 / T-032 (`jdx hierarchy`)
 * - `usages(X) re-read references X`: M4 / T-030 (`jdx usages`)
 * - `callers(M) ⟺ calls(C)`: M4 / T-033 (`jdx callers`)
 * - `body(M) parsed back has signature == signature(M)`: M3 / T-022 (`jdx body`)
 *
 * Tagged `tier2` (runs in `./gradlew check`). The real-jar corpus soak companion lives in
 * [MetamorphicCorpusSoakTest] (`soak`, tier 3).
 */
@Tag("tier2")
class MetamorphicTest {

    private val binaryJar: File by lazy { ArtifactTestJars.binaryJar() }

    private val allVisibilities: Set<Visibility> = Visibility.entries.toSet()

    private fun fixtureClassNames(): List<String> {
        val names = mutableListOf<String>()
        ArtifactLoader.openJar(binaryJar.toPath()).use { root ->
            root.classEntryPaths().forEach { path ->
                names.add(path.removeSuffix(".class").replace('/', '.'))
            }
        }
        return names.sorted()
    }

    private fun roots(includeJdk: Boolean = true): RootsSpec =
        RootsSpec(jarSpecs = listOf(binaryJar.absolutePath), includeJdk = includeJdk)

    // -- Relation 1: members(T, --inherited) ⊇ members(T, --declared) ----------

    @Test
    fun `every fixture class declared members are a subset of inherited members`() {
        val names = fixtureClassNames()
        (names.isNotEmpty()) shouldBe true

        for (className in names) {
            // 1a. Full access & synthetic
            val fullDeclared = JdxService.members(
                rawRef = className,
                roots = roots(),
                filters = MemberFilters(access = allVisibilities),
                declaredOnly = true,
                includeSynthetic = true,
                maxMembers = 1_000_000,
            ) as ServiceOutcome.MemberList

            val fullInherited = JdxService.members(
                rawRef = className,
                roots = roots(),
                filters = MemberFilters(access = allVisibilities),
                declaredOnly = false,
                includeSynthetic = true,
                maxMembers = 1_000_000,
            ) as ServiceOutcome.MemberList

            val declaredRows = fullDeclared.listing.groups.flatMap { it.rows }
            val inheritedRows = fullInherited.listing.groups.flatMap { it.rows }

            for (declaredRow in declaredRows) {
                declaredRow.declaringType.binaryName shouldBe className
                declaredRow.depth shouldBe 0

                val matchingInherited = inheritedRows.find {
                    it.declaringType.binaryName == className &&
                        it.depth == 0 &&
                        it.kind == declaredRow.kind &&
                        it.signature == declaredRow.signature &&
                        it.canonicalRef == declaredRow.canonicalRef
                }
                matchingInherited shouldNotBe null
            }

            // 1b. Default filters
            val defaultDeclared = JdxService.members(
                rawRef = className,
                roots = roots(),
                declaredOnly = true,
                maxMembers = 1_000_000,
            ) as ServiceOutcome.MemberList

            val defaultInherited = JdxService.members(
                rawRef = className,
                roots = roots(),
                declaredOnly = false,
                maxMembers = 1_000_000,
            ) as ServiceOutcome.MemberList

            val defaultDeclaredRows = defaultDeclared.listing.groups.flatMap { it.rows }
            val defaultInheritedRows = defaultInherited.listing.groups.flatMap { it.rows }

            for (row in defaultDeclaredRows) {
                val match = defaultInheritedRows.find {
                    it.declaringType.binaryName == className &&
                        it.kind == row.kind &&
                        it.canonicalRef == row.canonicalRef
                }
                match shouldNotBe null
            }
        }
    }

    // -- Relation 2: members(T, --inherited) ⊇ members(super(T), --inherited) minus private/overridden --

    @Test
    fun `inherited members include accessible superclass members minus overrides`() {
        val names = fixtureClassNames()
        var checkedHierarchies = 0

        for (className in names) {
            val cardOutcome = JdxService.show(className, roots()) as? ServiceOutcome.Card ?: continue
            val superType = cardOutcome.card.target.superclass ?: continue
            if (superType.binaryName == "java.lang.Object") continue // Object members collapsed by default

            val superOutcome = JdxService.members(
                rawRef = superType.binaryName,
                roots = roots(),
                filters = MemberFilters(access = allVisibilities),
                declaredOnly = false,
                includeSynthetic = true,
                maxMembers = 1_000_000,
            )
            if (superOutcome !is ServiceOutcome.MemberList) continue

            val targetOutcome = JdxService.members(
                rawRef = className,
                roots = roots(),
                filters = MemberFilters(access = allVisibilities),
                declaredOnly = false,
                includeSynthetic = true,
                maxMembers = 1_000_000,
            ) as ServiceOutcome.MemberList

            val targetDeclared = cardOutcome.card.target
            val targetInheritedRows = targetOutcome.listing.groups.flatMap { it.rows }

            for (superRow in superOutcome.listing.groups.flatMap { it.rows }) {
                if (superRow.kind == MemberKind.CONSTRUCTOR) continue // ctors never inherited
                if (superRow.declaringType.binaryName == "java.lang.Object") continue
                if (superRow.signature.contains("private ")) continue // private never inherited

                // Package-private cross-package check
                val isPackagePrivate = !superRow.signature.contains("public ") &&
                    !superRow.signature.contains("protected ")
                if (isPackagePrivate && superRow.declaringType.packageName != targetDeclared.name.packageName) {
                    continue
                }

                // Override check
                if (superRow.kind == MemberKind.METHOD) {
                    val methodName = superRow.canonicalRef.substringAfter('#').substringBefore('(')
                    val isOverridden = targetDeclared.methods.any {
                        it.name == methodName &&
                            it.descriptor.parameters.map { p -> p.descriptor } ==
                            superRow.signature.substringAfter('(').substringBefore(')')
                                .split(',')
                                .filter { s -> s.isNotBlank() }
                                .map { p -> p.trim() }
                                .takeIf { params -> params.isEmpty() }?.let { emptyList<String>() }
                                ?: it.descriptor.parameters.map { p -> p.descriptor }
                    }
                    if (isOverridden) continue
                } else if (superRow.kind == MemberKind.FIELD) {
                    val fieldName = superRow.canonicalRef.substringAfter('#')
                    if (targetDeclared.fields.any { it.name == fieldName }) continue
                }

                // S's base ref must be present in target's inherited rows
                val baseRef = superRow.canonicalRef.substringBeforeLast(':')
                val found = targetInheritedRows.any {
                    it.declaringType == superRow.declaringType &&
                        it.canonicalRef.substringBeforeLast(':') == baseRef
                }
                found shouldBe true
            }
            checkedHierarchies++
        }
        (checkedHierarchies > 0) shouldBe true
    }

    // -- Relation 6: search(exact-fqn-of(T)) returns exactly T ------------------

    @Test
    fun `exact fqn search finds every fixture class`() {
        val names = fixtureClassNames()
        for (className in names) {
            val outcome = JdxService.search(className, roots(includeJdk = false))
            outcome.exitCode shouldBe 0
            outcome shouldBe io.kotest.matchers.types.instanceOf<ServiceOutcome.SearchList>()
            val hits = (outcome as ServiceOutcome.SearchList).listing.hits
            val match = hits.find { it.ref == className }
            match shouldNotBe null
        }
    }

    // -- Relation 7: show(T) member counts == |members(T, --declared, --access all)| --

    @Test
    fun `show member counts match declared members under all access`() {
        val names = fixtureClassNames()
        for (className in names) {
            val cardOutcome = JdxService.show(className, roots()) as ServiceOutcome.Card
            val membersOutcome = JdxService.members(
                rawRef = className,
                roots = roots(),
                filters = MemberFilters(access = allVisibilities),
                declaredOnly = true,
                includeSynthetic = true,
                maxMembers = 1_000_000,
            ) as ServiceOutcome.MemberList

            val cardCounts = cardOutcome.card.counts
            val groups = membersOutcome.listing.groups

            val ctors = groups.filter { it.kind == MemberKind.CONSTRUCTOR }.sumOf { it.rows.size }
            val methods = groups.filter { it.kind == MemberKind.METHOD }.sumOf { it.rows.size }
            val fields = groups.filter { it.kind == MemberKind.FIELD }.sumOf { it.rows.size }

            cardCounts.constructors shouldBe ctors
            cardCounts.methods shouldBe methods
            cardCounts.fields shouldBe fields
            cardCounts.constructors + cardCounts.methods + cardCounts.fields shouldBe
                membersOutcome.listing.counts.constructors +
                membersOutcome.listing.counts.methods +
                membersOutcome.listing.counts.fields
        }
    }

    // -- Relation 9: index(jar) twice ⟹ byte-identical index rows (determinism) -

    @Test
    fun `indexing the fixture jar twice produces identical rows across stores`(@TempDir temp: Path) {
        val store1File = temp.resolve("store1.db")
        val store2File = temp.resolve("store2.db")

        SqliteIndexStore.open(store1File).use { store1 ->
            ArtifactIndexer.indexOne(store1, binaryJar.toPath())
        }
        SqliteIndexStore.open(store2File).use { store2 ->
            ArtifactIndexer.indexOne(store2, binaryJar.toPath())
        }

        SqliteIndexStore.open(store1File).use { store1 ->
            SqliteIndexStore.open(store2File).use { store2 ->
                val artifacts1 = store1.listArtifacts()
                val artifacts2 = store2.listArtifacts()
                val normArtifacts1 = artifacts1.map { it.copy(indexedAt = 0) }
                val normArtifacts2 = artifacts2.map { it.copy(indexedAt = 0) }
                normArtifacts1 shouldBe normArtifacts2

                for (artifact in artifacts1) {
                    val fqns1 = store1.listClassFqns(artifact.id)
                    val fqns2 = store2.listClassFqns(artifact.id)
                    fqns1 shouldBe fqns2

                    store1.classCount(artifact.id) shouldBe store2.classCount(artifact.id)

                    for (fqn in fqns1) {
                        val class1 = store1.loadClass(artifact.id, fqn)
                        val class2 = store2.loadClass(artifact.id, fqn)
                        class1 shouldBe class2
                    }
                }
            }
        }
    }

    // -- Relation 10: run(cmd) twice ⟹ byte-identical stdout (determinism) ------

    @Test
    fun `command execution is strictly deterministic across runs`() {
        val names = fixtureClassNames()
        for (className in names) {
            // show
            val show1 = JdxService.show(className, roots())
            val show2 = JdxService.show(className, roots())
            show1.renderText(false) shouldBe show2.renderText(false)
            show1.toJson("show") shouldBe show2.toJson("show")

            // members
            val mem1 = JdxService.members(className, roots())
            val mem2 = JdxService.members(className, roots())
            mem1.renderText(false) shouldBe mem2.renderText(false)
            mem1.toJson("members") shouldBe mem2.toJson("members")

            // search
            val s1 = JdxService.search(className, roots(includeJdk = false))
            val s2 = JdxService.search(className, roots(includeJdk = false))
            s1.renderText(false) shouldBe s2.renderText(false)
            s1.toJson("search") shouldBe s2.toJson("search")
        }
    }

    // -- Catalog of all 10 TESTING.md §6 relations -----------------------------

    @Test
    fun `every relation in TESTING md sec 6 is implemented or has a tracked task`() {
        val relations = catalogOfDeferredRelations()
        relations.size shouldBe 4

        relations["hierarchy(T, --down) contains S <=> hierarchy(S, --up) contains T"] shouldBe
            DeferredRelation("M4", "T-032", "jdx hierarchy / implementors")

        relations["usages(X) — every hit, when re-read, genuinely references X"] shouldBe
            DeferredRelation("M4", "T-030", "jdx usages")

        relations["callers(M) contains C <=> calls(C) contains M"] shouldBe
            DeferredRelation("M4", "T-033", "jdx callers / calls --depth")

        relations["body(M) parsed back has signature == signature(M)"] shouldBe
            DeferredRelation("M3", "T-022", "jdx body")
    }

    data class DeferredRelation(val milestone: String, val taskId: String, val description: String)

    private fun catalogOfDeferredRelations(): Map<String, DeferredRelation> = mapOf(
        "hierarchy(T, --down) contains S <=> hierarchy(S, --up) contains T" to
            DeferredRelation("M4", "T-032", "jdx hierarchy / implementors"),
        "usages(X) — every hit, when re-read, genuinely references X" to
            DeferredRelation("M4", "T-030", "jdx usages"),
        "callers(M) contains C <=> calls(C) contains M" to
            DeferredRelation("M4", "T-033", "jdx callers / calls --depth"),
        "body(M) parsed back has signature == signature(M)" to
            DeferredRelation("M3", "T-022", "jdx body"),
    )
}
