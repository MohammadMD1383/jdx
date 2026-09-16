package dev.jdx.index.service

import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.Visibility
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.resolve.MemberResolver
import dev.jdx.core.resolve.ResolvedMembers
import dev.jdx.index.service.JdxService.KindFilter
import dev.jdx.index.service.JdxService.MemberFilters
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Pure query logic behind `members`/`outline`/`show` (T-011): name matching,
 * suggestions and member filters. No disk, no jars — hand-built [ClassInfo]
 * graphs resolved in memory, so this stays in tier 1.
 */
class MemberQueryFilterTest {

    private fun classType(binary: String): TypeName.ClassType =
        typeNameFromBinaryName(binary) as TypeName.ClassType

    private fun method(
        name: String,
        descriptor: String = "()V",
        access: Access = Access.of(AccessFlag.PUBLIC),
    ): MethodInfo = MethodInfo(
        name = name,
        descriptor = JvmDescriptor.parse(descriptor) as JvmDescriptor.Method,
        access = access,
    )

    private fun field(
        name: String,
        descriptor: String = "I",
        access: Access = Access.of(AccessFlag.PUBLIC),
    ): FieldInfo = FieldInfo(
        name = name,
        type = (JvmDescriptor.parse(descriptor) as JvmDescriptor.Field).type,
        access = access,
    )

    private val supertype = ClassInfo(
        name = classType("com.example.Base"),
        kind = TypeKind.CLASS,
        superclass = classType("java.lang.Object"),
        methods = listOf(
            method("inherited"),
            method("hidden", access = Access.of(AccessFlag.PRIVATE)),
            method("create", access = Access.of(AccessFlag.PUBLIC, AccessFlag.STATIC)),
        ),
        fields = listOf(field("baseField")),
    )

    private val target = ClassInfo(
        name = classType("com.example.Point"),
        kind = TypeKind.CLASS,
        superclass = classType("com.example.Base"),
        methods = listOf(
            method("<init>", "(II)V"),
            method("getX", "()I"),
            method("secret", access = Access.of(AccessFlag.PRIVATE)),
            method("packageHelper", access = Access.NONE),
        ),
        fields = listOf(
            field("x"),
            field("count", access = Access.of(AccessFlag.PUBLIC, AccessFlag.STATIC)),
        ),
    )

    private val objectStub = ClassInfo(
        name = classType("java.lang.Object"),
        kind = TypeKind.CLASS,
    )

    private fun resolved(): ResolvedMembers {
        val byBinary = listOf(target, supertype, objectStub).associateBy { it.name.binaryName }
        return MemberResolver.resolve(target, { name -> byBinary[name.binaryName] })
    }

    // -- access -----------------------------------------------------------------

    @Test
    fun `default filters keep public and protected only`() {
        val filtered = JdxService.applyFilters(resolved(), MemberFilters())
        filtered.methods.map { it.member.name }.sorted() shouldBe listOf("<init>", "create", "getX", "inherited")
        filtered.fields.map { it.member.name }.sorted() shouldBe listOf("baseField", "count", "x")
    }

    @Test
    fun `access all keeps private and package members`() {
        val filtered = JdxService.applyFilters(
            resolved(),
            MemberFilters(access = setOf(Visibility.PUBLIC, Visibility.PROTECTED, Visibility.PACKAGE_PRIVATE, Visibility.PRIVATE)),
        )
        // `hidden` is a *private supertype* member: the resolver drops those per
        // JLS visibility before filtering ever sees them, so even `all` cannot
        // show it. Target-owned privates (`secret`, `packageHelper`) do appear.
        (filtered.methods.map { it.member.name }.toSet()) shouldBe
            setOf("<init>", "getX", "secret", "packageHelper", "inherited", "create")
    }

    // -- kind -------------------------------------------------------------------

    @Test
    fun `kind method drops constructors and fields`() {
        val filtered = JdxService.applyFilters(resolved(), MemberFilters(kind = KindFilter.METHOD))
        (filtered.methods.map { it.member.name }.toSet()) shouldBe setOf("getX", "inherited", "create")
        filtered.fields shouldBe emptyList()
    }

    @Test
    fun `kind ctor keeps only constructors`() {
        val filtered = JdxService.applyFilters(resolved(), MemberFilters(kind = KindFilter.CTOR))
        filtered.methods.map { it.member.name } shouldBe listOf("<init>")
        filtered.fields shouldBe emptyList()
    }

    @Test
    fun `kind field drops every method`() {
        val filtered = JdxService.applyFilters(resolved(), MemberFilters(kind = KindFilter.FIELD))
        filtered.methods shouldBe emptyList()
        filtered.fields.map { it.member.name }.sorted() shouldBe listOf("baseField", "count", "x")
    }

    // -- static / from / grep ----------------------------------------------------

    @Test
    fun `static only keeps static members`() {
        val filtered = JdxService.applyFilters(resolved(), MemberFilters(staticOnly = true))
        (filtered.methods.map { it.member.name }.toSet()) shouldBe setOf("create")
        filtered.fields.map { it.member.name } shouldBe listOf("count")
    }

    @Test
    fun `instance only drops static members`() {
        val filtered = JdxService.applyFilters(resolved(), MemberFilters(staticOnly = false))
        (filtered.methods.map { it.member.name }.toSet()) shouldBe setOf("<init>", "getX", "inherited")
        filtered.fields.map { it.member.name }.sorted() shouldBe listOf("baseField", "x")
    }

    @Test
    fun `from scopes rows to one declaring type`() {
        val filtered = JdxService.applyFilters(resolved(), MemberFilters(), fromBinary = "com.example.Base")
        (filtered.methods.map { it.member.name }.toSet()) shouldBe setOf("inherited", "create")
        filtered.fields.map { it.member.name } shouldBe listOf("baseField")
    }

