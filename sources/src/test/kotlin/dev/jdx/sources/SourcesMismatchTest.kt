package dev.jdx.sources

import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.MethodSignature
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.TypeVariableSignature
import dev.jdx.core.model.VoidSignature
import dev.jdx.core.model.WarningCode
import dev.jdx.core.model.typeNameFromBinaryName
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * `SOURCES_VERSION_MISMATCH` detection (T-028, tier 1): pure examples over
 * hand-built [ClassInfo]s plus generating properties. Real-jar behaviour
 * (matched fixtures stay silent, crafted stale jars warn) lives in the
 * tier-2 service suite; this suite owns the pairing rules.
 */
class SourcesMismatchTest {

    private val binary = "com.example.Widget"

    private fun classType(name: String): TypeName.ClassType =
        typeNameFromBinaryName(name) as TypeName.ClassType

    private fun methodDescriptor(params: List<String>): JvmDescriptor.Method {
        val key = mapOf(
            "int" to "I",
            "boolean" to "Z",
            "String" to "Ljava/lang/String;",
            "Object" to "Ljava/lang/Object;",
        )
        val encoded = params.joinToString("") { key.getValue(it) }
        return JvmDescriptor.parse("($encoded)V") as JvmDescriptor.Method
    }

    private fun fieldType(name: String): TypeName = when (name) {
        "int" -> typeNameFromBinaryName("int")
        "String" -> typeNameFromBinaryName("java.lang.String")
        else -> typeNameFromBinaryName("java.lang.Object")
    }

    private fun method(
        name: String,
        params: List<String> = emptyList(),
        access: Access = Access.of(AccessFlag.PUBLIC),
        genericParams: List<String>? = null,
    ): MethodInfo = MethodInfo(
        name = name,
        descriptor = methodDescriptor(params),
        access = access,
        genericSignature = genericParams?.let {
            MethodSignature(
                typeParameters = emptyList(),
                parameters = it.map { param -> TypeVariableSignature(param) },
                returnType = VoidSignature,
                throwsSignatures = emptyList(),
            )
        },
    )

    private fun field(name: String, access: Access = Access.of(AccessFlag.PRIVATE)): FieldInfo =
        FieldInfo(name = name, type = fieldType("Object"), access = access)

    private fun target(
        methods: List<MethodInfo> = emptyList(),
        fields: List<FieldInfo> = emptyList(),
        kind: TypeKind = TypeKind.CLASS,
        outer: String? = null,
    ): ClassInfo = ClassInfo(
        name = classType(binary),
        kind = kind,
        methods = methods,
        fields = fields,
        outerClass = outer?.let(::classType),
    )

    private fun sourceMethod(name: String, vararg params: String): DeclaredSourceMember =
        DeclaredSourceMember(SourceBodyKind.METHOD, name, params.toList())

    private fun sourceCtor(vararg params: String): DeclaredSourceMember =
        DeclaredSourceMember(SourceBodyKind.CONSTRUCTOR, "<init>", params.toList())

    private fun sourceField(name: String): DeclaredSourceMember =
        DeclaredSourceMember(SourceBodyKind.FIELD, name, emptyList())

    // -- agreement -----------------------------------------------------------

    @Test
    fun `identical member sets do not warn`() {
        val info = target(
            methods = listOf(method("greet", listOf("String")), method("<init>")),
            fields = listOf(field("seed")),
        )
        val sources = listOf(sourceMethod("greet", "String"), sourceCtor(), sourceField("seed"))
        detectSourcesMismatch(info, sources, "widget-sources.jar").shouldBeNull()
    }

    @Test
    fun `generic spellings pair with erased descriptors`() {
        // `U identity(U)` erases to `(Object)Object`; the generic signature
        // still names `U`, which is what the source text says (D-009).
        val info = target(methods = listOf(method("identity", listOf("Object"), genericParams = listOf("U"))))
        detectSourcesMismatch(info, listOf(sourceMethod("identity", "U")), "w-sources.jar").shouldBeNull()
    }

    @Test
    fun `varargs pair with array descriptors`() {
        val info = target(methods = listOf(method("join", listOf("String"))))
        // Bytecode holds `String[]`; sources spell `String...` — one key.
        detectSourcesMismatch(info, listOf(sourceMethod("join", "String...")), "w-sources.jar").shouldBeNull()
    }

    // -- disagreement --------------------------------------------------------

    @Test
    fun `a source-only member warns naming the member`() {
        val info = target(methods = listOf(method("kept")))
        val warning = detectSourcesMismatch(
            info,
            listOf(sourceMethod("kept"), sourceMethod("brandNew", "int")),
            "widget-sources.jar",
        )
        warning.shouldNotBeNull()
        warning.code shouldBe WarningCode.SOURCES_VERSION_MISMATCH
        warning.message shouldContain "widget-sources.jar declares"
        warning.message shouldContain "com.example.Widget#brandNew(int)"
        warning.message shouldContain "absent from com.example.Widget"
        warning.subject shouldBe binary
    }

