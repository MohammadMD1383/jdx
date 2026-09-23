package dev.jdx.index.service

import dev.jdx.core.rpc.RpcCommand
import dev.jdx.core.rpc.RpcRequest
import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.service.JdxService.BodyOptions
import dev.jdx.index.service.JdxService.CallOptions
import dev.jdx.index.service.JdxService.DocOptions
import dev.jdx.index.service.JdxService.HierarchyOptions
import dev.jdx.index.service.JdxService.MemberFilters
import dev.jdx.index.service.JdxService.MemberView
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.SampleOptions
import dev.jdx.index.service.JdxService.SearchKindFilter
import dev.jdx.index.service.JdxService.SearchOptions
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.index.service.JdxService.SignatureOptions
import dev.jdx.index.service.JdxService.SourceOptions
import dev.jdx.index.service.JdxService.UsageOptions
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.index.workspace.WorkspaceDefinition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.pair
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Tier 2: the daemon's query dispatch (T-082) over real artifacts — the fixture
 * corpus jar plus crafted case jars whose every edge is placed by hand.
 *
 * The acceptance is byte parity: every [RpcCommand] dispatched through
 * [JdxService.dispatch] returns the same bytes as the in-process service call
 * with the equivalent options, so warm daemon answers are byte-identical to
 * cold one-shot ones (D-056 §1). Param errors read as exit-3 envelopes naming
 * the param. The generating family is the hostile-params property: dispatch is
 * total, so hostile input reads as an exit-coded envelope on one line, never a
 * throw.
 */
@Tag("tier2")
class RpcDispatchTest {

    private val tempDir: java.nio.file.Path = Files.createTempDirectory("rpc-dispatch-test")

    private fun fixtureRoots(): RootsSpec = RootsSpec(
        jarSpecs = listOf(
            ArtifactTestJars.binaryJar().absolutePath,
            buildCallsCaseJar(tempDir).toString(),
            buildUsagesCaseJar(tempDir).toString(),
            buildHierarchyCaseJar(tempDir).toString(),
            buildSamplesCaseJar(tempDir).toString(),
        ),
        includeJdk = false,
    )

    private fun viaWire(request: RpcRequest, roots: RootsSpec = fixtureRoots()): String =
        JdxService.dispatch(request, roots).toJson(request.command.wire)

    private fun req(command: RpcCommand, query: String = "", vararg params: Pair<String, String>): RpcRequest =
        RpcRequest(command, query, params.toMap())

    // -- parity: one representative request per read command --------------------------

    @Test
    fun `show dispatches to the class card`() {
        val query = "dev.jdx.fixtures.Generics"
        viaWire(req(RpcCommand.SHOW, query)) shouldBe JdxService.show(query, fixtureRoots()).toJson("show")
    }

    @Test
    fun `members dispatches with filters`() {
        val query = "dev.jdx.fixtures.Generics"
        val request = req(
            RpcCommand.MEMBERS, query,
            "kind" to "method", "limit" to "5", "view" to "jvm",
        )
        val direct = JdxService.members(
            query, fixtureRoots(),
            MemberFilters(kind = JdxService.KindFilter.METHOD, view = MemberView.JVM),
            declaredOnly = false, includeSynthetic = false, maxMembers = 5,
        )
        viaWire(request) shouldBe direct.toJson("members")
        direct.exitCode shouldBe 0
    }

    @Test
    fun `outline dispatches declared-only`() {
        val query = "dev.jdx.fixtures.Generics"
        val request = req(RpcCommand.OUTLINE, query, "access" to "public", "sort" to "name")
        val direct = JdxService.outline(
            query, fixtureRoots(),
            MemberFilters(
                access = setOf(dev.jdx.core.model.Visibility.PUBLIC),
                sort = dev.jdx.core.render.MemberSort.NAME,
            ),
            includeSynthetic = false, maxMembers = 50,
        )
        viaWire(request) shouldBe direct.toJson("outline")
        direct.exitCode shouldBe 0
    }

    @Test
    fun `body dispatches with presentation options`() {
        val query = "dev.jdx.fixtures.Generics#identity(java.lang.Object)"
        val request = req(RpcCommand.BODY, query, "maxLines" to "50", "withSignature" to "true")
        val direct = JdxService.body(
            query, fixtureRoots(), BodyOptions(maxLines = 50, withSignature = true),
        )
        viaWire(request) shouldBe direct.toJson("body")
        direct.exitCode shouldBe 0
    }