    @Test
    fun `grep filters by member name`() {
        val filtered = JdxService.applyFilters(resolved(), MemberFilters(grep = Regex("get|count")))
        filtered.methods.map { it.member.name } shouldBe listOf("getX")
        filtered.fields.map { it.member.name } shouldBe listOf("count")
    }

    @Test
    fun `filtering never touches linearisation or missing supertypes`() {
        val before = resolved()
        val after = JdxService.applyFilters(before, MemberFilters(grep = Regex("zzz-no-match")))
        after.methods shouldBe emptyList()
        after.fields shouldBe emptyList()
        after.linearization shouldBe before.linearization
        after.missingSupertypes shouldBe before.missingSupertypes
    }

    // -- name matching -----------------------------------------------------------

    @Test
    fun `packaged names match exactly`() {
        val binaries = setOf("com.example.Point", "com.example.Base", "java.lang.Object")
        JdxService.matchCandidates(classType("com.example.Point"), binaries) shouldBe
            listOf("com.example.Point")
        JdxService.matchCandidates(classType("com.example.Missing"), binaries) shouldBe emptyList()
    }

    @Test
    fun `short names match every same-named class sorted`() {
        val binaries = setOf("com.b.Point", "com.a.Point", "com.example.Other")
        JdxService.matchCandidates(classType("Point"), binaries) shouldBe
            listOf("com.a.Point", "com.b.Point")
    }

    @Test
    fun `short nested names match the dollar-joined suffix`() {
        val binaries = setOf("java.util.Map\$Entry", "com.example.Entry")
        JdxService.matchCandidates(classType("Map\$Entry"), binaries) shouldBe
            listOf("java.util.Map\$Entry")
    }

    @Test
    fun `suggestions prefer same simple names then near misses`() {
        val binaries = setOf(
            "com.b.Gson",
            "com.a.Gson",
            "com.google.gson.GsonBuilder",
            "com.example.Unrelated",
        )
        JdxService.suggestSimilar("Gson", binaries) shouldBe
            listOf("com.a.Gson", "com.b.Gson")
        // Near miss on the simple name still suggests.
        val near = JdxService.suggestSimilar("Gsonn", binaries)
        (near.toSet()) shouldBe setOf("com.a.Gson", "com.b.Gson")
    }

    @Test
    fun `narrowing filters never adds rows`() = runBlocking<Unit> {
        val kindArb = Arb.of(KindFilter.ALL, KindFilter.METHOD, KindFilter.FIELD, KindFilter.CTOR)
        val accessArb: Arb<Set<Visibility>?> = Arb.of(
            null,
            MemberFilters.DEFAULT_ACCESS,
            setOf(Visibility.PUBLIC),
            setOf(Visibility.PUBLIC, Visibility.PROTECTED, Visibility.PACKAGE_PRIVATE, Visibility.PRIVATE),
        )
        val staticArb: Arb<Boolean?> = Arb.of(null, true, false)
        val grepArb: Arb<Regex?> = Arb.of(null, Regex(".*"), Regex("zzz-no-match"), Regex("e|a"))
        checkAll(500, kindArb, accessArb, staticArb, grepArb) { kind, access, staticOnly, grep ->
            val before = resolved()
            val filters = MemberFilters(kind = kind, access = access, staticOnly = staticOnly, grep = grep)
            val after = JdxService.applyFilters(before, filters)
            // Filtering hides rows, never invents them: every surviving row (by
            // declaring type + name + descriptor) was in the resolved set.
            val beforeKeys = before.methods.map {
                Triple(it.declaringType.binaryName, it.member.name, it.member.descriptor.descriptor)
            }.toSet() + before.fields.map {
                Triple(it.declaringType.binaryName, it.member.name, it.member.type.descriptor)
            }.toSet()
            for (method in after.methods) {
                val key = Triple(
                    method.declaringType.binaryName,
                    method.member.name,
                    method.member.descriptor.descriptor,
                )
                (key in beforeKeys) shouldBe true
            }
            for (field in after.fields) {
                val key = Triple(
                    field.declaringType.binaryName,
                    field.member.name,
                    field.member.type.descriptor,
                )
                (key in beforeKeys) shouldBe true
            }
            // Same filters twice: identical rows (the D-007 determinism promise).
            val twice = JdxService.applyFilters(before, filters)
            twice.methods.map { it.member.name } shouldBe after.methods.map { it.member.name }
            twice.fields.map { it.member.name } shouldBe after.fields.map { it.member.name }
        }
    }

    @Test
    fun `levenshtein is a metric on the basics`() {
        JdxService.levenshtein("abc", "abc") shouldBe 0
        JdxService.levenshtein("", "abc") shouldBe 3
        JdxService.levenshtein("kitten", "sitting") shouldBe 3
    }

    @Test
    fun `levenshtein is symmetric and non-negative`() = runBlocking<Unit> {
        val words = Arb.of("", "a", "ab", "Gson", "Gsonn", "kitten", "sitting", "HashMap", "HMap", "λ", "x1_")
        checkAll(500, words, words) { a, b ->
            (JdxService.levenshtein(a, b) >= 0) shouldBe true
            JdxService.levenshtein(a, b) shouldBe JdxService.levenshtein(b, a)
        }
    }

    @Test
    fun `candidate order is stable under input order`() = runBlocking<Unit> {
        checkAll(200, Arb.of("Point", "Gson", "Entry"), Arb.of("com.a", "com.b", "org.x")) { name, pkg ->
            val binaries = setOf("$pkg.$name", "com.other.$name")
            val once = JdxService.matchCandidates(classType(name), binaries)
            val twice = JdxService.matchCandidates(classType(name), binaries.reversed().toSet())
            once shouldBe twice
        }
    }
}
