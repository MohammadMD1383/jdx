package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.jdx.cli.JdxCli
import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.Visibility
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.render.ErrorResult
import dev.jdx.core.render.buildClassCard
import dev.jdx.core.render.buildMemberListing
import dev.jdx.core.resolve.MemberResolver
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.index.workspace.WorkspaceDefinition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * In-process tests for `show`, `members` and `outline` (T-011).
 *
 * No disk, no jars: the service query and the process exit are both injected, so every
 * path — including the non-zero exits — runs in tier 1. Real-artifact behaviour (the
 * JDK, the fixture jar, goldens) is tier 2 in `ReadCommandsGoldenTest`.
 */
class ReadCommandsTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }

    // Tier 1 runs with no disk and no ambient project: discovery is off here.
    // Real discovery is covered in tier 2 (ReadCommandDiscoveryTest).
    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }

    // Hermetic roots (T-066): every command construction below resolves against an
    // empty in-memory store and an empty environment, so the contributor's ambient
    // `~/.config/jdx/active-workspace` (e.g. `fx`) and `JDX_WORKSPACE` can never
    // re-root these assertions. Workspace resolution itself is covered by the
    // `workspaceStore()` tests further down.
    private val noEnv: (String) -> String? = { null }
    private fun emptyStore(): InMemoryWorkspaceStore = InMemoryWorkspaceStore()

    private fun classType(binary: String): TypeName.ClassType =
        typeNameFromBinaryName(binary) as TypeName.ClassType

    private fun pointCard(): JdxService.ServiceOutcome.Card {
        val point = ClassInfo(name = classType("com.example.Point"), kind = TypeKind.CLASS)
        return JdxService.ServiceOutcome.Card(
            buildClassCard(point, listOf(Provenance(artifact = "app.jar", origin = Origin.BYTECODE))),
        )
    }

    private fun pointListing(): JdxService.ServiceOutcome.MemberList {
        val target = ClassInfo(
            name = classType("com.example.Point"),
            kind = TypeKind.CLASS,
            superclass = classType("java.lang.Object"),
            methods = listOf(
                MethodInfo(
                    name = "<init>",
                    descriptor = JvmDescriptor.parse("(II)V") as JvmDescriptor.Method,
                    access = Access.of(AccessFlag.PUBLIC),
                ),
                MethodInfo(
                    name = "getX",
                    descriptor = JvmDescriptor.parse("()I") as JvmDescriptor.Method,
                    access = Access.of(AccessFlag.PUBLIC),
                ),
            ),
            fields = listOf(
                FieldInfo(
                    name = "x",
                    type = (JvmDescriptor.parse("I") as JvmDescriptor.Field).type,
                    access = Access.of(AccessFlag.PUBLIC),
                ),
            ),
        )
        val objectStub = ClassInfo(name = classType("java.lang.Object"), kind = TypeKind.CLASS)
        val byBinary = listOf(target, objectStub).associateBy { it.name.binaryName }
        val resolved = MemberResolver.resolve(target, lookup = { name -> byBinary[name.binaryName] })
        return JdxService.ServiceOutcome.MemberList(
            buildMemberListing(
                target = target,
                resolved = resolved,
                provenance = listOf(Provenance(artifact = "app.jar", origin = Origin.BYTECODE)),
            ),
        )
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }

    // -- show -------------------------------------------------------------------

    @Test
    fun `show prints the card text and exits zero`() {
        var seenRoots: JdxService.RootsSpec? = null
        val output = captureStdout {
            ShowCommand(
                query = { ref, roots -> seenRoots = roots; ref shouldBe "com.example.Point"; pointCard() },
                terminate = noExit,
                discover = noDiscovery,
                store = emptyStore(),
                getenv = noEnv,
            ).parse(listOf("com.example.Point"))
        }
        output shouldContain "class com.example.Point"
        output shouldContain "next: jdx members com.example.Point --inherited"
        seenRoots shouldBe JdxService.RootsSpec(emptyList(), includeJdk = true)
    }

    @Test
    fun `show forwards jars and no-jdk to the roots`() {
        var seenRoots: JdxService.RootsSpec? = null
        captureStdout {
            ShowCommand(
                query = { _, roots -> seenRoots = roots; pointCard() },
                terminate = noExit,
                discover = noDiscovery,
                store = emptyStore(),
                getenv = noEnv,
            ).parse(listOf("Point", "--jars", "a.jar", "--jars", "b/*.jar", "--no-jdk"))
        }
        seenRoots shouldBe JdxService.RootsSpec(listOf("a.jar", "b/*.jar"), includeJdk = false)
    }

    @Test
    fun `show --json after the subcommand prints the envelope`() {
        val output = captureStdout {
            ShowCommand(query = { _, _ -> pointCard() }, terminate = noExit, discover = noDiscovery, store = emptyStore(), getenv = noEnv)
                .parse(listOf("Point", "--json"))
        }
        val parsed = Json.parseToJsonElement(output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "show"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
    }

    @Test
    fun `--json before the subcommand flows to show`() {
        val output = captureStdout {
            JdxCli().subcommands(
                ShowCommand(query = { _, _ -> pointCard() }, terminate = noExit, discover = noDiscovery, store = emptyStore(), getenv = noEnv),
            )
                .parse(listOf("--json", "show", "Point"))
        }
        Json.parseToJsonElement(output.trim()).jsonObject["command"]
            ?.jsonPrimitive?.content shouldBe "show"
    }

    @Test
    fun `show maps a service failure to its exit code`() {
        val thrown = try {
            captureStdout {
                ShowCommand(
                    query = { ref, _ ->
                        JdxService.ServiceOutcome.Failure(ErrorResult.notFound(ref, emptyList()))
                    },
                    terminate = noExit,
                    discover = noDiscovery,
                    store = emptyStore(),
                    getenv = noEnv,
                ).parse(listOf("Missing"))
            }
            null
        } catch (e: TestExit) {
            e
        }
        (thrown?.code) shouldBe 1
    }

    // -- members ----------------------------------------------------------------

    @Test
    fun `members defaults to inherited public members`() {
        var declared = true
        var filters: JdxService.MemberFilters? = null
        val output = captureStdout {
            MembersCommand(
                query = { _, _, f, d, _, _ -> filters = f; declared = d; pointListing() },
                terminate = noExit,
                discover = noDiscovery,
                store = emptyStore(),
                getenv = noEnv,
            ).parse(listOf("com.example.Point"))
        }
        declared shouldBe false
        filters?.kind shouldBe JdxService.KindFilter.ALL
        filters?.access shouldBe null
        filters?.staticOnly shouldBe null
        output shouldContain "members of com.example.Point"
    }

    @Test
    fun `members --declared scopes to the type itself`() {
        var declared = false
        captureStdout {
            MembersCommand(
                query = { _, _, _, d, _, _ -> declared = d; pointListing() },
                terminate = noExit,
                discover = noDiscovery,
                store = emptyStore(),
                getenv = noEnv,
            ).parse(listOf("Point", "--declared"))
        }
        declared shouldBe true
    }

    @Test
    fun `members forwards every filter flag to the service`() {
        var filters: JdxService.MemberFilters? = null
        var synthetic = false
        var limit = 0
        captureStdout {
            MembersCommand(
                query = { _, _, f, _, s, m -> filters = f; synthetic = s; limit = m; pointListing() },
                terminate = noExit,
                discover = noDiscovery,
                store = emptyStore(),
                getenv = noEnv,
            ).parse(
                listOf(
                    "Point", "--kind", "ctor", "--access", "all", "--static",
                    "--from", "com.example.Base", "--grep", "get.*", "--limit", "7",
                    "--include-synthetic",
                ),
            )
        }
        filters?.kind shouldBe JdxService.KindFilter.CTOR
        filters?.access shouldBe
            setOf(Visibility.PUBLIC, Visibility.PROTECTED, Visibility.PACKAGE_PRIVATE, Visibility.PRIVATE)
        filters?.staticOnly shouldBe true
        filters?.fromRef shouldBe "com.example.Base"
        (filters?.grep?.pattern) shouldBe "get.*"
        synthetic shouldBe true
        limit shouldBe 7
    }

    @Test
    fun `members --instance maps to instance-only`() {
        var filters: JdxService.MemberFilters? = null
        captureStdout {
            MembersCommand(
                query = { _, _, f, _, _, _ -> filters = f; pointListing() },
                terminate = noExit,
                discover = noDiscovery,
                store = emptyStore(),
                getenv = noEnv,
            ).parse(listOf("Point", "--instance"))
        }
        filters?.staticOnly shouldBe false
    }

    @Test
    fun `members --static and --instance together exit 3 without querying`() {
        var queried = false
        val output = captureStdout {
            val thrown = try {
                MembersCommand(
                    query = { _, _, _, _, _, _ -> queried = true; pointListing() },
                    terminate = noExit,
                    discover = noDiscovery,
                    store = emptyStore(),
                    getenv = noEnv,
                ).parse(listOf("Point", "--static", "--instance"))
                null
            } catch (e: TestExit) {
                e
            }
            (thrown?.code) shouldBe 3
        }
        queried shouldBe false
        output shouldContain "usage error: --static and --instance are mutually exclusive"
    }

    @Test
    fun `members with an invalid grep exits 3 without querying`() {
        var queried = false
        val output = captureStdout {
            val thrown = try {
                MembersCommand(
                    query = { _, _, _, _, _, _ -> queried = true; pointListing() },
                    terminate = noExit,
                    discover = noDiscovery,
                    store = emptyStore(),
                    getenv = noEnv,
                ).parse(listOf("Point", "--grep", "[unclosed"))
                null
            } catch (e: TestExit) {
                e
            }
            (thrown?.code) shouldBe 3
        }
        queried shouldBe false
        output shouldContain "usage error: invalid --grep regex"
    }

    @Test
    fun `members --with-doc reaches the service`() {
        var seen = false
        captureStdout {
            MembersCommand(
                query = { _, _, filters, _, _, _ -> seen = filters.withDoc; pointListing() },
                terminate = noExit,
                discover = noDiscovery,
                store = emptyStore(),
                getenv = noEnv,
            ).parse(listOf("Point", "--with-doc"))
        }
        seen shouldBe true
    }

    @Test
    fun `members --sort reaches the service in every order (T-062)`() {
        for ((flag, expected) in listOf(
            "kind" to dev.jdx.core.render.MemberSort.KIND,
            "name" to dev.jdx.core.render.MemberSort.NAME,
            "declaring" to dev.jdx.core.render.MemberSort.DECLARING,
        )) {
            var seen: dev.jdx.core.render.MemberSort? = null
            captureStdout {
                MembersCommand(
                    query = { _, _, filters, _, _, _ -> seen = filters.sort; pointListing() },
                    terminate = noExit,
                    discover = noDiscovery,
                    store = emptyStore(),
                    getenv = noEnv,
                ).parse(listOf("Point", "--sort", flag))
            }
            seen shouldBe expected
        }
    }

    @Test
    fun `outline --sort reaches the service in every order (T-062)`() {
        for ((flag, expected) in listOf(
            "kind" to dev.jdx.core.render.MemberSort.KIND,
            "name" to dev.jdx.core.render.MemberSort.NAME,
            "declaring" to dev.jdx.core.render.MemberSort.DECLARING,
        )) {
            var seen: dev.jdx.core.render.MemberSort? = null
            captureStdout {
                OutlineCommand(
                    query = { _, _, filters, _, _, _ -> seen = filters.sort; pointListing() },
                    terminate = noExit,
                    discover = noDiscovery,
                    store = emptyStore(),
                    getenv = noEnv,
                ).parse(listOf("Point", "--sort", flag))
            }
            seen shouldBe expected
        }
    }

    @Test
    fun `members json carries the same signatures as text`() {
        val text = captureStdout {
            MembersCommand(query = { _, _, _, _, _, _ -> pointListing() }, terminate = noExit, discover = noDiscovery, store = emptyStore(), getenv = noEnv)
                .parse(listOf("Point"))
        }
        val json = captureStdout {
            MembersCommand(query = { _, _, _, _, _, _ -> pointListing() }, terminate = noExit, discover = noDiscovery, store = emptyStore(), getenv = noEnv)
                .parse(listOf("Point", "--json"))
        }
        val parsed = Json.parseToJsonElement(json.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "members"
        for (line in text.lines().filter { it.startsWith("  ") }) {
            val signature = line.substringAfter("  ").substringAfter(" ")
            json shouldContain signature
        }
    }

    @Test
    fun `members maps an ambiguous service failure to exit 2 with candidates`() {
        val output = captureStdout {
            val thrown = try {
                MembersCommand(
                    query = { ref, _, _, _, _, _ ->
                        JdxService.ServiceOutcome.Failure(
                            ErrorResult.ambiguous(ref, listOf("com.a.Point", "com.b.Point")),
                        )
                    },
                    terminate = noExit,
                    discover = noDiscovery,
                    store = emptyStore(),
                    getenv = noEnv,
                ).parse(listOf("Point"))
                null
            } catch (e: TestExit) {
                e
            }
            (thrown?.code) shouldBe 2
        }
        output shouldContain "com.a.Point"
        output shouldContain "com.b.Point"
    }

    // -- outline ------------------------------------------------------------------

    @Test
    fun `outline always queries in declared-only mode`() {        var declared = false
        val output = captureStdout {
            OutlineCommand(
                query = { _, _, _, d, _, _ -> declared = d; pointListing() },
                terminate = noExit,
                discover = noDiscovery,
                store = emptyStore(),
                getenv = noEnv,
            ).parse(listOf("Point", "--kind", "method"))
        }
        declared shouldBe true
        output shouldContain "members of com.example.Point"
    }

    @Test
    fun `outline --json prints the outline envelope`() {
        val output = captureStdout {
            OutlineCommand(query = { _, _, _, _, _, _ -> pointListing() }, terminate = noExit, discover = noDiscovery, store = emptyStore(), getenv = noEnv)
                .parse(listOf("Point", "--json"))
        }
        Json.parseToJsonElement(output.trim()).jsonObject["command"]
            ?.jsonPrimitive?.content shouldBe "outline"
    }

    // -- workspaces ---------------------------------------------------------------

    private fun workspaceStore(): InMemoryWorkspaceStore = InMemoryWorkspaceStore().also {
        it.save(WorkspaceDefinition("mc", listOf("ws.jar"), includeJdk = true))
        it.save(WorkspaceDefinition("nojdk", listOf("ws.jar"), includeJdk = false))
    }

    @Test
    fun `show -w resolves the workspace jars`() {
        var seenRoots: JdxService.RootsSpec? = null
        captureStdout {
            ShowCommand(
                query = { _, roots -> seenRoots = roots; pointCard() },
                terminate = noExit,
                discover = noDiscovery,
                store = workspaceStore(),
                getenv = { null },
            ).parse(listOf("Point", "-w", "mc"))
        }
        seenRoots shouldBe JdxService.RootsSpec(listOf("ws.jar"), includeJdk = true)
    }

    @Test
    fun `explicit jars merge in front of workspace jars`() {
        var seenRoots: JdxService.RootsSpec? = null
        captureStdout {
            ShowCommand(
                query = { _, roots -> seenRoots = roots; pointCard() },
                terminate = noExit,
                discover = noDiscovery,
                store = workspaceStore(),
                getenv = { null },
            ).parse(listOf("Point", "--jars", "cli.jar", "-w", "mc"))
        }
        seenRoots shouldBe JdxService.RootsSpec(listOf("cli.jar", "ws.jar"), includeJdk = true)
    }

    @Test
    fun `no-jdk wins over a workspace that keeps the JDK`() {
        var seenRoots: JdxService.RootsSpec? = null
        captureStdout {
            ShowCommand(
                query = { _, roots -> seenRoots = roots; pointCard() },
                terminate = noExit,
                discover = noDiscovery,
                store = workspaceStore(),
                getenv = { null },
            ).parse(listOf("Point", "-w", "mc", "--no-jdk"))
        }
        seenRoots shouldBe JdxService.RootsSpec(listOf("ws.jar"), includeJdk = false)
    }

    @Test
    fun `-w before the subcommand flows to show`() {
        var seenRoots: JdxService.RootsSpec? = null
        captureStdout {
            JdxCli().subcommands(
                ShowCommand(
                    query = { _, roots -> seenRoots = roots; pointCard() },
                    terminate = noExit,
                    discover = noDiscovery,
                    store = workspaceStore(),
                    getenv = { null },
                ),
            ).parse(listOf("-w", "mc", "show", "Point"))
        }
        seenRoots shouldBe JdxService.RootsSpec(listOf("ws.jar"), includeJdk = true)
    }

    @Test
    fun `JDX_WORKSPACE and the use default feed resolution, flag wins`() {
        var seenRoots: JdxService.RootsSpec? = null
        fun parse(argv: List<String>, env: String?, active: String?) {
            val store = workspaceStore().also { if (active != null) it.setActive(active) }
            captureStdout {
                ShowCommand(
                    query = { _, roots -> seenRoots = roots; pointCard() },
                    terminate = noExit,
                    discover = noDiscovery,
                    store = store,
                    getenv = { if (it == "JDX_WORKSPACE") env else null },
                ).parse(argv)
            }
        }
        parse(listOf("Point"), env = "nojdk", active = "mc")
        seenRoots shouldBe JdxService.RootsSpec(listOf("ws.jar"), includeJdk = false)
        parse(listOf("Point"), env = null, active = "nojdk")
        seenRoots shouldBe JdxService.RootsSpec(listOf("ws.jar"), includeJdk = false)
        parse(listOf("Point", "-w", "mc"), env = "nojdk", active = "nojdk")
        seenRoots shouldBe JdxService.RootsSpec(listOf("ws.jar"), includeJdk = true)
    }

    @Test
    fun `a missing workspace exits 4 without querying`() {
        var queried = false
        val output = captureStdout {
            val thrown = try {
                ShowCommand(
                    query = { _, _ -> queried = true; pointCard() },
                    terminate = noExit,
                    discover = noDiscovery,
                    store = workspaceStore(),
                    getenv = { null },
                ).parse(listOf("Point", "-w", "ghost"))
                null
            } catch (e: TestExit) {
                e
            }
            (thrown?.code) shouldBe 4
        }
        queried shouldBe false
        output shouldContain "no such workspace 'ghost'"
    }

    @Test
    fun `members -w resolves the workspace too`() {
        var seenRoots: JdxService.RootsSpec? = null
        captureStdout {
            MembersCommand(
                query = { _, roots, _, _, _, _ -> seenRoots = roots; pointListing() },
                terminate = noExit,
                discover = noDiscovery,
                store = workspaceStore(),
                getenv = { null },
            ).parse(listOf("Point", "--workspace", "mc"))
        }
        seenRoots shouldBe JdxService.RootsSpec(listOf("ws.jar"), includeJdk = true)
    }

    // -- pure flag mapping ----------------------------------------------------------

    @Test
    fun `access mapping covers every choice value`() {
        ReadCommandSupport.accessOf(null) shouldBe null
        ReadCommandSupport.accessOf("public") shouldBe setOf(Visibility.PUBLIC)
        ReadCommandSupport.accessOf("protected") shouldBe setOf(Visibility.PROTECTED)
        ReadCommandSupport.accessOf("package") shouldBe setOf(Visibility.PACKAGE_PRIVATE)
        ReadCommandSupport.accessOf("private") shouldBe setOf(Visibility.PRIVATE)
        ReadCommandSupport.accessOf("all") shouldBe
            setOf(Visibility.PUBLIC, Visibility.PROTECTED, Visibility.PACKAGE_PRIVATE, Visibility.PRIVATE)
    }

    @Test
    fun `kind mapping covers every choice value`() {
        ReadCommandSupport.kindOf("all") shouldBe JdxService.KindFilter.ALL
        ReadCommandSupport.kindOf("method") shouldBe JdxService.KindFilter.METHOD
        ReadCommandSupport.kindOf("field") shouldBe JdxService.KindFilter.FIELD
        ReadCommandSupport.kindOf("ctor") shouldBe JdxService.KindFilter.CTOR
    }

    @Test
    fun `sort mapping covers every choice value`() {
        ReadCommandSupport.sortOf("kind") shouldBe dev.jdx.core.render.MemberSort.KIND
        ReadCommandSupport.sortOf("NAME") shouldBe dev.jdx.core.render.MemberSort.NAME
        ReadCommandSupport.sortOf("declaring") shouldBe dev.jdx.core.render.MemberSort.DECLARING
        ReadCommandSupport.sortOf("bogus") shouldBe dev.jdx.core.render.MemberSort.KIND
    }

    @Test
    fun `flag validation accepts every sort order`() {
        ReadCommandSupport.validateMemberFlags(false, false, null, false, "kind") shouldBe null
        ReadCommandSupport.validateMemberFlags(false, false, null, false, "name") shouldBe null
        ReadCommandSupport.validateMemberFlags(false, false, null, false, "declaring") shouldBe null
        ReadCommandSupport.validateMemberFlags(false, false, null, false, "bogus") shouldContain "--sort"
    }

    @Test
    fun `repo base urls keep flag order and Central last`() {
        val central = dev.jdx.index.maven.MavenCoords.CENTRAL_BASE_URL
        ReadCommandSupport.buildRepoBaseUrls(emptyList()) shouldBe listOf(central)
        ReadCommandSupport.buildRepoBaseUrls(listOf("https://mirror.example.com/maven2")) shouldBe
            listOf("https://mirror.example.com/maven2", central)
        // Trailing-slash variants dedupe; first occurrence wins.
        ReadCommandSupport.buildRepoBaseUrls(
            listOf("https://a.example.com/r/", "https://a.example.com/r", "https://b.example.com/r"),
        ) shouldBe listOf("https://a.example.com/r/", "https://b.example.com/r", central)
        // An explicit Central stays where it was named, not duplicated last.
        ReadCommandSupport.buildRepoBaseUrls(listOf(central)) shouldBe listOf(central)
    }
}