    @Test
    fun `source dispatches with a lines window`() {
        val query = "dev.jdx.fixtures.Generics"
        val request = req(RpcCommand.SOURCE, query, "lines" to "1:10", "lineNumbers" to "true")
        val direct = JdxService.source(
            query, fixtureRoots(), SourceOptions(lines = 1 to 10, lineNumbers = true),
        )
        viaWire(request) shouldBe direct.toJson("source")
    }

    @Test
    fun `signature dispatches with view`() {
        val query = "dev.jdx.fixtures.Generics#identity"
        val request = req(RpcCommand.SIGNATURE, query, "view" to "jvm", "limit" to "10")
        val direct = JdxService.signature(
            query, fixtureRoots(), SignatureOptions(maxSignatures = 10, view = MemberView.JVM),
        )
        viaWire(request) shouldBe direct.toJson("signature")
        direct.exitCode shouldBe 0
    }

    @Test
    fun `doc dispatches with raw`() {
        val query = "dev.jdx.fixtures.Generics"
        val request = req(RpcCommand.DOC, query, "raw" to "true")
        val direct = JdxService.doc(query, fixtureRoots(), DocOptions(raw = true))
        viaWire(request) shouldBe direct.toJson("doc")
    }

    @Test
    fun `search dispatches with kind`() {
        val request = req(RpcCommand.SEARCH, "*Generic*", "kind" to "class")
        val direct = JdxService.search("*Generic*", fixtureRoots(), SearchOptions(kind = SearchKindFilter.CLASS))
        viaWire(request) shouldBe direct.toJson("search")
        direct.exitCode shouldBe 0
    }

    @Test
    fun `resolve dispatches with limit`() {
        val request = req(RpcCommand.RESOLVE, "Generics", "limit" to "10")
        val direct = JdxService.resolve("Generics", fixtureRoots(), 10)
        viaWire(request) shouldBe direct.toJson("resolve")
        direct.exitCode shouldBe 0
    }

    @Test
    fun `ls dispatches an exact package`() {
        val request = req(RpcCommand.LS, "dev.jdx.fixtures")
        val direct = JdxService.ls("dev.jdx.fixtures", fixtureRoots(), 50)
        viaWire(request) shouldBe direct.toJson("ls")
        direct.exitCode shouldBe 0
    }

    @Test
    fun `tree dispatches with depth and counts`() {
        val request = req(RpcCommand.TREE, "", "depth" to "3", "counts" to "true")
        val direct = JdxService.tree(null, fixtureRoots(), depth = 3, withCounts = true, limit = 50)
        viaWire(request) shouldBe direct.toJson("tree")
        direct.exitCode shouldBe 0
    }

    @Test
    fun `usages dispatches with kind`() {
        val request = req(RpcCommand.USAGES, "u.Lib#greet", "kind" to "call")
        val direct = JdxService.usages("u.Lib#greet", fixtureRoots(), UsageOptions(kind = JdxService.UsageKindFilter.CALL))
        viaWire(request) shouldBe direct.toJson("usages")
        direct.exitCode shouldBe 0
    }

    @Test
    fun `hierarchy dispatches both directions`() {
        val request = req(RpcCommand.HIERARCHY, "h.Middle", "direct" to "true")
        val direct = JdxService.hierarchy(
            "h.Middle", fixtureRoots(),
            HierarchyOptions(up = true, down = true, directOnly = true, depth = Int.MAX_VALUE, limit = 50),
        )
        viaWire(request) shouldBe direct.toJson("hierarchy")
        direct.exitCode shouldBe 0
    }

    @Test
    fun `implementors dispatches downward only`() {
        val request = req(RpcCommand.IMPLEMENTORS, "h.Iface")
        val direct = JdxService.hierarchy(
            "h.Iface", fixtureRoots(),
            HierarchyOptions(up = false, down = true, depth = Int.MAX_VALUE, limit = 50),
        )
        viaWire(request) shouldBe direct.toJson("implementors")
        direct.exitCode shouldBe 0
    }

