package dev.jdx.index.service

import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.KotlinMethodView
import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.ModuleSymbolRef
import dev.jdx.core.model.Origin
import dev.jdx.core.model.PackageSymbolRef
import dev.jdx.core.model.Provenance
import dev.jdx.core.model.ReferenceEdge
import dev.jdx.core.model.ReferenceKind
import dev.jdx.core.model.SymbolRef
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.TypeSymbolRef
import dev.jdx.core.model.Visibility
import dev.jdx.core.model.kotlinViewKey
import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import dev.jdx.core.model.arrayTypeName
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.ref.SymbolRefParser
import dev.jdx.core.ref.SymbolRefParseResult
import dev.jdx.core.ref.SymbolRefPrinter
import dev.jdx.core.render.BodyBlock
import dev.jdx.core.render.CallDirection
import dev.jdx.core.render.CallListing
import dev.jdx.core.render.CallNode
import dev.jdx.core.render.ClassCard
import dev.jdx.core.render.DEFAULT_BODY_MAX_LINES
import dev.jdx.core.render.DEFAULT_CALLS_DEPTH
import dev.jdx.core.render.DEFAULT_CALLS_LIMIT
import dev.jdx.core.render.DEFAULT_DOC_MAX_LINES
import dev.jdx.core.render.DEFAULT_HIERARCHY_LIMIT
import dev.jdx.core.render.DEFAULT_SIGNATURE_LIMIT
import dev.jdx.core.render.DEFAULT_SOURCE_MAX_LINES
import dev.jdx.core.render.DocBlock
import dev.jdx.core.render.DocSubject
import dev.jdx.core.render.MemberKind
import dev.jdx.core.render.SignatureBlock
import dev.jdx.core.render.SignatureEntry
import dev.jdx.core.render.SourceBlock
import dev.jdx.core.render.buildBodyBlock
import dev.jdx.core.render.buildCallListing
import dev.jdx.core.render.buildDocBlock
import dev.jdx.core.render.buildSignatureBlock
import dev.jdx.core.render.buildSourceBlock
import dev.jdx.core.render.renderJavadoc
import dev.jdx.core.render.DEFAULT_MEMBER_LIMIT
import dev.jdx.core.render.DEFAULT_SAMPLES_LIMIT
import dev.jdx.core.render.DEFAULT_SEARCH_LIMIT
import dev.jdx.core.render.DEFAULT_TREE_DEPTH
import dev.jdx.core.render.DEFAULT_USAGES_LIMIT
import dev.jdx.core.render.ErrorResult
import dev.jdx.core.render.HierarchyListing
import dev.jdx.core.render.LsListing
import dev.jdx.core.render.LsTypeEntry
import dev.jdx.core.render.MemberListing
import dev.jdx.core.render.MemberListingOptions
import dev.jdx.core.render.MemberSort
import dev.jdx.core.render.OBJECT_BINARY_NAME
import dev.jdx.core.render.PackageEntry
import dev.jdx.core.render.SearchHit
import dev.jdx.core.render.SearchListing
import dev.jdx.core.render.methodRefString
import dev.jdx.core.render.SampleHit
import dev.jdx.core.render.SampleListing
import dev.jdx.core.render.SampleSnippet
import dev.jdx.core.render.SignatureLines
import dev.jdx.core.render.SubtypeEntry
import dev.jdx.core.render.SupertypeEntry
import dev.jdx.core.render.TreeListing
import dev.jdx.core.render.UsageHit
import dev.jdx.core.render.UsageListing
import dev.jdx.core.render.buildArtifactTree
import dev.jdx.core.render.buildHierarchyListing
import dev.jdx.core.render.buildLsListing
import dev.jdx.core.render.buildSearchListing
import dev.jdx.core.render.buildSampleListing
import dev.jdx.core.render.buildTreeListing
import dev.jdx.core.render.buildUsageListing
import dev.jdx.core.render.sampleOrderKey
import dev.jdx.core.render.buildClassCard
import dev.jdx.core.render.buildMemberListing
import dev.jdx.core.render.countNodes
import dev.jdx.core.render.searchKindWord
import dev.jdx.core.resolve.MemberResolutionOptions
import dev.jdx.core.resolve.MemberResolver
import dev.jdx.core.resolve.ResolvedMembers
import dev.jdx.core.search.SymbolSearch
import dev.jdx.decompile.DecompileResult
import dev.jdx.decompile.DecompilerEngine
import dev.jdx.decompile.DecompilerId
import dev.jdx.decompile.JavapDecompiler
import dev.jdx.decompile.VineflowerDecompiler
import dev.jdx.decompile.findJavapSection
import dev.jdx.decompile.splitJavapSections
import dev.jdx.index.artifact.ArtifactKind
import dev.jdx.index.artifact.ArtifactLoader
import dev.jdx.index.artifact.ArtifactReadException
import dev.jdx.index.artifact.ArtifactRoot
import dev.jdx.index.asm.AsmClassReader
import dev.jdx.index.asm.ClassReadResult
import dev.jdx.index.maven.MavenResolveFn
import dev.jdx.index.maven.MavenResolver
import dev.jdx.index.maven.productionMavenResolve
import dev.jdx.index.refs.ReferenceExtractor
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * The behaviour behind `jdx show`, `jdx outline` and `jdx members` (T-011).
 *
 * This is the `JdxService` facade PROPOSAL.md §9.2 promises: every CLI/MCP/HTTP
 * adapter calls here, so the four front-ends cannot drift (D-004). There is no
 * persistent index yet (M2) — each query opens its roots, reads the classes it
 * needs with ASM (never loading them, D-017), resolves, renders and closes.
 *
 * Roots are classpath-ordered: `--jars` values in CLI order, then the JDK
 * (`--no-jdk` drops it). The first root providing a class wins; further
 * providers become a `DUPLICATE_FQN` warning, mirroring JVM shadowing.
 */
public object JdxService {

    /** Which roots a query reads: explicit jar/dir/glob specs plus the JDK unless dropped. */
    public data class RootsSpec(
        public val jarSpecs: List<String> = emptyList(),
        public val includeJdk: Boolean = true,
        /** Warnings contributed by root resolution itself (e.g. discovery fallback). */
        public val extraWarnings: List<Warning> = emptyList(),
        /**
         * Whether a `g:a:v/` ref prefix may hit the network (T-019). False by
         * default: without `--fetch` a prefix resolves from the local caches
         * only (D-006).
         */
        public val allowFetch: Boolean = false,
        /**
         * Resolves one `g:a:v` prefix text to jars. Defaults to the production
         * [MavenResolver][dev.jdx.index.maven.MavenResolver]; tests inject a
         * fake over fabricated repositories so no test touches the network.
         */
        public val mavenResolve: MavenResolveFn = ::productionMavenResolve,
        /**
         * Project source dirs (T-031): scanned textually by `usages` for
         * whole-word mentions (`ref` kind); other readers ignore them.
         */
        public val srcSpecs: List<String> = emptyList(),
    ) {
        public companion object {
            /** Converts a resolved workspace selection into the roots a query opens. */
            public fun fromResolved(
                resolved: dev.jdx.index.workspace.WorkspaceResolver.ResolvedRoots,
            ): RootsSpec = RootsSpec(
                jarSpecs = resolved.jarSpecs,
                includeJdk = resolved.includeJdk,
                extraWarnings = resolved.warnings,
                srcSpecs = resolved.srcSpecs,
            )
        }
    }

    /** `--kind` values for the members family (PROPOSAL.md §7.1). */
    public enum class KindFilter(public val flag: String) {
        ALL("all"),
        METHOD("method"),
        FIELD("field"),
        CTOR("ctor"),
        PROPERTY("property"),
    }

    /**
     * `--view` values for the members family (PROPOSAL.md §12.1, T-037):
     * the Kotlin declaration view by default, the raw JVM projection on
     * demand (calling Kotlin from Java, reading a stack trace).
     */
    public enum class MemberView(public val flag: String) {
        KOTLIN("kotlin"),
        JVM("jvm"),
    }

    /**
     * Filters applied to the resolved member set before rendering. `access = null`
     * means the proposal default (`public` + `protected`); `--access all` passes
     * the full set explicitly. `staticOnly = null` means both.
     * [fromRef] is the raw `--from` reference text, resolved against the same
     * workspace as the query itself.
     */
    public data class MemberFilters(
        public val kind: KindFilter = KindFilter.ALL,
        public val access: Set<Visibility>? = null,
        public val staticOnly: Boolean? = null,
        public val fromRef: String? = null,
        public val grep: Regex? = null,
        /**
         * Row order (T-062). Presentation only: filtering hides rows, sorting
         * orders the survivors. Kept here (rather than as a new `members()`
         * parameter) so the thin-adapter [MemberQuery][dev.jdx.cli.commands.MemberQuery]
         * arity stays stable across front-ends (D-004).
         */
        public val sort: MemberSort = MemberSort.KIND,
        /**
         * First javadoc sentence per row (`--with-doc`, T-072): looked up from
         * the declaring type's paired sources (direct, else inherited for
         * methods like `doc`); missing docs read as no suffix, never a failure.
         */
        public val withDoc: Boolean = false,
        /**
         * Kotlin declaration view by default; `JVM` forces the raw JVM
         * projection (`--view jvm`, T-037).
         */
        public val view: MemberView = MemberView.KOTLIN,
    ) {
        public companion object {
            /** The §7.1 default: `public` + `protected` only. */
            public val DEFAULT_ACCESS: Set<Visibility> = setOf(Visibility.PUBLIC, Visibility.PROTECTED)
        }
    }

    /** One query's answer, carrying its own exit code and both renderings (D-007). */
    public sealed interface ServiceOutcome {
        /** The process exit code (D-015). */
        public val exitCode: Int

        /** Human-readable text; [color] is TTY-ness passed in by the adapter (D-028). */
        public fun renderText(color: Boolean = false): String

        /**
         * Minimal human-readable text for `--brief` (T-047). Defaults to the
         * full text; only outcomes with a minimal rendering override it.
         * `--json` is unaffected (text-only flag, D-059 precedent).
         */
        public fun renderBriefText(color: Boolean = false): String = renderText(color)

        /** The shared JSON envelope for [command]. */
        public fun toJson(command: String): String

        /** A member listing (`members`, `outline`) — exit 0. */
        public data class MemberList(public val listing: MemberListing) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = listing.renderText(color)
            override fun renderBriefText(color: Boolean): String = listing.renderBriefText(color)
            override fun toJson(command: String): String = listing.toJson(command)
        }

