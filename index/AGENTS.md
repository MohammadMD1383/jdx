# AGENTS.md — `index/`

Bytecode reading, artifacts, store, workspaces, and `JdxService` — **all query
behaviour lives here** (adapters call in, never the reverse). `explicitApi()` is on.

## Key files

- `service/JdxService` — every command's implementation; `service/RpcDispatch` —
  wire-command dispatch (same package, keeps the service file untouched).
- `asm/AsmClassReader` — `ClassReader` with `SKIP_FRAMES` (never `SKIP_DEBUG`:
  parameter names live there). Rejects class files newer than the *running* JDK.
- `artifact/` — `ArtifactLoader` (jars, dirs, `jrt:/`), `ZipSafety` (traversal +
  zip-bomb caps), `SourcesPairing` (5 pairing rules), `MultiRelease` (warn, don't
  resolve variants), content-hash artifact identity. Dedup identical resolved roots
  before querying (same file via glob + explicit path must not double-scan).
- `refs/ReferenceExtractor` — bytecode → reference edges (`call`/`read`/`write`/
  `ref`/`new`/`throw`/`annotation`); no line numbers on bytecode edges by design.
- `store/` — `IndexStore` interface + `sqlite/SqliteIndexStore` (WAL, single global
  `~/.cache/jdx/index/v1.db`, artifact-scoped rows). No SQL outside the impl package.
- `workspace/` — TOML workspaces, classpath-order shadowing, `DUPLICATE_FQN`,
  `ProjectDiscovery` (walk up for build files; **never run Gradle/Maven**;
  nesting checks use `Path.startsWith`, never a `"$candidate/"` prefix),
  `WorkspaceResolver` (explicit `--jars` merge in front of stored roots).
  `--jars` expansion (`JdxService.expandJarSpec`): `~`/`~\` home, slash-normalised
  glob roots (drive/UNC-safe), case-insensitive `.jar`/`.zip`, `toRealPath`
  dedupe, symlink-loop-safe walk; the `active-workspace` file is LF-pinned.
- `maven/` — `MavenResolver` (local caches first) + `MavenFetch` (opt-in `--fetch`,
  checksum-verified into `~/.cache/jdx/m2/`); `--repo` mirrors tried before Central.
- `kotlin/` — `KotlinMetadata` (never-throws `@Metadata` decode), `KotlinMembers`
  (suspend/`@JvmName`/`internal` repair, property folding, default-arg `= ...`),
  `KotlinSidecarFetch` (7-artifact table, SHA-1, skip-present/resume).
- `usages/SourceUsages` — textual whole-word mentions (`ref` kind, file:line).
- `cache/CacheService` — `info`/`gc`/`clear`; JDK rows kept while any workspace
  includes the JDK; orphan daemon `.log`s swept when no daemon answers.

## Rules

- Bytecode-authoritative: overload ambiguity is decided from bytecode *before*
  sources/decompilers run. Member matching is overload-blind unless the ref carries
  params. Call matching is exact name+descriptor — not override-aware, by design.
- Kotlin views ride `ClassInfo` with defaults (no `ktmeta` blob writes); file facades
  stay JVM-projected; `core` carriers must not leak mutable `KmClass` equality into
  pinned shapes (compare contents, never carriers).
- `doctor` is refused over the daemon wire (exit 6); the daemon never fetches.

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
- Text⊆JSON checks must compare `JsonEscape.quote(entity)` minus quotes — result
  payloads legitimately contain envelope field names. Calibrate pairing rules against
  a scratch dump of the real fixture (dump first, rule second).
