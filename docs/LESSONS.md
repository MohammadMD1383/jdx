# Lessons learned

> The durable log of mistakes already made, so no contributor — human or agent — pays for
> them twice (**D-024**). Skim the index below before fighting a toolchain, a spec, or
> this repo's own machinery; entries live in shards under `docs/lessons/` (25 per shard,
> **D-023**) — open a shard only when an entry is relevant to you.

## What belongs here

- A mistake you made, its cause, and its fix — if it could bite someone else the same way.
- Non-obvious toolchain/platform behaviour (Gradle, kotlinc, javap, the shell, this OS).
- JVM spec gotchas (JVMS/JLS) that cost time.
- **Not** here: environment facts → `CLAUDE.md` §8; design rationale → `docs/DECISIONS.md`;
  session narrative → `docs/progress/`. Reference those instead of restating them.

## Entry format

```
### L-00n — short title `tag`
<symptom / mistake> → <cause> → <fix>. Learned in <session/task, pointer if useful>.
```

One fact per entry, ≤ 10 lines. Numbering (`L-001`, `L-002`, …) is global, append-only
and never reused. Tags in use: `build` · `tooling` · `jvm-spec` · `kotlin` · `process`.

## Index

| Tag | Entries |
|---|---|
| `build` | L-001, L-004, L-012, L-017, L-019, L-020, L-029, L-045, L-046, L-047, L-049 |
| `tooling` | L-002, L-003, L-015, L-018, L-026, L-030, L-031, L-036, L-040, L-043, L-044, L-053, L-054, L-059, L-063, L-066, L-068, L-070, L-075, L-077, L-078, L-082, L-083 |
| `jvm-spec` | L-005, L-006, L-024, L-032, L-037, L-079 |
| `kotlin` | L-009, L-010, L-011, L-014, L-016, L-021, L-022, L-023, L-034, L-039 (incl. L-072), L-042, L-048, L-052, L-060, L-065, L-073 |
| `process` | L-007, L-008, L-013, L-025 (incl. L-028), L-027, L-033, L-035, L-038, L-041, L-050, L-051, L-057, L-058, L-061, L-062, L-064, L-067, L-069, L-071, L-074, L-076, L-080, L-081, L-084, L-085 |

| Shard | Entries | Status |
|---|---|---|
| `docs/lessons/L-001-025.md` | L-001…L-025 | full |
| `docs/lessons/L-026-050.md` | L-026…L-050 | full |
| `docs/lessons/L-051-075.md` | L-051…L-075 | full |
| `docs/lessons/L-076-100.md` | L-076… | open |

## Topic guide

Cross-cutting topics that span tags — skim the one matching your situation.
(Merged pairs: L-028 → L-025, L-072 → L-039; old numbers stay as pointers so
session-log references keep resolving. Numbers are never reused.)

| Topic | Entries |
|---|---|
| Test hermeticity / ambient machine state | L-038, L-041, L-061, L-066, L-067, L-070 (tasks T-066, T-070) |
| kotest 6 traps (discovery, `map`, matchers, naming, eager `Arb`) | L-010, L-022, L-023, L-034, L-036, L-066 |
| Edit-tool discipline (`oldString` anchors, `git diff` first) | L-025 (incl. L-028) |
| Kotlin nested comments in KDoc | L-039 (incl. L-072) |
| Gradle build / toolchain / config-cache | L-001, L-004, L-012, L-017, L-019, L-020, L-045, L-046, L-047, L-049, L-064, L-066 |
| JSON / escaping / golden discipline | L-014, L-030, L-054, L-057, L-068, L-080 |
| JVM spec / bytecode shapes | L-005, L-006, L-024, L-032, L-037, L-042, L-063, L-078, L-079 |
| Indexer / soak scale (batching, heap, harness JVM) | L-031, L-059, L-081 |
| Seams: inject, default to the seam, split in the seam | L-052, L-053, L-062, L-069, L-074, L-076 |
| Never zip sorted views against their source | L-071 |
| Symbol reference grammar (package globs) | L-086 |
| Kotlin `@Metadata` reading (`Metadata(...)` helper, not `KotlinClassHeader`) | L-087 |
| Kotlin `KmClass` equality (compare contents, never carriers) | L-088 |
| Never-throw metadata/ASM accessors (`lateinit`, `AssertionError`, textual checks) | L-089 |
| Kotlin `isVar` flag vs setter presence (infer `var` from the setter) | L-090 |

## Adding an entry

Append to the shard that still has capacity, update the index above (tags and shard
table), and mention the new `L-nnn` in your session entry's "Lessons distilled" section.
When a shard is full, create the next one (`L-026-050.md`) and update the shard table.