        /** A class card (`show`) — exit 0. */
        public data class Card(public val card: ClassCard) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = card.renderText(color)
            override fun toJson(command: String): String = card.toJson(command)
        }

        /** A symbol search (`search`, `resolve`) — exit 0. */
        public data class SearchList(public val listing: SearchListing) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = listing.renderText(color)
            override fun toJson(command: String): String = listing.toJson(command)
        }

        /** A package listing (`ls`) — exit 0. */
        public data class LsList(public val listing: LsListing) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = listing.renderText(color)
            override fun toJson(command: String): String = listing.toJson(command)
        }

        /** A package forest (`tree`) — exit 0. */
        public data class TreeList(public val listing: TreeListing) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = listing.renderText(color)
            override fun toJson(command: String): String = listing.toJson(command)
        }

        /** A find-usages listing (`usages`) — exit 0. */
        public data class UsageList(public val listing: UsageListing) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = listing.renderText(color)
            override fun toJson(command: String): String = listing.toJson(command)
        }

        /** A type-hierarchy listing (`hierarchy`, `implementors`) — exit 0. */
        public data class Hierarchy(public val listing: HierarchyListing) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = listing.renderText(color)
            override fun toJson(command: String): String = listing.toJson(command)
        }

        /** A call-hierarchy tree (`callers`, `calls`) — exit 0. */
        public data class CallGraph(public val listing: CallListing) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = listing.renderText(color)
            override fun toJson(command: String): String = listing.toJson(command)
        }

        /** Ranked usage examples (`samples`) — exit 0. */
        public data class SampleList(public val listing: SampleListing) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = listing.renderText(color)
            override fun toJson(command: String): String = listing.toJson(command)
        }

        /** A member body (`body`) — exit 0. */
        public data class Body(val block: BodyBlock) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = block.renderText(color)
            override fun toJson(command: String): String = block.toJson(command)
        }

        /** A source file or slice (`source`) — exit 0. */
        public data class Source(val block: SourceBlock) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = block.renderText(color)
            override fun toJson(command: String): String = block.toJson(command)
        }

        /** Member signature(s) (`signature`) — exit 0. */
        public data class SignatureList(val block: SignatureBlock) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = block.renderText(color)
            override fun toJson(command: String): String = block.toJson(command)
        }

        /** Rendered javadoc (`doc`) — exit 0. */
        public data class Doc(val block: DocBlock) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = block.renderText(color)
            override fun toJson(command: String): String = block.toJson(command)
        }

        /** A machine-legible failure — exit 1..6, never a guess, never a trace. */
        public data class Failure(public val error: ErrorResult) : ServiceOutcome {
            override val exitCode: Int = error.exitCode
            override fun renderText(color: Boolean): String = error.renderText()
            override fun toJson(command: String): String = error.toJson(command)
        }
    }

    /**
     * Answers `show <type>`: the class card. [rawRef] must be a type reference;
     * anything else is a usage error (exit 3).
     */
    public fun show(rawRef: String, roots: RootsSpec): ServiceOutcome =
        query(rawRef, roots, QueryMode.SHOW, MemberFilters(), DEFAULT_MEMBER_LIMIT)

    /**
     * Answers `members <type>`: the inherited member listing honouring [filters].
     * `--inherited` is the default; pass [declaredOnly] for `--declared`.
     */
    public fun members(
        rawRef: String,
        roots: RootsSpec,
        filters: MemberFilters = MemberFilters(),
        declaredOnly: Boolean = false,
        includeSynthetic: Boolean = false,
        maxMembers: Int = DEFAULT_MEMBER_LIMIT,
    ): ServiceOutcome =
        query(rawRef, roots, QueryMode.MEMBERS, filters, maxMembers, declaredOnly, includeSynthetic)

    /**
     * Answers `outline <type>`: the members declared on the type itself —
     * `members --declared` under a different command name.
     */
    public fun outline(
        rawRef: String,
        roots: RootsSpec,
        filters: MemberFilters = MemberFilters(),
        includeSynthetic: Boolean = false,
        maxMembers: Int = DEFAULT_MEMBER_LIMIT,
    ): ServiceOutcome =
        query(rawRef, roots, QueryMode.OUTLINE, filters, maxMembers, declaredOnly = true, includeSynthetic)

    /** Presentation options for `body` (PROPOSAL.md §7.1). */
    public data class BodyOptions(
        /** Surrounding source lines shown each side of the member (`--context N`). */
        public val contextLines: Int = 0,
        /** Prefix each shown line with its 1-based number (`--line-numbers`). */
        public val lineNumbers: Boolean = false,
        /** Maximum shown lines; the rest become a truncation footer (`--max-lines N`). */
        public val maxLines: Int = DEFAULT_BODY_MAX_LINES,
        /** Prepend the resolved bytecode signature header (`--with-signature`, T-024). */
        public val withSignature: Boolean = false,
        /**
         * Member doc beside the slice (`--with-doc`, T-072): rendered
         * plain-text lines from the paired sources (direct, else inherited
         * for methods like `doc`); missing docs read as no block, never a
         * failure. Works over forced engines too — docs come from sources,
         * the body from the engine.
         */
        public val withDoc: Boolean = false,
        /**
         * Forced decompiler (`--engine vineflower|javap`, T-026/T-027): sources
         * are skipped and the class is always reconstructed. `null` is the
         * ladder default — paired sources first, Vineflower when they are absent.
         */
        public val engine: DecompilerId? = null,
        /**
         * The reconstruction engine behind the Vineflower fallback. Defaults to
         * the production Vineflower decompiler; tests inject fakes or temp-dir
         * instances so no test writes to the real cache.
         */
        public val decompiler: DecompilerEngine = VineflowerDecompiler(),
        /**
         * The raw-opcode engine behind `--engine javap` (T-027). Defaults to
         * the production `javap` subprocess wrapper; tests inject fakes or
         * temp-dir instances so no test writes to the real cache.
         */
        public val javapDecompiler: DecompilerEngine = JavapDecompiler(),
        /**
         * Home directory the Kotlin sidecar is probed under (T-039).
         * Defaults to the real user home; tests point it at a temp home
         * with a symlinked compiler set so no test writes to the real
         * `~/.cache`.
         */
        public val kotlinUserHome: Path? = null,
    )

    /**
     * Answers `body <member>`: the member's verbatim source slice (T-022),
     * reconstructed by Vineflower when no paired sources exist (T-026).
     *
     * Structure is bytecode-authoritative (D-009): the declaring type resolves
     * with the same machinery as [show], overload ambiguity is decided from
     * bytecode *before* sources are read or the decompiler runs, and only
     * then is the winning root's paired sources sliced via the T-021 seam —
     * or, without sources, its bytes decompiled through the T-026 seam.
     * `--engine vineflower` forces the reconstructed path.
     */
    public fun body(rawRef: String, roots: RootsSpec, options: BodyOptions = BodyOptions()): ServiceOutcome {
        if (options.contextLines < 0) {
            return failure(3, rawRef, "usage error: --context must be >= 0, got ${options.contextLines}")
        }
        if (options.maxLines < 0) {
            return failure(3, rawRef, "usage error: --max-lines must be >= 0, got ${options.maxLines}")
        }
        val parsed = SymbolRefParser.parse(rawRef)
        if (parsed is SymbolRefParseResult.Failure) {
            return failure(
                3,
                rawRef,
                "usage error: invalid reference '$rawRef': ${parsed.message} at column ${parsed.position}",
            )
        }
        val ref = (parsed as SymbolRefParseResult.Ok).ref
        if (ref is TypeSymbolRef) {
            return failure(
                3,
                rawRef,
                "usage error: body takes a member reference like 'com.example.Foo#bar()', " +
                    "got type '$rawRef' (whole types: jdx source, T-023)",
            )
        }
        if (ref is PackageSymbolRef || ref is ModuleSymbolRef) {
            return failure(3, rawRef, "usage error: body takes a member reference, got '$rawRef'")
        }
        val memberRef = ref as MemberSymbolRef
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk && memberRef.coordinate == null) {
            return failure(
                4,
                rawRef,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        // A `g:a:v/` prefix scopes the query to one artifact (T-019): its jar
        // reads first (shadowing order), and candidates match inside it only —
        // while supertypes still resolve from the full workspace behind it.
        var scopedRoots = roots
        var candidateScope: Set<String>? = null
        val coordinate = memberRef.coordinate
        if (coordinate != null) {
            val coordText = "${coordinate.group}:${coordinate.artifact}:${coordinate.version}"
            val outcome = try {
                roots.mavenResolve(coordText, roots.allowFetch)
            } catch (e: Exception) {
                return failure(
                    6,
                    rawRef,
                    "internal error: coordinate resolution failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            val artifact = when (outcome) {
                is MavenResolver.Outcome.Resolved -> outcome.artifact
                is MavenResolver.Outcome.Unresolved ->
                    return failure(5, rawRef, "artifact read error: ${outcome.message}")
            }
            val binarySpec = artifact.binaryJar.toString()
            val scope = try {
                ArtifactLoader.open(artifact.binaryJar).use { root -> root.classEntryPaths().map(::entryToBinary).toSet() }
            } catch (e: ArtifactReadException) {
                return failure(5, rawRef, e.message ?: "artifact read error")
            } catch (e: Exception) {
                return failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
            }
            scopedRoots = roots.copy(jarSpecs = listOf(binarySpec) + roots.jarSpecs)
            candidateScope = scope
        }
        return try {
            executeBody(memberRef, rawRef, scopedRoots, options, candidateScope)
        } catch (e: ArtifactReadException) {
            failure(5, rawRef, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    /** Presentation options for `source` (PROPOSAL.md §7.1). */
    public data class SourceOptions(
        /**
         * The `--lines A:B` window (1-based inclusive). Mutually exclusive
         * with [aroundRef]; `null` serves the context window around the
         * `--around` member, or the whole file when that is also null.
         */
        public val lines: Pair<Int, Int>? = null,
        /**
         * The raw `--around <member-ref>` text centering the slice. Parsed
         * here (a member of the queried type, short form allowed); `--context`
         * expands its range each side.
         */
        public val aroundRef: String? = null,
        /** Surrounding source lines shown each side of the `--around` member (`--context N`). */
        public val contextLines: Int = 0,
        /** Prefix each shown line with its 1-based number (`--line-numbers`). */
        public val lineNumbers: Boolean = false,
        /** Maximum shown lines; the rest become a truncation footer (`--max-lines N`). */
        public val maxLines: Int = DEFAULT_SOURCE_MAX_LINES,
        /**
         * Forced decompiler (`--engine vineflower|javap`, T-026/T-027): sources
         * are skipped and the class is always reconstructed. `null` is the
         * ladder default — paired sources first, Vineflower when they are absent.
         */
        public val engine: DecompilerId? = null,
        /**
         * The reconstruction engine behind the Vineflower fallback. Defaults to
         * the production Vineflower decompiler; tests inject fakes or temp-dir
         * instances so no test writes to the real cache.
         */
        public val decompiler: DecompilerEngine = VineflowerDecompiler(),
        /**
         * The raw-opcode engine behind `--engine javap` (T-027). Defaults to
         * the production `javap` subprocess wrapper; tests inject fakes or
         * temp-dir instances so no test writes to the real cache.
         */
        public val javapDecompiler: DecompilerEngine = JavapDecompiler(),
        /**
         * Home directory the Kotlin sidecar is probed under (T-039).
         * Defaults to the real user home; tests point it at a temp home
         * with a symlinked compiler set so no test writes to the real
         * `~/.cache`.
         */
        public val kotlinUserHome: Path? = null,
    )

    /**
     * Answers `source <type>`: the type's verbatim source file or slice
     * (T-023), reconstructed by Vineflower when no paired sources exist
     * (T-026).
     *
     * Structure is bytecode-authoritative (D-009): the type resolves with the
     * same machinery as [show] *before* sources are read, and only then is the
     * winning root's paired sources served — or, without sources, its bytes
     * decompiled through the T-026 seam. Whole files and `--lines` windows
     * are served verbatim without parsing; only `--around` parses (via the
     * T-021 seam) to locate the member. `--engine vineflower` forces the
     * reconstructed path.
     */
    public fun source(rawRef: String, roots: RootsSpec, options: SourceOptions = SourceOptions()): ServiceOutcome {
        if (options.contextLines < 0) {
            return failure(3, rawRef, "usage error: --context must be >= 0, got ${options.contextLines}")
        }
        if (options.maxLines < 0) {
            return failure(3, rawRef, "usage error: --max-lines must be >= 0, got ${options.maxLines}")
        }
        val window = options.lines
        if (window != null && (window.first < 1 || window.second < window.first)) {
            return failure(
                3,
                rawRef,
                "usage error: --lines must be A:B with 1 <= A <= B, got '${window.first}:${window.second}'",
            )
        }
        if (window != null && options.aroundRef != null) {
            return failure(3, rawRef, "usage error: --lines and --around are mutually exclusive")
        }
        if (window != null && options.contextLines != 0) {
            return failure(3, rawRef, "usage error: --context needs --around (a --lines window is exact)")
        }
        val around = options.aroundRef
        if (around != null) {
            when (val parsed = SymbolRefParser.parse(around)) {
                is SymbolRefParseResult.Failure ->
                    return failure(
                        3,
                        rawRef,
                        "usage error: invalid --around reference '$around': " +
                            "${parsed.message} at column ${parsed.position}",
                    )
                is SymbolRefParseResult.Ok -> {
                    if (parsed.ref !is MemberSymbolRef) {
                        return failure(3, rawRef, "usage error: --around takes a member reference, got '$around'")
                    }
                }
            }
        }
        val parsed = SymbolRefParser.parse(rawRef)
        if (parsed is SymbolRefParseResult.Failure) {
            return failure(
                3,
                rawRef,
                "usage error: invalid reference '$rawRef': ${parsed.message} at column ${parsed.position}",
            )
        }
        val ref = (parsed as SymbolRefParseResult.Ok).ref
        if (ref is MemberSymbolRef) {
            return failure(
                3,
                rawRef,
                "usage error: source takes a type reference like 'com.example.Foo', " +
                    "got member '$rawRef' (member bodies: jdx body; center on one: --around '$rawRef')",
            )
        }
        if (ref is PackageSymbolRef || ref is ModuleSymbolRef) {
            return failure(3, rawRef, "usage error: source takes a type, got '$rawRef'")
        }
        val typeRef = ref as TypeSymbolRef
        val typeName = typeRef.type as? TypeName.ClassType
            ?: return failure(3, rawRef, "usage error: source takes a class, got '$rawRef'")
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk && typeRef.coordinate == null) {
            return failure(
                4,
                rawRef,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        // A `g:a:v/` prefix scopes the query to one artifact (T-019): its jar
        // reads first (shadowing order), and candidates match inside it only —
        // while supertypes still resolve from the full workspace behind it.
        var scopedRoots = roots
        var candidateScope: Set<String>? = null
        val coordinate = typeRef.coordinate
        if (coordinate != null) {
            val coordText = "${coordinate.group}:${coordinate.artifact}:${coordinate.version}"
            val outcome = try {
                roots.mavenResolve(coordText, roots.allowFetch)
            } catch (e: Exception) {
                return failure(
                    6,
                    rawRef,
                    "internal error: coordinate resolution failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            val artifact = when (outcome) {
                is MavenResolver.Outcome.Resolved -> outcome.artifact
                is MavenResolver.Outcome.Unresolved ->
                    return failure(5, rawRef, "artifact read error: ${outcome.message}")
            }
            val binarySpec = artifact.binaryJar.toString()
            val scope = try {
                ArtifactLoader.open(artifact.binaryJar).use { root -> root.classEntryPaths().map(::entryToBinary).toSet() }
            } catch (e: ArtifactReadException) {
                return failure(5, rawRef, e.message ?: "artifact read error")
            } catch (e: Exception) {
                return failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
            }
            scopedRoots = roots.copy(jarSpecs = listOf(binarySpec) + roots.jarSpecs)
            candidateScope = scope
        }
        return try {
            executeSource(typeName, rawRef, scopedRoots, options, candidateScope)
        } catch (e: ArtifactReadException) {
            failure(5, rawRef, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    // -- query pipeline ---------------------------------------------------------

    /** Presentation options for `signature` (PROPOSAL.md §7.1). */
    public data class SignatureOptions(
        /** Show bridge/synthetic members (`--include-synthetic`); hidden by default. */
        public val includeSynthetic: Boolean = false,
        /** Maximum shown signature rows; the rest become a truncation footer (`--limit N`). */
        public val maxSignatures: Int = DEFAULT_SIGNATURE_LIMIT,
        /**
         * Kotlin declaration view by default; `JVM` forces the raw JVM
         * projection (`--view jvm`, T-037).
         */
        public val view: MemberView = MemberView.KOTLIN,
    )

    /**
     * Answers `signature <member>`: one signature line per matching overload (T-024).
     *
     * Structure is bytecode-authoritative (D-009): the declaring type resolves
     * with the same machinery as [show], members match by name/arity/
     * simple-name narrowing, and each renders through the T-010
     * [SignatureLines] with real parameter names, generics, throws and
     * defaults. No sources are read, so sources-less jars answer by design.
     * An under-specified name lists every overload (exit 0) — a signature can
     * show many, unlike a body.
     */
    public fun signature(
        rawRef: String,
        roots: RootsSpec,
        options: SignatureOptions = SignatureOptions(),
    ): ServiceOutcome {
        if (options.maxSignatures < 0) {
            return failure(3, rawRef, "usage error: --limit must be >= 0, got ${options.maxSignatures}")
        }
        val parsed = SymbolRefParser.parse(rawRef)
        if (parsed is SymbolRefParseResult.Failure) {
            return failure(
                3,
                rawRef,
                "usage error: invalid reference '$rawRef': ${parsed.message} at column ${parsed.position}",
            )
        }
        val ref = (parsed as SymbolRefParseResult.Ok).ref
        if (ref is TypeSymbolRef) {
            return failure(
                3,
                rawRef,
                "usage error: signature takes a member reference like 'com.example.Foo#bar()', " +
                    "got type '$rawRef' (whole types: jdx show, member lists: jdx members)",
            )
        }
        if (ref is PackageSymbolRef || ref is ModuleSymbolRef) {
            return failure(3, rawRef, "usage error: signature takes a member reference, got '$rawRef'")
        }
        val memberRef = ref as MemberSymbolRef
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk && memberRef.coordinate == null) {
            return failure(
                4,
                rawRef,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        // A `g:a:v/` prefix scopes the query to one artifact (T-019): its jar
        // reads first (shadowing order), and candidates match inside it only —
        // while supertypes still resolve from the full workspace behind it.
        var scopedRoots = roots
        var candidateScope: Set<String>? = null
        val coordinate = memberRef.coordinate
        if (coordinate != null) {
            val coordText = "${coordinate.group}:${coordinate.artifact}:${coordinate.version}"
            val outcome = try {
                roots.mavenResolve(coordText, roots.allowFetch)
            } catch (e: Exception) {
                return failure(
                    6,
                    rawRef,
                    "internal error: coordinate resolution failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            val artifact = when (outcome) {
                is MavenResolver.Outcome.Resolved -> outcome.artifact
                is MavenResolver.Outcome.Unresolved ->
                    return failure(5, rawRef, "artifact read error: ${outcome.message}")
            }
            val binarySpec = artifact.binaryJar.toString()
            val scope = try {
                ArtifactLoader.open(artifact.binaryJar).use { root -> root.classEntryPaths().map(::entryToBinary).toSet() }
            } catch (e: ArtifactReadException) {
                return failure(5, rawRef, e.message ?: "artifact read error")
            } catch (e: Exception) {
                return failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
            }
            scopedRoots = roots.copy(jarSpecs = listOf(binarySpec) + roots.jarSpecs)
            candidateScope = scope
        }
        return try {
            executeSignature(memberRef, rawRef, scopedRoots, options, candidateScope)
        } catch (e: ArtifactReadException) {
            failure(5, rawRef, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    /** Presentation options for `doc` (PROPOSAL.md §7.1). */
    public data class DocOptions(
        /**
         * Walk supertypes for the nearest documenting method declaration
         * (`--no-inherited` disables; methods only, D-037).
         */
        public val inherit: Boolean = true,
        /** Serve the verbatim comment instead of rendered plain text (`--raw`). */
        public val raw: Boolean = false,
        /** Maximum shown doc lines; the rest become a truncation footer (`--max-lines N`). */
        public val maxLines: Int = DEFAULT_DOC_MAX_LINES,
        /**
         * Home directory the Kotlin sidecar is probed under (T-039).
         * Defaults to the real user home; tests point it at a temp home
         * with a symlinked compiler set so no test writes to the real
         * `~/.cache`.
         */
        public val kotlinUserHome: Path? = null,
    )

    /**
     * Answers `doc <symbol>`: one type's or member's rendered javadoc (T-025).
     *
     * Structure is bytecode-authoritative (D-009): the declaring type resolves
     * with the same machinery as [show], overload ambiguity is decided from
     * bytecode *before* sources are read, and only then is the winning root's
     * paired sources rendered via the T-025 seam. Undocumented methods fall
     * back to the nearest documenting supertype (IntelliJ quick-doc
     * semantics), labelled as such. No decompilation yet (T-026/T-027):
     * a symbol without paired sources is exit 1 naming that task.
     */
    public fun doc(
        rawRef: String,
        roots: RootsSpec,
        options: DocOptions = DocOptions(),
    ): ServiceOutcome {
        if (options.maxLines < 0) {
            return failure(3, rawRef, "usage error: --max-lines must be >= 0, got ${options.maxLines}")
        }
        val parsed = SymbolRefParser.parse(rawRef)
        if (parsed is SymbolRefParseResult.Failure) {
            return failure(
                3,
                rawRef,
                "usage error: invalid reference '$rawRef': ${parsed.message} at column ${parsed.position}",
            )
        }
        val ref = (parsed as SymbolRefParseResult.Ok).ref
        if (ref is PackageSymbolRef || ref is ModuleSymbolRef) {
            return failure(3, rawRef, "usage error: doc takes a type or member reference, got '$rawRef'")
        }
        val coordinate = (ref as? MemberSymbolRef)?.coordinate ?: (ref as? TypeSymbolRef)?.coordinate
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk && coordinate == null) {
            return failure(
                4,
                rawRef,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        // A `g:a:v/` prefix scopes the query to one artifact (T-019): its jar
        // reads first (shadowing order), and candidates match inside it only —
        // while supertypes still resolve from the full workspace behind it.
        var scopedRoots = roots
        var candidateScope: Set<String>? = null
        if (coordinate != null) {
            val coordText = "${coordinate.group}:${coordinate.artifact}:${coordinate.version}"
            val outcome = try {
                roots.mavenResolve(coordText, roots.allowFetch)
            } catch (e: Exception) {
                return failure(
                    6,
                    rawRef,
                    "internal error: coordinate resolution failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            val artifact = when (outcome) {
                is MavenResolver.Outcome.Resolved -> outcome.artifact
                is MavenResolver.Outcome.Unresolved ->
                    return failure(5, rawRef, "artifact read error: ${outcome.message}")
            }
            val binarySpec = artifact.binaryJar.toString()
            val scope = try {
                ArtifactLoader.open(artifact.binaryJar).use { root -> root.classEntryPaths().map(::entryToBinary).toSet() }
            } catch (e: ArtifactReadException) {
                return failure(5, rawRef, e.message ?: "artifact read error")
            } catch (e: Exception) {
                return failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
            }
            scopedRoots = roots.copy(jarSpecs = listOf(binarySpec) + roots.jarSpecs)
            candidateScope = scope
        }
        return try {
            executeDoc(ref, rawRef, scopedRoots, options, candidateScope)
        } catch (e: ArtifactReadException) {
            failure(5, rawRef, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    private enum class QueryMode(val flag: String) {
        SHOW("show"),
        MEMBERS("members"),
        OUTLINE("outline"),
    }

    private fun query(
        rawRef: String,
        roots: RootsSpec,
        mode: QueryMode,
        filters: MemberFilters,
        maxMembers: Int,
        declaredOnly: Boolean = false,
        includeSynthetic: Boolean = false,
    ): ServiceOutcome {
        if (maxMembers < 0) {
            return failure(3, rawRef, "usage error: --limit must be >= 0, got $maxMembers")
        }
        val parsed = SymbolRefParser.parse(rawRef)
        if (parsed is SymbolRefParseResult.Failure) {
            return failure(
                3,
                rawRef,
                "usage error: invalid reference '$rawRef': ${parsed.message} at column ${parsed.position}",
            )
        }
        val ref = (parsed as SymbolRefParseResult.Ok).ref
        if (ref is MemberSymbolRef) {
            return failure(
                3,
                rawRef,
                "usage error: ${mode.flag} takes a type, got member reference '$rawRef'",
            )
        }
        if (ref is PackageSymbolRef || ref is ModuleSymbolRef) {
            return failure(3, rawRef, "usage error: ${mode.flag} takes a type, got '$rawRef'")
        }
        val typeRef = ref as TypeSymbolRef
        val typeName = typeRef.type as? TypeName.ClassType
            ?: return failure(3, rawRef, "usage error: ${mode.flag} takes a class, got '$rawRef'")
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk && typeRef.coordinate == null) {
            return failure(
                4,
                rawRef,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        // A `g:a:v/` prefix scopes the query to one artifact (T-019): its jar
        // reads first (shadowing order), and candidates match inside it only —
        // while supertypes still resolve from the full workspace behind it.
        var scopedRoots = roots
        var candidateScope: Set<String>? = null
        val coordinate = typeRef.coordinate
        if (coordinate != null) {
            val coordText = "${coordinate.group}:${coordinate.artifact}:${coordinate.version}"
            val outcome = try {
                roots.mavenResolve(coordText, roots.allowFetch)
            } catch (e: Exception) {
                return failure(
                    6,
                    rawRef,
                    "internal error: coordinate resolution failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            val artifact = when (outcome) {
                is MavenResolver.Outcome.Resolved -> outcome.artifact
                is MavenResolver.Outcome.Unresolved ->
                    return failure(5, rawRef, "artifact read error: ${outcome.message}")
            }
            val binarySpec = artifact.binaryJar.toString()
            val scope = try {
                ArtifactLoader.open(artifact.binaryJar).use { root -> root.classEntryPaths().map(::entryToBinary).toSet() }
            } catch (e: ArtifactReadException) {
                return failure(5, rawRef, e.message ?: "artifact read error")
            } catch (e: Exception) {
                return failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
            }
            scopedRoots = roots.copy(jarSpecs = listOf(binarySpec) + roots.jarSpecs)
            candidateScope = scope
        }
        return try {
            execute(typeName, rawRef, scopedRoots, mode, filters, maxMembers, declaredOnly, includeSynthetic, candidateScope)
        } catch (e: ArtifactReadException) {
            failure(5, rawRef, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    private fun failure(code: Int, query: String, message: String): ServiceOutcome.Failure =
        ServiceOutcome.Failure(ErrorResult.generic(query, message = message, exitCode = code))

    /** An opened root plus its JDK-module lookup (non-null for `jrt:/` only). */
    private data class OpenRoot(
        val root: ArtifactRoot,
        val jrtModuleFor: ((String) -> String?)? = null,
    )

    private fun execute(
        typeName: TypeName.ClassType,
        rawRef: String,
        roots: RootsSpec,
        mode: QueryMode,
        filters: MemberFilters,
        maxMembers: Int,
        declaredOnly: Boolean,
        includeSynthetic: Boolean,
        candidateScope: Set<String>? = null,
    ): ServiceOutcome {
        val opened = openRoots(roots)
        try {
            // Cheap entry index first (names only, no parsing): powers exact
            // lookup, short-name search, duplicates and did-you-mean alike.
            val binariesByRoot = opened.map { it.root.classEntryPaths().map(::entryToBinary).toSet() }
            val providers = mutableMapOf<String, MutableList<Int>>()
            binariesByRoot.forEachIndexed { index, binaries ->
                for (binary in binaries) providers.getOrPut(binary) { mutableListOf() }.add(index)
            }
            val allBinaries = providers.keys

            // A coordinate prefix restricts candidates to the scoped artifact;
            // everything else (supertype reads, did-you-mean breadth) stays full.
            val candidates = matchCandidates(typeName, candidateScope ?: allBinaries)
            if (candidates.isEmpty()) {
                val suggestions = suggestSimilar(typeName.simpleName, allBinaries)
                return ServiceOutcome.Failure(ErrorResult.notFound(rawRef, suggestions))
            }
            if (candidates.size > 1) {
                return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, candidates))
            }
            val binary = candidates.single()
            val winner = providers.getValue(binary).first()

            val warnings = mutableListOf<Warning>()
            // Resolution-time warnings (e.g. project-discovery fallback) lead, so the
            // agent sees why the root set looks the way it does before artifact notes.
            warnings.addAll(roots.extraWarnings)
            for (open in opened) warnings.addAll(open.root.warnings)
            val extraProviders = providers.getValue(binary).drop(1)
            if (extraProviders.isNotEmpty()) {
                val names = listOf(winner).plus(extraProviders).map { rootLabel(opened[it], binary) }
                warnings.add(
                    Warning(
                        code = WarningCode.DUPLICATE_FQN,
                        message = "$binary is provided by ${names.joinToString(", ")}; " +
                            "showing ${names.first()} (classpath order)",
                        subject = binary,
                    ),
                )
            }

            val workspace = Workspace(opened, providers, warnings)
            val target = workspace.load(binary)
            if (target == null) {
                // The entry exists but its bytes cannot serve the query.
                return failure(
                    5,
                    rawRef,
                    "artifact read error: $binary in ${rootLabel(opened[winner], binary)} cannot be parsed",
                )
            }

            // `--from` names a second type; resolve it with the same machinery so
            // a mistyped supertype reports itself instead of matching nothing.
            val fromBinary = filters.fromRef?.let { from ->
                val parsedFrom = SymbolRefParser.parse(from)
                val fromType = (parsedFrom as? SymbolRefParseResult.Ok)?.ref as? TypeSymbolRef
                val fromClass = fromType?.type as? TypeName.ClassType
                if (fromClass == null) {
                    return failure(3, from, "usage error: invalid --from type '$from'")
                }
                val fromCandidates = matchCandidates(fromClass, allBinaries)
                if (fromCandidates.isEmpty()) {
                    return ServiceOutcome.Failure(
                        ErrorResult.notFound(from, suggestSimilar(fromClass.simpleName, allBinaries)),
                    )
                }
                if (fromCandidates.size > 1) {
                    return ServiceOutcome.Failure(ErrorResult.ambiguous(from, fromCandidates))
                }
                fromCandidates.single()
            }

            val provenance = listOf(
                Provenance(
                    artifact = rootLabel(opened[winner], binary),
                    origin = if (opened[winner].root.kind == ArtifactKind.JRT) Origin.JRT else Origin.BYTECODE,
                ),
            )
            val sortedWarnings = warnings.sortedBy { it.code }
            if (mode == QueryMode.SHOW) {
                return ServiceOutcome.Card(buildClassCard(target, provenance, sortedWarnings))
            }
            val resolved = MemberResolver.resolve(
                target,
                workspace::loadByName,
                MemberResolutionOptions(
                    includeSynthetic = includeSynthetic,
                    // `--view jvm` (T-037): the JVM projection ignores every Kotlin view.
                    jvmView = filters.view == MemberView.JVM,
                ),
            )
            val filtered = applyFilters(resolved, filters.copy(fromRef = null), fromBinary)
            val listing = buildMemberListing(
                target = target,
                resolved = filtered,
                provenance = provenance,
                options = MemberListingOptions(
                    declaredOnly = declaredOnly,
                    // An explicit `--from java.lang.Object` is the documented escape
                    // hatch out of the collapse (PROPOSAL.md §7.1) — collapsing
                    // after it would hide exactly what was asked for.
                    collapseObjectMembers = fromBinary != OBJECT_BINARY_NAME,
                    maxMembers = maxMembers,
                    sort = filters.sort,
                ),
            )
            // Resolver warnings (UNRESOLVED_SUPERTYPE) join the artifact ones.
            val merged = listing.copy(warnings = (sortedWarnings + listing.warnings).sortedBy { it.code })
            val enriched = if (filters.withDoc) {
                enrichListingWithDocs(merged, opened, providers, workspace)
            } else {
                merged
            }
            return ServiceOutcome.MemberList(enriched)
        } finally {
            opened.forEach { it.root.close() }
        }
    }

    private fun rootLabel(open: OpenRoot, binary: String): String {
        // T-012 reports the JDK module as the artifact (e.g. `java.base`).
        val module = open.jrtModuleFor?.invoke(entryForBinary(binary))
        if (module != null) return module
        return open.root.displayName
    }

    // -- workspace: lazy per-class loading over open roots ----------------------

    /**
     * Reads classes on demand (one ASM parse per class, cached): a `members`
     * query touches the target plus its supertypes, never the whole artifact —
     * which is what keeps `jdx members java.util.HashMap` cheap with no index.
     * Parse failures read as missing (resolver reports the hole) and are warned
     * once each; the *target* failing is the caller's artifact error instead.
     */
    private class Workspace(
        private val opened: List<OpenRoot>,
        private val providers: Map<String, List<Int>>,
        private val warnings: MutableList<Warning>,
    ) {
        private val cache = mutableMapOf<String, ClassInfo?>()
        private val reported = mutableSetOf<String>()

        fun load(binary: String): ClassInfo? {
            val winner = providers[binary]?.firstOrNull() ?: return null
            return cache.getOrPut(binary) { readFrom(opened[winner], binary) }
        }

        fun loadByName(name: TypeName): ClassInfo? {
            val binary = (name as? TypeName.ClassType)?.binaryName ?: return null
            return load(binary)
        }

        private fun readFrom(open: OpenRoot, binary: String): ClassInfo? {
            val bytes = try {
                open.root.openClass(entryForBinary(binary)).use { it.readBytes() }
            } catch (e: ArtifactReadException) {
                if (reported.add(binary)) {
                    warnings.add(
                        Warning(
                            WarningCode.CORRUPT_CLASS,
                            "cannot read $binary from ${open.root.displayName}: ${e.message}",
                            binary,
                        ),
                    )
                }
                return null
            }
            val hint = "$binary in ${open.root.displayName}"
            return when (val read = AsmClassReader.read(bytes, hint)) {
                is ClassReadResult.Ok -> read.info
                is ClassReadResult.UnsupportedVersion -> {
                    if (reported.add(binary)) warnings.add(read.warning.copy(subject = binary))
                    null
                }
                is ClassReadResult.Corrupt -> {
                    if (reported.add(binary)) warnings.add(read.warning.copy(subject = binary))
                    null
                }
            }
        }
    }

    // -- roots ------------------------------------------------------------------

    private fun openRoots(spec: RootsSpec): List<OpenRoot> {
        val opened = mutableListOf<OpenRoot>()
        try {
            // Dedupe identical files before opening (T-068): explicit `--jars`
            // merged in front of a workspace holding the same jar (e.g. once as a
            // direct path, once via a workspace glob) would otherwise open one
            // file as two roots — doubling every `search` hit and suffixing
            // `tree` artifacts with `(2)`. The key is the normalised absolute
            // path, so the same file *name* in different directories (shading)
            // keeps per-provider rows. First occurrence wins, preserving the
            // explicit-first shadowing order.
            val seen = mutableSetOf<String>()
            for (jarSpec in spec.jarSpecs) {
                for (path in expandJarSpec(jarSpec)) {
                    if (!seen.add(path.toAbsolutePath().normalize().toString())) continue
                    opened.add(OpenRoot(ArtifactLoader.open(path)))
                }
            }
            if (spec.includeJdk) {
                val jrt = ArtifactLoader.openJdk()
                opened.add(OpenRoot(jrt, jrtModuleFor = { entry -> jrt.moduleForClass(entry) }))
            }
            return opened
        } catch (e: Exception) {
            opened.forEach { runCatching { it.root.close() } }
            throw e
        }
    }

    /**
     * Expands one `--jars` value: a plain jar/dir path, or a glob (`*`, `?`,
     * `[`, `{`). A leading `~` expands to the home directory. An empty glob
     * match is an artifact error, not silence — the agent asked for something
     * that names nothing.
     */
    internal fun expandJarSpec(spec: String): List<Path> {
        val expanded = if (spec.startsWith("~/") || spec == "~") {
            System.getProperty("user.home") + spec.substring(1)
        } else {
            spec
        }
        if (!expanded.any { it == '*' || it == '?' || it == '[' || it == '{' }) {
            val path = Path.of(expanded)
            if (!path.isRegularFile() && !path.isDirectory()) {
                throw ArtifactReadException("artifact read error: no such artifact: $spec")
            }
            return listOf(path)
        }
        val absolute = Path.of(expanded).toAbsolutePath().normalize().toString()
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$absolute")
        val firstGlob = absolute.indexOfFirst { it == '*' || it == '?' || it == '[' || it == '{' }
        val slash = absolute.lastIndexOf('/', firstGlob)
        val walkRoot = if (slash <= 0) Path.of("/") else Path.of(absolute.substring(0, slash))
        if (!Files.isDirectory(walkRoot)) {
            throw ArtifactReadException("artifact read error: no artifacts match: $spec")
        }
        val hits = mutableListOf<Path>()
        Files.walk(walkRoot).use { walk ->
            walk.filter { candidate ->
                matcher.matches(candidate) &&
                    (candidate.isDirectory() || isJarFile(candidate))
            }.forEach { hits.add(it) }
        }
        if (hits.isEmpty()) throw ArtifactReadException("artifact read error: no artifacts match: $spec")
        return hits.sorted()
    }

    private fun isJarFile(path: Path): Boolean {
        if (!path.isRegularFile()) return false
        val name = path.fileName.toString()
        return name.endsWith(".jar") || name.endsWith(".zip")
    }

    // -- name matching (pure, unit-tested) --------------------------------------

    /**
     * Resolves a parsed type name against the workspace's binary names.
     * A packaged name is exact; a short name matches every class with the same
     * simple (or nested `$`-joined) name. Sorted, so ambiguity lists are stable.
     */
    internal fun matchCandidates(ref: TypeName.ClassType, binaries: Set<String>): List<String> {
        if (ref.packageName.isNotEmpty()) {
            return if (ref.binaryName in binaries) listOf(ref.binaryName) else emptyList()
        }
        val nested = ref.nestedNames.joinToString("$")
        return binaries.filter { binary ->
            binary.substringAfterLast('.') == nested
        }.sorted()
    }

    /**
     * Did-you-mean suggestions for a missed type: same simple name in another
     * package first, then Levenshtein-near simple names — capped, sorted.
     */
    internal fun suggestSimilar(missedSimple: String, binaries: Set<String>, cap: Int = 5): List<String> {
        val bySimple = binaries.groupBy { it.substringAfterLast('.').substringAfterLast('$') }
        val sameName = (bySimple[missedSimple] ?: emptyList()).sorted()
        if (sameName.size >= cap) return sameName.take(cap)
        val near = bySimple.keys
            .filter { it != missedSimple && levenshtein(it, missedSimple) <= 2 }
            .sorted()
            .flatMap { bySimple.getValue(it).sorted() }
        return (sameName + near).take(cap)
    }

    /**
     * Edit distance, behind did-you-mean (PROPOSAL.md §16). Delegates to
     * [SymbolSearch] so the CLI and the search fallback share one implementation.
     */
    internal fun levenshtein(a: String, b: String): Int = SymbolSearch.levenshtein(a, b)

    // -- member filtering (pure, unit-tested) -----------------------------------

    /**
     * Applies the §7.1 `members` filters to a resolved set: kind, access level
     * (default public+protected), static/instance, `--from` declaring type and
     * `--grep` name pattern. Linearisation and missing supertypes pass through —
     * filtering hides rows, never hierarchy edges. The caller resolves
     * `--from` text to [fromBinary] first; [MemberFilters.fromRef] is ignored
     * here (it is the CLI's unresolved input form).
     */
    internal fun applyFilters(
        resolved: ResolvedMembers,
        filters: MemberFilters,
        fromBinary: String? = null,
    ): ResolvedMembers {
        val access = filters.access ?: MemberFilters.DEFAULT_ACCESS
        val methods = resolved.methods.filter { method ->
            if (filters.kind == KindFilter.FIELD || filters.kind == KindFilter.PROPERTY) return@filter false
            val isCtor = method.member.name == "<init>"
            if (filters.kind == KindFilter.CTOR && !isCtor) return@filter false
            if (filters.kind == KindFilter.METHOD && isCtor) return@filter false
            if (method.member.access.visibility !in access) return@filter false
            if (filters.staticOnly != null &&
                method.member.access.has(AccessFlag.STATIC) != filters.staticOnly
            ) {
                return@filter false
            }
            if (fromBinary != null && method.declaringType.binaryName != fromBinary) return@filter false
            if (filters.grep != null && !filters.grep.containsMatchIn(method.member.name)) return@filter false
            true
        }
        val fields = resolved.fields.filter { field ->
            if (filters.kind == KindFilter.METHOD || filters.kind == KindFilter.CTOR ||
                filters.kind == KindFilter.PROPERTY
            ) {
                return@filter false
            }
            if (field.member.access.visibility !in access) return@filter false
            if (filters.staticOnly != null &&
                field.member.access.has(AccessFlag.STATIC) != filters.staticOnly
            ) {
                return@filter false
            }
            if (fromBinary != null && field.declaringType.binaryName != fromBinary) return@filter false
            if (filters.grep != null && !filters.grep.containsMatchIn(field.member.name)) return@filter false
            true
        }
        val properties = resolved.properties.filter { property ->
            if (filters.kind == KindFilter.METHOD || filters.kind == KindFilter.CTOR ||
                filters.kind == KindFilter.FIELD
            ) {
                return@filter false
            }
            if (property.property.access.visibility !in access) return@filter false
            if (filters.staticOnly != null &&
                property.property.access.has(AccessFlag.STATIC) != filters.staticOnly
            ) {
                return@filter false
            }
            if (fromBinary != null && property.declaringType.binaryName != fromBinary) return@filter false
            if (filters.grep != null && !filters.grep.containsMatchIn(property.property.propertyName)) {
                return@filter false
            }
            true
        }
        return resolved.copy(methods = methods, fields = fields, properties = properties)
    }

    // -- search / resolve / ls / tree (T-017) ------------------------------------

    /** `--kind` values for `search` (PROPOSAL.md §7.2), plus `type` and `all`. */
    public enum class SearchKindFilter(public val flag: String) {
        ALL("all"),
        TYPE("type"),
        CLASS("class"),
        INTERFACE("interface"),
        ENUM("enum"),
        RECORD("record"),
        ANNOTATION("annotation"),
        OBJECT("object"),
        COMPANION("companion"),
        METHOD("method"),
        FIELD("field"),
        PACKAGE("package"),
        MODULE("module"),
    }

    /**
     * Options for `search`: which symbols match and how much to show. `inArtifact`
     * is the `--in` glob over artifact labels (jar file names, class-dir names,
     * JDK module names); `inPackage` is `--package` over dotted package names.
     * Both accept globs, else match as case-insensitive substrings (D-031).
     */
    public data class SearchOptions(
        public val kind: SearchKindFilter = SearchKindFilter.ALL,
        public val regex: Boolean = false,
        public val fuzzy: Boolean = false,
        public val inArtifact: String? = null,
        public val inPackage: String? = null,
        public val limit: Int = DEFAULT_SEARCH_LIMIT,
    )

    /**
     * Answers `search <pattern>`: symbol search across the workspace's roots.
     * Name matching is [SymbolSearch] (glob, regex, camel-hump, fuzzy fallback);
     * orchestration here only enumerates entry names and parses the classes the
     * pattern actually touches — a live-roots search, no persistent index yet
     * (index-backed search will land with the M4 graph work).
     */
    public fun search(rawPattern: String, roots: RootsSpec, options: SearchOptions = SearchOptions()): ServiceOutcome {
        if (options.limit < 0) {
            return failure(3, rawPattern, "usage error: --limit must be >= 0, got ${options.limit}")
        }
        if (options.regex && !SymbolSearch.isValidRegex(rawPattern)) {
            return failure(3, rawPattern, "usage error: invalid --regex pattern '$rawPattern'")
        }
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk) {
            return failure(
                4,
                rawPattern,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        return try {
            executeSearch(rawPattern, roots, options)
        } catch (e: ArtifactReadException) {
            failure(5, rawPattern, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, rawPattern, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    /**
     * Answers `resolve <name>`: exact-match disambiguation for an unqualified or
     * partial name. Every candidate lists kind and artifact; several candidates
     * are success (exit 0) here — that is the point of the command — while none
     * is exit 1 with did-you-mean suggestions.
     */
    public fun resolve(rawName: String, roots: RootsSpec, limit: Int = DEFAULT_SEARCH_LIMIT): ServiceOutcome {
        if (limit < 0) {
            return failure(3, rawName, "usage error: --limit must be >= 0, got $limit")
        }
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk) {
            return failure(
                4,
                rawName,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        return try {
            executeResolve(rawName, roots, limit)
        } catch (e: ArtifactReadException) {
            failure(5, rawName, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, rawName, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    /**
     * Answers `ls [package-glob]`: packages matching the glob with type counts —
     * or, when the glob names one exact package, the types inside it.
     */
    public fun ls(packageGlob: String?, roots: RootsSpec, limit: Int = DEFAULT_SEARCH_LIMIT): ServiceOutcome {
        val query = packageGlob ?: "*"
        if (limit < 0) {
            return failure(3, query, "usage error: --limit must be >= 0, got $limit")
        }
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk) {
            return failure(
                4,
                query,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        return try {
            executeLs(query, packageGlob, roots, limit)
        } catch (e: ArtifactReadException) {
            failure(5, query, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, query, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    /**
     * Answers `tree [artifact-glob]`: the package forest of each matching
     * artifact, nested to [depth] (0 shows top-level segments only).
     */
    public fun tree(
        artifactGlob: String?,
        roots: RootsSpec,
        depth: Int = DEFAULT_TREE_DEPTH,
        withCounts: Boolean = false,
        limit: Int = DEFAULT_SEARCH_LIMIT,
    ): ServiceOutcome {
        val query = artifactGlob ?: "*"
        if (limit < 0) {
            return failure(3, query, "usage error: --limit must be >= 0, got $limit")
        }
        if (depth < 0) {
            return failure(3, query, "usage error: --depth must be >= 0, got $depth")
        }
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk) {
            return failure(
                4,
                query,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        return try {
            executeTree(query, roots, depth, withCounts, limit)
        } catch (e: ArtifactReadException) {
            failure(5, query, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, query, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    // -- search execution -------------------------------------------------------

    private data class SearchEntry(
        val binary: String,
        val packageName: String,
        val artifact: String,
        val rootIndex: Int,
    )

    private class SearchWorkspace(
        val entries: List<SearchEntry>,
        val workspace: Workspace,
        val warnings: MutableList<Warning>,
        val opened: List<OpenRoot>,
    )

    private fun openSearchWorkspace(roots: RootsSpec): SearchWorkspace {
        val opened = openRoots(roots)
        try {
            val warnings = mutableListOf<Warning>()
            warnings.addAll(roots.extraWarnings)
            for (open in opened) warnings.addAll(open.root.warnings)
            // Per-provider rows: shadowing duplicates list one hit per artifact
            // (the honest answer for search — D-031), so nothing is keyed away.
            // `rootIndex` disambiguates same-named artifacts in `tree` (D-031).
            val entries = opened.flatMapIndexed { rootIndex, open ->
                open.root.classEntryPaths().map { path ->
                    val binary = entryToBinary(path)
                    SearchEntry(
                        binary = binary,
                        packageName = binary.substringBeforeLast('.', ""),
                        artifact = rootLabel(open, binary),
                        rootIndex = rootIndex,
                    )
                }
            }
            val providers = mutableMapOf<String, MutableList<Int>>()
            opened.forEachIndexed { index, open ->
                for (binary in open.root.classEntryPaths().map(::entryToBinary)) {
                    providers.getOrPut(binary) { mutableListOf() }.add(index)
                }
            }
            return SearchWorkspace(entries, Workspace(opened, providers, warnings), warnings, opened)
        } catch (e: Exception) {
            opened.forEach { runCatching { it.root.close() } }
            throw e
        }
    }

    private fun SearchWorkspace.close(): Unit = opened.forEach { it.root.close() }

    private fun executeSearch(rawPattern: String, roots: RootsSpec, options: SearchOptions): ServiceOutcome {
        val search = openSearchWorkspace(roots)
        try {
            val scope = search.entries.filter { entry ->
                matchesArtifactFilter(options.inArtifact, entry.artifact) &&
                    matchesPackageFilter(options.inPackage, entry.packageName)
            }
            val hits = mutableListOf<SearchHit>()
            val wantTypes = options.kind == SearchKindFilter.ALL ||
                options.kind == SearchKindFilter.TYPE || isTypeKind(options.kind)
            val wantMembers = options.kind == SearchKindFilter.METHOD || options.kind == SearchKindFilter.FIELD
            val wantPackages = options.kind == SearchKindFilter.ALL || options.kind == SearchKindFilter.PACKAGE
            val wantModules = options.kind == SearchKindFilter.ALL || options.kind == SearchKindFilter.MODULE
            if (wantTypes) hits.addAll(matchTypes(search, scope, rawPattern, options))
            if (wantMembers) hits.addAll(matchMembers(search, scope, rawPattern, options))
            if (wantPackages) hits.addAll(matchPackages(scope, rawPattern, options))
            if (wantModules) hits.addAll(matchModules(search, rawPattern, options))
            // The `--fuzzy` fallback runs only when the precise modes find nothing.
            if (hits.isEmpty() && options.fuzzy && !options.regex) {
                hits.addAll(fuzzyMatchTypes(search, scope, rawPattern, options))
            }
            if (hits.isEmpty()) {
                val suggestions = suggestSimilar(
                    rawPattern.substringAfterLast('.').substringAfterLast('$'),
                    scope.map { it.binary }.toSet(),
                )
                return ServiceOutcome.Failure(ErrorResult.notFound(rawPattern, suggestions))
            }
            val sorted = hits.sortedWith(compareBy({ it.ref }, { it.artifact }))
            val warnings = search.warnings.sortedBy { it.code }
            val listing = buildSearchListing(rawPattern, sorted, options.limit, warnings)
            return ServiceOutcome.SearchList(listing)
        } finally {
            search.close()
        }
    }

    private fun isTypeKind(kind: SearchKindFilter): Boolean = when (kind) {
        SearchKindFilter.CLASS, SearchKindFilter.INTERFACE, SearchKindFilter.ENUM,
        SearchKindFilter.RECORD, SearchKindFilter.ANNOTATION,
        SearchKindFilter.OBJECT, SearchKindFilter.COMPANION -> true
        else -> false
    }

    private fun matchTypes(
        search: SearchWorkspace,
        scope: List<SearchEntry>,
        pattern: String,
        options: SearchOptions,
    ): List<SearchHit> {
        // Name-match first (entry names only, no parsing), then load the winners
        // for their kind words — one ASM parse per *matched* class, never a scan.
        val nameMatched = scope.filter { matchesTypeName(pattern, it.binary, options.regex) }.distinctBy { it.binary }
        if (nameMatched.isEmpty()) return emptyList()
        val kinds = nameMatched.associate { entry ->
            entry.binary to search.workspace.load(entry.binary)?.kind
        }
        return scope
            .filter { matchesTypeName(pattern, it.binary, options.regex) }
            .filter { entry ->
                when (options.kind) {
                    SearchKindFilter.ALL, SearchKindFilter.TYPE -> true
                    else -> {
                        val kind = kinds[entry.binary] ?: return@filter false
                        searchKindWord(kind).equals(options.kind.flag, ignoreCase = true)
                    }
                }
            }
            .map { entry ->
                val kind = kinds[entry.binary]
                SearchHit(
                    kind = if (kind == null) "class" else searchKindWord(kind),
                    ref = entry.binary,
                    artifact = entry.artifact,
                    packageName = entry.packageName,
                )
            }
    }

    private fun fuzzyMatchTypes(
        search: SearchWorkspace,
        scope: List<SearchEntry>,
        pattern: String,
        options: SearchOptions,
    ): List<SearchHit> {
        val nameMatched = scope.filter {
            SymbolSearch.fuzzyMatches(pattern, it.binary.substringAfterLast('.').substringAfterLast('$'))
        }.distinctBy { it.binary }
        if (nameMatched.isEmpty()) return emptyList()
        val kinds = nameMatched.associate { entry ->
            entry.binary to search.workspace.load(entry.binary)?.kind
        }
        return scope
            .filter {
                SymbolSearch.fuzzyMatches(pattern, it.binary.substringAfterLast('.').substringAfterLast('$'))
            }
            .filter { entry ->
                when (options.kind) {
                    SearchKindFilter.ALL, SearchKindFilter.TYPE -> true
                    else -> {
                        val kind = kinds[entry.binary] ?: return@filter false
                        searchKindWord(kind).equals(options.kind.flag, ignoreCase = true)
                    }
                }
            }
            .map { entry ->
                val kind = kinds[entry.binary]
                SearchHit(
                    kind = if (kind == null) "class" else searchKindWord(kind),
                    ref = entry.binary,
                    artifact = entry.artifact,
                    packageName = entry.packageName,
                )
            }
    }

    private fun matchMembers(
        search: SearchWorkspace,
        scope: List<SearchEntry>,
        pattern: String,
        options: SearchOptions,
    ): List<SearchHit> {
        // Member search parses every in-scope class (the slow path — index-backed
        // member search will lift this with the M4 graph work). Bridge/synthetic
        // members stay hidden, mirroring `members` (D-031).
        val wantMethods = options.kind == SearchKindFilter.METHOD
        val hits = mutableListOf<SearchHit>()
        for (entry in scope.distinctBy { it.binary }) {
            val info = search.workspace.load(entry.binary) ?: continue
            val methodSiblings = info.methods.groupingBy {
                it.name to it.descriptor.parameters.joinToString("") { parameter -> parameter.descriptor }
            }.eachCount()
            if (wantMethods) {
                for (method in info.methods) {
                    if (method.name == "<clinit>") continue
                    if (method.access.has(AccessFlag.SYNTHETIC) || method.access.has(AccessFlag.BRIDGE)) continue
                    if (!matchesMemberName(pattern, method.name, options.regex)) continue
                    val baseRef = SymbolRefPrinter.print(
                        MemberSymbolRef(
                            declaringType = info.name,
                            name = method.name,
                            parameterTypes = method.descriptor.parameters,
                        ),
                    )
                    val key = method.name to
                        method.descriptor.parameters.joinToString("") { parameter -> parameter.descriptor }
                    val ref = if ((methodSiblings[key] ?: 0) > 1 && method.name != "<init>") {
                        baseRef + ":" + SignatureLines.renderTypeName(method.descriptor.returnType)
                    } else {
                        baseRef
                    }
                    providersOf(search, entry.binary, entry).forEach { artifact ->
                        hits.add(
                            SearchHit(
                                kind = if (method.name == "<init>") "constructor" else "method",
                                ref = ref,
                                artifact = artifact,
                                packageName = entry.packageName,
                            ),
                        )
                    }
                }
            } else {
                for (field in info.fields) {
                    if (field.access.has(AccessFlag.SYNTHETIC)) continue
                    if (!matchesMemberName(pattern, field.name, options.regex)) continue
                    val ref = SymbolRefPrinter.print(MemberSymbolRef(info.name, field.name))
                    providersOf(search, entry.binary, entry).forEach { artifact ->
                        hits.add(
                            SearchHit(
                                kind = "field",
                                ref = ref,
                                artifact = artifact,
                                packageName = entry.packageName,
                            ),
                        )
                    }
                }
            }
        }
        return hits
    }

    private fun providersOf(search: SearchWorkspace, binary: String, entry: SearchEntry): List<String> {
        // Every provider of the binary lists its own artifact label (D-031).
        return search.entries.filter { it.binary == binary }.map { it.artifact }.distinct().ifEmpty {
            listOf(entry.artifact)
        }
    }

    private fun matchPackages(
        scope: List<SearchEntry>,
        pattern: String,
        options: SearchOptions,
    ): List<SearchHit> {
        val pairs = scope.map { it.packageName to it.artifact }.distinct()
            .filter { (packageName, _) -> matchesPackageName(pattern, packageName, options.regex) }
        return pairs.map { (packageName, artifact) ->
            SearchHit(kind = "package", ref = packageName, artifact = artifact, packageName = packageName)
        }
    }

    private fun matchModules(
        search: SearchWorkspace,
        pattern: String,
        options: SearchOptions,
    ): List<SearchHit> {
        val modules = search.opened.flatMap { open ->
            val jrtModuleFor = open.jrtModuleFor ?: return@flatMap emptyList()
            open.root.classEntryPaths().mapNotNull { jrtModuleFor(it) }
        }.distinct()
        return modules.filter { matchesMemberName(pattern, it, options.regex) }.map { module ->
            SearchHit(kind = "module", ref = module, artifact = module, packageName = "")
        }
    }

    // -- name matching over workspace names (pure, unit-tested) ------------------

    /**
     * Matches [pattern] against a binary type name. Regex tries the binary, dotted
     * and simple forms; a dotted glob tries binary and dotted forms; a bare glob
     * tries the simple name and the binary. A dotted plain word names a location:
     * it matches exactly or as a `.`-boundary suffix (`dev.jdx.fixtures.Generics`
     * finds that type but not `Generics$Recursive` — the TESTING.md metamorphic
     * law). A bare plain word is a case-insensitive substring or a camel-hump on
     * the simple name (`JsonAdapter` also finds `JsonAdapterAnnotation...`, D-031).
     */
    internal fun matchesTypeName(pattern: String, binary: String, regex: Boolean): Boolean {
        val dotted = binary.replace('$', '.')
        val simple = binary.substringAfterLast('.').substringAfterLast('$')
        if (regex) {
            return SymbolSearch.matchesRegex(pattern, binary) ||
                SymbolSearch.matchesRegex(pattern, dotted) ||
                SymbolSearch.matchesRegex(pattern, simple)
        }
        if (SymbolSearch.isGlobPattern(pattern)) {
            return if ('.' in pattern) {
                SymbolSearch.matchesGlob(pattern, binary) || SymbolSearch.matchesGlob(pattern, dotted)
            } else {
                SymbolSearch.matchesGlob(pattern, simple) || SymbolSearch.matchesGlob(pattern, binary)
            }
        }
        if ('.' in pattern) {
            return binary == pattern || dotted == pattern ||
                (binary.endsWith(pattern) && binary[binary.length - pattern.length - 1] == '.') ||
                (dotted.endsWith(pattern) && dotted[dotted.length - pattern.length - 1] == '.')
        }
        return SymbolSearch.matchesSubstring(pattern, simple) ||
            SymbolSearch.matchesSubstring(pattern, binary) ||
            SymbolSearch.camelHumpMatches(pattern, simple)
    }

    /** Matches [pattern] against a member or module name (no package structure). */
    internal fun matchesMemberName(pattern: String, name: String, regex: Boolean): Boolean {
        if (regex) return SymbolSearch.matchesRegex(pattern, name)
        if (SymbolSearch.isGlobPattern(pattern)) return SymbolSearch.matchesGlob(pattern, name)
        return SymbolSearch.matchesSubstring(pattern, name) || SymbolSearch.camelHumpMatches(pattern, name)
    }

    /** Matches [pattern] against a dotted package name. */
    internal fun matchesPackageName(pattern: String, packageName: String, regex: Boolean): Boolean {
        if (regex) return SymbolSearch.matchesRegex(pattern, packageName)
        if (SymbolSearch.isGlobPattern(pattern)) return SymbolSearch.matchesGlob(pattern, packageName)
        return SymbolSearch.matchesSubstring(pattern, packageName) ||
            SymbolSearch.camelHumpMatches(pattern, packageName.substringAfterLast('.'))
    }

    /** `--in` matches artifact labels: glob when wild, substring otherwise. */
    internal fun matchesArtifactFilter(filter: String?, artifact: String): Boolean {
        if (filter == null) return true
        return if (SymbolSearch.isGlobPattern(filter)) SymbolSearch.matchesGlob(filter, artifact)
        else artifact.contains(filter, ignoreCase = true)
    }

    /** `--package` matches dotted package names: glob when wild, substring otherwise. */
    internal fun matchesPackageFilter(filter: String?, packageName: String): Boolean {
        if (filter == null) return true
        return if (SymbolSearch.isGlobPattern(filter)) SymbolSearch.matchesGlob(filter, packageName)
        else packageName.contains(filter, ignoreCase = true)
    }

    // -- resolve / ls / tree execution ------------------------------------------

    private fun executeResolve(rawName: String, roots: RootsSpec, limit: Int): ServiceOutcome {
        val search = openSearchWorkspace(roots)
        try {
            // A `#`/`::` suffix names a member; anything else is a type or package.
            val hashIndex = rawName.indexOf('#').takeIf { it >= 0 }
                ?: rawName.indexOf("::").takeIf { it >= 0 }
            val typePart = if (hashIndex == null) rawName else rawName.substring(0, hashIndex)
            val memberPart = if (hashIndex == null) {
                null
            } else if (rawName[hashIndex] == '#') {
                rawName.substring(hashIndex + 1)
            } else {
                rawName.substring(hashIndex + 2)
            }
            val scope = search.entries
            val typeBinaries = scope.map { it.binary }.distinct().filter { binary ->
                binary == typePart ||
                    binary.replace('$', '.') == typePart ||
                    binary.substringAfterLast('.') == typePart ||
                    binary.substringAfterLast('.').substringAfterLast('$') == typePart
            }
            val hits = mutableListOf<SearchHit>()
            if (memberPart == null) {
                for (binary in typeBinaries) {
                    val kind = search.workspace.load(binary)?.kind
                    if (kind == null) continue
                    for (artifact in scope.filter { it.binary == binary }.map { it.artifact }.distinct()) {
                        val packageName = binary.substringBeforeLast('.', "")
                        hits.add(SearchHit(searchKindWord(kind), binary, artifact, packageName))
                    }
                }
                if (hits.isEmpty()) {
                    // An exact package name resolves to its providers (D-031).
                    for (artifact in scope.filter { it.packageName == typePart }.map { it.artifact }.distinct()) {
                        hits.add(SearchHit("package", typePart, artifact, typePart))
                    }
                }
            } else {
                for (binary in typeBinaries) {
                    val info = search.workspace.load(binary) ?: continue
                    val packageName = binary.substringBeforeLast('.', "")
                    val artifacts = scope.filter { it.binary == binary }.map { it.artifact }.distinct()
                    for (method in info.methods.filter { it.name == memberPart }) {
                        if (method.name == "<clinit>") continue
                        val ref = SymbolRefPrinter.print(
                            MemberSymbolRef(
                                declaringType = info.name,
                                name = method.name,
                                parameterTypes = method.descriptor.parameters,
                            ),
                        )
                        for (artifact in artifacts) {
                            hits.add(
                                SearchHit(
                                    if (method.name == "<init>") "constructor" else "method",
                                    ref,
                                    artifact,
                                    packageName,
                                ),
                            )
                        }
                    }
                    for (field in info.fields.filter { it.name == memberPart }) {
                        val ref = SymbolRefPrinter.print(MemberSymbolRef(info.name, field.name))
                        for (artifact in artifacts) {
                            hits.add(SearchHit("field", ref, artifact, packageName))
                        }
                    }
                }
            }
            if (hits.isEmpty()) {
                val suggestions = suggestSimilar(
                    typePart.substringAfterLast('.').substringAfterLast('$'),
                    scope.map { it.binary }.toSet(),
                )
                return ServiceOutcome.Failure(ErrorResult.notFound(rawName, suggestions))
            }
            val sorted = hits.sortedWith(compareBy({ it.ref }, { it.artifact }))
            val listing = buildSearchListing(rawName, sorted, limit, search.warnings.sortedBy { it.code })
            return ServiceOutcome.SearchList(listing)
        } finally {
            search.close()
        }
    }

    private fun executeLs(query: String, packageGlob: String?, roots: RootsSpec, limit: Int): ServiceOutcome {
        val search = openSearchWorkspace(roots)
        try {
            if (packageGlob != null && !SymbolSearch.isGlobPattern(packageGlob)) {
                // Exact package: the types directly inside it (D-031).
                val inPackage = search.entries.filter { it.packageName == packageGlob }.distinct()
                if (inPackage.isEmpty()) {
                    return ServiceOutcome.Failure(ErrorResult.notFound(query))
                }
                val kinds = inPackage.distinctBy { it.binary }.associate { entry ->
                    entry.binary to search.workspace.load(entry.binary)?.kind
                }
                val types = inPackage.map { entry ->
                    val kind = kinds[entry.binary]
                    LsTypeEntry(
                        kind = if (kind == null) "class" else searchKindWord(kind),
                        ref = entry.binary,
                        artifact = entry.artifact,
                    )
                }.sortedWith(compareBy({ it.ref }, { it.artifact }))
                val packages = listOf(PackageEntry(packageGlob, inPackage.distinctBy { it.binary }.size))
                val listing = buildLsListing(query, packages, types, limit, search.warnings.sortedBy { it.code })
                return ServiceOutcome.LsList(listing)
            }
            val binariesByPackage = search.entries.distinctBy { it.binary to it.artifact }
                .groupBy({ it.packageName }, { it.binary })
            val packages = binariesByPackage
                .filter { (packageName, _) ->
                    packageGlob == null || SymbolSearch.matchesGlob(packageGlob, packageName)
                }
                .map { (packageName, binaries) -> PackageEntry(packageName, binaries.distinct().size) }
                .sortedBy { it.name }
            if (packages.isEmpty()) {
                return ServiceOutcome.Failure(ErrorResult.notFound(query))
            }
            val listing = buildLsListing(query, packages, emptyList(), limit, search.warnings.sortedBy { it.code })
            return ServiceOutcome.LsList(listing)
        } finally {
            search.close()
        }
    }

    private fun executeTree(
        query: String,
        roots: RootsSpec,
        depth: Int,
        withCounts: Boolean,
        limit: Int,
    ): ServiceOutcome {
        val search = openSearchWorkspace(roots)
        try {
            // JRT entries group by module (the artifact an agent recognises);
            // jars and dirs group by display label. A label served by several
            // roots gains a `(2)`-style suffix — deterministic by root order.
            val jrtModules = search.opened.flatMap { open ->
                val moduleFor = open.jrtModuleFor ?: return@flatMap emptyList<String>()
                open.root.classEntryPaths().mapNotNull { moduleFor(it) }
            }.toSet()
            val rootsByLabel = search.entries.groupBy({ it.artifact }, { it.rootIndex })
                .mapValues { it.value.toSet() }
            fun groupLabel(entry: SearchEntry): String {
                if (entry.artifact in jrtModules) return entry.artifact
                val rootIndexes = rootsByLabel.getValue(entry.artifact).sorted()
                if (rootIndexes.size == 1) return entry.artifact
                val ordinal = rootIndexes.indexOf(entry.rootIndex) + 1
                return if (ordinal <= 1) entry.artifact else "${entry.artifact} ($ordinal)"
            }
            val labels = mutableMapOf<String, MutableSet<String>>()
            val packagesOf = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()
            for (entry in search.entries) {
                val label = groupLabel(entry)
                labels.getOrPut(label) { mutableSetOf() }.add(entry.binary)
                packagesOf.getOrPut(label) { mutableMapOf() }
                    .getOrPut(entry.packageName) { mutableSetOf() }.add(entry.binary)
            }
            val matched = labels.keys.filter { label -> matchesArtifactFilter(query, label) }.sorted()
            if (matched.isEmpty()) {
                return ServiceOutcome.Failure(ErrorResult.notFound(query))
            }
            val trees = matched.map { label ->
                val packageNames = packagesOf.getValue(label).keys.filter { it.isNotEmpty() }.sorted()
                val direct = packagesOf.getValue(label)
                val subtree = mutableMapOf<String, Int>()
                for (packageName in packageNames) {
                    val segments = packageName.split('.')
                    for (end in 1..segments.size) {
                        val path = segments.take(end).joinToString(".")
                        if (path == packageName) {
                            subtree[path] = (subtree[path] ?: 0) + direct.getValue(packageName).size
                        } else {
                            subtree.putIfAbsent(path, 0)
                        }
                    }
                }
                // Roll up descendant counts after the direct pass.
                val ordered = subtree.keys.sortedByDescending { it.length }
                for (path in ordered) {
                    val parent = path.substringBeforeLast('.', "")
                    if (parent.isNotEmpty() && subtree.containsKey(parent)) {
                        subtree[parent] = subtree.getValue(parent) + subtree.getValue(path)
                    }
                }
                buildArtifactTree(label, packageNames, typeCountOf = { subtree.getValue(it) }, depth = depth)
            }
            val nodeTotal = trees.sumOf { countNodes(it.roots) }
            val listing = buildTreeListing(
                query,
                trees,
                withCounts,
                nodeTotal,
                limit,
                search.warnings.sortedBy { it.code },
            )
            return ServiceOutcome.TreeList(listing)
        } finally {
            search.close()
        }
    }

    // -- usages (T-030) ------------------------------------------------------------

    /**
     * `--kind` values for `usages` (PROPOSAL.md §7.3). The five T-030 kinds map
     * onto the closed [ReferenceKind] vocabulary (D-042); `impl`/`override`
     * are parsed here so `--help` shows the full proposal vocabulary, but
     * rejected naming the hierarchy commands. `new` filters the vocabulary to
     * constructor `<init>` calls; `throw`/`annotation` read `ClassInfo`
     * metadata (`throws`, annotations) over the same live-roots scan (T-075,
     * D-053).
     */
    public enum class UsageKindFilter(public val flag: String) {
        ALL("all"),
        CALL("call"),
        READ("read"),
        WRITE("write"),
        REF("ref"),
        IMPL("impl"),
        OVERRIDE("override"),
        NEW("new"),
        THROW("throw"),
        ANNOTATION("annotation"),
    }

    /**
     * Options for `usages`: which edges count and how much to show. [inArtifact]
     * is the `--in` glob over artifact labels (jar file names, class-dir names,
     * JDK module names); [exclude] drops matching labels. Both accept globs,
     * else match as case-insensitive substrings (D-031).
     */
    public data class UsageOptions(
        public val kind: UsageKindFilter = UsageKindFilter.ALL,
        public val inArtifact: String? = null,
        public val exclude: String? = null,
        public val limit: Int = DEFAULT_USAGES_LIMIT,
        /**
         * Source lines around each call site (`--context N`). Parsed here so
         * the flag exists, but always rejected: no line data in v1 (D-042 §4)
         * and source rendering belongs to `samples` (`jdx samples`, T-034).
         */
        public val contextLines: Int = 0,
    )

    /**
     * Answers `usages <symbol>`: every referencing method across the
     * workspace's roots, grouped by artifact.
     *
     * Live-roots scan (D-031 precedent): each class's edges are extracted with
     * the T-029 [ReferenceExtractor] and filtered by target — no persistent
     * index read yet (indexed acceleration lands with the daemon/`jdx index`
     * work). Structure is bytecode-authoritative (D-009): the target resolves
     * with the T-011 machinery before any scan, so a typo reports
     * did-you-mean instead of an empty answer.
     */
    public fun usages(
        rawRef: String,
        roots: RootsSpec,
        options: UsageOptions = UsageOptions(),
    ): ServiceOutcome {
        if (options.limit < 0) {
            return failure(3, rawRef, "usage error: --limit must be >= 0, got ${options.limit}")
        }
        if (options.contextLines != 0) {
            return failure(
                3,
                rawRef,
                "usage error: --context is not supported for usages yet " +
                    "(source-rendered call sites: jdx samples '$rawRef')",
            )
        }
        when (options.kind) {
            UsageKindFilter.IMPL, UsageKindFilter.OVERRIDE ->
                return failure(
                    3,
                    rawRef,
                    "usage error: --kind ${options.kind.flag} is not supported for usages yet " +
                        "(type hierarchy: jdx hierarchy, implementors: jdx implementors)",
                )
            // T-075: NEW/THROW/ANNOTATION are live (graph enrichment over the
            // T-029 vocabulary + ClassInfo metadata); --context stays a
            // `samples` redirect (D-053).
            else -> Unit
        }
        val parsed = SymbolRefParser.parse(rawRef)
        if (parsed is SymbolRefParseResult.Failure) {
            return failure(
                3,
                rawRef,
                "usage error: invalid reference '$rawRef': ${parsed.message} at column ${parsed.position}",
            )
        }
        val ref = (parsed as SymbolRefParseResult.Ok).ref
        if (ref is PackageSymbolRef || ref is ModuleSymbolRef) {
            return failure(3, rawRef, "usage error: usages takes a type or member reference, got '$rawRef'")
        }
        val coordinate = (ref as? MemberSymbolRef)?.coordinate ?: (ref as? TypeSymbolRef)?.coordinate
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk && coordinate == null) {
            return failure(
                4,
                rawRef,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        // A `g:a:v/` prefix scopes the query to one artifact (T-019): its jar
        // reads first (shadowing order), and candidates match inside it only —
        // while the scan still covers the full workspace behind it.
        var scopedRoots = roots
        var candidateScope: Set<String>? = null
        if (coordinate != null) {
            val coordText = "${coordinate.group}:${coordinate.artifact}:${coordinate.version}"
            val outcome = try {
                roots.mavenResolve(coordText, roots.allowFetch)
            } catch (e: Exception) {
                return failure(
                    6,
                    rawRef,
                    "internal error: coordinate resolution failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            val artifact = when (outcome) {
                is MavenResolver.Outcome.Resolved -> outcome.artifact
                is MavenResolver.Outcome.Unresolved ->
                    return failure(5, rawRef, "artifact read error: ${outcome.message}")
            }
            val binarySpec = artifact.binaryJar.toString()
            val scope = try {
                ArtifactLoader.open(artifact.binaryJar).use { root -> root.classEntryPaths().map(::entryToBinary).toSet() }
            } catch (e: ArtifactReadException) {
                return failure(5, rawRef, e.message ?: "artifact read error")
            } catch (e: Exception) {
                return failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
            }
            scopedRoots = roots.copy(jarSpecs = listOf(binarySpec) + roots.jarSpecs)
            candidateScope = scope
        }
        return try {
            executeUsages(ref, rawRef, scopedRoots, options, candidateScope)
        } catch (e: ArtifactReadException) {
            failure(5, rawRef, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    /**
     * Serves `usages <symbol>`: resolves the target type from bytecode (D-009),
     * then scans every class in every open root with the T-029 extractor.
     * Unreadable classes warn once each ([WarningCode.CORRUPT_CLASS]) and are
     * skipped — one bad entry never aborts the scan (D-017).
     */
    private fun executeUsages(
        ref: SymbolRef,
        rawRef: String,
        roots: RootsSpec,
        options: UsageOptions,
        candidateScope: Set<String>? = null,
    ): ServiceOutcome {
        val declaring = when (ref) {
            is MemberSymbolRef -> ref.declaringType as? TypeName.ClassType
            is TypeSymbolRef -> ref.type as? TypeName.ClassType
            else -> null
        } ?: return failure(3, rawRef, "usage error: usages takes a type or member reference, got '$rawRef'")
        val opened = openRoots(roots)
        try {
            val binariesByRoot = opened.map { it.root.classEntryPaths().map(::entryToBinary).toSet() }
            val providers = mutableMapOf<String, MutableList<Int>>()
            binariesByRoot.forEachIndexed { index, binaries ->
                for (binary in binaries) providers.getOrPut(binary) { mutableListOf() }.add(index)
            }
            val allBinaries = providers.keys

            val candidates = matchCandidates(declaring, candidateScope ?: allBinaries)
            if (candidates.isEmpty()) {
                val suggestions = suggestSimilar(declaring.simpleName, allBinaries)
                return ServiceOutcome.Failure(ErrorResult.notFound(rawRef, suggestions))
            }
            if (candidates.size > 1) {
                return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, candidates))
            }
            val binary = candidates.single()
            val winner = providers.getValue(binary).first()

            val warnings = mutableListOf<Warning>()
            warnings.addAll(roots.extraWarnings)
            for (open in opened) warnings.addAll(open.root.warnings)
            val extraProviders = providers.getValue(binary).drop(1)
            if (extraProviders.isNotEmpty()) {
                val names = listOf(winner).plus(extraProviders).map { rootLabel(opened[it], binary) }
                warnings.add(
                    Warning(
                        code = WarningCode.DUPLICATE_FQN,
                        message = "$binary is provided by ${names.joinToString(", ")}; " +
                            "showing ${names.first()} (classpath order)",
                        subject = binary,
                    ),
                )
            }

            val workspace = Workspace(opened, providers, warnings)
            val target = workspace.load(binary)
            if (target == null) {
                return failure(
                    5,
                    rawRef,
                    "artifact read error: $binary in ${rootLabel(opened[winner], binary)} cannot be parsed",
                )
            }

            // The target member set is structural (D-009): a member proven
            // absent from bytecode reports did-you-mean instead of scanning.
            // Member-only refs are overload-blind by design (D-042 §5) — every
            // overload's edges count — and narrow to one overload only when
            // the ref carries a parameter list.
            val memberName: String?
            val memberDescriptors: Set<String>?
            val wantFieldEdges: Boolean
            val canonicalTarget: String
            if (ref is MemberSymbolRef) {
                if (ref.name == "<clinit>") {
                    return failure(3, rawRef, "usage error: static initialisers have no usages to show: '$rawRef'")
                }
                val matches = matchBytecodeMembers(target, ref)
                if (matches.isEmpty()) {
                    return ServiceOutcome.Failure(
                        ErrorResult.notFound(rawRef, suggestSimilarMember(target, ref.name)),
                    )
                }
                memberName = ref.name
                memberDescriptors = if (ref.parameterTypes != null) {
                    matches.filterIsInstance<BytecodeMember.Method>()
                        .map { it.info.descriptor.descriptor }.toSet()
                } else {
                    null
                }
                wantFieldEdges = ref.parameterTypes == null && ref.returnType == null
                canonicalTarget = SymbolRefPrinter.print(MemberSymbolRef(target.name, ref.name))
            } else {
                memberName = null
                memberDescriptors = null
                wantFieldEdges = true
                canonicalTarget = binary
            }

            // T-075 graph enrichment (D-053): `new` is a filtered view over
            // the T-029 vocabulary (METHOD_CALL to `<init>`); `throw` reads
            // `throws` declarations and `annotation` reads annotation uses
            // from ClassInfo metadata over the same live-roots scan. `all`
            // is edges plus the two metadata kinds (constructors stay under
            // their `call`/`ref` rows there, so nothing double-counts).
            // Metadata kinds are type-level: a member ref with `throw` or
            // `annotation` scans nothing and reports no usages (exit 1).
            val isTypeQuery = ref is TypeSymbolRef
            val edgeKinds: Set<ReferenceKind>
            var newOnly = false
            var wantThrows = false
            var wantAnnotations = false
            when (options.kind) {
                UsageKindFilter.ALL -> {
                    edgeKinds = ReferenceKind.entries.toSet()
                    wantThrows = isTypeQuery
                    wantAnnotations = isTypeQuery
                }
                UsageKindFilter.CALL -> edgeKinds = setOf(ReferenceKind.METHOD_CALL)
                UsageKindFilter.READ -> edgeKinds = setOf(ReferenceKind.FIELD_READ)
                UsageKindFilter.WRITE -> edgeKinds = setOf(ReferenceKind.FIELD_WRITE)
                UsageKindFilter.REF -> edgeKinds = setOf(ReferenceKind.TYPE_REFERENCE)
                UsageKindFilter.NEW -> {
                    edgeKinds = setOf(ReferenceKind.METHOD_CALL)
                    newOnly = true
                }
                UsageKindFilter.THROW -> {
                    edgeKinds = emptySet()
                    wantThrows = isTypeQuery
                }
                UsageKindFilter.ANNOTATION -> {
                    edgeKinds = emptySet()
                    wantAnnotations = isTypeQuery
                }
                // impl/override exit 3 in usages() before any scan reaches here.
                else -> return failure(3, rawRef, "usage error: --kind ${options.kind.flag} is not supported yet")
            }

            val hits = mutableListOf<UsageHit>()
            val reported = mutableSetOf<String>()
            for (open in opened) {
                for (entry in open.root.classEntryPaths()) {
                    val fromBinary = entryToBinary(entry)
                    // T-012 reports the JDK module as the artifact (e.g.
                    // `java.base`) — per entry, since one `jrt:/` root spans
                    // many modules.
                    val label = rootLabel(open, fromBinary)
                    if (!matchesArtifactFilter(options.inArtifact, label) ||
                        (options.exclude != null && matchesArtifactFilter(options.exclude, label))
                    ) {
                        continue
                    }
                    val bytes = try {
                        open.root.openClass(entry).use { it.readBytes() }
                    } catch (e: Exception) {
                        if (reported.add(fromBinary)) {
                            warnings.add(
                                Warning(
                                    code = WarningCode.CORRUPT_CLASS,
                                    message = "cannot read $fromBinary from ${open.root.displayName}: " +
                                        "${e.message ?: e.javaClass.simpleName}",
                                    subject = fromBinary,
                                ),
                            )
                        }
                        continue
                    }
                    if (edgeKinds.isNotEmpty()) {
                        for (edge in ReferenceExtractor.extract(bytes)) {
                            if (edge.toOwner != binary) continue
                            if (edge.kind !in edgeKinds) continue
                            if (newOnly && edge.toMember != "<init>") continue
                            if (memberName != null) {
                                if (edge.toMember != memberName) continue
                                val edgeDescriptor = edge.toDescriptor
                                if (memberDescriptors != null) {
                                    if (edgeDescriptor == null || edgeDescriptor !in memberDescriptors) continue
                                } else if (!wantFieldEdges && edgeDescriptor != null && !edgeDescriptor.startsWith("(")) {
                                    // A return-qualified ref names a method, never a field.
                                    continue
                                }
                            }
                            hits.add(
                                UsageHit(
                                    fromRef = canonicalFromRef(edge.fromClass, edge.fromMember, edge.fromDescriptor),
                                    artifact = label,
                                    kind = if (newOnly) "new" else usageKindWord(edge.kind),
                                    targetRef = edgeTargetRef(edge),
                                ),
                            )
                        }
                    }
                    if (wantThrows || wantAnnotations) {
                        collectMetadataUsages(bytes, fromBinary, label, binary, wantThrows, wantAnnotations, reported, warnings, hits)
                    }
                }
            }

            // T-031 source-dir scan (D-010): textual whole-word mentions, always
            // `ref` kind — text cannot tell call from read from write, so
            // `--kind call|read|write` shows bytecode edges alone while
            // `--kind all|ref` adds these rows. Missing dirs fail (exit 5,
            // like missing `--jars`); unreadable files read as no mentions
            // inside the scanner (never abort the scan).
            if (ReferenceKind.TYPE_REFERENCE in edgeKinds) {
                val wanted = if (memberName != null && memberName != "<init>") memberName else declaring.simpleName
                val seenDirs = LinkedHashSet<String>()
                for (spec in roots.srcSpecs) {
                    val dir = java.nio.file.Paths.get(spec)
                    if (!java.nio.file.Files.isDirectory(dir)) {
                        return failure(5, rawRef, "artifact read error: source dir '$spec' does not exist or is not a directory")
                    }
                    val key = runCatching { dir.toAbsolutePath().normalize().toString() }.getOrElse { spec }
                    if (!seenDirs.add(key)) continue
                    val label = dir.fileName?.toString() ?: spec
                    if (!matchesArtifactFilter(options.inArtifact, label) ||
                        (options.exclude != null && matchesArtifactFilter(options.exclude, label))
                    ) {
                        continue
                    }
                    for (mention in dev.jdx.index.usages.SourceUsages.scanSourceDir(dir, wanted)) {
                        hits.add(
                            UsageHit(
                                fromRef = "${mention.relativePath}:${mention.line}",
                                artifact = label,
                                kind = "ref",
                                targetRef = canonicalTarget,
                            ),
                        )
                    }
                }
            }

            if (hits.isEmpty()) {
                return ServiceOutcome.Failure(
                    ErrorResult.notFound(
                        rawRef,
                        emptyList(),
                        detail = "no usages of '$canonicalTarget' in the workspace",
                    ),
                )
            }
            val sorted = hits.sortedWith(
                compareBy({ it.artifact }, { it.fromRef }, { it.kind }, { it.targetRef }),
            )
            val listing = buildUsageListing(
                query = rawRef,
                targetRef = canonicalTarget,
                hits = sorted,
                limit = options.limit,
                warnings = warnings.sortedBy { it.code },
                showTargets = ref is TypeSymbolRef,
            )
            return ServiceOutcome.UsageList(listing)
        } finally {
            opened.forEach { it.root.close() }
        }
    }

    /**
     * The kind word an edge renders as: `call` (method invocation), `read` /
     * `write` (field access), `ref` (any other mention of the type —
     * `checkcast`, `instanceof`, class constants). Constructor invocations
     * render as `new` only under `--kind new` (a filtered view, T-075); under
     * `all` they keep their `call` row plus the `NEW` instruction's `ref`
     * row, so nothing double-counts. The D-042 vocabulary.
     */
    private fun usageKindWord(kind: ReferenceKind): String = when (kind) {
        ReferenceKind.METHOD_CALL -> "call"
        ReferenceKind.FIELD_READ -> "read"
        ReferenceKind.FIELD_WRITE -> "write"
        ReferenceKind.TYPE_REFERENCE -> "ref"
    }

    /**
     * Collects the T-075 metadata hits for one class file: `throw` rows for
     * every method whose `throws` declares [targetBinary], `annotation` rows
     * for the class and each member annotated with it. One row per declaring
     * method / annotated element, kind-led like the edge rows. Never throws:
     * unparseable bytes warn once via [reported] and read as no hits (D-017).
     */
    private fun collectMetadataUsages(
        bytes: ByteArray,
        fromBinary: String,
        label: String,
        targetBinary: String,
        wantThrows: Boolean,
        wantAnnotations: Boolean,
        reported: MutableSet<String>,
        warnings: MutableList<Warning>,
        hits: MutableList<UsageHit>,
    ) {
        val info = when (val read = AsmClassReader.read(bytes, fromBinary)) {
            is ClassReadResult.Ok -> read.info
            is ClassReadResult.UnsupportedVersion -> {
                if (reported.add(fromBinary)) warnings.add(read.warning.copy(subject = fromBinary))
                return
            }
            is ClassReadResult.Corrupt -> {
                if (reported.add(fromBinary)) warnings.add(read.warning.copy(subject = fromBinary))
                return
            }
        }
        // The target itself never reports itself: `throws Foo` on Foo's own
        // method or `@Foo` on Foo is a declaration, not a usage.
        val hostBinary = info.name.binaryName
        if (hostBinary == targetBinary) return
        val canonicalTarget = targetBinary
        if (wantThrows) {
            for (method in info.methods) {
                if (method.throwsTypes.any { (it as? TypeName.ClassType)?.binaryName == targetBinary }) {
                    hits.add(
                        UsageHit(
                            fromRef = SymbolRefPrinter.print(
                                MemberSymbolRef(
                                    declaringType = info.name,
                                    name = method.name,
                                    parameterTypes = method.descriptor.parameters,
                                ),
                            ),
                            artifact = label,
                            kind = "throw",
                            targetRef = canonicalTarget,
                        ),
                    )
                }
            }
        }
        if (wantAnnotations) {
            if (info.annotations.any { (it.type as? TypeName.ClassType)?.binaryName == targetBinary }) {
                hits.add(
                    UsageHit(
                        fromRef = hostBinary,
                        artifact = label,
                        kind = "annotation",
                        targetRef = canonicalTarget,
                    ),
                )
            }
            for (member in info.members) {
                if (member.annotations.any { (it.type as? TypeName.ClassType)?.binaryName == targetBinary }) {
                    val fromRef = when (member) {
                        is MethodInfo -> SymbolRefPrinter.print(
                            MemberSymbolRef(
                                declaringType = info.name,
                                name = member.name,
                                parameterTypes = member.descriptor.parameters,
                            ),
                        )
                        else -> "$hostBinary#${member.name}"
                    }
                    hits.add(
                        UsageHit(
                            fromRef = fromRef,
                            artifact = label,
                            kind = "annotation",
                            targetRef = canonicalTarget,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Renders a call site as `Binary#member(params)`: the erased descriptor
     * parses back to parameter types (pure string work, never throws — hostile
     * bytes yield the bare `Binary#member` instead of a failure).
     */
    private fun canonicalFromRef(fromClass: String, fromMember: String, fromDescriptor: String): String {
        val declaring = runCatching { typeNameFromBinaryName(fromClass) }.getOrNull()
            as? TypeName.ClassType ?: return "$fromClass#$fromMember"
        val parameters = (JvmDescriptor.parse(fromDescriptor) as? JvmDescriptor.Method)?.parameters
            ?: return "$fromClass#$fromMember"
        return SymbolRefPrinter.print(
            MemberSymbolRef(declaringType = declaring, name = fromMember, parameterTypes = parameters),
        )
    }

    /**
     * Renders the touched member as `Owner#member(params)`: fields print bare
     * (`Owner#name`), methods with their erased parameter list, pure type
     * edges as the bare owner. Descriptors arrive from class files we do not
     * control, so anything unparseable degrades to the bare member — never a
     * throw (fault injection, TESTING.md §7).
     */
    private fun edgeTargetRef(edge: ReferenceEdge): String {
        val member = edge.toMember ?: return edge.toOwner
        val descriptor = edge.toDescriptor
        if (descriptor == null || !descriptor.startsWith("(")) return "${edge.toOwner}#$member"
        val declaring = runCatching { typeNameFromBinaryName(edge.toOwner) }.getOrNull()
            as? TypeName.ClassType ?: return "${edge.toOwner}#$member"
        val parameters = (JvmDescriptor.parse(descriptor) as? JvmDescriptor.Method)?.parameters
            ?: return "${edge.toOwner}#$member"
        return SymbolRefPrinter.print(
            MemberSymbolRef(declaringType = declaring, name = member, parameterTypes = parameters),
        )
    }

    // -- hierarchy (T-032) ------------------------------------------------------

    /**
     * Options for `hierarchy`/`implementors`: which directions to show and how
     * much of the workspace to cover. [depth] caps transitive levels (1 =
     * direct supertypes/subtypes only); [directOnly] is `--direct`, spelled
     * separately so `implementors --direct` reads naturally. [inArtifact] and
     * [exclude] scope the downward workspace scan by artifact label (jar file
     * names, class-dir names, JDK module names); the upward lineage always
     * shows — it is the type's own ancestry, not a workspace search.
     */
    public data class HierarchyOptions(
        public val up: Boolean = true,
        public val down: Boolean = true,
        public val directOnly: Boolean = false,
        public val depth: Int = Int.MAX_VALUE,
        public val inArtifact: String? = null,
        public val exclude: String? = null,
        public val limit: Int = DEFAULT_HIERARCHY_LIMIT,
    )

    /**
     * Answers `hierarchy <type>`: supertypes upward and subtypes downward.
     *
     * Live-roots scan (D-043 precedent): the upward chain walks lazily-loaded
     * supertypes and the downward scan parses every class in every open root
     * with ASM — no persistent index read yet (indexed acceleration lands with
     * the daemon/`jdx index` work). Structure is bytecode-authoritative
     * (D-009): the target resolves with the T-011 machinery before any scan,
     * so a typo reports did-you-mean instead of an empty answer.
     */
    public fun hierarchy(
        rawRef: String,
        roots: RootsSpec,
        options: HierarchyOptions = HierarchyOptions(),
    ): ServiceOutcome {
        if (options.limit < 0) {
            return failure(3, rawRef, "usage error: --limit must be >= 0, got ${options.limit}")
        }
        if (options.depth < 1) {
            return failure(3, rawRef, "usage error: --depth must be >= 1, got ${options.depth}")
        }
        if (!options.up && !options.down) {
            return failure(3, rawRef, "usage error: select --up and/or --down (both off shows nothing)")
        }
        val parsed = SymbolRefParser.parse(rawRef)
        if (parsed is SymbolRefParseResult.Failure) {
            return failure(
                3,
                rawRef,
                "usage error: invalid reference '$rawRef': ${parsed.message} at column ${parsed.position}",
            )
        }
        val ref = (parsed as SymbolRefParseResult.Ok).ref
        if (ref is MemberSymbolRef) {
            return failure(3, rawRef, "usage error: hierarchy takes a type reference, got '$rawRef'")
        }
        if (ref is PackageSymbolRef || ref is ModuleSymbolRef) {
            return failure(3, rawRef, "usage error: hierarchy takes a type reference, got '$rawRef'")
        }
        val coordinate = (ref as? TypeSymbolRef)?.coordinate
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk && coordinate == null) {
            return failure(
                4,
                rawRef,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        // A `g:a:v/` prefix scopes the query to one artifact (T-019): its jar
        // reads first (shadowing order), and candidates match inside it only —
        // while the downward scan still covers the full workspace behind it.
        var scopedRoots = roots
        var candidateScope: Set<String>? = null
        if (coordinate != null) {
            val coordText = "${coordinate.group}:${coordinate.artifact}:${coordinate.version}"
            val outcome = try {
                roots.mavenResolve(coordText, roots.allowFetch)
            } catch (e: Exception) {
                return failure(
                    6,
                    rawRef,
                    "internal error: coordinate resolution failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            val artifact = when (outcome) {
                is MavenResolver.Outcome.Resolved -> outcome.artifact
                is MavenResolver.Outcome.Unresolved ->
                    return failure(5, rawRef, "artifact read error: ${outcome.message}")
            }
            val binarySpec = artifact.binaryJar.toString()
            val scope = try {
                ArtifactLoader.open(artifact.binaryJar).use { root -> root.classEntryPaths().map(::entryToBinary).toSet() }
            } catch (e: ArtifactReadException) {
                return failure(5, rawRef, e.message ?: "artifact read error")
            } catch (e: Exception) {
                return failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
            }
            scopedRoots = roots.copy(jarSpecs = listOf(binarySpec) + roots.jarSpecs)
            candidateScope = scope
        }
        return try {
            executeHierarchy(ref, rawRef, scopedRoots, options, candidateScope)
        } catch (e: ArtifactReadException) {
            failure(5, rawRef, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    /**
     * Serves `hierarchy <type>`: resolves the target type from bytecode
     * (D-009), walks its supertype chain upward through lazily-loaded parents
     * and scans every distinct workspace class once for transitive subtypes
     * downward. Unreadable classes warn once each ([WarningCode.CORRUPT_CLASS])
     * and are skipped — one bad entry never aborts the scan (D-017).
     */
    private fun executeHierarchy(
        ref: SymbolRef,
        rawRef: String,
        roots: RootsSpec,
        options: HierarchyOptions,
        candidateScope: Set<String>? = null,
    ): ServiceOutcome {
        val declaring = (ref as? TypeSymbolRef)?.type as? TypeName.ClassType
            ?: return failure(3, rawRef, "usage error: hierarchy takes a type reference, got '$rawRef'")
        val opened = openRoots(roots)
        try {
            val binariesByRoot = opened.map { it.root.classEntryPaths().map(::entryToBinary).toSet() }
            val providers = mutableMapOf<String, MutableList<Int>>()
            binariesByRoot.forEachIndexed { index, binaries ->
                for (binary in binaries) providers.getOrPut(binary) { mutableListOf() }.add(index)
            }
            val allBinaries = providers.keys

            val candidates = matchCandidates(declaring, candidateScope ?: allBinaries)
            if (candidates.isEmpty()) {
                val suggestions = suggestSimilar(declaring.simpleName, allBinaries)
                return ServiceOutcome.Failure(ErrorResult.notFound(rawRef, suggestions))
            }
            if (candidates.size > 1) {
                return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, candidates))
            }
            val binary = candidates.single()
            val winner = providers.getValue(binary).first()

            val warnings = mutableListOf<Warning>()
            warnings.addAll(roots.extraWarnings)
            for (open in opened) warnings.addAll(open.root.warnings)
            val extraProviders = providers.getValue(binary).drop(1)
            if (extraProviders.isNotEmpty()) {
                val names = listOf(winner).plus(extraProviders).map { rootLabel(opened[it], binary) }
                warnings.add(
                    Warning(
                        code = WarningCode.DUPLICATE_FQN,
                        message = "$binary is provided by ${names.joinToString(", ")}; " +
                            "showing ${names.first()} (classpath order)",
                        subject = binary,
                    ),
                )
            }

            val workspace = Workspace(opened, providers, warnings)
            val target = workspace.load(binary)
            if (target == null) {
                return failure(
                    5,
                    rawRef,
                    "artifact read error: $binary in ${rootLabel(opened[winner], binary)} cannot be parsed",
                )
            }

            val maxDepth = if (options.directOnly) 1 else options.depth
            val supertypes = if (options.up) {
                collectSupertypes(binary, target, workspace, opened, providers, maxDepth, warnings)
            } else {
                emptyList()
            }
            val subtypes = if (options.down) {
                collectSubtypes(binary, workspace, opened, providers, options, maxDepth)
            } else {
                emptyList()
            }
            val listing = buildHierarchyListing(
                query = rawRef,
                targetRef = binary,
                supertypes = supertypes,
                subtypes = subtypes.sortedBy { it.binary },
                showUp = options.up,
                showDown = options.down,
                limit = options.limit,
                warnings = warnings.sortedBy { it.code },
            )
            return ServiceOutcome.Hierarchy(listing)
        } finally {
            opened.forEach { it.root.close() }
        }
    }

    /**
     * The direct supertype edges out of one class: its superclass (`extends`)
     * plus its interfaces (`implements` — or `extends` when the child is
     * itself an interface or annotation, mirroring `ClassCard`, since
     * interfaces extend their superinterfaces).
     */
    private fun directSupertypeEdges(info: ClassInfo): List<Pair<String, String>> {
        val edges = mutableListOf<Pair<String, String>>()
        (info.superclass as? TypeName.ClassType)?.let { edges.add("extends" to it.binaryName) }
        val interfaceRelation = if (info.kind == TypeKind.INTERFACE || info.kind == TypeKind.ANNOTATION) {
            "extends"
        } else {
            "implements"
        }
        for (iface in info.interfaces) {
            (iface as? TypeName.ClassType)?.let { edges.add(interfaceRelation to it.binaryName) }
        }
        return edges
    }

    /**
     * Walks the supertype chain upward, breadth-first (superclass then
     * interfaces per level, first visit wins) and cycle-safe. Rows for
     * supertypes outside the workspace still print — the edge is known from
     * the child's bytes — with a null artifact; every genuinely missing
     * supertype warns [WarningCode.UNRESOLVED_SUPERTYPE] once, except
     * `java.lang.Object` (the universal root: absent under `--no-jdk` by
     * design, with no supertypes of its own to lose).
     */
    private fun collectSupertypes(
        binary: String,
        target: ClassInfo,
        workspace: Workspace,
        opened: List<OpenRoot>,
        providers: Map<String, List<Int>>,
        maxDepth: Int,
        warnings: MutableList<Warning>,
    ): List<SupertypeEntry> {
        val entries = mutableListOf<SupertypeEntry>()
        val visited = mutableSetOf(binary)
        val warned = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<ClassInfo, Int>>()
        queue.add(target to 0)
        while (queue.isNotEmpty()) {
            val (info, depth) = queue.removeFirst()
            if (depth >= maxDepth) continue
            for ((relation, parent) in directSupertypeEdges(info)) {
                if (!visited.add(parent)) continue
                val parentDepth = depth + 1
                val provider = providers[parent]?.firstOrNull()
                entries.add(
                    SupertypeEntry(
                        binary = parent,
                        relation = relation,
                        artifact = provider?.let { rootLabel(opened[it], parent) },
                        depth = parentDepth,
                    ),
                )
                if (provider == null) {
                    if (parent != OBJECT_BINARY_NAME && warned.add(parent)) {
                        warnings.add(
                            Warning(
                                code = WarningCode.UNRESOLVED_SUPERTYPE,
                                message = "$parent (a supertype of ${info.name.binaryName}) " +
                                    "is not in the workspace; the chain above it is unknown",
                                subject = parent,
                            ),
                        )
                    }
                    continue
                }
                if (parentDepth < maxDepth) {
                    workspace.load(parent)?.let { queue.add(it to parentDepth) }
                }
            }
        }
        return entries
    }

    /**
     * Scans every distinct workspace class once for transitive subtypes of
     * [binary]. Each class's supertype closure is walked breadth-first and
     * cycle-safe; the first step of the winning path becomes the row's `via`
     * note (`extends h.Middle`), null for direct children. One FQN in two
     * roots resolves to its shadowing winner (first provider) rather than
     * printing twice.
     */
    private fun collectSubtypes(
        binary: String,
        workspace: Workspace,
        opened: List<OpenRoot>,
        providers: Map<String, List<Int>>,
        options: HierarchyOptions,
        maxDepth: Int,
    ): List<SubtypeEntry> {
        val found = mutableListOf<SubtypeEntry>()
        for (candidate in providers.keys) {
            if (candidate == binary) continue
            val info = workspace.load(candidate) ?: continue
            val match = findSubtypePath(candidate, info, binary, workspace, maxDepth)
            if (match is SubtypeMatch.Absent) continue
            val label = rootLabel(opened[providers.getValue(candidate).first()], candidate)
            if (!matchesArtifactFilter(options.inArtifact, label) ||
                (options.exclude != null && matchesArtifactFilter(options.exclude, label))
            ) {
                continue
            }
            found.add(SubtypeEntry(binary = candidate, artifact = label, via = (match as SubtypeMatch.Present).via))
        }
        return found
    }

    /**
     * Whether [candidate] is a subtype of [binary] within [maxDepth] levels:
     * [Absent], or [Present] carrying the `via` note (null for a direct
     * child, `extends h.Middle` for a transitive path's first step).
     */
    private sealed interface SubtypeMatch {
        data class Present(val via: String?) : SubtypeMatch

        data object Absent : SubtypeMatch
    }

    /**
     * Returns [SubtypeMatch.Present] when [binary] is a supertype of
     * [candidate] within [maxDepth] levels, [SubtypeMatch.Absent] otherwise.
     * Breadth-first and cycle-safe; paths through supertypes missing from the
     * workspace simply end.
     */
    private fun findSubtypePath(
        candidate: String,
        info: ClassInfo,
        binary: String,
        workspace: Workspace,
        maxDepth: Int,
    ): SubtypeMatch {
        // The direct edges first: a direct child answers without any loads.
        for ((_, parent) in directSupertypeEdges(info)) {
            if (parent == binary) return SubtypeMatch.Present(null)
        }
        if (maxDepth < 2) return SubtypeMatch.Absent
        val visited = mutableSetOf(candidate)
        // Each queued node carries the first step out of the candidate, so the
        // row can name the direct parent even on a transitive path.
        val queue = ArrayDeque<Triple<String, String, Int>>()
        for ((relation, parent) in directSupertypeEdges(info)) {
            if (visited.add(parent)) queue.add(Triple(parent, "$relation $parent", 1))
        }
        while (queue.isNotEmpty()) {
            val (current, firstStep, depth) = queue.removeFirst()
            val currentInfo = workspace.load(current) ?: continue
            for ((_, parent) in directSupertypeEdges(currentInfo)) {
                if (parent == binary) return SubtypeMatch.Present(firstStep)
                if (depth + 1 < maxDepth && visited.add(parent)) {
                    queue.add(Triple(parent, firstStep, depth + 1))
                }
            }
        }
        return SubtypeMatch.Absent
    }

    // -- callers/calls (T-033) --------------------------------------------------------

    /**
     * Options for `callers`/`calls`: how deep the transitive walk goes and how
     * much to show. [depth] counts displayed levels (1 = direct callers/callees
     * only); [inArtifact] and [exclude] scope rows by artifact label (jar file
     * names, class-dir names, JDK module names) with the D-031 glob-or-substring
     * match, mirroring `usages`/`hierarchy`; [externalOnly] (`calls` only,
     * Appendix B) prunes callees in the query target's own artifact.
     */
    public data class CallOptions(
        public val depth: Int = DEFAULT_CALLS_DEPTH,
        public val inArtifact: String? = null,
        public val exclude: String? = null,
        public val limit: Int = DEFAULT_CALLS_LIMIT,
        public val externalOnly: Boolean = false,
    )

    /**
     * Answers `callers <method>`: every method calling it, transitively to
     * [CallOptions.depth].
     *
     * Live-roots scan (D-043 precedent): every class's `METHOD_CALL` edges are
     * extracted once with the T-029 [ReferenceExtractor], then the tree is
     * walked in memory — no persistent index read yet (indexed acceleration
     * lands with the daemon/`jdx index` work). Structure is
     * bytecode-authoritative (D-009): the target resolves with the T-011
     * machinery before any scan, so a typo reports did-you-mean instead of an
     * empty answer. Matching is exact name+descriptor at every level — no
     * virtual-dispatch resolution in v1 — and overload-blind at the root
     * unless the ref carries a parameter list (the usages rule, D-042 §5).
     */
    public fun callers(
        rawRef: String,
        roots: RootsSpec,
        options: CallOptions = CallOptions(),
    ): ServiceOutcome =
        callGraph(rawRef, roots, options, CallDirection.CALLERS)

    /**
     * Answers `calls <method>`: every method it calls, transitively to
     * [CallOptions.depth]. Same live-roots machinery as [callers]; `--depth`
     * walks the callee tree instead of the caller tree.
     */
    public fun calls(
        rawRef: String,
        roots: RootsSpec,
        options: CallOptions = CallOptions(),
    ): ServiceOutcome =
        callGraph(rawRef, roots, options, CallDirection.CALLS)

    private fun callGraph(
        rawRef: String,
        roots: RootsSpec,
        options: CallOptions,
        direction: CallDirection,
    ): ServiceOutcome {
        if (options.limit < 0) {
            return failure(3, rawRef, "usage error: --limit must be >= 0, got ${options.limit}")
        }
        if (options.depth < 1) {
            return failure(3, rawRef, "usage error: --depth must be >= 1, got ${options.depth}")
        }
        if (options.externalOnly && direction == CallDirection.CALLERS) {
            return failure(3, rawRef, "usage error: --external-only is a calls flag (callers has no artifact to be external to)")
        }
        val parsed = SymbolRefParser.parse(rawRef)
        if (parsed is SymbolRefParseResult.Failure) {
            return failure(
                3,
                rawRef,
                "usage error: invalid reference '$rawRef': ${parsed.message} at column ${parsed.position}",
            )
        }
        val ref = (parsed as SymbolRefParseResult.Ok).ref
        if (ref is TypeSymbolRef) {
            return failure(
                3,
                rawRef,
                "usage error: ${direction.flag} takes a member reference like " +
                    "'com.example.Foo#bar()', got type '$rawRef'",
            )
        }
        if (ref is PackageSymbolRef || ref is ModuleSymbolRef) {
            return failure(3, rawRef, "usage error: ${direction.flag} takes a member reference, got '$rawRef'")
        }
        val memberRef = ref as MemberSymbolRef
        if (memberRef.name == "<clinit>" && direction == CallDirection.CALLERS) {
            return failure(3, rawRef, "usage error: static initialisers are never called: '$rawRef'")
        }
        val coordinate = memberRef.coordinate
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk && coordinate == null) {
            return failure(
                4,
                rawRef,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        // A `g:a:v/` prefix scopes the query to one artifact (T-019): its jar
        // reads first (shadowing order), and candidates match inside it only —
        // while the edge scan still covers the full workspace behind it.
        var scopedRoots = roots
        var candidateScope: Set<String>? = null
        if (coordinate != null) {
            val coordText = "${coordinate.group}:${coordinate.artifact}:${coordinate.version}"
            val outcome = try {
                roots.mavenResolve(coordText, roots.allowFetch)
            } catch (e: Exception) {
                return failure(
                    6,
                    rawRef,
                    "internal error: coordinate resolution failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            val artifact = when (outcome) {
                is MavenResolver.Outcome.Resolved -> outcome.artifact
                is MavenResolver.Outcome.Unresolved ->
                    return failure(5, rawRef, "artifact read error: ${outcome.message}")
            }
            val binarySpec = artifact.binaryJar.toString()
            val scope = try {
                ArtifactLoader.open(artifact.binaryJar).use { root -> root.classEntryPaths().map(::entryToBinary).toSet() }
            } catch (e: ArtifactReadException) {
                return failure(5, rawRef, e.message ?: "artifact read error")
            } catch (e: Exception) {
                return failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
            }
            scopedRoots = roots.copy(jarSpecs = listOf(binarySpec) + roots.jarSpecs)
            candidateScope = scope
        }
        return try {
            executeCallGraph(memberRef, rawRef, scopedRoots, options, direction, candidateScope)
        } catch (e: ArtifactReadException) {
            failure(5, rawRef, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    /**
     * Serves `callers`/`calls`: resolves the target method from bytecode
     * (D-009), scans every class in every open root once for `METHOD_CALL`
     * edges, and walks the tree in memory to [CallOptions.depth]. Unreadable
     * classes warn once each ([WarningCode.CORRUPT_CLASS]) and are skipped —
     * one bad entry never aborts the scan (D-017).
     */
    private fun executeCallGraph(
        ref: MemberSymbolRef,
        rawRef: String,
        roots: RootsSpec,
        options: CallOptions,
        direction: CallDirection,
        candidateScope: Set<String>? = null,
    ): ServiceOutcome {
        val declaring = ref.declaringType as? TypeName.ClassType
            ?: return failure(3, rawRef, "usage error: ${direction.flag} takes a class member, got '$rawRef'")
        val opened = openRoots(roots)
        try {
            val binariesByRoot = opened.map { it.root.classEntryPaths().map(::entryToBinary).toSet() }
            val providers = mutableMapOf<String, MutableList<Int>>()
            binariesByRoot.forEachIndexed { index, binaries ->
                for (binary in binaries) providers.getOrPut(binary) { mutableListOf() }.add(index)
            }
            val allBinaries = providers.keys

            val candidates = matchCandidates(declaring, candidateScope ?: allBinaries)
            if (candidates.isEmpty()) {
                val suggestions = suggestSimilar(declaring.simpleName, allBinaries)
                return ServiceOutcome.Failure(ErrorResult.notFound(rawRef, suggestions))
            }
            if (candidates.size > 1) {
                return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, candidates))
            }
            val binary = candidates.single()
            val winner = providers.getValue(binary).first()

            val warnings = mutableListOf<Warning>()
            warnings.addAll(roots.extraWarnings)
            for (open in opened) warnings.addAll(open.root.warnings)
            val extraProviders = providers.getValue(binary).drop(1)
            if (extraProviders.isNotEmpty()) {
                val names = listOf(winner).plus(extraProviders).map { rootLabel(opened[it], binary) }
                warnings.add(
                    Warning(
                        code = WarningCode.DUPLICATE_FQN,
                        message = "$binary is provided by ${names.joinToString(", ")}; " +
                            "showing ${names.first()} (classpath order)",
                        subject = binary,
                    ),
                )
            }

            val workspace = Workspace(opened, providers, warnings)
            val target = workspace.load(binary)
            if (target == null) {
                return failure(
                    5,
                    rawRef,
                    "artifact read error: $binary in ${rootLabel(opened[winner], binary)} cannot be parsed",
                )
            }

            // The target method set is structural (D-009): a method proven
            // absent from bytecode reports did-you-mean instead of an empty
            // tree, and a field reports the usages redirect instead of one —
            // fields have no call hierarchy. `<clinit>` reaches here only via
            // `calls` (callers rejects it up front): it matches no
            // `matchBytecodeMembers` row by design, so it is read off the
            // class directly — static initialisers do call out.
            val bytecodeMethods = matchBytecodeMembers(target, ref).filterIsInstance<BytecodeMember.Method>()
            val rootMethods = if (bytecodeMethods.isNotEmpty()) {
                bytecodeMethods.map { it.info }
            } else if (ref.name == "<clinit>") {
                target.methods.filter { it.name == "<clinit>" }
            } else if (matchBytecodeMembers(target, ref).isEmpty()) {
                return ServiceOutcome.Failure(
                    ErrorResult.notFound(rawRef, suggestSimilarMember(target, ref.name)),
                )
            } else {
                return failure(
                    3,
                    rawRef,
                    "usage error: fields have no call hierarchy: '$rawRef' " +
                        "(find reads and writes: jdx usages '$rawRef')",
                )
            }
            if (rootMethods.isEmpty()) {
                return ServiceOutcome.Failure(
                    ErrorResult.notFound(rawRef, suggestSimilarMember(target, ref.name)),
                )
            }
            // Overload-blind at the root unless the ref carries parameters —
            // the usages rule (D-042 §5). Tree nodes below the root are always
            // descriptor-specific, so sibling overloads render as distinct rows.
            val rootDescriptors = if (ref.parameterTypes != null) {
                rootMethods.map { it.descriptor.descriptor }.toSet()
            } else {
                null
            }
            val canonicalTarget = SymbolRefPrinter.print(MemberSymbolRef(target.name, ref.name))

            val targetArtifact = rootLabel(opened[winner], binary)
            val scanned = scanCallEdges(opened, warnings)
            val tree = if (direction == CallDirection.CALLERS) {
                expandCallers(
                    owner = binary,
                    name = ref.name,
                    descriptors = rootDescriptors,
                    scanned = scanned,
                    options = options,
                    targetArtifact = targetArtifact,
                    path = emptySet(),
                    remaining = options.depth,
                )
            } else {
                expandCallees(
                    seeds = rootMethods.map { ExactMethod(binary, ref.name, it.descriptor.descriptor) },
                    scanned = scanned,
                    providers = providers,
                    opened = opened,
                    options = options,
                    targetArtifact = targetArtifact,
                    path = rootMethods.map {
                        CallNodeKey(binary, ref.name, it.descriptor.descriptor, targetArtifact)
                    }.toSet(),
                    remaining = options.depth,
                )
            }

            if (tree.isEmpty()) {
                val noun = if (direction == CallDirection.CALLERS) "callers" else "calls"
                val preposition = if (direction == CallDirection.CALLERS) "of" else "from"
                return ServiceOutcome.Failure(
                    ErrorResult.notFound(
                        rawRef,
                        emptyList(),
                        detail = "no $noun $preposition '$canonicalTarget' in the workspace",
                    ),
                )
            }
            val provenance = listOf(
                Provenance(
                    artifact = targetArtifact,
                    origin = if (opened[winner].root.kind == ArtifactKind.JRT) Origin.JRT else Origin.BYTECODE,
                ),
            )
            val listing = buildCallListing(
                query = rawRef,
                targetRef = canonicalTarget,
                direction = direction,
                roots = tree,
                limit = options.limit,
                warnings = warnings.sortedBy { it.code },
                provenance = provenance,
            )
            return ServiceOutcome.CallGraph(listing)
        } finally {
            opened.forEach { it.root.close() }
        }
    }

    /** One `METHOD_CALL` edge plus the artifact label of the class holding it. */
    private data class ScannedCall(val edge: ReferenceEdge, val label: String)

    /** An exact method: the tree's expansion unit below the (possibly blind) root. */
    private data class ExactMethod(val owner: String, val name: String, val descriptor: String)

    /**
     * Identity of one displayed tree row: the exact method plus the artifact
     * row it came from — one FQN provided by two roots renders as two rows
     * (mirroring `usages`), and cycle detection treats them as distinct.
     */
    private data class CallNodeKey(
        val owner: String,
        val name: String,
        val descriptor: String,
        val label: String?,
    )

    /**
     * Extracts every `METHOD_CALL` edge in the workspace once: both tree
     * directions walk this list in memory instead of re-reading class bytes
     * per level. Unreadable classes warn once each and contribute nothing.
     */
    private fun scanCallEdges(opened: List<OpenRoot>, warnings: MutableList<Warning>): List<ScannedCall> {
        val scanned = mutableListOf<ScannedCall>()
        val reported = mutableSetOf<String>()
        for (open in opened) {
            for (entry in open.root.classEntryPaths()) {
                val fromBinary = entryToBinary(entry)
                val label = rootLabel(open, fromBinary)
                val bytes = try {
                    open.root.openClass(entry).use { it.readBytes() }
                } catch (e: Exception) {
                    if (reported.add(fromBinary)) {
                        warnings.add(
                            Warning(
                                code = WarningCode.CORRUPT_CLASS,
                                message = "cannot read $fromBinary from ${open.root.displayName}: " +
                                    "${e.message ?: e.javaClass.simpleName}",
                                subject = fromBinary,
                            ),
                        )
                    }
                    continue
                }
                for (edge in ReferenceExtractor.extract(bytes)) {
                    if (edge.kind != ReferenceKind.METHOD_CALL) continue
                    scanned.add(ScannedCall(edge, label))
                }
            }
        }
        return scanned
    }

    /**
     * Whether a tree row survives the display filters: artifact labels scope
     * by `--in`/`--exclude`, and `calls --external-only` prunes callees in the
     * query target's own artifact. Rows with no known provider (edges naming
     * classes outside the workspace) always show — there is nothing to match.
     */
    private fun keepCallNode(label: String?, options: CallOptions, targetArtifact: String): Boolean {
        if (label == null) return true
        if (options.externalOnly && label == targetArtifact) return false
        if (!matchesArtifactFilter(options.inArtifact, label)) return false
        if (options.exclude != null && matchesArtifactFilter(options.exclude, label)) return false
        return true
    }

    private fun callerLabel(providers: Map<String, List<Int>>, opened: List<OpenRoot>, binary: String): String? =
        providers[binary]?.firstOrNull()?.let { rootLabel(opened[it], binary) }

    /**
     * One `callers` level: distinct callers of (`owner`, `name`,
     * `descriptors`) — `descriptors == null` is the overload-blind root,
     * deeper levels pass the exact singleton — sorted by label then ref,
     * pruned by the display filters, cycle-marked against [path], expanded
     * while [remaining] allows.
     */
    private fun expandCallers(
        owner: String,
        name: String,
        descriptors: Set<String>?,
        scanned: List<ScannedCall>,
        options: CallOptions,
        targetArtifact: String,
        path: Set<CallNodeKey>,
        remaining: Int,
    ): List<CallNode> {
        val seeds = scanned
            .filter { call ->
                call.edge.toOwner == owner && call.edge.toMember == name &&
                    (descriptors == null || call.edge.toDescriptor in descriptors)
            }
            .map { call ->
                val descriptor = call.edge.toDescriptor
                if (descriptor == null) return@map null
                Triple(call.edge.fromClass, call.edge.fromMember, call.edge.fromDescriptor) to call.label
            }
            .filterNotNull()
            .distinct()
            .filter { (_, label) -> keepCallNode(label, options, targetArtifact) }
            .sortedWith(compareBy({ it.second }, { it.first.first }, { it.first.second }, { it.first.third }))
        return seeds.map { (from, label) ->
            val key = CallNodeKey(from.first, from.second, from.third, label)
            val ref = canonicalFromRef(from.first, from.second, from.third)
            if (key in path) {
                CallNode(ref = ref, artifact = label, cycle = true)
            } else {
                CallNode(
                    ref = ref,
                    artifact = label,
                    children = if (remaining > 1) {
                        expandCallers(
                            owner = from.first,
                            name = from.second,
                            descriptors = setOf(from.third),
                            scanned = scanned,
                            options = options,
                            targetArtifact = targetArtifact,
                            path = path + key,
                            remaining = remaining - 1,
                        )
                    } else {
                        emptyList()
                    },
                )
            }
        }
    }

    /**
     * One `calls` level: the union of [seeds]' outgoing edges, grouped by
     * exact callee so two overloads called from one method render as distinct
     * rows. Same sorting, pruning, cycle and depth rules as [expandCallers].
     */
    private fun expandCallees(
        seeds: List<ExactMethod>,
        scanned: List<ScannedCall>,
        providers: Map<String, List<Int>>,
        opened: List<OpenRoot>,
        options: CallOptions,
        targetArtifact: String,
        path: Set<CallNodeKey>,
        remaining: Int,
    ): List<CallNode> {
        val seedSet = seeds.toSet()
        val groups = scanned
            .filter { call ->
                ExactMethod(call.edge.fromClass, call.edge.fromMember, call.edge.fromDescriptor) in seedSet
            }
            .groupBy { call ->
                Triple(call.edge.toOwner, call.edge.toMember, call.edge.toDescriptor)
            }
        val ordered = groups.entries
            .mapNotNull { (to, calls) ->
                val member = to.second ?: return@mapNotNull null
                val descriptor = to.third ?: return@mapNotNull null
                val label = callerLabel(providers, opened, to.first)
                if (!keepCallNode(label, options, targetArtifact)) return@mapNotNull null
                Triple(ExactMethod(to.first, member, descriptor), label, edgeTargetRef(calls.first().edge))
            }
            .sortedWith(compareBy({ it.second ?: "" }, { it.third }))
        return ordered.map { (callee, label, ref) ->
            val key = CallNodeKey(callee.owner, callee.name, callee.descriptor, label)
            if (key in path) {
                CallNode(ref = ref, artifact = label, cycle = true)
            } else {
                CallNode(
                    ref = ref,
                    artifact = label,
                    children = if (remaining > 1) {
                        expandCallees(
                            seeds = listOf(callee),
                            scanned = scanned,
                            providers = providers,
                            opened = opened,
                            options = options,
                            targetArtifact = targetArtifact,
                            path = path + key,
                            remaining = remaining - 1,
                        )
                    } else {
                        emptyList()
                    },
                )
            }
        }
    }

    // -- samples (T-034) -----------------------------------------------------------

    /**
     * Options for `samples`: how many ranked examples to show and how much of
     * the workspace to cover. [limit] defaults to 3 (PROPOSAL.md §7.3);
     * [preferSources] ranks callers from sources-paired artifacts first.
     */
    public data class SampleOptions(
        public val limit: Int = DEFAULT_SAMPLES_LIMIT,
        public val inArtifact: String? = null,
        public val exclude: String? = null,
        public val preferSources: Boolean = false,
    )

    /**
     * Answers `samples <symbol>`: ranked usage examples with enclosing-method
     * source snippets when paired sources exist.
     *
     * Live-roots scan (D-043 precedent): every class's `METHOD_CALL` edges are
     * extracted once with the T-029 [ReferenceExtractor], ranked in memory by
     * exemplariness, and source-sliced through the T-021 seam — no persistent
     * index read yet (indexed acceleration lands with the daemon/`jdx index`
     * work). Structure is bytecode-authoritative (D-009): the target resolves
     * with the T-011 machinery before any scan, so a typo reports
     * did-you-mean instead of an empty answer. Matching is overload-blind at
     * the root unless the ref carries a parameter list (the usages rule,
     * D-042 §5).
     */
    public fun samples(
        rawRef: String,
        roots: RootsSpec,
        options: SampleOptions = SampleOptions(),
    ): ServiceOutcome {
        if (options.limit < 0) {
            return failure(3, rawRef, "usage error: --limit must be >= 0, got ${options.limit}")
        }
        val parsed = SymbolRefParser.parse(rawRef)
        if (parsed is SymbolRefParseResult.Failure) {
            return failure(
                3,
                rawRef,
                "usage error: invalid reference '$rawRef': ${parsed.message} at column ${parsed.position}",
            )
        }
        val ref = (parsed as SymbolRefParseResult.Ok).ref
        if (ref is PackageSymbolRef || ref is ModuleSymbolRef) {
            return failure(3, rawRef, "usage error: samples takes a type or member reference, got '$rawRef'")
        }
        if (ref is MemberSymbolRef && ref.name == "<clinit>") {
            return failure(3, rawRef, "usage error: static initialisers are never invoked: '$rawRef'")
        }
        val coordinate = (ref as? MemberSymbolRef)?.coordinate ?: (ref as? TypeSymbolRef)?.coordinate
        if (roots.jarSpecs.isEmpty() && !roots.includeJdk && coordinate == null) {
            return failure(
                4,
                rawRef,
                "no workspace: no --jars given, no workspace selected (-w <name>, " +
                    "JDX_WORKSPACE, jdx ws use) and --no-jdk set " +
                    "(pass --jars <path>, select a workspace, or drop --no-jdk)",
            )
        }
        // A `g:a:v/` prefix scopes the query to one artifact (T-019): its jar
        // reads first (shadowing order), and candidates match inside it only —
        // while the edge scan still covers the full workspace behind it.
        var scopedRoots = roots
        var candidateScope: Set<String>? = null
        if (coordinate != null) {
            val coordText = "${coordinate.group}:${coordinate.artifact}:${coordinate.version}"
            val outcome = try {
                roots.mavenResolve(coordText, roots.allowFetch)
            } catch (e: Exception) {
                return failure(
                    6,
                    rawRef,
                    "internal error: coordinate resolution failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            val artifact = when (outcome) {
                is MavenResolver.Outcome.Resolved -> outcome.artifact
                is MavenResolver.Outcome.Unresolved ->
                    return failure(5, rawRef, "artifact read error: ${outcome.message}")
            }
            val binarySpec = artifact.binaryJar.toString()
            val scope = try {
                ArtifactLoader.open(artifact.binaryJar).use { root -> root.classEntryPaths().map(::entryToBinary).toSet() }
            } catch (e: ArtifactReadException) {
                return failure(5, rawRef, e.message ?: "artifact read error")
            } catch (e: Exception) {
                return failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
            }
            scopedRoots = roots.copy(jarSpecs = listOf(binarySpec) + roots.jarSpecs)
            candidateScope = scope
        }
        return try {
            executeSamples(ref, rawRef, scopedRoots, options, candidateScope)
        } catch (e: ArtifactReadException) {
            failure(5, rawRef, e.message ?: "artifact read error")
        } catch (e: Exception) {
            failure(6, rawRef, "internal error: ${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        }
    }

    /**
     * Serves `samples`: resolves the target type or method from bytecode
     * (D-009), scans every class in every open root once for `METHOD_CALL`
     * edges to it, ranks the calling methods by exemplariness, and slices the
     * displayed callers' bodies through their own paired sources. Unreadable
     * classes warn once each ([WarningCode.CORRUPT_CLASS]) and are skipped —
     * one bad entry never aborts the scan (D-017). Callers without sources
     * render as snippet-less rows, never as failures.
     */
    private fun executeSamples(
        ref: SymbolRef,
        rawRef: String,
        roots: RootsSpec,
        options: SampleOptions,
        candidateScope: Set<String>? = null,
    ): ServiceOutcome {
        val declaring = when (ref) {
            is MemberSymbolRef -> ref.declaringType as? TypeName.ClassType
            is TypeSymbolRef -> ref.type as? TypeName.ClassType
            else -> null
        } ?: return failure(3, rawRef, "usage error: samples takes a type or member reference, got '$rawRef'")
        val opened = openRoots(roots)
        // Paired sources per root, opened lazily: presence is the cheap
        // `--prefer-sources` signal (no parsing), the bodies behind them slice
        // only the displayed rows. Every opened root closes here.
        val sourceCache = mutableMapOf<Int, dev.jdx.sources.SourceRoot?>()
        // One ambient Kotlin parser for the displayed snippets (T-039, best
        // effort): opened lazily-cheap, parsed only when a `.kt` caller
        // actually needs a slice. No options seam — snippet-less rows are
        // the honest degradation either way.
        val ktParser = openKotlinParserFor(null)
        try {
            val binariesByRoot = opened.map { it.root.classEntryPaths().map(::entryToBinary).toSet() }
            val providers = mutableMapOf<String, MutableList<Int>>()
            binariesByRoot.forEachIndexed { index, binaries ->
                for (binary in binaries) providers.getOrPut(binary) { mutableListOf() }.add(index)
            }
            val allBinaries = providers.keys

            val candidates = matchCandidates(declaring, candidateScope ?: allBinaries)
            if (candidates.isEmpty()) {
                val suggestions = suggestSimilar(declaring.simpleName, allBinaries)
                return ServiceOutcome.Failure(ErrorResult.notFound(rawRef, suggestions))
            }
            if (candidates.size > 1) {
                return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, candidates))
            }
            val binary = candidates.single()
            val winner = providers.getValue(binary).first()

            val warnings = mutableListOf<Warning>()
            warnings.addAll(roots.extraWarnings)
            for (open in opened) warnings.addAll(open.root.warnings)
            val extraProviders = providers.getValue(binary).drop(1)
            if (extraProviders.isNotEmpty()) {
                val names = listOf(winner).plus(extraProviders).map { rootLabel(opened[it], binary) }
                warnings.add(
                    Warning(
                        code = WarningCode.DUPLICATE_FQN,
                        message = "$binary is provided by ${names.joinToString(", ")}; " +
                            "showing ${names.first()} (classpath order)",
                        subject = binary,
                    ),
                )
            }

            val workspace = Workspace(opened, providers, warnings)
            val target = workspace.load(binary)
            if (target == null) {
                return failure(
                    5,
                    rawRef,
                    "artifact read error: $binary in ${rootLabel(opened[winner], binary)} cannot be parsed",
                )
            }

            // The target set is structural (D-009): a member proven absent
            // from bytecode reports did-you-mean, and a field reports the
            // usages redirect — fields have no call sites to exemplify.
            // Overload-blind unless the ref carries parameters (D-042 §5).
            val memberName: String?
            val memberDescriptors: Set<String>?
            val canonicalTarget: String
            if (ref is MemberSymbolRef) {
                val methods = matchBytecodeMembers(target, ref).filterIsInstance<BytecodeMember.Method>()
                if (methods.isEmpty()) {
                    if (matchBytecodeMembers(target, ref).isEmpty()) {
                        return ServiceOutcome.Failure(
                            ErrorResult.notFound(rawRef, suggestSimilarMember(target, ref.name)),
                        )
                    }
                    return failure(
                        3,
                        rawRef,
                        "usage error: fields have no usage examples: '$rawRef' " +
                            "(find reads and writes: jdx usages '$rawRef')",
                    )
                }
                memberName = ref.name
                memberDescriptors = if (ref.parameterTypes != null) {
                    methods.map { it.info.descriptor.descriptor }.toSet()
                } else {
                    null
                }
                canonicalTarget = SymbolRefPrinter.print(MemberSymbolRef(target.name, ref.name))
            } else {
                memberName = null
                memberDescriptors = null
                canonicalTarget = binary
            }

            val targetArtifact = rootLabel(opened[winner], binary)
            val calls = scanSampleEdges(opened, warnings, options)
            // One example per calling method and artifact label (a class
            // provided by two roots renders once per root, mirroring
            // `usages`): multiple edges from one caller to several overloads
            // collapse to the fullest overload touched.
            val grouped = calls
                .filter { call ->
                    if (call.edge.toOwner != binary) return@filter false
                    if (memberName != null) {
                        if (call.edge.toMember != memberName) return@filter false
                        if (memberDescriptors != null &&
                            (call.edge.toDescriptor == null || call.edge.toDescriptor !in memberDescriptors)
                        ) {
                            return@filter false
                        }
                    }
                    true
                }
                .groupBy { call ->
                    SampleCaller(
                        fromClass = call.edge.fromClass,
                        fromMember = call.edge.fromMember,
                        fromDescriptor = call.edge.fromDescriptor,
                        label = call.label,
                    )
                }
            if (grouped.isEmpty()) {
                return ServiceOutcome.Failure(
                    ErrorResult.notFound(
                        rawRef,
                        emptyList(),
                        detail = "no samples of '$canonicalTarget' in the workspace",
                    ),
                )
            }
            val ranked = grouped.map { (caller, edges) ->
                val fullest = edges.maxBy { targetParamCount(it.edge.toDescriptor) }
                val rootIndex = providers[caller.fromClass]?.firstOrNull()
                val fromRef = canonicalFromRef(caller.fromClass, caller.fromMember, caller.fromDescriptor)
                RankedSample(
                    fromRef = fromRef,
                    label = caller.label,
                    targetRef = edgeTargetRef(fullest.edge),
                    paramCount = targetParamCount(fullest.edge.toDescriptor),
                    hasSources = hasSampleSources(opened, sourceCache, rootIndex),
                    rootIndex = rootIndex,
                    fromClass = caller.fromClass,
                    fromMember = caller.fromMember,
                )
            }.sortedWith(
                compareBy(
                    { sampleOrderKey(it.fromRef, it.paramCount, options.preferSources, it.hasSources) },
                    { it.label },
                    { it.targetRef },
                ),
            )
            // Snippets slice only the displayed prefix: parsing a Java file
            // per workspace-wide hit would turn a 3-row answer into a
            // whole-corpus parse. Rows past the limit never render, so their
            // absent snippets are invisible (D-007 holds over shown rows).
            val effectiveLimit = options.limit.coerceAtLeast(0)
            val hits = ranked.mapIndexed { index, sample ->
                SampleHit(
                    fromRef = sample.fromRef,
                    artifact = sample.label,
                    targetRef = sample.targetRef,
                    snippet = if (index < effectiveLimit) {
                        snippetForCaller(sample.fromClass, sample.fromMember, sample.rootIndex, opened, sourceCache, ktParser)
                    } else {
                        null
                    },
                )
            }
            val provenance = listOf(
                Provenance(
                    artifact = targetArtifact,
                    origin = if (opened[winner].root.kind == ArtifactKind.JRT) Origin.JRT else Origin.BYTECODE,
                ),
            )
            return ServiceOutcome.SampleList(
                buildSampleListing(
                    query = rawRef,
                    targetRef = canonicalTarget,
                    hits = hits,
                    limit = options.limit,
                    warnings = warnings.sortedBy { it.code },
                    provenance = provenance,
                ),
            )
        } finally {
            sourceCache.values.forEach { runCatching { it?.close() } }
            runCatching { ktParser.close() }
            opened.forEach { it.root.close() }
        }
    }

    /** One calling method in one artifact: the grouping key for examples. */
    private data class SampleCaller(
        val fromClass: String,
        val fromMember: String,
        val fromDescriptor: String,
        val label: String,
    )

    /** One `METHOD_CALL` edge plus the artifact label of the class holding it. */
    private data class ScannedSample(val edge: ReferenceEdge, val label: String)

    /** One ranked example before snippet slicing. */
    private data class RankedSample(
        val fromRef: String,
        val label: String,
        val targetRef: String,
        val paramCount: Int,
        val hasSources: Boolean,
        val rootIndex: Int?,
        val fromClass: String,
        val fromMember: String,
    )

    /**
     * Extracts every `METHOD_CALL` edge in the workspace once, honouring the
     * display filters. Unreadable classes warn once each and contribute
     * nothing. Source dirs (`--src`) are not scanned: textual mentions have
     * no enclosing method to render (documented in D-047).
     */
    private fun scanSampleEdges(
        opened: List<OpenRoot>,
        warnings: MutableList<Warning>,
        options: SampleOptions,
    ): List<ScannedSample> {
        val scanned = mutableListOf<ScannedSample>()
        val reported = mutableSetOf<String>()
        for (open in opened) {
            for (entry in open.root.classEntryPaths()) {
                val fromBinary = entryToBinary(entry)
                val label = rootLabel(open, fromBinary)
                if (!matchesArtifactFilter(options.inArtifact, label) ||
                    (options.exclude != null && matchesArtifactFilter(options.exclude, label))
                ) {
                    continue
                }
                val bytes = try {
                    open.root.openClass(entry).use { it.readBytes() }
                } catch (e: Exception) {
                    if (reported.add(fromBinary)) {
                        warnings.add(
                            Warning(
                                code = WarningCode.CORRUPT_CLASS,
                                message = "cannot read $fromBinary from ${open.root.displayName}: " +
                                    "${e.message ?: e.javaClass.simpleName}",
                                subject = fromBinary,
                            ),
                        )
                    }
                    continue
                }
                for (edge in ReferenceExtractor.extract(bytes)) {
                    if (edge.kind != ReferenceKind.METHOD_CALL) continue
                    scanned.add(ScannedSample(edge, label))
                }
            }
        }
        return scanned
    }

    /** Erased parameter count of a method descriptor; hostile bytes count zero, never throw. */
    private fun targetParamCount(descriptor: String?): Int {
        if (descriptor == null) return 0
        return runCatching {
            (JvmDescriptor.parse(descriptor) as? JvmDescriptor.Method)?.parameters?.size ?: 0
        }.getOrDefault(0)
    }

    /**
     * Whether the caller's root pairs sources: the cheap `--prefer-sources`
     * signal (root presence, no parsing). Roots are opened once into
     * [sourceCache] and closed by the caller. Never throws: an unreadable
     * sources root reads as absent, and the row degrades to snippet-less.
     */
    private fun hasSampleSources(
        opened: List<OpenRoot>,
        sourceCache: MutableMap<Int, dev.jdx.sources.SourceRoot?>,
        rootIndex: Int?,
    ): Boolean {
        if (rootIndex == null) return false
        return runCatching {
            sourceCache.getOrPut(rootIndex) { openSourcesFor(opened[rootIndex]) } != null
        }.getOrDefault(false)
    }

    /**
     * Slices the caller's enclosing method through its own paired sources
     * (best effort): any miss — no sources root, no file for the class,
     * unparseable member — yields `null` and the row renders snippet-less.
     * `.kt` callers slice through the shared [ktParser] (T-039); an
     * unavailable parser reads snippet-less too. Never throws.
     */
    private fun snippetForCaller(
        fromClass: String,
        fromMember: String,
        rootIndex: Int?,
        opened: List<OpenRoot>,
        sourceCache: MutableMap<Int, dev.jdx.sources.SourceRoot?>,
        ktParser: dev.jdx.sources.KotlinSourceParser? = null,
    ): SampleSnippet? {
        if (fromMember == "<clinit>") return null
        if (rootIndex == null) return null
        return runCatching {
            val sources = sourceCache.getOrPut(rootIndex) { openSourcesFor(opened[rootIndex]) }
                ?: return@runCatching null
            val declaring = (
                runCatching { typeNameFromBinaryName(fromClass) }.getOrNull() as? TypeName.ClassType
                ) ?: return@runCatching null
            val lookup = MemberSymbolRef(declaringType = declaring, name = fromMember)
            val found = when (
                val java = dev.jdx.sources.findJavaBodies(sources, lookup)
            ) {
                is dev.jdx.sources.JavaBodyResult.NoSource,
                is dev.jdx.sources.JavaBodyResult.NotJava,
                -> if (ktParser != null) {
                    dev.jdx.sources.findKotlinBodies(sources, lookup, ktParser)
                } else {
                    java
                }
                else -> java
            }
            when (found) {
                is dev.jdx.sources.JavaBodyResult.Found -> {
                    val body = found.bodies.firstOrNull() ?: return@runCatching null
                    SampleSnippet(
                        file = body.file,
                        startLine = body.startLine,
                        endLine = body.endLine,
                        lines = body.text.split("\n"),
                        truncated = false,
                    )
                }
                else -> null
            }
        }.getOrNull()
    }

    // -- signature execution (T-024) -----------------------------------------------

    /**
     * Serves `signature <member>`: resolves the declaring type from bytecode
     * (D-009) with the T-011 machinery, matches members with the shared
     * bytecode matcher, and renders one [SignatureLines] line per overload in
     * declaration order.
     */
    private fun executeSignature(
        memberRef: MemberSymbolRef,
        rawRef: String,
        roots: RootsSpec,
        options: SignatureOptions,
        candidateScope: Set<String>? = null,
    ): ServiceOutcome {
        val declaring = memberRef.declaringType as? TypeName.ClassType
            ?: return failure(3, rawRef, "usage error: signature takes a class member, got '$rawRef'")
        val opened = openRoots(roots)
        try {
            val binariesByRoot = opened.map { it.root.classEntryPaths().map(::entryToBinary).toSet() }
            val providers = mutableMapOf<String, MutableList<Int>>()
            binariesByRoot.forEachIndexed { index, binaries ->
                for (binary in binaries) providers.getOrPut(binary) { mutableListOf() }.add(index)
            }
            val allBinaries = providers.keys

            val candidates = matchCandidates(declaring, candidateScope ?: allBinaries)
            if (candidates.isEmpty()) {
                val suggestions = suggestSimilar(declaring.simpleName, allBinaries)
                return ServiceOutcome.Failure(ErrorResult.notFound(rawRef, suggestions))
            }
            if (candidates.size > 1) {
                return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, candidates))
            }
            val binary = candidates.single()
            val winner = providers.getValue(binary).first()

            val warnings = mutableListOf<Warning>()
            warnings.addAll(roots.extraWarnings)
            for (open in opened) warnings.addAll(open.root.warnings)
            val extraProviders = providers.getValue(binary).drop(1)
            if (extraProviders.isNotEmpty()) {
                val names = listOf(winner).plus(extraProviders).map { rootLabel(opened[it], binary) }
                warnings.add(
                    Warning(
                        code = WarningCode.DUPLICATE_FQN,
                        message = "$binary is provided by ${names.joinToString(", ")}; " +
                            "showing ${names.first()} (classpath order)",
                        subject = binary,
                    ),
                )
            }

            val workspace = Workspace(opened, providers, warnings)
            val target = workspace.load(binary)
            if (target == null) {
                return failure(
                    5,
                    rawRef,
                    "artifact read error: $binary in ${rootLabel(opened[winner], binary)} cannot be parsed",
                )
            }

            val bytecodeMatches = matchBytecodeMembers(target, memberRef, jvmView = options.view == MemberView.JVM)
                .filter { match -> options.includeSynthetic || !isSyntheticMember(match) }
            if (bytecodeMatches.isEmpty()) {
                if (memberRef.name == "<clinit>") {
                    return failure(3, rawRef, "usage error: static initialisers have no signature to show: '$rawRef'")
                }
                return ServiceOutcome.Failure(
                    ErrorResult.notFound(rawRef, suggestSimilarMember(target, memberRef.name, jvmView = options.view == MemberView.JVM)),
                )
            }

            val matchRefs = canonicalMemberRefs(target, bytecodeMatches, jvmView = options.view == MemberView.JVM)
            // Pair in declaration order first: `canonicalMemberRefs` sorts, and
            // zipping a sorted list against declaration-ordered matches swaps
            // refs whenever the orders differ (bridge/field siblings).
            val entries = bytecodeMatches.zip(orderedMemberRefs(target, bytecodeMatches, jvmView = options.view == MemberView.JVM)).map { (match, ref) ->
                when (match) {
                    is BytecodeMember.Method -> SignatureEntry(
                        canonicalRef = ref,
                        kind = if (match.info.name == "<init>") MemberKind.CONSTRUCTOR else MemberKind.METHOD,
                        signature = SignatureLines.methodLine(
                            member = match.info,
                            declaringSimpleName = target.name.simpleName,
                            kotlinView = kotlinViewOf(target, match.info, jvmView = options.view == MemberView.JVM),
                        ),
                        declaringType = binary,
                    )
                    is BytecodeMember.Field -> SignatureEntry(
                        canonicalRef = ref,
                        kind = MemberKind.FIELD,
                        signature = SignatureLines.fieldLine(match.info),
                        declaringType = binary,
                    )
                    // T-078 folded property: `property public final val T name`.
                    is BytecodeMember.Property -> SignatureEntry(
                        canonicalRef = ref,
                        kind = MemberKind.PROPERTY,
                        signature = SignatureLines.propertyLine(
                            access = match.view.access,
                            isVar = match.view.isVar,
                            typeText = match.view.displayType ?: "java.lang.Object",
                            propertyName = match.view.propertyName,
                        ),
                        declaringType = binary,
                    )
                }
            }
            val header = SymbolRefPrinter.print(MemberSymbolRef(target.name, memberRef.name))
            return ServiceOutcome.SignatureList(
                buildSignatureBlock(
                    query = header,
                    declaringType = binary,
                    entries = entries,
                    provenance = listOf(
                        Provenance(
                            artifact = rootLabel(opened[winner], binary),
                            origin = if (opened[winner].root.kind == ArtifactKind.JRT) Origin.JRT else Origin.BYTECODE,
                        ),
                    ),
                    warnings = warnings.sortedBy { it.code },
                    maxSignatures = options.maxSignatures,
                ),
            )
        } finally {
            opened.forEach { it.root.close() }
        }
    }

    /**
     * Bridge/synthetic members are compiler output, not source API (T-009):
     * hidden from `signature` unless `--include-synthetic`. The bridge bit
     * shares its mask with `VOLATILE`, so it is only meaningful on methods —
     * fields check `SYNTHETIC` alone (mirrors `MemberResolver`).
     */
    private fun isSyntheticMember(match: BytecodeMember): Boolean = when (match) {
        is BytecodeMember.Method ->
            match.info.access.has(AccessFlag.SYNTHETIC) || match.info.access.has(AccessFlag.BRIDGE)
        is BytecodeMember.Field ->
            match.info.access.has(AccessFlag.SYNTHETIC)
        // T-078 properties are source API, never synthetic.
        is BytecodeMember.Property -> false
    }

    // -- doc execution (T-025) -------------------------------------------------------

    /**
     * Serves `doc <symbol>`: resolves the declaring type from bytecode (D-009),
     * then renders the winning root's paired-sources javadoc — a member's own
     * comment, else the nearest documenting supertype's (methods only, D-037).
     */
    private fun executeDoc(
        ref: SymbolRef,
        rawRef: String,
        roots: RootsSpec,
        options: DocOptions,
        candidateScope: Set<String>? = null,
    ): ServiceOutcome {
        val declaring = when (ref) {
            is MemberSymbolRef -> ref.declaringType as? TypeName.ClassType
            is TypeSymbolRef -> ref.type as? TypeName.ClassType
            else -> null
        } ?: return failure(3, rawRef, "usage error: doc takes a class member or type, got '$rawRef'")
        val opened = openRoots(roots)
        // One Kotlin parser per command (T-039): cheap to open, and the
        // ~1 s PSI init happens at most once, on the first `.kt` parse.
        val ktParser = openKotlinParserFor(options.kotlinUserHome)
        try {
            val binariesByRoot = opened.map { it.root.classEntryPaths().map(::entryToBinary).toSet() }
            val providers = mutableMapOf<String, MutableList<Int>>()
            binariesByRoot.forEachIndexed { index, binaries ->
                for (binary in binaries) providers.getOrPut(binary) { mutableListOf() }.add(index)
            }
            val allBinaries = providers.keys

            val candidates = matchCandidates(declaring, candidateScope ?: allBinaries)
            if (candidates.isEmpty()) {
                val suggestions = suggestSimilar(declaring.simpleName, allBinaries)
                return ServiceOutcome.Failure(ErrorResult.notFound(rawRef, suggestions))
            }
            if (candidates.size > 1) {
                return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, candidates))
            }
            val binary = candidates.single()
            val winner = providers.getValue(binary).first()

            val warnings = mutableListOf<Warning>()
            warnings.addAll(roots.extraWarnings)
            for (open in opened) warnings.addAll(open.root.warnings)
            val extraProviders = providers.getValue(binary).drop(1)
            if (extraProviders.isNotEmpty()) {
                val names = listOf(winner).plus(extraProviders).map { rootLabel(opened[it], binary) }
                warnings.add(
                    Warning(
                        code = WarningCode.DUPLICATE_FQN,
                        message = "$binary is provided by ${names.joinToString(", ")}; " +
                            "showing ${names.first()} (classpath order)",
                        subject = binary,
                    ),
                )
            }

            val workspace = Workspace(opened, providers, warnings)
            val target = workspace.load(binary)
            if (target == null) {
                return failure(
                    5,
                    rawRef,
                    "artifact read error: $binary in ${rootLabel(opened[winner], binary)} cannot be parsed",
                )
            }

            return if (ref is MemberSymbolRef) {
                memberDocOutcome(ref, rawRef, binary, target, workspace, opened, providers, warnings, options, ktParser)
            } else {
                typeDocOutcome(binary, rawRef, target, opened, providers, warnings, options, ktParser)
            }
        } finally {
            opened.forEach { it.root.close() }
            runCatching { ktParser.close() }
        }
    }

    /**
     * Serves a member's doc: the member's own comment, else the nearest
     * documenting supertype's (methods only — fields hide and constructors
     * are never inherited, D-037). Overload ambiguity is structural (D-009):
     * decided from bytecode before any source is read.
     */
    private fun memberDocOutcome(
        memberRef: MemberSymbolRef,
        rawRef: String,
        binary: String,
        target: ClassInfo,
        workspace: Workspace,
        opened: List<OpenRoot>,
        providers: Map<String, List<Int>>,
        warnings: List<Warning>,
        options: DocOptions,
        // Shared Kotlin parser (T-039), opened once per `doc` command.
        ktParser: dev.jdx.sources.KotlinSourceParser,
    ): ServiceOutcome {
        val bytecodeMatches = matchBytecodeMembers(target, memberRef)
        if (bytecodeMatches.isEmpty()) {
            if (memberRef.name == "<clinit>") {
                return failure(3, rawRef, "usage error: static initialisers have no documentation to show: '$rawRef'")
            }
            return ServiceOutcome.Failure(
                ErrorResult.notFound(rawRef, suggestSimilarMember(target, memberRef.name)),
            )
        }
        val specified = memberRef.parameterTypes != null
        val matchRefs = canonicalMemberRefs(target, bytecodeMatches)
        if (!specified && bytecodeMatches.size > 1) {
            return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, matchRefs))
        }
        if (specified && bytecodeMatches.size > 1 && memberRef.returnType == null) {
            return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, matchRefs))
        }
        // Properties read their KDoc off the `KtProperty` declaration (T-039):
        // the flow below serves them — a JVM-spelled accessor query lands on
        // the property through the alias mapping, never the synthetic getter.
        // Only real methods inherit docs (D-037): the first non-synthetic one
        // (bridge pairs share one source declaration — either spelling walks).
        val primaryMethod = bytecodeMatches.filterIsInstance<BytecodeMember.Method>()
            .firstOrNull { it.info.name != "<init>" && !isSyntheticMember(it) }
            ?: bytecodeMatches.filterIsInstance<BytecodeMember.Method>().firstOrNull { it.info.name != "<init>" }

        val effectiveRef = memberRef.copy(declaringType = target.name)
        // Source lookup spellings: the query as written, plus — for generic
        // members queried in erased form — the generic signature's own
        // spellings, which is what the source text actually says (D-009).
        val singleMatch = bytecodeMatches.singleOrNull()
        val lookupRefs = listOf(effectiveRef) +
            (singleMatch?.let { genericSpelledRef(target, it) }?.takeIf { it != effectiveRef }?.let(::listOf).orEmpty())
        val canonicalRef = matchRefs.singleOrNull() ?: SymbolRefPrinter.print(
            MemberSymbolRef(
                declaringType = target.name,
                name = memberRef.name,
                parameterTypes = memberRef.parameterTypes,
            ),
        )
        val winner = providers.getValue(binary).first()
        val label = rootLabel(opened[winner], binary)
        val sources = openSourcesFor(opened[winner])
            ?: return ServiceOutcome.Failure(
                ErrorResult.notFound(
                    rawRef,
                    detail = "no sources for $binary in " +
                        "${rootLabel(opened[winner], binary)} " +
                        "(decompilation not yet implemented, T-026)",
                ),
            )
        try {
            var directDoc: dev.jdx.sources.SourceDoc? = null
            var memberNotFound = false
            val ktAliases = kotlinSourceAliases(target, bytecodeMatches.singleOrNull())
            for (lookupRef in lookupRefs) {
                // `.kt` flesh (T-039): same NoSource/NotJava → Kotlin mapping
                // as the body path.
                val found = when (
                    val java = dev.jdx.sources.findMemberDocs(sources, lookupRef)
                ) {
                    is dev.jdx.sources.JavaDocResult.NoSource,
                    is dev.jdx.sources.JavaDocResult.NotJava,
                    -> dev.jdx.sources.findKotlinMemberDocs(sources, lookupRef, ktParser, ktAliases)
                    else -> java
                }
                when (found) {
                    is dev.jdx.sources.JavaDocResult.Found -> {
                        if (!specified && found.docs.size > 1) {
                            return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, matchRefs))
                        }
                        directDoc = found.docs.singleOrNull()
                            ?: return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, matchRefs))
                        break
                    }
                    is dev.jdx.sources.JavaDocResult.MemberNotFound -> {
                        memberNotFound = true
                    }
                    is dev.jdx.sources.JavaDocResult.TypeNotFound,
                    is dev.jdx.sources.JavaDocResult.TypeUndocumented -> {
                        // Unreachable: member lookup reports unknown nested
                        // types as MemberNotFound — kept for exhaustiveness.
                        memberNotFound = true
                    }
                    is dev.jdx.sources.JavaDocResult.NoSource ->
                        return ServiceOutcome.Failure(
                            ErrorResult.notFound(
                                rawRef,
                                detail = "no sources for $binary in $label " +
                                    "(decompilation not yet implemented, T-026)",
                            ),
                        )
                    is dev.jdx.sources.JavaDocResult.NotJava ->
                        // Unreachable: the mapping above sends every `NotJava`
                        // through the Kotlin seam, which never emits it —
                        // kept for exhaustiveness.
                        return ServiceOutcome.Failure(
                            ErrorResult.notFound(
                                rawRef,
                                detail = "no sources for $binary in $label " +
                                    "(decompilation not yet implemented, T-026)",
                            ),
                        )
                    is dev.jdx.sources.JavaDocResult.ParserUnavailable ->
                        // No usable PSI (T-039): `doc` has no decompiled path
                        // (decompiled text carries no KDoc), so the install
                        // hint is the honest answer — `~`-relative and
                        // deterministic, never an absolute home path.
                        return ServiceOutcome.Failure(
                            ErrorResult.notFound(
                                rawRef,
                                detail = "$binary only ships Kotlin sources here " +
                                    "(${dev.jdx.sources.kotlinMissingHint()})",
                            ),
                        )
                    is dev.jdx.sources.JavaDocResult.ParseError ->
                        return failure(5, rawRef, found.message)
                }
            }
            // One supertype walk serves both fallbacks: the `{@inheritDoc}`
            // replacement inside the direct doc, and the inherited doc when
            // the direct comment is absent or renders to nothing.
            val inherited = if (options.inherit && primaryMethod != null) {
                findInheritedMemberDoc(target, memberRef, opened, providers, workspace, options.raw, ktParser)
            } else {
                null
            }
            if (directDoc != null) {
                val replacement = if (directDoc.rawComment.contains("{@inheritDoc")) inherited?.paragraph else null
                val lines = docLines(directDoc, options.raw, replacement)
                if (lines.isNotEmpty()) {
                    val allWarnings = warnings + listOfNotNull(mismatchWarning(target, sources, binary))
                    return docOutcome(
                        directDoc, sources.displayName, binary, canonicalRef,
                        docSubjectOf(directDoc.kind), allWarnings, options, lines, inheritedFrom = null,
                    )
                }
            }
            if (inherited != null) {
                val allWarnings = warnings + listOfNotNull(mismatchWarning(target, sources, binary))
                return docOutcome(
                    inherited.doc, inherited.displayName, binary, canonicalRef,
                    docSubjectOf(inherited.doc.kind), allWarnings, options, inherited.lines,
                    inheritedFrom = inherited.superBinary,
                )
            }
            check(memberNotFound || directDoc != null) { "lookup spellings exhausted without a terminal result" }
            val detail = if (directDoc != null || sourceDeclaresMember(sources, binary, memberRef.name, ktParser)) {
                "no javadoc comment for '$rawRef' in $label nor any documenting supertype"
            } else {
                "'$rawRef' has no source counterpart in $label " +
                    "(SOURCES_VERSION_MISMATCH)"
            }
            return ServiceOutcome.Failure(ErrorResult.notFound(rawRef, detail = detail))
        } finally {
            runCatching { sources.close() }
        }
    }

    /**
     * Serves a type's own doc. Types never inherit docs (D-037): a
     * superclass's class comment describes the superclass, and serving it
     * under the subclass's name would mislead.
     */
    private fun typeDocOutcome(
        binary: String,
        rawRef: String,
        target: ClassInfo,
        opened: List<OpenRoot>,
        providers: Map<String, List<Int>>,
        warnings: List<Warning>,
        options: DocOptions,
        // Shared Kotlin parser (T-039), opened once per `doc` command.
        ktParser: dev.jdx.sources.KotlinSourceParser,
    ): ServiceOutcome {
        val winner = providers.getValue(binary).first()
        val label = rootLabel(opened[winner], binary)
        val sources = openSourcesFor(opened[winner])
            ?: return ServiceOutcome.Failure(
                ErrorResult.notFound(
                    rawRef,
                    detail = "no sources for $binary in " +
                        "${rootLabel(opened[winner], binary)} " +
                        "(decompilation not yet implemented, T-026)",
                ),
            )
        try {
            // `.kt` flesh (T-039): same NoSource/NotJava → Kotlin mapping as
            // members — a Kotlin class in a shared file reports NoSource.
            val found = when (
                val java = dev.jdx.sources.findTypeDoc(sources, binary)
            ) {
                is dev.jdx.sources.JavaDocResult.NoSource,
                is dev.jdx.sources.JavaDocResult.NotJava,
                -> dev.jdx.sources.findKotlinTypeDoc(sources, binary, ktParser)
                else -> java
            }
            return when (found) {
                is dev.jdx.sources.JavaDocResult.Found -> {
                    val doc = found.docs.single()
                    val lines = docLines(doc, options.raw, inheritDocReplacement = null)
                    if (lines.isEmpty()) {
                        ServiceOutcome.Failure(
                            ErrorResult.notFound(
                                rawRef,
                                detail = "no javadoc comment for '$rawRef' in $label",
                            ),
                        )
                    } else {
                        val allWarnings = warnings + listOfNotNull(mismatchWarning(target, sources, binary))
                        docOutcome(
                            doc, sources.displayName, binary, binary,
                            docSubjectOf(doc.kind), allWarnings, options, lines, inheritedFrom = null,
                        )
                    }
                }
                is dev.jdx.sources.JavaDocResult.TypeNotFound,
                is dev.jdx.sources.JavaDocResult.MemberNotFound ->
                    ServiceOutcome.Failure(
                        ErrorResult.notFound(
                            rawRef,
                            detail = "$binary has no source counterpart in $label " +
                                "(SOURCES_VERSION_MISMATCH)",
                        ),
                    )
                is dev.jdx.sources.JavaDocResult.TypeUndocumented ->
                    ServiceOutcome.Failure(
                        ErrorResult.notFound(
                            rawRef,
                            detail = "no javadoc comment for '$rawRef' in $label",
                        ),
                    )
                is dev.jdx.sources.JavaDocResult.NoSource ->
                    ServiceOutcome.Failure(
                        ErrorResult.notFound(
                            rawRef,
                            detail = "no sources for $binary in $label " +
                                "(decompilation not yet implemented, T-026)",
                        ),
                    )
                is dev.jdx.sources.JavaDocResult.NotJava ->
                    // Unreachable: the mapping above sends every `NotJava`
                    // through the Kotlin seam, which never emits it — kept
                    // for exhaustiveness.
                    ServiceOutcome.Failure(
                        ErrorResult.notFound(
                            rawRef,
                            detail = "no sources for $binary in $label " +
                                "(decompilation not yet implemented, T-026)",
                        ),
                    )
                is dev.jdx.sources.JavaDocResult.ParserUnavailable ->
                    ServiceOutcome.Failure(
                        ErrorResult.notFound(
                            rawRef,
                            detail = "$binary only ships Kotlin sources here " +
                                "(${dev.jdx.sources.kotlinMissingHint()})",
                        ),
                    )
                is dev.jdx.sources.JavaDocResult.ParseError ->
                    failure(5, rawRef, found.message)
            }
        } finally {
            runCatching { sources.close() }
        }
    }

    /** One inherited method doc: the comment, its provider, and its rendered lines. */
    private data class InheritedDoc(
        val doc: dev.jdx.sources.SourceDoc,
        val superBinary: String,
        val displayName: String,
        val lines: List<String>,
    ) {
        /** The `{@inheritDoc}` replacement: the first paragraph as one line. */
        val paragraph: String
            get() = lines.takeWhile { it.isNotBlank() }.joinToString(" ").ifEmpty { lines.joinToString(" ") }
    }

    /**
     * Walks the supertype chain breadth-first (superclass then interfaces,
     * first-visit-wins — the [MemberResolver] linearisation order) for the
     * first supertype whose sources document the same erased member. Each
     * supertype is read from the root that provides *it* (not the query's
     * winning root), so cross-artifact hierarchies resolve honestly. A
     * supertype without readable or documenting sources is skipped, never
     * fatal — inheritance is a best-effort fallback.
     */
    private fun findInheritedMemberDoc(
        target: ClassInfo,
        memberRef: MemberSymbolRef,
        opened: List<OpenRoot>,
        providers: Map<String, List<Int>>,
        workspace: Workspace,
        raw: Boolean,
        // Shared Kotlin parser (T-039), or null to stay Java-only.
        ktParser: dev.jdx.sources.KotlinSourceParser? = null,
    ): InheritedDoc? {
        for (superInfo in supertypeChain(target, workspace::loadByName)) {
            val superBinary = superInfo.name.binaryName
            val superMatches = matchBytecodeMembers(superInfo, memberRef.copy(declaringType = superInfo.name))
            val superMethod = superMatches.filterIsInstance<BytecodeMember.Method>()
                .firstOrNull { it.info.name != "<init>" } ?: continue
            val rootIndex = providers[superBinary]?.firstOrNull() ?: continue
            val superSources = openSourcesFor(opened[rootIndex]) ?: continue
            try {
                val superRef = memberRef.copy(declaringType = superInfo.name)
                val refs = listOf(superRef) +
                    (genericSpelledRef(superInfo, superMethod)
                        ?.takeIf { it != superRef }
                        ?.let(::listOf).orEmpty())
                for (lookupRef in refs) {
                    val java = dev.jdx.sources.findMemberDocs(superSources, lookupRef)
                    val found = if (
                        (java is dev.jdx.sources.JavaDocResult.NoSource ||
                            java is dev.jdx.sources.JavaDocResult.NotJava) &&
                        ktParser != null
                    ) {
                        val superAliases = kotlinSourceAliases(
                            superInfo,
                            superMatches.singleOrNull(),
                        )
                        dev.jdx.sources.findKotlinMemberDocs(superSources, lookupRef, ktParser, superAliases)
                    } else {
                        java
                    }
                    when (found) {
                        is dev.jdx.sources.JavaDocResult.Found -> {
                            val doc = found.docs.firstOrNull() ?: break
                            val lines = docLines(doc, raw, inheritDocReplacement = null)
                            if (lines.isEmpty()) break
                            return InheritedDoc(doc, superBinary, superSources.displayName, lines)
                        }
                        else -> {
                            // MemberNotFound tries the next spelling; NoSource,
                            // NotJava, ParserUnavailable and ParseError move
                            // to the next supertype.
                            if (found !is dev.jdx.sources.JavaDocResult.MemberNotFound) break
                        }
                    }
                }
            } finally {
                runCatching { superSources.close() }
            }
        }
        return null
    }

    /**
     * The supertype chain in linearisation order (superclass then interfaces,
     * first-visit-wins, unresolvable edges skipped). Mirrors
     * [MemberResolver]'s traversal without pulling the full resolution along.
     */
    private fun supertypeChain(target: ClassInfo, load: (TypeName) -> ClassInfo?): List<ClassInfo> {
        val seen = mutableSetOf(target.name.binaryName)
        val order = mutableListOf<ClassInfo>()
        val queue = ArrayDeque<ClassInfo>()
        queue.add(target)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val supers = listOfNotNull(current.superclass) + current.interfaces
            for (superName in supers) {
                val binary = (superName as? TypeName.ClassType)?.binaryName ?: continue
                if (!seen.add(binary)) continue
                val info = load(superName) ?: continue
                order.add(info)
                queue.add(info)
            }
        }
        return order
    }

    /** Whether the winning root's sources declare a member of this name at all (T-028 pairing). */
    private fun sourceDeclaresMember(
        sources: dev.jdx.sources.SourceRoot,
        binary: String,
        memberName: String,
        // Shared Kotlin parser (T-039), or null to stay Java-only.
        ktParser: dev.jdx.sources.KotlinSourceParser? = null,
    ): Boolean {
        when (val listed = dev.jdx.sources.listJavaMembers(sources, binary)) {
            is dev.jdx.sources.JavaMemberList.Listed ->
                return listed.members.any { it.name == memberName }
            is dev.jdx.sources.JavaMemberList.NotJava,
            is dev.jdx.sources.JavaMemberList.NoSource,
            -> {
                // `.kt` flesh (T-039): a Kotlin class in a shared file
                // reports NoSource, an exact-name `.kt` hit NotJava — both
                // pair against the Kotlin declarations, accepting JVM
                // accessor spellings of properties.
            }
            else -> return false
        }
        if (ktParser == null) return false
        val kotlinListed = dev.jdx.sources.listKotlinMembers(sources, binary, ktParser)
        val members = (kotlinListed as? dev.jdx.sources.JavaMemberList.Listed)?.members ?: return false
        return members.any {
            it.name == memberName || memberName in dev.jdx.sources.accessorNames(it.name)
        }
    }

    /**
     * The `SOURCES_VERSION_MISMATCH` warning for one sources-backed answer
     * (T-028): compares the winning root's source declarations against the
     * bytecode the query resolved from. `null` when they agree or when the
     * sources cannot be listed (missing file, `.kt`-only, unparseable) — those
     * degradations already have their own labels.
     */
    private fun mismatchWarning(
        target: ClassInfo,
        sources: dev.jdx.sources.SourceRoot,
        binary: String,
    ): Warning? {
        val listed = dev.jdx.sources.listJavaMembers(sources, binary)
        val members = (listed as? dev.jdx.sources.JavaMemberList.Listed)?.members ?: return null
        return dev.jdx.sources.detectSourcesMismatch(target, members, sources.displayName)
    }

    /** Rendered (`--raw` verbatim) lines of one doc, before `--max-lines` truncation. */
    private fun docLines(
        doc: dev.jdx.sources.SourceDoc,
        raw: Boolean,
        inheritDocReplacement: String?,
    ): List<String> =
        if (raw) {
            doc.rawComment.lines().map { it.removeSuffix("\r") }
                .dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        } else {
            renderJavadoc(doc.rawComment, inheritDocReplacement)
        }

    /** Renders one sliced [dev.jdx.sources.SourceDoc] as a [ServiceOutcome.Doc]. */
    private fun docOutcome(
        doc: dev.jdx.sources.SourceDoc,
        displayName: String,
        binary: String,
        canonicalRef: String,
        subject: DocSubject,
        warnings: List<Warning>,
        options: DocOptions,
        lines: List<String>,
        inheritedFrom: String?,
    ): ServiceOutcome {
        return ServiceOutcome.Doc(
            buildDocBlock(
                canonicalRef = canonicalRef,
                declaringType = binary,
                subject = subject,
                file = doc.file,
                startLine = doc.startLine,
                endLine = doc.endLine,
                rendered = lines,
                provenance = listOf(
                    Provenance(
                        artifact = displayName,
                        origin = Origin.SOURCES,
                        file = doc.file,
                        lineRange = doc.startLine..doc.endLine,
                    ),
                ),
                warnings = warnings.sortedBy { it.code },
                raw = options.raw,
                inheritedFrom = inheritedFrom,
                maxLines = options.maxLines,
            ),
        )
    }

    private fun docSubjectOf(kind: dev.jdx.sources.SourceDocKind): DocSubject = when (kind) {
        dev.jdx.sources.SourceDocKind.TYPE -> DocSubject.TYPE
        dev.jdx.sources.SourceDocKind.METHOD -> DocSubject.METHOD
        dev.jdx.sources.SourceDocKind.CONSTRUCTOR -> DocSubject.CONSTRUCTOR
        dev.jdx.sources.SourceDocKind.FIELD -> DocSubject.FIELD
        dev.jdx.sources.SourceDocKind.ENUM_ENTRY -> DocSubject.ENUM_ENTRY
    }

    /**
     * Rendered member doc lines for `--with-doc` (T-072): the member's own
     * comment, else the nearest documenting supertype's (methods only, like
     * `doc` — D-037). `lookupRefs` are the bytecode-authoritative spellings
     * (query plus generic-signature retry); `primaryForInherit` gates the
     * inherited walk to real methods. Returns `null` when undocumented or
     * without sources — enrichment is best-effort, never a failure. Never
     * throws on agent-reachable input.
     */
    private fun withDocLines(
        target: ClassInfo,
        effectiveRef: MemberSymbolRef,
        lookupRefs: List<MemberSymbolRef>,
        bytecodeMatches: List<BytecodeMember>,
        opened: List<OpenRoot>,
        providers: Map<String, List<Int>>,
        workspace: Workspace,
        sourceCache: MutableMap<Int, dev.jdx.sources.SourceRoot?>,
        // Shared Kotlin parser (T-039), or null to stay Java-only. Opened
        // once per command by the caller — enrichment never opens its own.
        ktParser: dev.jdx.sources.KotlinSourceParser? = null,
    ): List<String>? {
        return try {
            val binary = target.name.binaryName
            val rootIndex = providers[binary]?.firstOrNull() ?: return null
            val sources = sourceCache.getOrPut(rootIndex) { openSourcesFor(opened[rootIndex]) }
                ?: return null
            val ktAliases = kotlinSourceAliases(target, bytecodeMatches.singleOrNull())
            var directDoc: dev.jdx.sources.SourceDoc? = null
            for (lookupRef in lookupRefs) {
                val java = dev.jdx.sources.findMemberDocs(sources, lookupRef)
                // `.kt` flesh (T-039): same NoSource/NotJava → Kotlin mapping
                // as the body path; without a parser the Java answer stands.
                val found = if (
                    (java is dev.jdx.sources.JavaDocResult.NoSource ||
                        java is dev.jdx.sources.JavaDocResult.NotJava) &&
                    ktParser != null
                ) {
                    dev.jdx.sources.findKotlinMemberDocs(sources, lookupRef, ktParser, ktAliases)
                } else {
                    java
                }
                when (found) {
                    is dev.jdx.sources.JavaDocResult.Found -> {
                        directDoc = found.docs.firstOrNull() ?: return null
                        break
                    }
                    else -> {
                        // MemberNotFound tries the next spelling; NoSource,
                        // NotJava, ParserUnavailable and ParseError fall
                        // through to the inherited walk (which skips
                        // unreadable supertypes anyway).
                        if (found !is dev.jdx.sources.JavaDocResult.MemberNotFound) break
                    }
                }
            }
            val primaryMethod = bytecodeMatches.filterIsInstance<BytecodeMember.Method>()
                .firstOrNull { it.info.name != "<init>" && !isSyntheticMember(it) }
                ?: bytecodeMatches.filterIsInstance<BytecodeMember.Method>().firstOrNull { it.info.name != "<init>" }
            val inherited = if (primaryMethod != null) {
                try {
                    findInheritedMemberDoc(target, effectiveRef, opened, providers, workspace, raw = false, ktParser)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            if (directDoc != null) {
                val replacement = if (directDoc.rawComment.contains("{@inheritDoc")) inherited?.paragraph else null
                val lines = docLines(directDoc, raw = false, inheritDocReplacement = replacement)
                if (lines.isNotEmpty()) return lines
            }
            inherited?.lines
        } catch (e: Exception) {
            null
        }
    }

    /**
     * First javadoc sentence for one shown listing row (T-072): parses the
     * row's canonical ref and reuses [withDocLines], then cuts to the first
     * sentence. Returns `null` when undocumented — the row renders unchanged.
     * Never throws: an unparseable ref simply has no doc.
     */
    private fun withDocSentence(
        canonicalRef: String,
        opened: List<OpenRoot>,
        providers: Map<String, List<Int>>,
        workspace: Workspace,
        sourceCache: MutableMap<Int, dev.jdx.sources.SourceRoot?>,
        // Shared Kotlin parser (T-039), or null to stay Java-only.
        ktParser: dev.jdx.sources.KotlinSourceParser? = null,
    ): String? {
        return try {
            val parsed = SymbolRefParser.parse(canonicalRef)
            val ref = (parsed as? SymbolRefParseResult.Ok)?.ref as? MemberSymbolRef ?: return null
            val declaring = ref.declaringType as? TypeName.ClassType ?: return null
            val declaringInfo = workspace.load(declaring.binaryName) ?: return null
            // The `:return` suffix (bridge disambiguation) still parses as a
            // return-qualified ref, which `matchBytecodeMembers` narrows —
            // so bridges resolve to their shared source declaration.
            val matches = matchBytecodeMembers(declaringInfo, ref)
            if (matches.isEmpty()) return null
            val effectiveRef = ref.copy(declaringType = declaringInfo.name)
            val singleMatch = matches.singleOrNull()
            val lookupRefs = listOf(effectiveRef) +
                (singleMatch?.let { genericSpelledRef(declaringInfo, it) }
                    ?.takeIf { it != effectiveRef }?.let(::listOf).orEmpty())
            val lines = withDocLines(
                declaringInfo, effectiveRef, lookupRefs, matches,
                opened, providers, workspace, sourceCache, ktParser,
            ) ?: return null
            dev.jdx.core.render.firstDocSentence(lines)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Copies [listing] with each shown row's first javadoc sentence filled in
     * (T-072). Docs come from each row's declaring type's paired sources, so
     * cross-artifact hierarchies resolve honestly; rows without docs keep a
     * `null` suffix and render byte-identically to the flag-off path. Cached
     * sources close before returning; enrichment never throws — a failure
     * here returns the unenriched listing rather than failing the query.
     */
    private fun enrichListingWithDocs(
        listing: MemberListing,
        opened: List<OpenRoot>,
        providers: Map<String, List<Int>>,
        workspace: Workspace,
    ): MemberListing {
        val sourceCache = mutableMapOf<Int, dev.jdx.sources.SourceRoot?>()
        // One ambient Kotlin parser for the whole enrichment (T-039): opened
        // lazily-cheap, parsed only when a `.kt` row actually needs docs.
        val ktParser = openKotlinParserFor(null)
        try {
            val groups = listing.groups.map { group ->
                group.copy(
                    rows = group.rows.map { row ->
                        if (row.doc != null) {
                            row
                        } else {
                            val sentence = try {
                                withDocSentence(row.canonicalRef, opened, providers, workspace, sourceCache, ktParser)
                            } catch (e: Exception) {
                                null
                            }
                            if (sentence == null) row else row.copy(doc = sentence)
                        }
                    },
                )
            }
            return listing.copy(groups = groups)
        } catch (e: Exception) {
            return listing
        } finally {
            sourceCache.values.forEach { runCatching { it?.close() } }
            runCatching { ktParser.close() }
        }
    }

    // -- body execution (T-022) ----------------------------------------------------

    private fun executeBody(
        memberRef: MemberSymbolRef,
        rawRef: String,
        roots: RootsSpec,
        options: BodyOptions,
        candidateScope: Set<String>? = null,
    ): ServiceOutcome {
        val declaring = memberRef.declaringType as? TypeName.ClassType
            ?: return failure(3, rawRef, "usage error: body takes a class member, got '$rawRef'")
        val opened = openRoots(roots)
        // One Kotlin parser per command (T-039): cheap to open, and the
        // ~1 s PSI init happens at most once, on the first `.kt` parse.
        val ktParser = openKotlinParserFor(options.kotlinUserHome)
        try {
            val binariesByRoot = opened.map { it.root.classEntryPaths().map(::entryToBinary).toSet() }
            val providers = mutableMapOf<String, MutableList<Int>>()
            binariesByRoot.forEachIndexed { index, binaries ->
                for (binary in binaries) providers.getOrPut(binary) { mutableListOf() }.add(index)
            }
            val allBinaries = providers.keys

            val candidates = matchCandidates(declaring, candidateScope ?: allBinaries)
            if (candidates.isEmpty()) {
                val suggestions = suggestSimilar(declaring.simpleName, allBinaries)
                return ServiceOutcome.Failure(ErrorResult.notFound(rawRef, suggestions))
            }
            if (candidates.size > 1) {
                return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, candidates))
            }
            val binary = candidates.single()
            val winner = providers.getValue(binary).first()

            val warnings = mutableListOf<Warning>()
            warnings.addAll(roots.extraWarnings)
            for (open in opened) warnings.addAll(open.root.warnings)
            val extraProviders = providers.getValue(binary).drop(1)
            if (extraProviders.isNotEmpty()) {
                val names = listOf(winner).plus(extraProviders).map { rootLabel(opened[it], binary) }
                warnings.add(
                    Warning(
                        code = WarningCode.DUPLICATE_FQN,
                        message = "$binary is provided by ${names.joinToString(", ")}; " +
                            "showing ${names.first()} (classpath order)",
                        subject = binary,
                    ),
                )
            }

            val workspace = Workspace(opened, providers, warnings)
            val target = workspace.load(binary)
            if (target == null) {
                return failure(
                    5,
                    rawRef,
                    "artifact read error: $binary in ${rootLabel(opened[winner], binary)} cannot be parsed",
                )
            }

            // Overload ambiguity is structural (D-009): decided from bytecode
            // before any source is read, so a stale sources jar cannot mislead.
            val bytecodeMatches = matchBytecodeMembers(target, memberRef)
            if (bytecodeMatches.isEmpty()) {
                if (memberRef.name == "<clinit>") {
                    return failure(3, rawRef, "usage error: static initialisers have no body to show: '$rawRef'")
                }
                return ServiceOutcome.Failure(
                    ErrorResult.notFound(rawRef, suggestSimilarMember(target, memberRef.name)),
                )
            }
            val specified = memberRef.parameterTypes != null
            val matchRefs = canonicalMemberRefs(target, bytecodeMatches)
            if (!specified && bytecodeMatches.size > 1) {
                return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, matchRefs))
            }
            if (specified && bytecodeMatches.size > 1 && memberRef.returnType == null) {
                return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, matchRefs))
            }
            // `--with-signature` (T-024): the header comes from the same
            // bytecode match the body is sliced for — the single match in the
            // usual case; the first in declaration order for return-qualified
            // bridge pairs that still resolve one source body.
            val signatureLine = if (options.withSignature) {
                val match = bytecodeMatches.singleOrNull() ?: bytecodeMatches.first()
                when (match) {
                    is BytecodeMember.Method -> SignatureLines.methodLine(
                        member = match.info,
                        declaringSimpleName = target.name.simpleName,
                        kotlinView = kotlinViewOf(target, match.info),
                    )
                    is BytecodeMember.Field -> SignatureLines.fieldLine(match.info)
                    // Properties have no JVM body of their own: the header
                    // still spells the folded property (the T-039 slice
                    // serves the `KtProperty` declaration).
                    is BytecodeMember.Property -> SignatureLines.propertyLine(
                        access = match.view.access,
                        isVar = match.view.isVar,
                        typeText = match.view.displayType ?: "java.lang.Object",
                        propertyName = match.view.propertyName,
                    )
                }
            } else {
                null
            }

            val effectiveRef = memberRef.copy(declaringType = target.name)
            // Source lookup spellings: the query as written, plus — for generic
            // members queried in erased form (`identity(java.lang.Object)` for
            // `U identity(U)`) — the generic signature's own spellings (`U`),
            // which is what the source text actually says. Bytecode stays the
            // authority (the match above already proved the member); these are
            // just the keys the T-021 narrowing understands.
            val singleMatch = bytecodeMatches.singleOrNull()
            val lookupRefs = listOf(effectiveRef) +
                (singleMatch?.let { genericSpelledRef(target, it) }?.takeIf { it != effectiveRef }?.let(::listOf).orEmpty())
            // Kotlin source spellings for the `.kt` attempt below (T-039):
            // the `@JvmName` display name, the unmangled `internal` name, or
            // the folded property name — the JVM spelling alone cannot match
            // sources.
            val ktAliases = kotlinSourceAliases(target, singleMatch)
            val label = rootLabel(opened[winner], binary)
            // `--with-doc` (T-072): docs come from the paired sources even
            // when the body itself is reconstructed — the doc block is
            // ground truth while the slice may be a reconstruction.
            val bodyDoc: List<String>? = if (options.withDoc) {
                val docCache = mutableMapOf<Int, dev.jdx.sources.SourceRoot?>()
                try {
                    withDocLines(
                        target, effectiveRef, lookupRefs, bytecodeMatches,
                        opened, providers, workspace, docCache, ktParser,
                    )
                } catch (e: Exception) {
                    null
                } finally {
                    docCache.values.forEach { runCatching { it?.close() } }
                }
            } else {
                null
            }
            // `--engine vineflower` skips the paired sources entirely (T-026):
            // the class is always reconstructed, even when sources are paired.
            if (options.engine == DecompilerId.VINEFLOWER) {
                return decompiledBodyOutcome(
                    memberRef = memberRef,
                    rawRef = rawRef,
                    binary = binary,
                    target = target,
                    lookupRefs = lookupRefs,
                    matchRefs = matchRefs,
                    specified = specified,
                    signatureLine = signatureLine,
                    doc = bodyDoc,
                    opened = opened,
                    winner = winner,
                    label = label,
                    warnings = warnings,
                    options = options,
                )
            }
            // `--engine javap` skips the paired sources entirely (T-027): the
            // class is always disassembled, even when sources are paired.
            if (options.engine == DecompilerId.JAVAP) {
                return javapBodyOutcome(
                    memberRef = memberRef,
                    rawRef = rawRef,
                    binary = binary,
                    target = target,
                    bytecodeMatches = bytecodeMatches,
                    matchRefs = matchRefs,
                    signatureLine = signatureLine,
                    doc = bodyDoc,
                    opened = opened,
                    winner = winner,
                    label = label,
                    warnings = warnings,
                    options = options,
                )
            }
            val sources = openSourcesFor(opened[winner])
                // No sources root is routine (most jars ship without one): the
                // ladder degrades to reconstruction (T-026), then to raw
                // disassembly when reconstruction itself fails (T-073) —
                // not a dead end.
                ?: return defaultBodyOutcome(
                    memberRef = memberRef,
                    rawRef = rawRef,
                    binary = binary,
                    target = target,
                    bytecodeMatches = bytecodeMatches,
                    lookupRefs = lookupRefs,
                    matchRefs = matchRefs,
                    specified = specified,
                    signatureLine = signatureLine,
                    doc = bodyDoc,
                    opened = opened,
                    winner = winner,
                    label = label,
                    warnings = warnings,
                    options = options,
                )
            try {
                var memberNotFound = false
                for (lookupRef in lookupRefs) {
                    // `.kt` flesh (T-039): both `NoSource` (a Kotlin class in
                    // a shared file resolves to no `.java`) and `NotJava` (an
                    // exact-name `.kt` hit) mean "try Kotlin" — the seam
                    // re-resolves with PSI confirmation, so a miss here stays
                    // a miss and never a wrong file.
                    val found = when (
                        val java = dev.jdx.sources.findJavaBodies(sources, lookupRef)
                    ) {
                        is dev.jdx.sources.JavaBodyResult.NoSource,
                        is dev.jdx.sources.JavaBodyResult.NotJava,
                        -> dev.jdx.sources.findKotlinBodies(sources, lookupRef, ktParser, ktAliases)
                        else -> java
                    }
                    when (found) {
                    is dev.jdx.sources.JavaBodyResult.Found -> {
                        if (!specified && found.bodies.size > 1) {
                            return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, matchRefs))
                        }
                        val body = found.bodies.singleOrNull()
                            ?: return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, matchRefs))
                        mismatchWarning(target, sources, binary)?.let(warnings::add)
                        return bodyOutcome(
                            body = body,
                            sources = sources,
                            // Provenance names the file the lines were sliced from
                            // (PROPOSAL.md §3.5): the sources jar, not the binary —
                            // shadowing stays in the warnings.
                            label = sources.displayName,
                            binary = binary,
                            canonicalRef = matchRefs.singleOrNull() ?: SymbolRefPrinter.print(
                                MemberSymbolRef(
                                    declaringType = target.name,
                                    name = body.name,
                                    parameterTypes = memberRef.parameterTypes,
                                ),
                            ),
                            warnings = warnings,
                            options = options,
                            rawRef = rawRef,
                            signature = signatureLine,
                            doc = bodyDoc,
                        )
                    }
                    is dev.jdx.sources.JavaBodyResult.MemberNotFound -> {
                        memberNotFound = true
                    }
                    is dev.jdx.sources.JavaBodyResult.NoSource ->
                        // The sources root exists but holds no file for this
                        // class: a stale or mismatched sources jar (T-028),
                        // not a missing one — reconstruction is only the
                        // fallback when no sources root exists at all.
                        return ServiceOutcome.Failure(
                            ErrorResult.notFound(
                                rawRef,
                                detail = "$binary has no source counterpart in $label " +
                                    "(SOURCES_VERSION_MISMATCH)",
                            ),
                        )
                    is dev.jdx.sources.JavaBodyResult.NotJava ->
                        // Unreachable: the mapping above sends every `NotJava`
                        // through the Kotlin seam, which never emits it —
                        // kept for exhaustiveness.
                        return ServiceOutcome.Failure(
                            ErrorResult.notFound(
                                rawRef,
                                detail = "$binary has no source counterpart in $label " +
                                    "(SOURCES_VERSION_MISMATCH)",
                            ),
                        )
                    is dev.jdx.sources.JavaBodyResult.ParserUnavailable ->
                        // No usable PSI (T-039): the `.kt` file might as well
                        // be absent — degrade down the reconstruction ladder.
                        return defaultBodyOutcome(
                            memberRef = memberRef,
                            rawRef = rawRef,
                            binary = binary,
                            target = target,
                            bytecodeMatches = bytecodeMatches,
                            lookupRefs = lookupRefs,
                            matchRefs = matchRefs,
                            specified = specified,
                            signatureLine = signatureLine,
                            doc = bodyDoc,
                            opened = opened,
                            winner = winner,
                            label = label,
                            warnings = warnings,
                            options = options,
                        )
                    is dev.jdx.sources.JavaBodyResult.ParseError ->
                        return failure(5, rawRef, found.message)
                    }
                }
                check(memberNotFound) { "lookup spellings exhausted without a terminal result" }
                return ServiceOutcome.Failure(
                    ErrorResult.notFound(
                        rawRef,
                        detail = "$rawRef has no source counterpart in $label " +
                            "(SOURCES_VERSION_MISMATCH)",
                    ),
                )
            } finally {
                runCatching { sources.close() }
            }
        } finally {
            opened.forEach { it.root.close() }
            runCatching { ktParser.close() }
        }
    }

    private fun openSourcesFor(open: OpenRoot): dev.jdx.sources.SourceRoot? = when (val root = open.root) {
        is dev.jdx.index.artifact.JarArtifact -> root.openSources()
        is dev.jdx.index.artifact.JrtArtifact -> root.openSources()
        else -> null
    }

    /**
     * Opens the Kotlin source parser for one command (T-039): [userHome]
     * overrides the ambient home (the `kotlinUserHome` test seam). Never
     * throws — absence reads as an unavailable value the callers degrade.
     * Cheap until the first parse (the ~1 s PSI init is lazy per parser),
     * so one parser is opened per command and shared across its lookups.
     */
    private fun openKotlinParserFor(userHome: Path?): dev.jdx.sources.KotlinSourceParser {
        val home = userHome
            ?: runCatching { Path.of(System.getProperty("user.home")) }.getOrNull()
            ?: Path.of(".")
        return dev.jdx.sources.openKotlinParser(home)
    }

    /** Kotlin source spellings of one bytecode match for `.kt` lookup (T-039). */
    private fun kotlinSourceAliases(target: ClassInfo, match: BytecodeMember?): Set<String> = when (match) {
        is BytecodeMember.Method ->
            kotlinViewOf(target, match.info)?.let { setOf(it.displayName) } ?: emptySet()
        is BytecodeMember.Property -> setOf(match.view.propertyName)
        is BytecodeMember.Field, null -> emptySet()
    }

    // -- decompilation fallback (T-026) --------------------------------------------

    /**
     * One decompiled class behind the ladder: `Ready` carries the
     * reconstructed text, `Failed` the exit-1 answer. Unreadable class bytes
     * throw [ArtifactReadException] instead (exit 5 at the `body`/`source`
     * entry points) — an unreadable artifact is not an engine failure.
     */
    private sealed interface DecompiledText {
        data class Ready(val text: String) : DecompiledText
        data class Failed(val outcome: ServiceOutcome.Failure) : DecompiledText
    }

    /**
     * Runs [decompiler] over the winning root's class bytes with the other
     * workspace roots as library context (PROPOSAL.md §11.2). Never throws:
     * Vineflower failures become exit 1 naming the `--engine javap` escape
     * hatch; `javap` itself is the last rung of the ladder, so its failures
     * name only their cause. The default ladder (T-073) retries through
     * `javap` one level up ([defaultBodyOutcome]/[defaultSourceOutcome])
     * before this failure surfaces; forced `--engine vineflower` surfaces it
     * directly (strict, no silent swap).
     */
    private fun decompileClassText(
        opened: List<OpenRoot>,
        winner: Int,
        binary: String,
        rawRef: String,
        decompiler: DecompilerEngine,
    ): DecompiledText {
        val bytes = opened[winner].root.openClass(entryForBinary(binary)).use { it.readBytes() }
        val libraries = opened.mapNotNull { it.root.libraryPath }
        return when (val result = decompiler.decompileClass(bytes, binary, libraries)) {
            is DecompileResult.Decompiled -> DecompiledText.Ready(result.text)
            is DecompileResult.Failed -> DecompiledText.Failed(
                ServiceOutcome.Failure(
                    if (decompiler.id == DecompilerId.JAVAP) {
                        ErrorResult.notFound(
                            rawRef,
                            detail = "could not disassemble $binary with javap: ${result.message}",
                        )
                    } else {
                        ErrorResult.notFound(
                            rawRef,
                            detail = "could not decompile $binary with ${decompiler.id.displayName}: " +
                                "${result.message} (raw bytecode: --engine javap)",
                        )
                    },
                ),
            )
        }
    }

    /**
     * The default-ladder `body` reconstruction (T-073): Vineflower first, then
     * the T-027 `javap` engine when Vineflower fails. Only an exit-1
     * Vineflower failure retries (timeout, crash, unparseable text, a member
     * missing from the reconstruction): ambiguity (exit 2) is structural
     * (D-009) and identical through either engine, so it returns as-is. A
     * `javap` failure too exits 1 naming both causes; a `javap` success exits
     * 0 with `DECOMPILED_JAVAP` provenance. Forced `--engine vineflower`
     * never reaches here — it stays strict (no silent swap).
     */
    private fun defaultBodyOutcome(
        memberRef: MemberSymbolRef,
        rawRef: String,
        binary: String,
        target: ClassInfo,
        bytecodeMatches: List<BytecodeMember>,
        lookupRefs: List<MemberSymbolRef>,
        matchRefs: List<String>,
        specified: Boolean,
        signatureLine: String?,
        doc: List<String>? = null,
        opened: List<OpenRoot>,
        winner: Int,
        label: String,
        warnings: List<Warning>,
        options: BodyOptions,
    ): ServiceOutcome {
        val first = decompiledBodyOutcome(
            memberRef, rawRef, binary, target, lookupRefs, matchRefs, specified,
            signatureLine, doc, opened, winner, label, warnings, options,
        )
        val firstFailure = first as? ServiceOutcome.Failure ?: return first
        if (firstFailure.exitCode != 1) return first
        val second = javapBodyOutcome(
            memberRef, rawRef, binary, target, bytecodeMatches, matchRefs,
            signatureLine, doc, opened, winner, label, warnings, options,
        )
        val secondFailure = second as? ServiceOutcome.Failure ?: return second
        if (secondFailure.exitCode != 1) return second
        return ServiceOutcome.Failure(
            ErrorResult.notFound(rawRef, detail = combinedEngineDetail(firstFailure, secondFailure)),
        )
    }

    /**
     * The default-ladder `source` reconstruction (T-073): the `source`
     * counterpart of [defaultBodyOutcome] — same retry rule, same
     * double-failure shape. Whole files, `--lines` windows and `--around`
     * slices all retry through `javap` identically, because the retry wraps
     * the whole decompiled outcome.
     */
    private fun defaultSourceOutcome(
        rawRef: String,
        binary: String,
        label: String,
        target: ClassInfo,
        opened: List<OpenRoot>,
        winner: Int,
        options: SourceOptions,
        warnings: List<Warning>,
    ): ServiceOutcome {
        val first = decompiledSourceOutcome(rawRef, binary, label, target, opened, winner, options, warnings)
        val firstFailure = first as? ServiceOutcome.Failure ?: return first
        if (firstFailure.exitCode != 1) return first
        val second = javapSourceOutcome(rawRef, binary, label, target, opened, winner, options, warnings)
        val secondFailure = second as? ServiceOutcome.Failure ?: return second
        if (secondFailure.exitCode != 1) return second
        return ServiceOutcome.Failure(
            ErrorResult.notFound(rawRef, detail = combinedEngineDetail(firstFailure, secondFailure)),
        )
    }

    /**
     * One double-failure line naming both ladder causes (T-073): the
     * Vineflower detail (minus its now-stale `--engine javap` hatch pointer —
     * the retry already happened) plus the `javap` detail. Both renderers
     * carry it via [ErrorResult.notFound] (D-007); the join is a fixed
     * string, so bytes stay deterministic.
     */
    private fun combinedEngineDetail(
        first: ServiceOutcome.Failure,
        second: ServiceOutcome.Failure,
    ): String = "${detailOf(first).removeSuffix(" (raw bytecode: --engine javap)")}; ${detailOf(second)}"

    /** The prose behind one exit-1 engine failure, for [combinedEngineDetail]. */
    private fun detailOf(failure: ServiceOutcome.Failure): String = when (val error = failure.error) {
        is ErrorResult.NotFound -> error.detail ?: "not found: ${error.query}"
        is ErrorResult.Generic -> error.message
        is ErrorResult.Ambiguous -> "ambiguous: ${error.candidates.size} candidates for ${error.query}"
    }

    /** The `.java` path decompiled text is keyed under: the outer class file. */
    private fun javaPathFor(binary: String): String =
        binary.substringBefore('$').replace('.', '/') + ".java"

    /**
     * Serves `body <member>` from reconstructed text (T-026): decompiles the
     * winning class, feeds the text through [dev.jdx.sources.MemorySourceRoot]
     * into the T-021 seam, and slices exactly like the sources path — so the
     * answer differs only in provenance. Mirrors the sources loop above; the
     * two degrade differently (a root with sources answers from ground truth,
     * a root without answers here), which is why they are two loops and not
     * one parametrised one.
     */
    private fun decompiledBodyOutcome(
        memberRef: MemberSymbolRef,
        rawRef: String,
        binary: String,
        target: ClassInfo,
        lookupRefs: List<MemberSymbolRef>,
        matchRefs: List<String>,
        specified: Boolean,
        signatureLine: String?,
        doc: List<String>? = null,
        opened: List<OpenRoot>,
        winner: Int,
        label: String,
        warnings: List<Warning>,
        options: BodyOptions,
    ): ServiceOutcome {
        val text = when (
            val decompiled = decompileClassText(opened, winner, binary, rawRef, options.decompiler)
        ) {
            is DecompiledText.Failed -> return decompiled.outcome
            is DecompiledText.Ready -> decompiled.text
        }
        val decompiledRoot = dev.jdx.sources.MemorySourceRoot(mapOf(javaPathFor(binary) to text))
        var memberNotFound = false
        for (lookupRef in lookupRefs) {
            when (val found = dev.jdx.sources.findJavaBodies(decompiledRoot, lookupRef)) {
                is dev.jdx.sources.JavaBodyResult.Found -> {
                    if (!specified && found.bodies.size > 1) {
                        return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, matchRefs))
                    }
                    val body = found.bodies.singleOrNull()
                        ?: return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, matchRefs))
                    val fileLines = text.split('\n').map { it.removeSuffix("\r") }
                    return ServiceOutcome.Body(
                        buildBodyBlock(
                            canonicalRef = matchRefs.singleOrNull() ?: SymbolRefPrinter.print(
                                MemberSymbolRef(
                                    declaringType = target.name,
                                    name = body.name,
                                    parameterTypes = memberRef.parameterTypes,
                                ),
                            ),
                            declaringType = binary,
                            file = body.file,
                            fileLines = fileLines,
                            startLine = body.startLine,
                            endLine = body.endLine,
                            provenance = listOf(
                                Provenance(
                                    artifact = label,
                                    origin = Origin.DECOMPILED_VINEFLOWER,
                                    file = body.file,
                                    lineRange = body.startLine..body.endLine,
                                ),
                            ),
                            warnings = warnings.sortedBy { it.code },
                            contextLines = options.contextLines,
                            lineNumbers = options.lineNumbers,
                            maxLines = options.maxLines,
                            signature = signatureLine,
                            doc = doc,
                        ),
                    )
                }
                is dev.jdx.sources.JavaBodyResult.MemberNotFound -> {
                    memberNotFound = true
                }
                is dev.jdx.sources.JavaBodyResult.NoSource,
                is dev.jdx.sources.JavaBodyResult.NotJava,
                -> {
                    // Unreachable: the root holds exactly the decompiled file —
                    // kept for exhaustiveness (L-073).
                    memberNotFound = true
                }
                is dev.jdx.sources.JavaBodyResult.ParserUnavailable -> {
                    // Unreachable: the Java seam never emits this (no parser
                    // involved) — kept for exhaustiveness.
                    memberNotFound = true
                }
                is dev.jdx.sources.JavaBodyResult.ParseError ->
                    return ServiceOutcome.Failure(
                        ErrorResult.notFound(
                            rawRef,
                            detail = "could not parse decompiled text for $binary: ${found.message} " +
                                "(raw bytecode: --engine javap)",
                        ),
                    )
            }
        }
        check(memberNotFound) { "lookup spellings exhausted without a terminal result" }
        return ServiceOutcome.Failure(
            ErrorResult.notFound(
                rawRef,
                detail = "$rawRef has no decompiled counterpart in $label " +
                    "(SOURCES_VERSION_MISMATCH)",
            ),
        )
    }

    /**
     * Serves `source <type>` from reconstructed text (T-026): whole file,
     * `--lines` window, or `--around` member slice. The windowing math is
     * shared with the sources path ([fileSourceOutcome]); only the text
     * origin differs.
     */
    private fun decompiledSourceOutcome(
        rawRef: String,
        binary: String,
        label: String,
        target: ClassInfo,
        opened: List<OpenRoot>,
        winner: Int,
        options: SourceOptions,
        warnings: List<Warning>,
    ): ServiceOutcome {
        val text = when (
            val decompiled = decompileClassText(opened, winner, binary, rawRef, options.decompiler)
        ) {
            is DecompiledText.Failed -> return decompiled.outcome
            is DecompiledText.Ready -> decompiled.text
        }
        val aroundRaw = options.aroundRef
        if (aroundRaw != null) {
            val matched = matchAroundMember(target, aroundRaw, rawRef)
            if (matched is AroundMatch.Failed) return matched.outcome
            matched as AroundMatch.Ready
            return decompiledAroundOutcome(
                binary = binary,
                rawRef = rawRef,
                aroundRaw = aroundRaw,
                label = label,
                target = target,
                effectiveRef = matched.effectiveRef,
                lookupRefs = matched.lookupRefs,
                matchRefs = matched.matchRefs,
                specified = matched.specified,
                text = text,
                options = options,
                warnings = warnings,
            )
        }
        return fileSourceOutcome(
            binary = binary,
            rawRef = rawRef,
            artifact = label,
            file = javaPathFor(binary),
            fileLines = splitTextLines(text),
            window = options.lines,
            origin = Origin.DECOMPILED_VINEFLOWER,
            warnings = warnings,
            options = options,
        )
    }

    /**
     * Serves `--around <member-ref>` from reconstructed text (T-026): the
     * member is located through the T-021 seam over the decompiled file, with
     * overload ambiguity decided from bytecode first (D-009) exactly like the
     * sources path ([aroundOutcome]).
     */
    private fun decompiledAroundOutcome(
        binary: String,
        rawRef: String,
        aroundRaw: String,
        label: String,
        target: ClassInfo,
        effectiveRef: MemberSymbolRef,
        lookupRefs: List<MemberSymbolRef>,
        matchRefs: List<String>,
        specified: Boolean,
        text: String,
        options: SourceOptions,
        warnings: List<Warning>,
    ): ServiceOutcome {
        val decompiledRoot = dev.jdx.sources.MemorySourceRoot(mapOf(javaPathFor(binary) to text))
        var memberNotFound = false
        for (lookupRef in lookupRefs) {
            when (val found = dev.jdx.sources.findJavaBodies(decompiledRoot, lookupRef)) {
                is dev.jdx.sources.JavaBodyResult.Found -> {
                    if (!specified && found.bodies.size > 1) {
                        return ServiceOutcome.Failure(ErrorResult.ambiguous(aroundRaw, matchRefs))
                    }
                    val body = found.bodies.singleOrNull()
                        ?: return ServiceOutcome.Failure(ErrorResult.ambiguous(aroundRaw, matchRefs))
                    val fileLines = splitTextLines(text)
                    return ServiceOutcome.Source(
                        buildSourceBlock(
                            canonicalRef = binary,
                            declaringType = binary,
                            file = body.file,
                            fileLines = fileLines,
                            startLine = body.startLine,
                            endLine = body.endLine,
                            provenance = listOf(
                                Provenance(
                                    artifact = label,
                                    origin = Origin.DECOMPILED_VINEFLOWER,
                                    file = body.file,
                                    lineRange = body.startLine..body.endLine,
                                ),
                            ),
                            warnings = warnings.sortedBy { it.code },
                            contextLines = options.contextLines,
                            lineNumbers = options.lineNumbers,
                            maxLines = options.maxLines,
                        ),
                    )
                }
                is dev.jdx.sources.JavaBodyResult.MemberNotFound -> {
                    memberNotFound = true
                }
                is dev.jdx.sources.JavaBodyResult.NoSource,
                is dev.jdx.sources.JavaBodyResult.NotJava,
                -> {
                    // Unreachable: the root holds exactly the decompiled file —
                    // kept for exhaustiveness (L-073).
                    memberNotFound = true
                }
                is dev.jdx.sources.JavaBodyResult.ParserUnavailable -> {
                    // Unreachable: the Java seam never emits this (no parser
                    // involved) — kept for exhaustiveness.
                    memberNotFound = true
                }
                is dev.jdx.sources.JavaBodyResult.ParseError ->
                    return ServiceOutcome.Failure(
                        ErrorResult.notFound(
                            rawRef,
                            detail = "could not parse decompiled text for $binary: ${found.message} " +
                                "(raw bytecode: --engine javap)",
                        ),
                    )
            }
        }
        check(memberNotFound) { "lookup spellings exhausted without a terminal result" }
        return ServiceOutcome.Failure(
            ErrorResult.notFound(
                aroundRaw,
                detail = "$aroundRaw has no decompiled counterpart in $label " +
                    "(SOURCES_VERSION_MISMATCH)",
            ),
        )
    }

    // -- javap engine (T-027) ----------------------------------------------------

    /**
     * The erased descriptor behind one bytecode match: the key the `javap`
     * `descriptor:` lines carry, so disassembly sections pair with the same
     * match the sources path would slice for.
     */
    private fun javapDescriptorOf(match: BytecodeMember): String = when (match) {
        is BytecodeMember.Method -> match.info.descriptor.descriptor
        is BytecodeMember.Field -> match.info.type.descriptor
        // T-078 properties have no javap section (T-039 owns bodies): callers
        // filter properties before reaching here; this branch never fires.
        is BytecodeMember.Property -> ""
    }

    /**
     * The member name behind one bytecode match, in `javap` terms: `<init>`
     * for constructors (printed as the FQN), the plain name otherwise.
     */
    private fun javapNameOf(match: BytecodeMember): String = when (match) {
        is BytecodeMember.Method -> match.info.name
        is BytecodeMember.Field -> match.info.name
        is BytecodeMember.Property -> match.view.propertyName
    }

    /**
     * Serves `body <member> --engine javap` (T-027): disassembles the winning
     * class through [BodyOptions.javapDecompiler] and serves the matched
     * member's section — the IDE's *Show Bytecode* action. Overload ambiguity
     * was already decided from bytecode (D-009); the erased descriptor pairs
     * the match with its section, so erased-vs-generic query spellings need
     * no retry here. Presentation (`--context`, `--line-numbers`,
     * `--max-lines`, `--with-signature`) is byte-identical in shape to the
     * sources path — only the provenance differs.
     */
    private fun javapBodyOutcome(
        memberRef: MemberSymbolRef,
        rawRef: String,
        binary: String,
        target: ClassInfo,
        bytecodeMatches: List<BytecodeMember>,
        matchRefs: List<String>,
        signatureLine: String?,
        doc: List<String>? = null,
        opened: List<OpenRoot>,
        winner: Int,
        label: String,
        warnings: List<Warning>,
        options: BodyOptions,
    ): ServiceOutcome {
        val text = when (
            val disassembled = decompileClassText(opened, winner, binary, rawRef, options.javapDecompiler)
        ) {
            is DecompiledText.Failed -> return disassembled.outcome
            is DecompiledText.Ready -> disassembled.text
        }
        val sections = splitJavapSections(text)
        // Non-empty by construction: `executeBody` returns before this point
        // when no bytecode member matches. Bridge/covariant pairs share name
        // and erased parameters but carry distinct descriptors (and returns):
        // the first declaration-order match wins, mirroring the
        // `--with-signature` header choice above.
        val first = bytecodeMatches.first()
        val section = findJavapSection(sections, javapNameOf(first), javapDescriptorOf(first))
            ?: return ServiceOutcome.Failure(
                ErrorResult.notFound(
                    rawRef,
                    detail = "$rawRef has no disassembled counterpart in $label " +
                        "(SOURCES_VERSION_MISMATCH)",
                ),
            )
        val fileLines = splitTextLines(text)
        return ServiceOutcome.Body(
            buildBodyBlock(
                canonicalRef = matchRefs.singleOrNull() ?: SymbolRefPrinter.print(
                    MemberSymbolRef(
                        declaringType = target.name,
                        name = memberRef.name,
                        parameterTypes = memberRef.parameterTypes,
                    ),
                ),
                declaringType = binary,
                file = javaPathFor(binary),
                fileLines = fileLines,
                startLine = section.startLine,
                endLine = section.endLine,
                provenance = listOf(
                    Provenance(
                        artifact = label,
                        origin = Origin.DECOMPILED_JAVAP,
                        file = javaPathFor(binary),
                        lineRange = section.startLine..section.endLine,
                    ),
                ),
                warnings = warnings.sortedBy { it.code },
                contextLines = options.contextLines,
                lineNumbers = options.lineNumbers,
                maxLines = options.maxLines,
                signature = signatureLine,
                doc = doc,
            ),
        )
    }

    /**
     * Serves `source <type> --engine javap` (T-027): whole disassembly,
     * `--lines` window, or `--around` member section. The windowing math is
     * the shared [fileSourceOutcome]; only the text origin differs.
     */
    private fun javapSourceOutcome(
        rawRef: String,
        binary: String,
        label: String,
        target: ClassInfo,
        opened: List<OpenRoot>,
        winner: Int,
        options: SourceOptions,
        warnings: List<Warning>,
    ): ServiceOutcome {
        val text = when (
            val disassembled = decompileClassText(opened, winner, binary, rawRef, options.javapDecompiler)
        ) {
            is DecompiledText.Failed -> return disassembled.outcome
            is DecompiledText.Ready -> disassembled.text
        }
        val aroundRaw = options.aroundRef
        if (aroundRaw != null) {
            val matched = matchAroundMember(target, aroundRaw, rawRef)
            if (matched is AroundMatch.Failed) return matched.outcome
            matched as AroundMatch.Ready
            return javapAroundOutcome(
                binary = binary,
                aroundRaw = aroundRaw,
                label = label,
                target = target,
                matched = matched,
                text = text,
                options = options,
                warnings = warnings,
            )
        }
        return fileSourceOutcome(
            binary = binary,
            rawRef = rawRef,
            artifact = label,
            file = javaPathFor(binary),
            fileLines = splitTextLines(text),
            window = options.lines,
            origin = Origin.DECOMPILED_JAVAP,
            warnings = warnings,
            options = options,
        )
    }

    /**
     * Serves `source --around <member-ref> --engine javap` (T-027): the member
     * is located through its erased descriptor (the same bytecode-first match
     * [matchAroundMember] decided), and its disassembly section is expanded by
     * `--context` exactly like the sources path.
     */
    private fun javapAroundOutcome(
        binary: String,
        aroundRaw: String,
        label: String,
        target: ClassInfo,
        matched: AroundMatch.Ready,
        text: String,
        options: SourceOptions,
        warnings: List<Warning>,
    ): ServiceOutcome {
        // `matchAroundMember` already proved exactly one resolvable member
        // (ambiguity exits 2 there); the first declaration-order match is the
        // section served, mirroring [javapBodyOutcome].
        val first = matched.matches.firstOrNull()
            ?: return ServiceOutcome.Failure(
                ErrorResult.notFound(aroundRaw, suggestSimilarMember(target, matched.effectiveRef.name)),
            )
        val section = findJavapSection(splitJavapSections(text), javapNameOf(first), javapDescriptorOf(first))
            ?: return ServiceOutcome.Failure(
                ErrorResult.notFound(
                    aroundRaw,
                    detail = "$aroundRaw has no disassembled counterpart in $label " +
                        "(SOURCES_VERSION_MISMATCH)",
                ),
            )
        val fileLines = splitTextLines(text)
        return ServiceOutcome.Source(
            buildSourceBlock(
                canonicalRef = binary,
                declaringType = binary,
                file = javaPathFor(binary),
                fileLines = fileLines,
                startLine = section.startLine,
                endLine = section.endLine,
                provenance = listOf(
                    Provenance(
                        artifact = label,
                        origin = Origin.DECOMPILED_JAVAP,
                        file = javaPathFor(binary),
                        lineRange = section.startLine..section.endLine,
                    ),
                ),
                warnings = warnings.sortedBy { it.code },
                contextLines = options.contextLines,
                lineNumbers = options.lineNumbers,
                maxLines = options.maxLines,
            ),
        )
    }

    /**
     * Serves a whole file or `--lines` window from already-read lines —
     * shared by the sources path ([sourceOutcome], via [readSourceLines]) and
     * the decompiled path (via [splitTextLines]), so window clamping and
     * beyond-EOF honesty cannot drift between origins.
     */
    private fun fileSourceOutcome(
        binary: String,
        rawRef: String,
        artifact: String,
        file: String,
        fileLines: List<String>,
        window: Pair<Int, Int>?,
        origin: Origin,
        warnings: List<Warning>,
        options: SourceOptions,
    ): ServiceOutcome {
        val (startLine, endLine) = if (window != null) {
            if (window.first > fileLines.size) {
                return ServiceOutcome.Failure(
                    ErrorResult.notFound(
                        rawRef,
                        detail = "--lines ${window.first}:${window.second} is beyond $file " +
                            "(${fileLines.size} lines)",
                    ),
                )
            }
            window.first to minOf(window.second, fileLines.size)
        } else {
            1 to fileLines.size.coerceAtLeast(1)
        }
        return ServiceOutcome.Source(
            buildSourceBlock(
                canonicalRef = binary,
                declaringType = binary,
                file = file,
                fileLines = fileLines,
                startLine = startLine,
                endLine = endLine,
                provenance = listOf(
                    Provenance(
                        artifact = artifact,
                        origin = origin,
                        file = file,
                        lineRange = startLine..endLine,
                    ),
                ),
                warnings = warnings.sortedBy { it.code },
                contextLines = 0,
                lineNumbers = options.lineNumbers,
                maxLines = options.maxLines,
            ),
        )
    }

    /**
     * Reads one source file as display lines: split on newlines with `\r`
     * stripped, dropping a single trailing empty line when the file ends with
     * a newline — otherwise every newline-terminated file gains a phantom
     * extra line (a 38-line file would report `1-39`). Returns `null` when the
     * entry cannot be read. Member ranges from the T-021 seam never address
     * the dropped line (no declaration covers it), so `--around` shares this
     * numbering safely. Internal (not private) so the golden suite serves the
     * byte-identical lines the service would.
     */
    internal fun readSourceLines(
        sources: dev.jdx.sources.SourceRoot,
        path: String,
    ): List<String>? = try {
        splitTextLines(
            sources.openSource(path).use { it.readBytes().toString(Charsets.UTF_8) },
        )
    } catch (e: Exception) {
        null
    }

    /**
     * Splits source text into display lines: newlines with `\r` stripped,
     * dropping a single trailing empty line when the text ends with a newline
     * — otherwise every newline-terminated file gains a phantom extra line.
     * Shared by the sources path ([readSourceLines]) and the decompiled path,
     * so both number lines identically.
     */
    internal fun splitTextLines(text: String): List<String> {
        val lines = text.split('\n').map { line -> line.removeSuffix("\r") }
        return if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    /**
     * Serves `source <type>`: resolves the type from bytecode (D-009), then
     * serves the winning root's paired sources — whole file, `--lines`
     * window, or `--around` member slice — as one verbatim [SourceBlock].
     */
    private fun executeSource(
        typeName: TypeName.ClassType,
        rawRef: String,
        roots: RootsSpec,
        options: SourceOptions,
        candidateScope: Set<String>? = null,
    ): ServiceOutcome {
        val opened = openRoots(roots)
        try {
            val binariesByRoot = opened.map { it.root.classEntryPaths().map(::entryToBinary).toSet() }
            val providers = mutableMapOf<String, MutableList<Int>>()
            binariesByRoot.forEachIndexed { index, binaries ->
                for (binary in binaries) providers.getOrPut(binary) { mutableListOf() }.add(index)
            }
            val allBinaries = providers.keys

            val candidates = matchCandidates(typeName, candidateScope ?: allBinaries)
            if (candidates.isEmpty()) {
                val suggestions = suggestSimilar(typeName.simpleName, allBinaries)
                return ServiceOutcome.Failure(ErrorResult.notFound(rawRef, suggestions))
            }
            if (candidates.size > 1) {
                return ServiceOutcome.Failure(ErrorResult.ambiguous(rawRef, candidates))
            }
            val binary = candidates.single()
            val winner = providers.getValue(binary).first()

            val warnings = mutableListOf<Warning>()
            warnings.addAll(roots.extraWarnings)
            for (open in opened) warnings.addAll(open.root.warnings)
            val extraProviders = providers.getValue(binary).drop(1)
            if (extraProviders.isNotEmpty()) {
                val names = listOf(winner).plus(extraProviders).map { rootLabel(opened[it], binary) }
                warnings.add(
                    Warning(
                        code = WarningCode.DUPLICATE_FQN,
                        message = "$binary is provided by ${names.joinToString(", ")}; " +
                            "showing ${names.first()} (classpath order)",
                        subject = binary,
                    ),
                )
            }

            val workspace = Workspace(opened, providers, warnings)
            val target = workspace.load(binary)
            if (target == null) {
                return failure(
                    5,
                    rawRef,
                    "artifact read error: $binary in ${rootLabel(opened[winner], binary)} cannot be parsed",
                )
            }

            val label = rootLabel(opened[winner], binary)
            // `--engine vineflower` skips the paired sources entirely (T-026):
            // the class is always reconstructed, even when sources are paired.
            if (options.engine == DecompilerId.VINEFLOWER) {
                return decompiledSourceOutcome(rawRef, binary, label, target, opened, winner, options, warnings)
            }
            // `--engine javap` skips the paired sources entirely (T-027): the
            // class is always disassembled, even when sources are paired.
            if (options.engine == DecompilerId.JAVAP) {
                return javapSourceOutcome(rawRef, binary, label, target, opened, winner, options, warnings)
            }
            val sources = openSourcesFor(opened[winner])
                // No sources root is routine (most jars ship without one): the
                // ladder degrades to reconstruction (T-026), then to raw
                // disassembly when reconstruction itself fails (T-073) —
                // not a dead end.
                ?: return defaultSourceOutcome(rawRef, binary, label, target, opened, winner, options, warnings)
            try {
                return sourceOutcome(binary, rawRef, label, target, sources, options, warnings, opened, winner)
            } finally {
                runCatching { sources.close() }
            }
        } finally {
            opened.forEach { it.root.close() }
        }
    }

    /**
     * Reads the source file behind [binary] and slices the requested window.
     * Whole files and `--lines` windows are verbatim text (no parse); only
     * `--around` parses via the T-021 seam to locate the member.
     */
    private fun sourceOutcome(
        binary: String,
        rawRef: String,
        label: String,
        target: ClassInfo,
        sources: dev.jdx.sources.SourceRoot,
        options: SourceOptions,
        warnings: List<Warning>,
        opened: List<OpenRoot>,
        winner: Int,
    ): ServiceOutcome {
        val aroundRaw = options.aroundRef
        if (aroundRaw != null) {
            return aroundOutcome(binary, rawRef, aroundRaw, label, target, sources, options, warnings, opened, winner)
        }
        // `.kt` flesh (T-039): whole files need no PSI parse, only the path —
        // and the Kotlin scan confirms it, so a same-package mention never
        // serves the wrong file. Java keeps its precedence (a `.java` sibling
        // wins over a `.kt` direct hit per T-074).
        val path = dev.jdx.sources.findJavaSourcePath(sources, binary)
            ?.takeIf { !it.endsWith(".kt") }
            ?: openKotlinParserFor(options.kotlinUserHome).use { kt ->
                dev.jdx.sources.findKotlinSourcePath(sources, binary, kt)
            }
            ?: return noSourceFileOutcome(binary, rawRef, label, sources)
        val fileLines = readSourceLines(sources, path)
            ?: return failure(5, rawRef, "source read error: cannot read $path")
        val allWarnings = warnings + listOfNotNull(mismatchWarning(target, sources, binary))
        return fileSourceOutcome(
            binary = binary,
            rawRef = rawRef,
            artifact = sources.displayName,
            file = path,
            fileLines = fileLines,
            window = options.lines,
            origin = Origin.SOURCES,
            warnings = allWarnings,
            options = options,
        )
    }

    /**
     * Maps a missing source file to its honest degradation: a `.kt`-only root
     * whose file the Kotlin scan could not confirm names the sidecar (T-039);
     * a root that holds no file at all for the class names a possible version
     * mismatch (T-028) — reconstruction is only the fallback when no sources
     * root exists at all.
     */
    private fun noSourceFileOutcome(
        binary: String,
        rawRef: String,
        label: String,
        sources: dev.jdx.sources.SourceRoot,
    ): ServiceOutcome {
        val available = try {
            sources.sourcePaths().toSet()
        } catch (e: Exception) {
            return failure(5, rawRef, "source read error: cannot list $label: ${e.message}")
        }
        val ktOnly = dev.jdx.sources.sourceCandidatesFor(binary)
            .filter { it.endsWith(".kt") }.any { it in available }
        if (ktOnly) {
            return ServiceOutcome.Failure(
                ErrorResult.notFound(
                    rawRef,
                    detail = "$binary only ships Kotlin sources here " +
                        "(${dev.jdx.sources.kotlinMissingHint()})",
                ),
            )
        }
        return ServiceOutcome.Failure(
            ErrorResult.notFound(
                rawRef,
                detail = "$binary has no source counterpart in $label " +
                    "(SOURCES_VERSION_MISMATCH)",
            ),
        )
    }

    /**
     * Matches an `--around` member against bytecode (D-009) with the
     * erased→generic-spelling retry keys: shared by the sources path
     * ([aroundOutcome]) and the decompiled path, so ambiguity and did-you-mean
     * cannot drift between origins.
     */
    private sealed interface AroundMatch {
        data class Ready(
            val effectiveRef: MemberSymbolRef,
            val lookupRefs: List<MemberSymbolRef>,
            val matchRefs: List<String>,
            val specified: Boolean,
            /** The bytecode-first matches (D-009) the refs above were built from. */
            val matches: List<BytecodeMember>,
        ) : AroundMatch

        data class Failed(val outcome: ServiceOutcome) : AroundMatch
    }

    private fun matchAroundMember(target: ClassInfo, aroundRaw: String, rawRef: String): AroundMatch {
        val parsed = SymbolRefParser.parse(aroundRaw)
        val aroundRef = (parsed as? SymbolRefParseResult.Ok)?.ref as? MemberSymbolRef
            ?: return AroundMatch.Failed(
                failure(3, rawRef, "usage error: invalid --around reference '$aroundRaw'"),
            )
        val effectiveRef = aroundRef.copy(declaringType = target.name)
        // Overload ambiguity is structural (D-009): decided from bytecode
        // before any source is read, so a stale sources jar cannot mislead.
        val bytecodeMatches = matchBytecodeMembers(target, effectiveRef)
        if (bytecodeMatches.isEmpty()) {
            if (effectiveRef.name == "<clinit>") {
                return AroundMatch.Failed(
                    failure(3, rawRef, "usage error: static initialisers have no source to center on: '$aroundRaw'"),
                )
            }
            return AroundMatch.Failed(
                ServiceOutcome.Failure(
                    ErrorResult.notFound(aroundRaw, suggestSimilarMember(target, effectiveRef.name)),
                ),
            )
        }
        val specified = effectiveRef.parameterTypes != null
        val matchRefs = canonicalMemberRefs(target, bytecodeMatches)
        if (!specified && bytecodeMatches.size > 1) {
            return AroundMatch.Failed(ServiceOutcome.Failure(ErrorResult.ambiguous(aroundRaw, matchRefs)))
        }
        if (specified && bytecodeMatches.size > 1 && effectiveRef.returnType == null) {
            return AroundMatch.Failed(ServiceOutcome.Failure(ErrorResult.ambiguous(aroundRaw, matchRefs)))
        }
        // Source lookup spellings: the query as written, plus — for generic
        // members queried in erased form (`identity(java.lang.Object)` for
        // `U identity(U)`) — the generic signature's own spellings (`U`),
        // which is what the source text actually says. Bytecode stays the
        // authority (the match above already proved the member); these are
        // just the keys the T-021 narrowing understands.
        val singleMatch = bytecodeMatches.singleOrNull()
        val lookupRefs = listOf(effectiveRef) +
            (singleMatch?.let { genericSpelledRef(target, it) }?.takeIf { it != effectiveRef }?.let(::listOf).orEmpty())
        return AroundMatch.Ready(effectiveRef, lookupRefs, matchRefs, specified, bytecodeMatches)
    }

    /**
     * Serves `--around <member-ref>`: locates the member through the T-021
     * seam and slices its range expanded by `--context`. Overload ambiguity
     * is decided from bytecode first (D-009), mirroring [executeBody].
     */
    private fun aroundOutcome(
        binary: String,
        rawRef: String,
        aroundRaw: String,
        label: String,
        target: ClassInfo,
        sources: dev.jdx.sources.SourceRoot,
        options: SourceOptions,
        warnings: List<Warning>,
        opened: List<OpenRoot>,
        winner: Int,
    ): ServiceOutcome {
        val matched = matchAroundMember(target, aroundRaw, rawRef)
        if (matched is AroundMatch.Failed) return matched.outcome
        matched as AroundMatch.Ready
        val (effectiveRef, lookupRefs, matchRefs, specified) = matched
        // `.kt` member spellings for the Kotlin attempt (T-039).
        val ktAliases = kotlinSourceAliases(target, matched.matches.singleOrNull())
        // One `--around` parser (T-039): cheap to open, parsed only when a
        // `.kt` member actually centers the slice.
        val ktParser = openKotlinParserFor(options.kotlinUserHome)
        try {
            var memberNotFound = false
            for (lookupRef in lookupRefs) {
                // `.kt` flesh (T-039): same NoSource/NotJava → Kotlin mapping
                // as the body path; the Found shape below serves both.
                val found = when (
                    val java = dev.jdx.sources.findJavaBodies(sources, lookupRef)
                ) {
                    is dev.jdx.sources.JavaBodyResult.NoSource,
                    is dev.jdx.sources.JavaBodyResult.NotJava,
                    -> dev.jdx.sources.findKotlinBodies(sources, lookupRef, ktParser, ktAliases)
                    else -> java
                }
                when (found) {
                is dev.jdx.sources.JavaBodyResult.Found -> {
                    if (!specified && found.bodies.size > 1) {
                        return ServiceOutcome.Failure(ErrorResult.ambiguous(aroundRaw, matchRefs))
                    }
                    val body = found.bodies.singleOrNull()
                        ?: return ServiceOutcome.Failure(ErrorResult.ambiguous(aroundRaw, matchRefs))
                    val fileLines = readSourceLines(sources, body.file)
                        ?: return failure(5, rawRef, "source read error: cannot read ${body.file}")
                    val allWarnings = warnings + listOfNotNull(mismatchWarning(target, sources, binary))
                    return ServiceOutcome.Source(
                        buildSourceBlock(
                            canonicalRef = binary,
                            declaringType = binary,
                            file = body.file,
                            fileLines = fileLines,
                            startLine = body.startLine,
                            endLine = body.endLine,
                            provenance = listOf(
                                Provenance(
                                    artifact = sources.displayName,
                                    origin = Origin.SOURCES,
                                    file = body.file,
                                    lineRange = body.startLine..body.endLine,
                                ),
                            ),
                            warnings = allWarnings.sortedBy { it.code },
                            contextLines = options.contextLines,
                            lineNumbers = options.lineNumbers,
                            maxLines = options.maxLines,
                        ),
                    )
                }
                is dev.jdx.sources.JavaBodyResult.MemberNotFound -> {
                    memberNotFound = true
                }
                is dev.jdx.sources.JavaBodyResult.NoSource ->
                    // The sources root exists but holds no file for this
                    // class: a stale or mismatched sources jar (T-028), not
                    // a missing one — mirrors [noSourceFileOutcome].
                    return ServiceOutcome.Failure(
                        ErrorResult.notFound(
                            rawRef,
                            detail = "$binary has no source counterpart in $label " +
                                "(SOURCES_VERSION_MISMATCH)",
                        ),
                    )
                is dev.jdx.sources.JavaBodyResult.NotJava ->
                    // Unreachable: the mapping above sends every `NotJava`
                    // through the Kotlin seam, which never emits it — kept
                    // for exhaustiveness.
                    return ServiceOutcome.Failure(
                        ErrorResult.notFound(
                            rawRef,
                            detail = "$binary has no source counterpart in $label " +
                                "(SOURCES_VERSION_MISMATCH)",
                        ),
                    )
                is dev.jdx.sources.JavaBodyResult.ParserUnavailable ->
                    // No usable PSI (T-039): the `.kt` file might as well be
                    // absent — degrade down the reconstruction ladder, which
                    // centers `--around` on decompiled/javap text.
                    return defaultSourceOutcome(
                        rawRef, binary, label, target, opened, winner, options, warnings,
                    )
                is dev.jdx.sources.JavaBodyResult.ParseError ->
                    return failure(5, rawRef, found.message)
            }
        }
        check(memberNotFound) { "lookup spellings exhausted without a terminal result" }
        return ServiceOutcome.Failure(
            ErrorResult.notFound(
                aroundRaw,
                detail = "$aroundRaw has no source counterpart in $label " +
                    "(SOURCES_VERSION_MISMATCH)",
            ),
        )
    } finally {
        runCatching { ktParser.close() }
    }
    }

    /**
     * The generic signature's spelling of a matched member's parameters (`U` for
     * `U identity(U)`), or `null` when there is none (non-generic members, fields).
     * Used as a second source-lookup key when the query came in erased form.
     */
    private fun genericSpelledRef(target: ClassInfo, match: BytecodeMember): MemberSymbolRef? {
        val info = (match as? BytecodeMember.Method)?.info ?: return null
        val generic = info.genericSignature ?: return null
        val spelled = generic.parameters.map { sigToTypeName(it) ?: return null }
        return MemberSymbolRef(declaringType = target.name, name = info.name, parameterTypes = spelled)
    }

    private fun sigToTypeName(sig: dev.jdx.core.model.TypeSignature): TypeName? = when (sig) {
        is dev.jdx.core.model.TypeVariableSignature ->
            TypeName.ClassType("", listOf(sig.name))
        is dev.jdx.core.model.BaseTypeSignature ->
            TypeName.PrimitiveType(sig.primitive)
        is dev.jdx.core.model.ArrayTypeSignature ->
            sigToTypeName(sig.elementType)?.let { arrayTypeName(it, 1) }
        is dev.jdx.core.model.ClassTypeSignature ->
            TypeName.ClassType(sig.packageName, listOf(sig.simpleName) + sig.innerClasses.map { it.simpleName })
        else -> null
    }

    /** Renders one sliced [SourceBody] as a [ServiceOutcome.Body], reading its file for context. */
    private fun bodyOutcome(
        body: dev.jdx.sources.SourceBody,
        sources: dev.jdx.sources.SourceRoot,
        label: String,
        binary: String,
        canonicalRef: String,
        warnings: List<Warning>,
        options: BodyOptions,
        rawRef: String,
        signature: String? = null,
        doc: List<String>? = null,
    ): ServiceOutcome {
        val fileLines = try {
            sources.openSource(body.file).use {
                it.readBytes().toString(Charsets.UTF_8).split('\n')
                    .map { line -> line.removeSuffix("\r") }
            }
        } catch (e: Exception) {
            return failure(5, rawRef, "source read error: cannot read ${body.file}: ${e.message}")
        }
        return ServiceOutcome.Body(
            buildBodyBlock(
                canonicalRef = canonicalRef,
                declaringType = binary,
                file = body.file,
                fileLines = fileLines,
                startLine = body.startLine,
                endLine = body.endLine,
                provenance = listOf(
                    Provenance(
                        artifact = label,
                        origin = Origin.SOURCES,
                        file = body.file,
                        lineRange = body.startLine..body.endLine,
                    ),
                ),
                warnings = warnings.sortedBy { it.code },
                contextLines = options.contextLines,
                lineNumbers = options.lineNumbers,
                maxLines = options.maxLines,
                signature = signature,
                doc = doc,
            ),
        )
    }

    /** Bytecode members matching [ref] by name, arity and source-simple-name narrowing. */
    private fun matchBytecodeMembers(
        target: ClassInfo,
        ref: MemberSymbolRef,
        // `--view jvm` (T-037): JVM-exact matching only — no property alias,
        // no Kotlin declaration-name aliases (true `javap` parity).
        jvmView: Boolean = false,
    ): List<BytecodeMember> {
        if (ref.name == "<clinit>") return emptyList()
        val wanted = ref.parameterTypes
        if (ref.name == "<init>") {
            val ctors = target.methods.filter { it.name == "<init>" }
            if (wanted == null) return ctors.map { BytecodeMember.Method(it) }
            return ctors.filter { it.descriptor.parameters.size == wanted.size && paramsMatch(it, wanted) }
                .map { BytecodeMember.Method(it) }
        }
        // T-078 property alias: a bare `Owner#name` naming a folded Kotlin property
        // resolves to the property view (JVM exact first — accessors still match
        // below when the query spells `getX`; property alias second).
        if (!jvmView && wanted == null && ref.returnType == null && ref.name in target.kotlinProperties) {
            val view = target.kotlinProperties.getValue(ref.name)
            return listOf(BytecodeMember.Property(view))
        }
        // Kotlin aliases (T-077): a query may spell the Kotlin declaration name
        // (`originalName`) or the JVM name (`renamedForJvm`) — the match always
        // returns the JVM-truthful member; only selection is alias-aware.
        val views = if (jvmView) emptyMap() else target.kotlinMethodViews
        val methods = target.methods.filter { it.name != "<clinit>" && methodNameMatches(it, ref.name, views) }
        val fields = if (wanted == null && ref.returnType == null) {
            target.fields.filter { it.name == ref.name }.map { BytecodeMember.Field(it) }
        } else if (wanted == null) {
            // A return-qualified ref names a method, never a field.
            emptyList()
        } else {
            emptyList()
        }
        val methodCandidates = if (wanted == null) {
            methods.map { BytecodeMember.Method(it) }
        } else {
            methods.filter { methodMatchesParams(it, wanted, views) }
                .map { BytecodeMember.Method(it) }
        }
        val all = methodCandidates + fields
        val returnType = ref.returnType
        if (wanted != null && returnType != null && all.size > 1) {
            val wantKey = bodyTypeKey(returnType)
            val kept = all.filter {
                it !is BytecodeMember.Method || bodyTypeKey(it.info.descriptor.returnType) == wantKey ||
                    it.info.genericSignature?.let { sig -> signatureTypeKey(sig.returnType) == wantKey } == true
            }
            if (kept.isNotEmpty()) return kept
        }
        return all
    }

    private sealed interface BytecodeMember {
        data class Method(val info: MethodInfo) : BytecodeMember
        data class Field(val info: FieldInfo) : BytecodeMember
        data class Property(val view: dev.jdx.core.model.KotlinPropertyView) : BytecodeMember
    }

    /**
     * A Kotlin view of one JVM method for display and alias matching (T-077):
     * the declaring class's `@Metadata` entry for [method], or `null` for
     * Java and unmapped Kotlin members (the JVM projection). `--view jvm`
     * (T-037) always reads `null`.
     */
    private fun kotlinViewOf(
        target: ClassInfo,
        method: MethodInfo,
        jvmView: Boolean = false,
    ): KotlinMethodView? =
        if (jvmView) {
            null
        } else {
            target.kotlinMethodViews[kotlinViewKey(method.name, method.descriptor.descriptor)]
        }

    /** JVM-name or Kotlin-alias name match (T-077); `<clinit>` never matches. */
    private fun methodNameMatches(
        method: MethodInfo,
        wanted: String,
        views: Map<String, KotlinMethodView>,
    ): Boolean {
        if (method.name == wanted) return true
        return views[kotlinViewKey(method.name, method.descriptor.descriptor)]?.displayName == wanted
    }

    /**
     * JVM-arity match, or Kotlin-arity match for a `suspend` method queried
     * without its hidden `Continuation` (T-077): the JVM tail strips to
     * exactly the wanted list.
     */
    private fun methodMatchesParams(
        method: MethodInfo,
        wanted: List<TypeName>,
        views: Map<String, KotlinMethodView>,
    ): Boolean {
        if (matchParameters(method.descriptor.parameters, method.genericSignature?.parameters, wanted)) return true
        val view = views[kotlinViewKey(method.name, method.descriptor.descriptor)]
        if (view?.stripAppliesTo(method.descriptor.parameters) != true) return false
        return matchParameters(
            method.descriptor.parameters.dropLast(1),
            method.genericSignature?.parameters,
            wanted,
        )
    }

    private fun paramsMatch(method: MethodInfo, wanted: List<TypeName>): Boolean =
        matchParameters(method.descriptor.parameters, method.genericSignature?.parameters, wanted)

    private fun matchParameters(
        have: List<TypeName>,
        generic: List<dev.jdx.core.model.TypeSignature>?,
        wanted: List<TypeName>,
    ): Boolean {
        if (have.size != wanted.size) return false
        return have.zip(wanted).withIndex().all { (index, pair) ->
            val (haveOne, want) = pair
            val wantKey = bodyTypeKey(want)
            if (bodyTypeKey(haveOne) == wantKey) true
            // Generic methods erase type variables (`U identity(U)` is `(Object)Object`
            // in the descriptor): the generic signature still names `U` (D-009 —
            // bytecode stays the authority, both spellings are its own words).
            else generic != null && index < generic.size && signatureTypeKey(generic[index]) == wantKey
        }
    }

    /**
     * Bare simple name of a generic-signature type, mirroring [bodyTypeKey]:
     * type variables keep their name, arrays collapse to their element.
     */
    private fun signatureTypeKey(sig: dev.jdx.core.model.TypeSignature): String = when (sig) {
        is dev.jdx.core.model.TypeVariableSignature -> sig.name
        is dev.jdx.core.model.BaseTypeSignature -> sig.primitive.keyword
        is dev.jdx.core.model.ArrayTypeSignature -> signatureTypeKey(sig.elementType)
        is dev.jdx.core.model.ClassTypeSignature -> sig.simpleName
        else -> "void"
    }

    /**
     * Compares one side of a bytecode-vs-ref type comparison by bare simple name,
     * mirroring the T-021 source key: `java.lang.Object` and `Object` share a key,
     * and varargs/arrays collapse to their element (`String...` matches `String[]`).
     */
    private fun bodyTypeKey(type: TypeName): String = when (type) {
        is TypeName.ArrayType -> bodyTypeKey(type.elementType)
        is TypeName.PrimitiveType -> type.simpleName
        is TypeName.ClassType -> type.simpleName
    }

    /**
     * Canonical refs for bytecode matches: `:return` is suffixed only when sibling
     * rows share name and erased parameters (bridge/covariant overloads,
     * PROPOSAL.md §6) — the same rule [buildMemberListing] uses. Sorted, so
     * ambiguity candidate lists are stable.
     */
    private fun canonicalMemberRefs(
        target: ClassInfo,
        matches: List<BytecodeMember>,
        jvmView: Boolean = false,
    ): List<String> =
        orderedMemberRefs(target, matches, jvmView).sorted()

    /**
     * The same refs in declaration order, for pairing against [matches] with
     * [zip]: the sorted form above must never be zipped against the
     * declaration-ordered match list — the orders differ for bridge/field
     * siblings and the refs would land on the wrong rows.
     */
    private fun orderedMemberRefs(
        target: ClassInfo,
        matches: List<BytecodeMember>,
        jvmView: Boolean = false,
    ): List<String> {
        val methods = matches.filterIsInstance<BytecodeMember.Method>()
        val siblingCounts = methods.groupingBy {
            it.info.name to it.info.descriptor.parameters.joinToString("") { parameter -> parameter.descriptor }
        }.eachCount()
        return matches.map { match ->
            when (match) {
                is BytecodeMember.Method -> {
                    // Sibling detection stays JVM-keyed (stable under renames);
                    // the ref itself spells the Kotlin view (T-077).
                    val key = match.info.name to
                        match.info.descriptor.parameters.joinToString("") { parameter -> parameter.descriptor }
                    methodRefString(
                        declaring = target.name,
                        member = match.info,
                        view = kotlinViewOf(target, match.info, jvmView),
                        disambiguateReturn = (siblingCounts[key] ?: 0) > 1 && match.info.name != "<init>",
                    )
                }
                is BytecodeMember.Field ->
                    SymbolRefPrinter.print(MemberSymbolRef(target.name, match.info.name))
                // T-078 properties spell `Owner#name` (no params — properties never overload).
                is BytecodeMember.Property ->
                    SymbolRefPrinter.print(MemberSymbolRef(target.name, match.view.propertyName))
            }
        }
    }

    /** Did-you-mean refs for a missed member: name-near members of the same type. */
    private fun suggestSimilarMember(
        target: ClassInfo,
        missed: String,
        cap: Int = 5,
        // `--view jvm` (T-037): JVM names only — no Kotlin aliases, no properties.
        jvmView: Boolean = false,
    ): List<String> {
        // Kotlin declaration names join the pool (T-077): a mistyped
        // `originalName` should suggest the Kotlin spelling, not the JVM one.
        // T-078 property names join too.
        val names = (target.methods.map { it.name } +
            (if (jvmView) emptyList() else target.methods.mapNotNull { kotlinViewOf(target, it)?.displayName }) +
            target.fields.map { it.name } +
            (if (jvmView) emptyList() else target.kotlinProperties.keys.toList()))
            .filter { it != "<clinit>" }.distinct()
        val near = names.filter { levenshtein(it, missed) <= 2 }.sorted()
            .ifEmpty { return emptyList() }
        val refs = mutableListOf<String>()
        for (name in near) {
            for (method in target.methods.filter { it.name == name }) {
                refs.add(
                    SymbolRefPrinter.print(
                        MemberSymbolRef(
                            declaringType = target.name,
                            name = method.name,
                            parameterTypes = method.descriptor.parameters,
                        ),
                    ),
                )
            }
            // Alias hits spell the Kotlin ref (T-077): the JVM loop above
            // found nothing under a Kotlin name, so emit the view spelling.
            if (!jvmView) {
                for (method in target.methods.filter {
                    it.name != name && kotlinViewOf(target, it)?.displayName == name
                }) {
                    refs.add(methodRefString(target.name, method, kotlinViewOf(target, method), false))
                }
            }
            for (field in target.fields.filter { it.name == name }) {
                refs.add(SymbolRefPrinter.print(MemberSymbolRef(target.name, field.name)))
            }
            // T-078 property hits spell `Owner#name`.
            if (!jvmView && name in target.kotlinProperties) {
                refs.add(SymbolRefPrinter.print(MemberSymbolRef(target.name, name)))
            }
            if (refs.size >= cap) break
        }
        return refs.take(cap)
    }

    // -- paths ------------------------------------------------------------------

    private fun entryToBinary(entry: String): String =
        entry.removeSuffix(".class").replace('/', '.')

    private fun entryForBinary(binary: String): String =
        binary.replace('.', '/') + ".class"
}