    @Test
    fun `a bytecode-only member warns naming the omission`() {
        val info = target(methods = listOf(method("kept"), method("removed", listOf("int"))))
        val warning = detectSourcesMismatch(info, listOf(sourceMethod("kept")), "widget-sources.jar")
        warning.shouldNotBeNull()
        warning.code shouldBe WarningCode.SOURCES_VERSION_MISMATCH
        warning.message shouldContain "omits com.example.Widget#removed(int)"
        warning.message shouldContain "Structure shown from bytecode."
    }

    @Test
    fun `a renamed member warns on both sides`() {
        val info = target(methods = listOf(method("newName")))
        val warning = detectSourcesMismatch(info, listOf(sourceMethod("oldName")), "w-sources.jar")
        warning.shouldNotBeNull()
        warning.message shouldContain "declares com.example.Widget#oldName"
        warning.message shouldContain "omits com.example.Widget#newName"
    }

    @Test
    fun `field and method sharing a name pair by kind`() {
        // `int seconds;` plus `int seconds()` — legal Java, matched kind-aware.
        val info = target(methods = listOf(method("seconds")), fields = listOf(field("seconds")))
        val sources = listOf(sourceMethod("seconds"), sourceField("seconds"))
        detectSourcesMismatch(info, sources, "w-sources.jar").shouldBeNull()
    }

    // -- compiler output that must never warn --------------------------------

    @Test
    fun `bridge synthetic and clinit members are ignored`() {
        val bridge = method(
            "copy",
            listOf("Object"),
            access = Access.of(AccessFlag.PUBLIC, AccessFlag.BRIDGE, AccessFlag.SYNTHETIC),
        )
        val lambda = method(
            "lambda\$run\$0",
            access = Access.of(AccessFlag.PRIVATE, AccessFlag.SYNTHETIC),
        )
        val clinit = method("<clinit>", access = Access.of(AccessFlag.STATIC))
        val info = target(
            methods = listOf(method("run"), bridge, lambda, clinit),
            fields = listOf(field("this\$0", Access.of(AccessFlag.FINAL, AccessFlag.SYNTHETIC))),
        )
        detectSourcesMismatch(info, listOf(sourceMethod("run")), "w-sources.jar").shouldBeNull()
    }

    @Test
    fun `an implicit default constructor does not warn`() {
        val info = target(methods = listOf(method("<init>")))
        detectSourcesMismatch(info, emptyList(), "w-sources.jar").shouldBeNull()
    }

    @Test
    fun `two constructors with no source constructor do warn`() {
        val info = target(methods = listOf(method("<init>"), method("<init>", listOf("int"))))
        val warning = detectSourcesMismatch(info, emptyList(), "w-sources.jar")
        warning.shouldNotBeNull()
        warning.code shouldBe WarningCode.SOURCES_VERSION_MISMATCH
    }

    @Test
    fun `record implicit members do not warn`() {
        val info = target(
            methods = listOf(
                method("<init>", listOf("String", "int")),
                method("name"),
                method("age"),
            ),
            fields = listOf(field("name"), field("age")),
            kind = TypeKind.RECORD,
        )
        // Compact constructor plus components only — accessors are implicit.
        val sources = listOf(
            DeclaredSourceMember(SourceBodyKind.CONSTRUCTOR, "<init>", emptyList()),
            sourceField("name"),
            sourceField("age"),
        )
        detectSourcesMismatch(info, sources, "w-sources.jar").shouldBeNull()
    }

    @Test
    fun `a removed record member still warns`() {
        val info = target(
            methods = listOf(method("greet"), method("name")),
            fields = listOf(field("name")),
            kind = TypeKind.RECORD,
        )
        val sources = listOf(sourceField("name"))
        val warning = detectSourcesMismatch(info, sources, "w-sources.jar")
        warning.shouldNotBeNull()
        warning.message shouldContain "omits com.example.Widget#greet()"
    }

    @Test
    fun `enum implicit members and ctor prefix do not warn`() {
        val info = target(
            methods = listOf(
                method("<init>", listOf("String", "int", "int")),
                method("values", access = Access.of(AccessFlag.PUBLIC, AccessFlag.STATIC)),
                method("valueOf", listOf("String"), Access.of(AccessFlag.PUBLIC, AccessFlag.STATIC)),
                method("isStop"),
            ),
            fields = listOf(field("RED"), field("seconds")),
            kind = TypeKind.ENUM,
        )
        val sources = listOf(
            sourceCtor("int"),
            DeclaredSourceMember(SourceBodyKind.ENUM_ENTRY, "RED", emptyList()),
            sourceField("seconds"),
            sourceMethod("isStop"),
        )
        detectSourcesMismatch(info, sources, "w-sources.jar").shouldBeNull()
    }

    @Test
    fun `an inner-class outer parameter does not warn`() {
        val info = target(
            methods = listOf(method("<init>", listOf("Object")), method("outer")),
            outer = "com.example.Outer",
        )
        // Implicit inner default: no source `<init>`, one bytecode `<init>`.
        detectSourcesMismatch(info, listOf(sourceMethod("outer")), "w-sources.jar").shouldBeNull()
    }

