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
| `tooling` | L-002, L-003, L-015, L-018, L-026, L-028, L-030, L-031, L-036, L-040, L-043, L-044, L-053, L-054, L-059 |
| `jvm-spec` | L-005, L-006, L-024, L-032, L-037 |
| `kotlin` | L-009, L-010, L-011, L-014, L-016, L-021, L-022, L-023, L-034, L-039, L-042, L-048, L-052, L-060 |
| `process` | L-007, L-008, L-013, L-025, L-027, L-033, L-035, L-038, L-041, L-050, L-051, L-057, L-058, L-061, L-062 |

| Shard | Entries | Status |
|---|---|---|
| `docs/lessons/L-001-025.md` | L-001…L-025 | full |
| `docs/lessons/L-026-050.md` | L-026…L-050 | full |
| `docs/lessons/L-051-075.md` | L-051…L-062 | open (13 free) |

## Adding an entry

Append to the shard that still has capacity, update the index above (tags and shard
table), and mention the new `L-nnn` in your session entry's "Lessons distilled" section.
When a shard is full, create the next one (`L-026-050.md`) and update the shard table.
