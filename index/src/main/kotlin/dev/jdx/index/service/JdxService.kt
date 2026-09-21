package dev.jdx.index.service

import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.ModuleSymbolRef
import dev.jdx.core.model.Origin
import dev.jdx.core.model.PackageSymbolRef
import dev.jdx.core.model.Provenance
import dev.jdx.core.model.SymbolRef
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.TypeSymbolRef
import dev.jdx.core.model.Visibility
import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import dev.jdx.core.model.arrayTypeName
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.ref.SymbolRefParser
import dev.jdx.core.ref.SymbolRefParseResult
import dev.jdx.core.ref.SymbolRefPrinter
import dev.jdx.core.render.BodyBlock
import dev.jdx.core.render.ClassCard
import dev.jdx.core.render.DEFAULT_BODY_MAX_LINES
import dev.jdx.core.render.DEFAULT_DOC_MAX_LINES
import dev.jdx.core.render.DEFAULT_SIGNATURE_LIMIT
import dev.jdx.core.render.DEFAULT_SOURCE_MAX_LINES
import dev.jdx.core.render.DocBlock
import dev.jdx.core.render.DocSubject
import dev.jdx.core.render.MemberKind
import dev.jdx.core.render.SignatureBlock
import dev.jdx.core.render.SignatureEntry
import dev.jdx.core.render.SourceBlock
import dev.jdx.core.render.buildBodyBlock
import dev.jdx.core.render.buildDocBlock
import dev.jdx.core.render.buildSignatureBlock
import dev.jdx.core.render.buildSourceBlock
import dev.jdx.core.render.renderJavadoc
import dev.jdx.core.render.DEFAULT_MEMBER_LIMIT
import dev.jdx.core.render.DEFAULT_SEARCH_LIMIT
import dev.jdx.core.render.DEFAULT_TREE_DEPTH
import dev.jdx.core.render.ErrorResult
import dev.jdx.core.render.LsListing
import dev.jdx.core.render.LsTypeEntry
import dev.jdx.core.render.MemberListing
import dev.jdx.core.render.MemberListingOptions
import dev.jdx.core.render.MemberSort
import dev.jdx.core.render.OBJECT_BINARY_NAME
import dev.jdx.core.render.PackageEntry
import dev.jdx.core.render.SearchHit
import dev.jdx.core.render.SearchListing
import dev.jdx.core.render.SignatureLines
import dev.jdx.core.render.TreeListing
import dev.jdx.core.render.buildArtifactTree
import dev.jdx.core.render.buildLsListing
import dev.jdx.core.render.buildSearchListing
import dev.jdx.core.render.buildTreeListing
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
    ) {
        public companion object {
            /** Converts a resolved workspace selection into the roots a query opens. */
            public fun fromResolved(
                resolved: dev.jdx.index.workspace.WorkspaceResolver.ResolvedRoots,
            ): RootsSpec = RootsSpec(
                jarSpecs = resolved.jarSpecs,
                includeJdk = resolved.includeJdk,
                extraWarnings = resolved.warnings,
            )
        }
    }

    /** `--kind` values for the members family (PROPOSAL.md §7.1). */
    public enum class KindFilter(public val flag: String) {
        ALL("all"),
        METHOD("method"),
        FIELD("field"),
        CTOR("ctor"),
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

        /** The shared JSON envelope for [command]. */
        public fun toJson(command: String): String

        /** A member listing (`members`, `outline`) — exit 0. */
        public data class MemberList(public val listing: MemberListing) : ServiceOutcome {
            override val exitCode: Int = 0
            override fun renderText(color: Boolean): String = listing.renderText(color)
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
                MemberResolutionOptions(includeSynthetic = includeSynthetic),
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
            return ServiceOutcome.MemberList(merged)
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
            if (filters.kind == KindFilter.FIELD) return@filter false
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
            if (filters.kind == KindFilter.METHOD || filters.kind == KindFilter.CTOR) return@filter false
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
        return resolved.copy(methods = methods, fields = fields)
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

            val bytecodeMatches = matchBytecodeMembers(target, memberRef)
                .filter { match -> options.includeSynthetic || !isSyntheticMember(match) }
            if (bytecodeMatches.isEmpty()) {
                if (memberRef.name == "<clinit>") {
                    return failure(3, rawRef, "usage error: static initialisers have no signature to show: '$rawRef'")
                }
                return ServiceOutcome.Failure(
                    ErrorResult.notFound(rawRef, suggestSimilarMember(target, memberRef.name)),
                )
            }

            val matchRefs = canonicalMemberRefs(target, bytecodeMatches)
            // Pair in declaration order first: `canonicalMemberRefs` sorts, and
            // zipping a sorted list against declaration-ordered matches swaps
            // refs whenever the orders differ (bridge/field siblings).
            val entries = bytecodeMatches.zip(orderedMemberRefs(target, bytecodeMatches)).map { (match, ref) ->
                when (match) {
                    is BytecodeMember.Method -> SignatureEntry(
                        canonicalRef = ref,
                        kind = if (match.info.name == "<init>") MemberKind.CONSTRUCTOR else MemberKind.METHOD,
                        signature = SignatureLines.methodLine(
                            member = match.info,
                            declaringSimpleName = target.name.simpleName,
                        ),
                        declaringType = binary,
                    )
                    is BytecodeMember.Field -> SignatureEntry(
                        canonicalRef = ref,
                        kind = MemberKind.FIELD,
                        signature = SignatureLines.fieldLine(match.info),
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
                memberDocOutcome(ref, rawRef, binary, target, workspace, opened, providers, warnings, options)
            } else {
                typeDocOutcome(binary, rawRef, target, opened, providers, warnings, options)
            }
        } finally {
            opened.forEach { it.root.close() }
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
            for (lookupRef in lookupRefs) {
                when (val found = dev.jdx.sources.findMemberDocs(sources, lookupRef)) {
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
                        return ServiceOutcome.Failure(
                            ErrorResult.notFound(
                                rawRef,
                                detail = "$binary only ships Kotlin sources here " +
                                    "(Kotlin bodies: T-039)",
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
                findInheritedMemberDoc(target, memberRef, opened, providers, workspace, options.raw)
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
            val detail = if (directDoc != null || sourceDeclaresMember(sources, binary, memberRef.name)) {
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
            return when (val found = dev.jdx.sources.findTypeDoc(sources, binary)) {
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
                    ServiceOutcome.Failure(
                        ErrorResult.notFound(
                            rawRef,
                            detail = "$binary only ships Kotlin sources here " +
                                "(Kotlin bodies: T-039)",
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
                    when (val found = dev.jdx.sources.findMemberDocs(superSources, lookupRef)) {
                        is dev.jdx.sources.JavaDocResult.Found -> {
                            val doc = found.docs.firstOrNull() ?: break
                            val lines = docLines(doc, raw, inheritDocReplacement = null)
                            if (lines.isEmpty()) break
                            return InheritedDoc(doc, superBinary, superSources.displayName, lines)
                        }
                        else -> {
                            // MemberNotFound tries the next spelling; NoSource,
                            // NotJava and ParseError move to the next supertype.
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
    ): Boolean {
        val listed = dev.jdx.sources.listJavaMembers(sources, binary)
        return (listed as? dev.jdx.sources.JavaMemberList.Listed)
            ?.members?.any { it.name == memberName } == true
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
                    )
                    is BytecodeMember.Field -> SignatureLines.fieldLine(match.info)
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
            val label = rootLabel(opened[winner], binary)
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
                    opened = opened,
                    winner = winner,
                    label = label,
                    warnings = warnings,
                    options = options,
                )
            }
            val sources = openSourcesFor(opened[winner])
                // No sources root is routine (most jars ship without one): the
                // ladder degrades to reconstruction (T-026), not a dead end.
                ?: return decompiledBodyOutcome(
                    memberRef = memberRef,
                    rawRef = rawRef,
                    binary = binary,
                    target = target,
                    lookupRefs = lookupRefs,
                    matchRefs = matchRefs,
                    specified = specified,
                    signatureLine = signatureLine,
                    opened = opened,
                    winner = winner,
                    label = label,
                    warnings = warnings,
                    options = options,
                )
            try {
                var memberNotFound = false
                for (lookupRef in lookupRefs) {
                    when (val found = dev.jdx.sources.findJavaBodies(sources, lookupRef)) {
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
                        return ServiceOutcome.Failure(
                            ErrorResult.notFound(
                                rawRef,
                                detail = "$binary only ships Kotlin sources here " +
                                    "(Kotlin bodies: T-039)",
                            ),
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
        }
    }

    private fun openSourcesFor(open: OpenRoot): dev.jdx.sources.SourceRoot? = when (val root = open.root) {
        is dev.jdx.index.artifact.JarArtifact -> root.openSources()
        is dev.jdx.index.artifact.JrtArtifact -> root.openSources()
        else -> null
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
     * name only their cause.
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
    }

    /**
     * The member name behind one bytecode match, in `javap` terms: `<init>`
     * for constructors (printed as the FQN), the plain name otherwise.
     */
    private fun javapNameOf(match: BytecodeMember): String = when (match) {
        is BytecodeMember.Method -> match.info.name
        is BytecodeMember.Field -> match.info.name
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
                // ladder degrades to reconstruction (T-026), not a dead end.
                ?: return decompiledSourceOutcome(rawRef, binary, label, target, opened, winner, options, warnings)
            try {
                return sourceOutcome(binary, rawRef, label, target, sources, options, warnings)
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
    ): ServiceOutcome {
        val aroundRaw = options.aroundRef
        if (aroundRaw != null) {
            return aroundOutcome(binary, rawRef, aroundRaw, label, target, sources, options, warnings)
        }
        val path = sources.findSource(binary)
            ?: return noSourceFileOutcome(binary, rawRef, label, sources)
        if (path.endsWith(".kt")) {
            // Kotlin sources need the PSI integration (T-039): served whole,
            // a `.kt` file would be verbatim text, but facade/class mapping
            // makes raw serving potentially misleading — degrade like `body`.
            return ServiceOutcome.Failure(
                ErrorResult.notFound(
                    rawRef,
                    detail = "$binary only ships Kotlin sources here " +
                        "(Kotlin bodies: T-039)",
                ),
            )
        }
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
     * Maps a missing source file to its honest degradation: `.kt`-only roots
     * name T-039; a root that holds no file at all for the class names a
     * possible version mismatch (T-028) — reconstruction is only the fallback
     * when no sources root exists at all.
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
                        "(Kotlin bodies: T-039)",
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
    ): ServiceOutcome {
        val matched = matchAroundMember(target, aroundRaw, rawRef)
        if (matched is AroundMatch.Failed) return matched.outcome
        matched as AroundMatch.Ready
        val (effectiveRef, lookupRefs, matchRefs, specified) = matched
        var memberNotFound = false
        for (lookupRef in lookupRefs) {
            when (val found = dev.jdx.sources.findJavaBodies(sources, lookupRef)) {
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
                    return ServiceOutcome.Failure(
                        ErrorResult.notFound(
                            rawRef,
                            detail = "$binary only ships Kotlin sources here " +
                                "(Kotlin bodies: T-039)",
                        ),
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
            ),
        )
    }

    /** Bytecode members matching [ref] by name, arity and source-simple-name narrowing. */
    private fun matchBytecodeMembers(
        target: ClassInfo,
        ref: MemberSymbolRef,
    ): List<BytecodeMember> {
        if (ref.name == "<clinit>") return emptyList()
        val wanted = ref.parameterTypes
        if (ref.name == "<init>") {
            val ctors = target.methods.filter { it.name == "<init>" }
            if (wanted == null) return ctors.map { BytecodeMember.Method(it) }
            return ctors.filter { it.descriptor.parameters.size == wanted.size && paramsMatch(it, wanted) }
                .map { BytecodeMember.Method(it) }
        }
        val methods = target.methods.filter { it.name == ref.name && it.name != "<clinit>" }
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
            methods.filter { it.descriptor.parameters.size == wanted.size && paramsMatch(it, wanted) }
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
    }

    private fun paramsMatch(method: MethodInfo, wanted: List<TypeName>): Boolean {
        val generic = method.genericSignature?.parameters
        return method.descriptor.parameters.zip(wanted).withIndex().all { (index, pair) ->
            val (have, want) = pair
            val wantKey = bodyTypeKey(want)
            if (bodyTypeKey(have) == wantKey) true
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
    private fun canonicalMemberRefs(target: ClassInfo, matches: List<BytecodeMember>): List<String> =
        orderedMemberRefs(target, matches).sorted()

    /**
     * The same refs in declaration order, for pairing against [matches] with
     * [zip]: the sorted form above must never be zipped against the
     * declaration-ordered match list — the orders differ for bridge/field
     * siblings and the refs would land on the wrong rows.
     */
    private fun orderedMemberRefs(target: ClassInfo, matches: List<BytecodeMember>): List<String> {
        val methods = matches.filterIsInstance<BytecodeMember.Method>()
        val siblingCounts = methods.groupingBy {
            it.info.name to it.info.descriptor.parameters.joinToString("") { parameter -> parameter.descriptor }
        }.eachCount()
        return matches.map { match ->
            when (match) {
                is BytecodeMember.Method -> {
                    val base = SymbolRefPrinter.print(
                        MemberSymbolRef(
                            declaringType = target.name,
                            name = match.info.name,
                            parameterTypes = match.info.descriptor.parameters,
                        ),
                    )
                    val key = match.info.name to
                        match.info.descriptor.parameters.joinToString("") { parameter -> parameter.descriptor }
                    if ((siblingCounts[key] ?: 0) > 1 && match.info.name != "<init>") {
                        base + ":" + dev.jdx.core.render.SignatureLines.renderTypeName(
                            match.info.descriptor.returnType,
                        )
                    } else {
                        base
                    }
                }
                is BytecodeMember.Field ->
                    SymbolRefPrinter.print(MemberSymbolRef(target.name, match.info.name))
            }
        }
    }

    /** Did-you-mean refs for a missed member: name-near members of the same type. */
    private fun suggestSimilarMember(target: ClassInfo, missed: String, cap: Int = 5): List<String> {
        val names = (target.methods.map { it.name } + target.fields.map { it.name })
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
            for (field in target.fields.filter { it.name == name }) {
                refs.add(SymbolRefPrinter.print(MemberSymbolRef(target.name, field.name)))
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