    @Test
    fun `callers dispatches with depth`() {
        val query = "c.Lib#greet(java.lang.String)"
        val request = req(RpcCommand.CALLERS, query, "depth" to "2")
        val direct = JdxService.callers(query, fixtureRoots(), CallOptions(depth = 2))
        viaWire(request) shouldBe direct.toJson("callers")
        direct.exitCode shouldBe 0
    }

    @Test
    fun `calls dispatches with external-only`() {
        val query = "c.App#run()"
        val request = req(RpcCommand.CALLS, query, "externalOnly" to "true")
        val direct = JdxService.calls(query, fixtureRoots(), CallOptions(externalOnly = true))
        viaWire(request) shouldBe direct.toJson("calls")
    }

    @Test
    fun `samples dispatches with prefer-sources`() {
        val query = "s.Lib#greet"
        val request = req(RpcCommand.SAMPLES, query, "preferSources" to "true")
        val direct = JdxService.samples(query, fixtureRoots(), SampleOptions(preferSources = true))
        viaWire(request) shouldBe direct.toJson("samples")
        direct.exitCode shouldBe 0
    }

    // -- param errors: exit 3 naming the param -----------------------------------------

    @Test
    fun `unknown enum values are usage errors`() {
        viaWire(req(RpcCommand.MEMBERS, "dev.jdx.fixtures.Generics", "kind" to "bogus")) shouldContain "\"code\":3"
        viaWire(req(RpcCommand.MEMBERS, "dev.jdx.fixtures.Generics", "kind" to "bogus")) shouldContain "--kind 'bogus'"
        viaWire(req(RpcCommand.MEMBERS, "dev.jdx.fixtures.Generics", "access" to "bogus")) shouldContain "--access 'bogus'"
        viaWire(req(RpcCommand.MEMBERS, "dev.jdx.fixtures.Generics", "sort" to "bogus")) shouldContain "--sort 'bogus'"
        viaWire(req(RpcCommand.MEMBERS, "dev.jdx.fixtures.Generics", "view" to "bogus")) shouldContain "--view 'bogus'"
        viaWire(req(RpcCommand.SEARCH, "x", "kind" to "bogus")) shouldContain "--kind 'bogus'"
        viaWire(req(RpcCommand.USAGES, "u.Lib", "kind" to "bogus")) shouldContain "--kind 'bogus'"
        viaWire(req(RpcCommand.BODY, "dev.jdx.fixtures.Generics#identity", "engine" to "bogus")) shouldContain "--engine bogus"
    }

    @Test
    fun `unparseable integers are usage errors`() {
        val line = viaWire(req(RpcCommand.MEMBERS, "dev.jdx.fixtures.Generics", "limit" to "many"))
        line shouldContain "\"code\":3"
        line shouldContain "--limit"
        viaWire(req(RpcCommand.TREE, "", "depth" to "deep")) shouldContain "--depth"
    }

    @Test
    fun `mutually exclusive flags are usage errors`() {
        viaWire(
            req(RpcCommand.MEMBERS, "dev.jdx.fixtures.Generics", "static" to "true", "instance" to "true"),
        ) shouldContain "--static and --instance are mutually exclusive"
        viaWire(
            req(RpcCommand.DOC, "dev.jdx.fixtures.Generics", "inherited" to "true", "no-inherited" to "true"),
        ) shouldContain "--inherited and --no-inherited are mutually exclusive"
    }

    @Test
    fun `bad grep and lines windows are usage errors`() {
        viaWire(req(RpcCommand.MEMBERS, "dev.jdx.fixtures.Generics", "grep" to "([")) shouldContain "--grep"
        viaWire(req(RpcCommand.SOURCE, "dev.jdx.fixtures.Generics", "lines" to "5:2")) shouldContain "--lines"
        viaWire(req(RpcCommand.SOURCE, "dev.jdx.fixtures.Generics", "lines" to "abc")) shouldContain "--lines"
    }

    @Test
    fun `service-level validation still applies through dispatch`() {
        // Negative limits bypass no parsing rule: the service owns the range check (exit 3).
        viaWire(req(RpcCommand.MEMBERS, "dev.jdx.fixtures.Generics", "limit" to "-1")) shouldContain "\"code\":3"
        viaWire(req(RpcCommand.CALLERS, "c.Lib#greet", "depth" to "0")) shouldContain "\"code\":3"
    }

