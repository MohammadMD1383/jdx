# Contributing to `jdx`

This project is built by a rotating cast of contributors — humans and AI agents — who do not
share memory, habits, or taste. Nobody here has read the whole codebase. **The conventions
below exist so that code written by six different people looks like it was written by one.**

If you are an AI agent, start with `CLAUDE.md`, then read the [For AI agents](#for-ai-agents)
section at the bottom of this file.

---

## The one-minute version

```bash
git clone <repo> && cd jdx
./gradlew build                 # no system gradle/maven needed; the wrapper is the only entry point
cat docs/PROGRESS.md            # CURRENT STATE block = where the project is
cat docs/TASKS.md               # pick the lowest-numbered unblocked TODO
# ... do the work ...
./gradlew check                 # tests + lint must pass
# update docs/TASKS.md status, append a session entry to docs/progress/, commit
```

---

## Where things are written down

| Question | File |
|---|---|
| What is this project and what are its rules? | `CLAUDE.md` |
| Why is it designed this way? What does each command do? | `docs/PROPOSAL.md` |
| Why was X chosen over Y? May I change it? | `docs/DECISIONS.md` (index → `docs/decisions/` shards) |
| What should I work on next? | `docs/TASKS.md` |
| What happened before I got here? | `docs/PROGRESS.md` (CURRENT STATE) + `docs/progress/` shards |
| What mistakes have already been made here? | `docs/LESSONS.md` (index → `docs/lessons/` shards) |
| How do I write code that fits in? | this file |

**Keep them accurate.** A stale doc in this project is worse than a missing one, because the
next contributor has no way to tell the difference.

---

## Non-negotiables

These are not style preferences. Violating one breaks the product.

1. **`core` has no dependencies.** No ASM, no SQLite, no JavaParser, no file IO, no network.
   It is the shared vocabulary and must stay trivially testable. If you need bytecode in
   `core`, you need a different design.
2. **Front-ends contain no logic.** `cli`, `mcp`, `server` parse input, call `JdxService`,
   render, and map exit codes. Nothing else. Four front-ends were a deliberate choice
   (D-004) and they only stay consistent if they are all thin. If you catch yourself writing
   an `if` about *behaviour* in a Clikt command, move it.
3. **Never load an inspected class into the JVM** (D-017). Parse with ASM. Reflection on a
   third-party jar executes its static initialisers — that is a security property we sell.
4. **Never guess a symbol** (D-016). Ambiguous input exits `2` with candidates listed.
5. **Exit codes are a public contract** (D-015). Changing one is a breaking change.
6. **Text and JSON must carry the same information** (D-007).
7. **Output is deterministic.** Sorted. No timestamps, absolute paths, hashes, or ANSI in
   non-TTY output. Golden tests enforce this; do not weaken them to make a test pass.
8. **Every fallback is labelled.** Decompiled output says it is decompiled. A guessed
   parameter name says so. `jdx` must never present a reconstruction as ground truth.

---

## Code style

We optimise for **a stranger reading this once, under time pressure**, not for elegance.

- **Kotlin official style**, 4-space indent, 100-column soft limit.
- **Explicit API mode** in `core` — every public declaration needs an explicit visibility and
  return type.
- **Name things fully.** `resolveInheritedMembers`, not `resolve`. `sourcesJarPath`, not
  `sp`. Long names are free; re-reading a function to learn what `s` is, is not.
- **Prefer boring code.** No operator overloading for domain types, no DSL builders for
  internal use, no reflection, no clever generic gymnastics. If a reviewer has to pause, it
  is too clever.
- **Sealed classes over booleans + nulls** for states with a closed set of cases
  (`Provenance`, `SymbolRef`, query results). Kotlin's exhaustive `when` is the main reason
  we chose Kotlin (D-001); use it.
- **Errors are values, not exceptions**, on any path an agent can trigger. Exceptions are for
  bugs. A missing class is a `Result`, not a throw.
- **Comment the invariant, not the syntax.** `// callers rely on this list being sorted by
  declaring-type depth` is useful. `// loop over members` is noise.
- **Every public type gets one KDoc line** saying what it represents and who produces it.
  That single line is what lets a newcomer navigate without reading call sites.

### Comment density
Match the surrounding file. In `core/resolve/` (subtle algorithms) comments are dense and
explain *why*. In `cli/commands/` (mechanical wiring) they are nearly absent, because the
code says everything. Do not sprinkle uniform comment density across both.

---

## Adding a command — the checklist

A command is **not finished** until all of these are true. This list is the most common
source of half-done work.

- [ ] Behaviour implemented in `core`/`index`/`sources`/`decompile`, exposed via `JdxService`
- [ ] Clikt command in `cli`, thin, with complete `--help` text (agents read `--help`)
- [ ] **Text renderer** following the layout conventions in `docs/PROPOSAL.md` §8
- [ ] **JSON renderer**, same information, inside the standard envelope
- [ ] Exit codes per D-015, including the ambiguity path per D-016
- [ ] Truncation handled: bounded output with `shown`/`total`/`hint`
- [ ] `next:` hint line on single-entity output, where a sensible follow-up exists
- [ ] **Golden tests** for text and JSON against the fixture corpus
- [ ] MCP tool entry (once M6 lands) — generated from shared metadata, not hand-written twice
- [ ] Row in the README command table
- [ ] Flag documented in `docs/PROPOSAL.md` Appendix B
- [ ] `docs/TASKS.md` status updated; `docs/PROGRESS.md` entry appended

---

## Testing

**Read [`docs/TESTING.md`](docs/TESTING.md) before writing tests.** It is the authoritative
strategy; this is the two-minute version.

- **`core` is written test-first.** Red → green → refactor, no exceptions. It has no
  dependencies and no IO, so "hard to test" is never true there.
- **Hand-written examples are not enough.** They plateau at what you already thought of. Every
  new behaviour also needs at least one test family that *generates* its own cases:
  property-based, differential-vs-`javap`, metamorphic, fault-injection, or corpus soak.
- **Fixtures over mocks.** The `testfixtures` corpus (T-006) compiles real, nasty Java and
  Kotlin at build time. Hand-built `ClassInfo` objects encode your assumptions — and your
  assumptions are exactly what the test should be checking. There is no mocking framework in
  `core`; if you need one, push the IO outward instead.
- **Use the oracles.** `javap -p -s` tells you the truth about any class file without you
  writing it down. For decompilation, assert that the output **recompiles to the same API**,
  never that it matches particular text.
- **Test names are sentences:**
  `` fun `inherited members from a generic supertype are substituted`() ``. The test report
  should read as a specification of what `jdx` does.
- **Every bug fix starts with a failing regression test** named after the issue.
- **Respect the tiers.** `./gradlew test` must stay under 30 s — anything touching disk,
  network or a real jar belongs in tier 2 or above. A slow inner loop kills TDD, and then it
  kills testing.

```bash
./gradlew test                       # tier 1 — the TDD loop (<30s)
./gradlew check                      # tier 2 — pre-commit (<3min)
./gradlew soak                       # tier 3 — real jar corpus (tagged, needs local jars)
./gradlew bench mutationTest         # tier 4 — performance budgets and mutation score
./gradlew test -Pgolden.update=true  # rewrite goldens — THEN READ THE DIFF
```

> **The golden-file hazard.** `-Pgolden.update=true` makes every failing output test pass
> instantly, correct or not. **Always read the diff before committing it.** A golden file you
> updated without reading is a test you deleted. A large diff is a signal to check whether you
> changed behaviour you didn't mean to — not a signal to skim faster.

## Commits and branches

- **Conventional Commits:** `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `build:`,
  `perf:`, `chore:`. Scope with the module: `feat(index): parallel artifact indexer`.
- **The cadence is: one small task → commit → push → log → next task** (D-022). Not
  "finish the milestone, then commit". Contributors here share no memory; an unpushed,
  unlogged working tree is invisible to everyone else, and if the session ends there the work
  is effectively lost. Small pushed increments also keep `git bisect` useful and let a bad
  task be reverted without unpicking four others.
- **Claim first.** Set the task `WIP` in `docs/TASKS.md` and commit that *before* you start
  coding.
- **Small, working increments.** Every commit should build. A 2,000-line commit cannot be
  reviewed and cannot be bisected.
- **If a task won't fit in one sitting, split it in `docs/TASKS.md` first**, then do the first
  half. That is normal, not an admission of failure.
- Reference the task: `feat(cli): add jdx outline (T-011)`.
- Branch per task: `t011-outline-command`.
- **Never push to a remote without an explicit go-ahead** from the project owner in the
  current conversation/session (D-012).

---

## Changing a decision

`docs/DECISIONS.md` marks each entry `locked` or `proposed-by-implementer`.

- **`proposed-by-implementer`** — a default someone picked while building. Improve it in a
  normal PR and update the entry in the same PR.
- **`locked`** — the project owner decided it explicitly, usually after being shown the
  alternatives. Do **not** change it, and do not erode it gradually through refactors. If you
  think it is wrong, open an issue making the case, get an explicit reversal, and record it
  as a **new** `D-nnn` that supersedes the old one.

Never renumber and never delete a decision. The history is the value.

---

## For AI agents

You are a first-class contributor here, and this project is designed around the assumption
that you have no memory of yesterday.

**Your session is only complete when the documentation is.** Concretely:

1. **Read before writing.** `CLAUDE.md` → `docs/PROGRESS.md` CURRENT STATE →
   `docs/DECISIONS.md` → `docs/TASKS.md`. Roughly 15 minutes. It will save you from
   re-deciding things that are already settled and from duplicating work.
2. **Claim your task by committing the status change** to `docs/TASKS.md` *before* you start.
   Another agent may be working in parallel.
3. **Ask the owner about genuine ambiguity instead of picking.** This is an explicit standing
   instruction from the owner. A wrong guess propagates silently through a codebase that
   nobody fully reads. Record the answer as a new `D-nnn`.
4. **Append a session entry before you stop** — to the newest `docs/progress/` shard
   (index and template in `docs/PROGRESS.md`), and update the `CURRENT STATE` block there.
   Include the exact commands a successor can run to verify your work. A session that
   changed files and left no log entry is an incomplete session (D-019).
5. **Distill lessons (D-024).** Anything that cost you time and could cost another
   contributor the same becomes an `L-nnn` entry in the newest `docs/lessons/` shard,
   indexed from `docs/LESSONS.md`. A session that learned something and logged no lesson
   is incomplete.
6. **Update the CURRENT STATE block.** The next agent reads it first and trusts it. If it is
   stale, you have actively misled someone.
7. **Report honestly.** If tests fail, say so and paste the output. If you skipped part of a
   task, say which part and why. Half-finished work that is *documented* as half-finished is
   useful; half-finished work reported as done is a trap that costs the next contributor more
   than the work was worth.
8. **Do not expand scope silently.** If you find adjacent work, add a task to
   `docs/TASKS.md`; do not fold it into the current one.
9. **Leave the build green.** If you cannot, say so loudly in the progress entry and in the
   CURRENT STATE block, with the exact failing command.
