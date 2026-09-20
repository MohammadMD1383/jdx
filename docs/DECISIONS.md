# Decision log (ADR-lite)

Every entry here was **decided explicitly by the project owner** or, where marked
`proposed-by-implementer`, chosen during implementation and open to challenge.

**Rules for contributors (human or agent):**
- Decisions marked **`locked`** are settled. Do **not** re-litigate them in a PR or a
  refactor. If you believe one is wrong, open an issue arguing the case and get an explicit
  reversal recorded as a new entry that supersedes the old one — never a silent change.
- Decisions marked **`proposed-by-implementer`** are defaults, not commandments. Improve them
  freely; update the entry when you do.
- Adding a decision: append a new `D-nnn` to the newest shard in `docs/decisions/`, never
  renumber, never delete, and add it to the index below. Superseding an entry means adding
  a new one and editing the old one's Status to `superseded by D-nnn`.

| Status values | Meaning |
|---|---|
| `locked` | Owner decided. Requires owner reversal to change. |
| `proposed-by-implementer` | Default chosen while building. Change with a normal PR. |
| `open` | Known question, not yet answered. Ask the owner before it blocks you. |
| `superseded by D-nnn` | No longer in force. |

**Entries are sharded (D-023):** full text lives in `docs/decisions/D-001-025.md` or
`docs/decisions/D-026-050.md` (25 entries per shard). Read the index below; open a shard
only for the entries a task needs. Context efficiency is the point — don't read the whole
log.

## Index

| ID | Subject | Status | Shard |
|---|---|---|---|
| D-001 | Implementation language: Kotlin on Gradle | locked | `D-001-025.md` |
| D-002 | Decompiler: Vineflower bundled, `javap` alternate engine | locked | `D-001-025.md` |
| D-003 | Index: persistent on-disk, content-hash keyed | locked | `D-001-025.md` |
| D-004 | Interfaces: CLI + MCP + daemon + HTTP, all four | locked | `D-001-025.md` |
| D-005 | Command name: `jdx` | locked | `D-001-025.md` |
| D-006 | Classpath resolution: all four mechanisms | locked | `D-001-025.md` |
| D-007 | Output: human-readable text default, `--json` opt-in | locked | `D-001-025.md` |
| D-008 | Kotlin: `@Metadata` + full `kotlin-compiler-embeddable` PSI | locked | `D-001-025.md` |
| D-009 | Truth model: bytecode is the skeleton, sources are the flesh | locked | `D-001-025.md` |
| D-010 | `usages` scope: workspace jars + project source dirs | locked | `D-001-025.md` |
| D-011 | v1 extras: javadoc/KDoc, hierarchy, call hierarchy | locked | `D-001-025.md` |
| D-012 | Repository: local git + public GitHub repo | locked | `D-001-025.md` |
| D-013 | Index storage: SQLite, single global DB | proposed-by-implementer | `D-001-025.md` |
| D-014 | Symbol reference syntax: javadoc-style `Type#member(params)` | proposed-by-implementer | `D-001-025.md` |
| D-015 | Exit-code taxonomy is a public contract | proposed-by-implementer | `D-001-025.md` |
| D-016 | Ambiguity is a result, not an error | proposed-by-implementer | `D-001-025.md` |
| D-017 | Read-only, never executes inspected code | proposed-by-implementer | `D-001-025.md` |
| D-018 | GitHub repository name: `jdx` | locked | `D-001-025.md` |
| D-019 | Documentation-for-successors is a release gate | locked | `D-001-025.md` |
| D-020 | Testing: TDD mandatory in `core` + self-generating families | locked | `D-001-025.md` |
| D-021 | Test tiers, coverage gates, golden-file hazard | proposed-by-implementer | `D-001-025.md` |
| D-022 | Work cadence: one small task → commit → push → log → next | locked | `D-001-025.md` |
| D-023 | Ever-growing docs are sharded; entry files stay small | locked | `D-001-025.md` |
| D-024 | Every hard-won lesson recorded in `docs/LESSONS.md` | locked | `D-001-025.md` |
| D-025 | Reference-grammar disambiguation rules (T-003) | proposed-by-implementer | `D-001-025.md` |
| D-026 | Launcher environment resolution and its exit code (T-004) | proposed-by-implementer | `D-026-050.md` |
| D-027 | `doctor` severity policy, M0 envelope shape, `--json` positions (T-005) | proposed-by-implementer | `D-026-050.md` |
| D-028 | Renderer placement, signature layout, JSON contract (T-010) | proposed-by-implementer | `D-026-050.md` |
| D-029 | Workspace file shape and resolution semantics (T-015) | proposed-by-implementer | `D-026-050.md` |
| D-030 | Project auto-discovery semantics and deviations (T-016) | proposed-by-implementer | `D-026-050.md` |
| D-031 | Search, resolve, ls and tree semantics (T-017) | proposed-by-implementer | `D-026-050.md` |
| D-032 | Maven coordinate resolution and fetching semantics (T-019) | proposed-by-implementer | `D-026-050.md` |
| D-033 | Member `--sort` order semantics (T-062) | proposed-by-implementer | `D-026-050.md` |
| D-034 | Configurable Maven repositories, `--repo` semantics (T-069; supersedes D-032 §6) | proposed-by-implementer | `D-026-050.md` |
| D-035 | `jdx body` output semantics (T-022) | proposed-by-implementer | `D-026-050.md` |
| D-036 | `jdx source` output semantics (T-023) | proposed-by-implementer | `D-026-050.md` |