    @Test
    fun `an explicit inner constructor pairs past the outer parameter`() {
        val outer = typeNameFromBinaryName("com.example.Outer")
        val info = target(
            methods = listOf(
                MethodInfo(
                    name = "<init>",
                    descriptor = JvmDescriptor.parse("(Lcom/example/Outer;I)V") as JvmDescriptor.Method,
                    access = Access.of(AccessFlag.PUBLIC),
                ),
            ),
            outer = "com.example.Outer",
        )
        check(outer.simpleName == "Outer")
        detectSourcesMismatch(info, listOf(sourceCtor("int")), "w-sources.jar").shouldBeNull()
    }

    // -- properties ----------------------------------------------------------

    private val cleanSpellings = listOf("int", "String", "Object", "boolean")
    private val hostileSpellings = cleanSpellings + listOf("U", "String...", "List<String>", "int[]", "", "a b")
    private val hostileNames = listOf(
        "add", "greet", "seed", "count", "isStop", "RED", "values", "valueOf",
        "toString", "name", "<init>", "<clinit>", "", "a b", "O\$dd", "ünïcode",
    )

    @Test
    fun `detection never throws on hostile input`() = runBlocking<Unit> {
        checkAll(1000, Arb.list(Arb.int(0..999), 0..6), Arb.list(Arb.int(0..999), 0..6)) { a, b ->
            detectSourcesMismatch(classInfoFromSeeds(a, hostile = false), sourcesFromSeeds(b, hostile = true), "s.jar")
        }
    }

    @Test
    fun `detection is deterministic`() = runBlocking<Unit> {
        checkAll(1000, Arb.list(Arb.int(0..999), 0..6), Arb.list(Arb.int(0..999), 0..6)) { a, b ->
            val info = classInfoFromSeeds(a, hostile = true)
            val sources = sourcesFromSeeds(b, hostile = true)
            detectSourcesMismatch(info, sources, "s.jar") shouldBe
                detectSourcesMismatch(info, sources, "s.jar")
        }
    }

    @Test
    fun `derived member sets never warn`() = runBlocking<Unit> {
        // Member sets derived from one source listing agree by construction —
        // the self-agreement law behind the matched-fixture silence.
        checkAll(1000, Arb.list(Arb.int(0..999), 0..6)) { seeds ->
            val sources = sourcesFromSeeds(seeds, hostile = false)
            detectSourcesMismatch(classInfoFromSources(sources), sources, "s.jar").shouldBeNull()
        }
    }

    /** Deterministic seed list → source members (clean or hostile spellings). */
    private fun sourcesFromSeeds(seeds: List<Int>, hostile: Boolean): List<DeclaredSourceMember> {
        val spellings = if (hostile) hostileSpellings else cleanSpellings
        val kinds = listOf(SourceBodyKind.METHOD, SourceBodyKind.CONSTRUCTOR, SourceBodyKind.FIELD, SourceBodyKind.ENUM_ENTRY)
        return seeds.map { seed ->
            val kind = kinds[(seed ushr 16) % kinds.size]
            val rawName = hostileNames[seed % hostileNames.size]
            // Constructors are always named `<init>` and methods never are —
            // the real listing upholds both, so generated inputs do too.
            val name = when {
                kind == SourceBodyKind.CONSTRUCTOR -> "<init>"
                rawName == "<init>" -> "renamedCtor"
                else -> rawName
            }
            val arity = (seed ushr 8) % 4
            val params = List(arity) { spellings[(seed ushr (it * 3)) % spellings.size] }
            DeclaredSourceMember(kind, name, params)
        }
    }

    /** Deterministic seed list → bytecode members over the clean pool (always valid descriptors). */
    private fun classInfoFromSeeds(seeds: List<Int>, hostile: Boolean): ClassInfo {
        val names = if (hostile) hostileNames else listOf("add", "greet", "run", "<init>")
        val methods = seeds.map { seed ->
            val name = names[seed % names.size]
            val arity = (seed ushr 8) % 4
            val params = List(arity) { cleanSpellings[(seed ushr (it * 3)) % cleanSpellings.size] }
            method(name, params)
        }
        return target(methods = methods)
    }

    /** Bytecode twin of one source listing: every entry has its counterpart. */
    private fun classInfoFromSources(sources: List<DeclaredSourceMember>): ClassInfo {
        val methods = sources.mapNotNull { source ->
            when (source.kind) {
                SourceBodyKind.METHOD -> method(source.name, source.parameterTypes)
                SourceBodyKind.CONSTRUCTOR -> method("<init>", source.parameterTypes)
                else -> null
            }
        }
        val fields = sources.mapNotNull { source ->
            when (source.kind) {
                SourceBodyKind.FIELD, SourceBodyKind.ENUM_ENTRY -> field(source.name)
                else -> null
            }
        }
        // An implicit default constructor when sources declare none — the
        // detector excuses exactly this shape, so agreement still holds.
        val allMethods = if (methods.none { it.name == "<init>" }) methods + method("<init>") else methods
        return target(methods = allMethods, fields = fields)
    }
}
