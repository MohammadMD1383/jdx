# Open items

The running list of everything unfinished, deferred, or known-flaky — tasks,
follow-ups, and environment caveats in one place, so no contributor has to
mine the session log to learn what still needs doing.

**Rules (always on, see CLAUDE.md §7):**
- `docs/TASKS.md` stays the source of truth for *tasks*; this file points at
  it and additionally records non-task follow-ups and known caveats that have
  no task number.
- Every working session updates this file: move finished items out, record
  newly found ones. A session that changed behaviour or found a caveat and
  left this file stale is incomplete.

---

## Next tasks (all DONE — board empty as of session 85)

No open tasks. New work gets the next free number per the board rule; the
rows below are kept as the closing record.

| Item | State | Notes |
|---|---|---|
| T-084 `doctor` daemon aliveness | DONE (session 83) | Probes each `.sock` with `health`: running → OK, stale → WARN (D-065, L-113) |
| T-085 `cache gc` daemon `.log` sweep | DONE (session 84) | Orphan `.log` files collected by `gc` (live kept, throwing probe keeps); dead sockets still `stop`/`doctor` business (D-066) |
| T-086 warm text output | DONE (session 85) | Daemon renders plain text into a `"text"` envelope field (`warmText`/`warmBrief`/`warmMaxLines` params); `--json` bytes untouched (D-067, L-114) |

---

## Known test / environment caveats (pre-existing, not failures)

- `JavapCorpusSoakTest` reds only on JDK-internal synthetic `access$`
  members (proven pre-existing on the stashed-clean tree; drift family from
  sessions 53/55/56/58).
- `verifyTier1Budget` is red on this machine (machine variance, 0 test
  failures — not a test failure).
- A one-build `:lint` validation red ("uses this output of
  `:core:compileKotlin`") can appear on the first `check` after a
  build-logic change amid `--rerun-tasks`/stash churn; it self-heals on
  retry and never reproduced in isolation (session 82, L-112).
- `indexed artifacts` in `daemon status` reports 0 by design with live
  roots (no persistent index in T-082; D-043 precedent).

---

## Documented behavior limitations (by design — file a task to change any)

Each item names the decision or task that fixed its current behavior. Nothing
below is a bug; each is a candidate the owner may or may not promote to the
board (next free number: T-087).

### Query power

- **No persistent index acceleration.** `usages`/`hierarchy`/`callers`/`calls`/
  `samples` re-scan live bytecode roots on every query (D-043 live-roots-first
  precedent); slow on giant roots, and `bench` cold-query rows read OVER by
  design. Fixing means persisting the T-029 edges in SQLite with indexed
  reads + invalidation (likely several slices).
- **Call hierarchy is not override-aware.** Exact name+descriptor matching
  only — no virtual-dispatch resolution, so `callers` misses call sites
  through supertypes/overrides (T-033, documented limitation).
- **`--kind throw` reads declarations, not sites.** Lists methods declaring
  the type in `throws`; attributing `ATHROW` sites needs data-flow the
  extractor deliberately skips (D-053 §2).
- **No line numbers on bytecode reference edges.** The v1 `ref` table stores
  no line data (D-042 §4); usage rows lack file:line (source-dir textual
  mentions have it).
- **`samples` snippets capped at 15 lines** (`MAX_SAMPLE_SNIPPET_LINES`) with
  a `… (truncated)` marker.
- **`usages --context` stays a `samples` redirect.** Exits 3 naming
  `jdx samples` instead of rendering inline snippets (D-053 §5).
- **No `usages` row in `jdx bench`.** Deliberately omitted: without an indexed
  path it would only document the D-043 gap (T-050).

### Kotlin

- **File facades stay JVM-projected.** Top-level Kotlin functions show the JVM
  projection: no property/suspend/`@JvmName` mapping (facades carry no
  `KmClass`, D-049/D-050).
- **Nullability is not rendered.** Types render Java-style; only `suspend`
  returns use the `KmType` (D-050: full nullability stays deferred).
- **Mismatch-detector blind spots** (D-063, in the detector KDoc): companion
  `const` vals under a direct `$Companion` query may false-warn; removing a
  body-declared property from sources is undetected (adding/renaming warns).
- **PSI minimal areas** (D-055 §6): facade file-docs, Kotlin enum entries with
  bodies, and parameterised extension queries stay minimal.
- **No fallback lexer.** Without the ~55 MB compiler sidecar, `.kt` bodies
  degrade to decompile/javap; the PROPOSAL §22 risk row's "fallback lexer
  extractor behind a flag" was never built.

### Daemon / serving

- **Warm text is plain.** No ANSI even on a TTY — the daemon has none, so it
  renders `color=false`; piped output is byte-identical to cold (D-067 §4).
- **The daemon never fetches.** Stored `--repo` mirrors are inert; an
  unresolvable stored coordinate fails warm queries with exit 5 (D-058 §3).
- **Read-only v1 wire.** `doctor` is refused over the daemon (exit 6);
  `ws`/`cache` are absent from the wire on purpose (D-056 §4).
- **MCP is workspace-bound.** No project auto-discovery, no explicit
  `--jars`/`--coord` — stored workspace plus a per-call override only
  (D-060 §2).
- **`serve` blocks until interrupted.** No idle shutdown; binds
  `127.0.0.1:7070` unless told otherwise (D-061 §4).
- **AppCDS follow-ups open** (T-048): no `doctor` row for archive presence,
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

*Last updated: 2026-09-24 (session 86).*