    @Test
    fun `transport-level commands refuse through dispatch`() {
        for (command in listOf(RpcCommand.VERSION, RpcCommand.DOCTOR, RpcCommand.HEALTH)) {
            val line = viaWire(req(command))
            line shouldContain "\"code\":3"
            line shouldContain "daemon transport"
        }
    }

    // -- roots --------------------------------------------------------------------------

    @Test
    fun `daemonRoots resolves a stored workspace`() {
        val store = InMemoryWorkspaceStore()
        val jar = ArtifactTestJars.binaryJar().absolutePath
        store.save(WorkspaceDefinition(name = "fx", jars = listOf(jar), includeJdk = false))
        when (val resolved = JdxService.daemonRoots("fx", "dev.jdx.fixtures.Generics", store)) {
            is DaemonRoots.Ready -> {
                resolved.roots.jarSpecs shouldBe listOf(jar)
                resolved.roots.includeJdk shouldBe false
            }
            is DaemonRoots.Failed -> throw AssertionError("expected Ready, got $resolved")
        }
    }

    @Test
    fun `daemonRoots on a missing workspace fails exit 4`() {
        val outcome = JdxService.daemonRoots("nope", "q", InMemoryWorkspaceStore())
        (outcome is DaemonRoots.Failed) shouldBe true
        val line = (outcome as DaemonRoots.Failed).outcome.toJson("show")
        line shouldContain "\"code\":4"
        line shouldContain "no such workspace 'nope'"
    }

    @Test
    fun `daemonRoots on an unresolvable stored coordinate fails exit 5`() {
        val store = InMemoryWorkspaceStore()
        store.save(WorkspaceDefinition(name = "fx", jars = emptyList(), coords = listOf("nope:nope:1.0")))
        val outcome = JdxService.daemonRoots("fx", "q", store)
        (outcome is DaemonRoots.Failed) shouldBe true
        (outcome as DaemonRoots.Failed).outcome.toJson("show") shouldContain "\"code\":5"
    }

    // -- generating family -----------------------------------------------------------------

    private val hostileChars: List<Char> = listOf(
        '"', '\\', '/', '\n', '\r', '\t', '\b', '\u000C', '\u0000', '\u0001', '\u001F',
        ' ', 'a', 'Z', '0', '9', '{', '}', '[', ']', ':', ',', '-', 'e', '.', '#', '(', ')',
    )

    private fun arbHostileString(range: IntRange): Arb<String> =
        Arb.list(Arb.of(hostileChars), range).map { chars -> chars.joinToString("") }

    private fun arbParams(): Arb<Map<String, String>> =
        Arb.list(Arb.pair(arbHostileString(0..8), arbHostileString(0..8)), 0..6).map { it.toMap() }

    @Test
    fun `hostile queries and params never throw, always one exit-coded line`(): Unit = runBlocking {
        // Empty roots fail fast (exit 3/4/5) before any jar is opened, so 200 hostile
        // dispatches per command stay milliseconds: this pins totality, not behaviour.
        val noRoots = RootsSpec(jarSpecs = emptyList(), includeJdk = false)
        val commands = RpcCommand.entries.filter { it != RpcCommand.VERSION && it != RpcCommand.DOCTOR && it != RpcCommand.HEALTH }
        checkAll(50, Arb.bind(Arb.of(commands), arbHostileString(0..24), arbParams()) { command, query, params ->
            RpcRequest(command, query, params)
        }) { request ->
            val outcome = JdxService.dispatch(request, noRoots)
            (outcome.exitCode in 3..5) shouldBe true
            val line = outcome.toJson(request.command.wire)
            line shouldNotContain "\n"
            line shouldNotContain "\r"
            line shouldContain "\"command\":\"${request.command.wire}\""
        }
    }

    @Test
    fun `dispatch is deterministic over hostile input`(): Unit = runBlocking {
        val roots = fixtureRoots()
        checkAll(20, Arb.bind(Arb.of(RpcCommand.entries), arbHostileString(0..24), arbParams()) { command, query, params ->
            RpcRequest(command, query, params)
        }) { request ->
            // HEALTH/VERSION/DOCTOR refuse deterministically too (exit 3, same bytes).
            val first: ServiceOutcome = JdxService.dispatch(request, roots)
            val second: ServiceOutcome = JdxService.dispatch(request, roots)
            first.toJson(request.command.wire) shouldBe second.toJson(request.command.wire)
        }
    }
}
