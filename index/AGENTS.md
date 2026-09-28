# AGENTS.md — `index/`

Bytecode reading, artifacts, store, workspaces, and `JdxService` — **all query
behaviour lives here** (adapters call in, never the reverse). `explicitApi()` is on.

## Key files

- `service/JdxService` — every command's implementation; `service/RpcDispatch` —
  wire-command dispatch (same package, keeps the service file untouched).
  `JdxService.diff(old, new, options)` is the one query that takes **no `RootsSpec`**: a
  diff names its own two artifacts, so `daemonRoots` short-circuits to `Ready` for
  `RpcCommand.DIFF` and the dispatch ignores the roots it is handed.
- `diff/ArtifactSnapshots` — artifact → `ApiSnapshot`: sorted `classEntryPaths()`,
  `AsmClassReader` per class, and every `UnsupportedVersion`/`Corrupt` skipped with its
  warning rather than failing the artifact. Structure only — a diff never pairs sources
  and never decompiles.
- `asm/AsmClassReader` — `ClassReader` with `SKIP_FRAMES` (never `SKIP_DEBUG`:
  parameter names live there). Rejects class files newer than the *running* JDK.
- `artifact/` — `ArtifactLoader` (jars, dirs, `jrt:/`), `ZipSafety` (traversal +
  zip-bomb caps), `SourcesPairing` (5 pairing rules), `MultiRelease` (warn, don't
  resolve variants), content-hash artifact identity. Dedup identical resolved roots
  before querying (same file via glob + explicit path must not double-scan).
- `refs/ReferenceExtractor` — bytecode → reference edges (`call`/`read`/`write`/
  `ref`/`new`/`throw`/`annotation`); no line numbers on bytecode edges by design.
- `store/` — `IndexStore` interface + `sqlite/SqliteIndexStore` (WAL, single global
  `<cache-dir>/index/v1.db`, artifact-scoped rows). No SQL outside the impl package.
- `workspace/` — TOML workspaces, classpath-order shadowing, `DUPLICATE_FQN`,
  `ProjectDiscovery` (walk up for build files; **never run Gradle/Maven**;
  nesting checks use `Path.startsWith`, never a `"$candidate/"` prefix),
  `WorkspaceResolver` (explicit `--jars` merge in front of stored roots).
  `--jars` expansion (`JdxService.expandJarSpec`): `~`/`~\` home, slash-normalised
  glob roots (drive/UNC-safe), case-insensitive `.jar`/`.zip`, `toRealPath`
  dedupe, symlink-loop-safe walk; the `active-workspace` file is LF-pinned.
- `maven/` — `MavenResolver` (local caches first) + `MavenFetch` (opt-in `--fetch`,
  checksum-verified into `<cache-dir>/m2/`); `--repo` mirrors tried before Central.
- `diff/ArtifactSnapshots` — one opened root → `ApiSnapshot` + that root's warnings,
  for `jdx diff` (issue #23). Bytecode only (no `SourcesPairing`, no decompiler) and
  degrade-don't-fail: an unparseable or too-new class is skipped with its warning and
  the rest of the jar still diffs. Warnings come back code-then-subject sorted.
- `kotlin/` — `KotlinMetadata` (never-throws `@Metadata` decode), `KotlinMembers`
  (suspend/`@JvmName`/`internal` repair, property folding, default-arg `= ...`),
  `KotlinSidecarFetch` (7-artifact table, SHA-1, skip-present/resume).
- `usages/SourceUsages` — textual whole-word mentions (`ref` kind, file:line).
- `cache/CacheService` — `info`/`gc`/`clear`; JDK rows kept while any workspace
  includes the JDK; orphan daemon `.log`s swept when no daemon answers.
  `cache/PlatformMigration` — one-shot legacy dot-dir → per-OS move (never merge).

## Rules

- Bytecode-authoritative: overload ambiguity is decided from bytecode *before*
  sources/decompilers run. Member matching is overload-blind unless the ref carries
  params. Call matching is exact name+descriptor — not override-aware, by design.
- Kotlin views ride `ClassInfo` with defaults (no `ktmeta` blob writes); file facades
  stay JVM-projected; `core` carriers must not leak mutable `KmClass` equality into
  pinned shapes (compare contents, never carriers).
- `doctor` is refused over the daemon wire (exit 6); the daemon never fetches.
- `diff` is the one command with **no classpath**: it names its own two artifacts, so
  it takes no `RootsSpec`, never consults the JDK, and `JdxService.daemonRoots(…,
  command = RpcCommand.DIFF)` short-circuits to an empty `RootsSpec` (otherwise the
  three daemon adapters would exit 4 for a diff that needs no workspace). Its hosts
  are `<cache>/m2`, the Gradle files cache, `~/.m2`, and — only with `--fetch` — the
  `--repo` mirrors. One side resolves to exactly one artifact; two is exit 3, because a
  diff has no root to hold a set.

## Gotchas (distilled)

- Decode `@Metadata` via the `Metadata(...)` helper + `readLenient` — the
  `KotlinClassHeader` constructor does not compile under `-Werror`. Never-throw
  metadata/ASM code must distrust every accessor (`lateinit` classifier,
  `Type.getArgumentTypes`/`getClassName` throw on hostile input incl.
  `AssertionError`); check hostile descriptors textually.
- `KmProperty.isVar` is unset on hand-built containers — infer `var` as
  `isVar || setter != null`. Split `$`-nested supertypes at the model edge
  (`GenericRef.toTypeName`), never in the signature parser (byte-faithful fixed point).
- Sample corpus classes through `ArtifactLoader`, never raw zip entries
  (multi-release `module-info` isn't addressable). `ZipFile` reads don't verify CRCs —
  corrupt framing (zeroed zlib header), not entry bytes, to pin read-failure branches.
- Indexer benchmarks must run under the *runtime* JDK, not the toolchain JDK, or every
  newer class warns `UNSUPPORTED_CLASS_VERSION` and measures zero.
- `diff`'s `--repo` ordering is spelled twice: `JdxService.diffRepoBaseUrls` here and
  `ReadCommandSupport.buildRepoBaseUrls` in `cli`, because `cli` sits *above* `index` and
  cannot be called from it. If that helper ever moves down, delete this copy — do not add
  a third. `ArtifactSpec` also has no repository-injection seam (unlike `RootsSpec`'s
  `mavenResolve`), so a *resolvable* coordinate side cannot be tested hermetically yet:
  only the malformed (exit 3) and unresolvable (exit 5) branches are.
- Text⊆JSON checks must compare `JsonEscape.quote(entity)` minus quotes — result
  payloads legitimately contain envelope field names. Calibrate pairing rules against
  a scratch dump of the real fixture (dump first, rule second).
- `ArtifactTestJars` is **not** a resolver: `binaryJar` delegates to
  `dev.jdx.testsupport.fixtures.FixtureJars` (#66 collapsed the copy it used to hold).
  The `craftJar` / `manifestBytes` hostile-jar builders stay here because they are this
  module's fault-injection tools, not shared support.
