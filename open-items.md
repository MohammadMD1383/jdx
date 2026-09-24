# Open items

The running list of everything unfinished, deferred, or known-flaky — tasks,
follow-ups, and environment caveats in one place, so no contributor has to
mine the session log to learn what still needs doing.

**Rules:**
- `open-items.md` is now the roadmap: it records known limitations, deferred
  follow-ups, and the phase-2 backlog. New work starts here or from owner direction.
- Keep this file current: move finished items out, record newly found ones.

---

## Known test / environment caveats (pre-existing, not failures)

- `JavapCorpusSoakTest` reds only on JDK-internal synthetic `access$`
  members (proven pre-existing on a clean tree).
- `verifyTier1Budget` is red on this machine (machine variance, 0 test
  failures — not a test failure).
- A one-build `:lint` validation red ("uses this output of
  `:core:compileKotlin`") can appear on the first `check` after a
  build-logic change amid `--rerun-tasks`/stash churn; it self-heals on
  retry and never reproduces in isolation.
- `indexed artifacts` in `daemon status` reports 0 by design with live
  roots (no persistent index — live-roots-first).

---

## Documented behavior limitations (by design — record a new item to change any)

Nothing below is a bug; each is a candidate for future work.

### Query power

- **No persistent index acceleration.** `usages`/`hierarchy`/`callers`/`calls`/
  `samples` re-scan live bytecode roots on every query (live-roots-first);
  slow on giant roots, and `bench` cold-query rows read OVER by
  design. Fixing means persisting the reference edges in SQLite with indexed
  reads + invalidation.
- **Call hierarchy is not override-aware.** Exact name+descriptor matching
  only — no virtual-dispatch resolution, so `callers` misses call sites
  through supertypes/overrides (documented limitation).
- **`--kind throw` reads declarations, not sites.** Lists methods declaring
  the type in `throws`; attributing `ATHROW` sites needs data-flow the
  extractor deliberately skips.
- **No line numbers on bytecode reference edges.** The v1 `ref` table stores
  no line data; usage rows lack file:line (source-dir textual
  mentions have it).
- **`samples` snippets capped at 15 lines** (`MAX_SAMPLE_SNIPPET_LINES`) with
  a `… (truncated)` marker.
- **`usages --context` stays a `samples` redirect.** Exits 3 naming
  `jdx samples` instead of rendering inline snippets.
- **No `usages` row in `jdx bench`.** Deliberately omitted: without an indexed
  path it would only document the known gap.

### Kotlin

- **File facades stay JVM-projected.** Top-level Kotlin functions show the JVM
  projection: no property/suspend/`@JvmName` mapping (facades carry no
  `KmClass`).
- **Nullability is not rendered.** Types render Java-style; only `suspend`
  returns use the `KmType` (full nullability stays deferred).
- **Mismatch-detector blind spots** (see the detector KDoc): companion
  `const` vals under a direct `$Companion` query may false-warn; removing a
  body-declared property from sources is undetected (adding/renaming warns).
- **PSI minimal areas**: facade file-docs, Kotlin enum entries with
  bodies, and parameterised extension queries stay minimal.
- **No fallback lexer.** Without the ~55 MB compiler sidecar, `.kt` bodies
  degrade to decompile/javap; the PROPOSAL §22 risk row's "fallback lexer
  extractor behind a flag" was never built.

### Daemon / serving

- **Warm text is plain.** No ANSI even on a TTY — the daemon has none, so it
  renders `color=false`; piped output is byte-identical to cold.
- **The daemon never fetches.** Stored `--repo` mirrors are inert; an
  unresolvable stored coordinate fails warm queries with exit 5.
- **Read-only v1 wire.** `doctor` is refused over the daemon (exit 6);
  `ws`/`cache` are absent from the wire on purpose.
- **MCP is workspace-bound.** No project auto-discovery, no explicit
  `--jars`/`--coord` — stored workspace plus a per-call override only.
- **`serve` blocks until interrupted.** No idle shutdown; binds
  `127.0.0.1:7070` unless told otherwise.
- **AppCDS follow-ups open**: no `doctor` row for archive presence,
  no `install.sh` change.

---

## Phase-2 backlog (PROPOSAL §21 — explicitly out of v1 scope)

Recorded so they are not lost. Each is large and would be sliced on boarding.

- **`jdx diff a.jar b.jar`** — public-API diff with binary-compatibility
  warnings, for dependency upgrades.
- **Mappings / remapping** — read Tiny/SRG/ProGuard mapping files so `jdx`
  answers obfuscated jars in either namespace. High value on this machine.
- **Resources & metadata inspection** — `META-INF/services`, `module-info`,
  manifest attributes, SPI providers, resource listing and extraction.
- **Annotation-driven views** — e.g. every `@Deprecated(forRemoval=true)`,
  all Spring `@Bean` factories, all JUnit `@Test`s.
- **`--since` / API-level reporting** — which JDK release introduced a member.
- **Dataflow-lite** — `jdx flow <method>`, a simplified reaching-values view.
- **Multi-release jar variant selection** beyond the current warning.
- **Scala/Groovy language views.**
- **Publishing** — Homebrew/AUR packaging, GitHub Releases with a
  native-image build.

---

*Last updated: 2026-09-24.*
