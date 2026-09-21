# Progress log

**Structure (D-023):** this file is the *entry file* — it holds only the `CURRENT STATE`
handoff, the shard index, and the session template. Session entries live in
`docs/progress/sessions-NNN-NNN.md` shards (10 sessions each, newest first). Never edit
or delete a past entry — if one turned out to be wrong, say so in a *new* entry.

---

# CURRENT STATE

> **Read this block first. It is the handoff.**
> Whoever writes the last session entry is responsible for making this block true.

| | |
|---|---|
| **Last updated** | 2026-09-21 (session 54) |
| **Repository** | <https://github.com/MohammadMD1383/jdx> (public, Apache-2.0) |
| **Phase** | Design complete; **M0–M3 DONE, M4 WIP** (T-029…T-033 DONE; T-034 TODO), M5–M7 TODO |
| **Next task** | **M4 T-034** (`jdx samples` with exemplariness ranking — expand into a detail block when started; last M4 task) |
| **Task count** | 74 tasks (T-001…T-074): all DONE except T-034, T-074 (`srcmap` remainder), and the coarse M5–M7 one-liners (T-060 done early) |
| **Build status** | **Green.** `./gradlew build` passes. Runnable `jdx`: `./gradlew :app:installDist` → `app/build/jdx`. Live commands: `version`, `doctor`, `show`, `members`, `outline`, `search`, `resolve`, `ls`, `tree`, `ws`, `cache`, `body`, `source`, `signature`, `doc`, `usages` (+`--src`), `hierarchy`/`implementors`, `callers`/`calls --depth` — text+JSON, `--coord`/`--fetch`/`--repo`, JDK via `jrt:/` + `src.zip`, workspaces + auto-discovery. |
| **Test status** | **Tiers 1–3 green** (`check`, `soak`); mutation tier 4 green (`core` 91 % vs the 80 gate). **Caveat:** `verifyTier1Budget` is red on this machine (pre-existing machine variance, 0 test failures — not a test failure). |
| **Docs** | Sharded append-only logs (D-023): sessions → `docs/progress/` (`sessions-051-060.md` open at 54), decisions → `docs/decisions/` (shard 2 at D-046), lessons → `docs/lessons/` (`L-076-100.md` open at L-085). New decisions/lessons go to the newest shard, never into session entries. |
| **Blocked on** | Nothing. Unpushed sessions need owner go-ahead per session (D-012). |

**What exists:** see `docs/TASKS.md` status summary + the session log — this file no
longer retells them. To resume cold: `CLAUDE.md` (rules) → CURRENT STATE above →
`docs/TASKS.md` (pick the task) → newest `docs/progress/` shard (recent context).
Working agreements (cadence, lesson distillation, commit rules) live in
`CLAUDE.md` §7 and `CONTRIBUTING.md` — not repeated here.

---

# Session log — sharded (D-023)

Entries live in `docs/progress/`, newest first. Append to the shard with free capacity;
when it holds 10 sessions, create the next (`sessions-011-020.md`) and update this index.

| Shard | Sessions | Status |
|---|---|---|
| `docs/progress/sessions-001-010.md` | 1–10 | full |
| `docs/progress/sessions-011-020.md` | 11–20 | full |
| `docs/progress/sessions-021-030.md` | 21–30 | full |
| `docs/progress/sessions-031-040.md` | 31–40 | full |
| `docs/progress/sessions-041-050.md` | 41–50 | full |
| `docs/progress/sessions-051-060.md` | 51–60 | open |

---

# Template — copy this for every session

Keep entries factual and specific. "Refactored some stuff" helps nobody. Name files, name
tasks, name the commands you ran. Write for someone with none of your context.

```markdown
## Session N — YYYY-MM-DD — <one-line summary>
**Agent/Author:** <model or person> · **Commits:** <range or "none">

### Goal
<What you set out to do, and which task IDs it covers.>

### What I did
<Concrete changes. Files touched. Commands that matter. Findings that surprised you.>

### Decisions made
<New D-nnn entries, or "none". Anything non-obvious you chose while coding belongs in
docs/DECISIONS.md, not only here.>

### Tasks moved
<T-nnn: TODO → WIP → DONE. Keep docs/TASKS.md in sync — this list is the audit trail.>

### Lessons distilled
<New L-nnn entries added to docs/LESSONS.md, or "none".>

### What works now (and how to verify it yourself)
<Exact commands a successor can run to see the state for themselves. This is the single most
useful part of the entry — a successor trusts what they can reproduce.>

### What is broken / half-done
<Be honest and specific. A known-broken thing that is documented costs an hour;
an undocumented one costs a day. Include the file and the reason you stopped.>

### Open questions / blockers
<New Q-nnn entries, or "none".>

### Next action
<The single next task ID, and anything the next person needs to know to start it cold.>
```

**Before you stop, verify:**
- [ ] `docs/TASKS.md` statuses match reality
- [ ] The **CURRENT STATE** block at the top of this file is true
- [ ] New decisions are in `docs/DECISIONS.md`'s newest shard, not buried in this log
- [ ] New lessons are in `docs/LESSONS.md`'s newest shard, not buried in this log
- [ ] Your entry's "how to verify it yourself" commands actually work when pasted
