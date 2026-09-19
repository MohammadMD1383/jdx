# Sessions 031–040

Newest first. Each entry follows the template at the bottom of `docs/PROGRESS.md`.

---

## Session 31 — 2026-09-19 — T-062 member sort orders (`--sort name|declaring`)
**Agent/Author:** Muse Spark 1.3 Free · **Commits:** `b7d0271` (claim) + closing commit (this session)

### Goal
Implement T-062, the lowest-numbered unblocked TODO: `--sort name|kind|declaring`
for `members` and `outline`, with exact layouts decided, text↔JSON parity,
per-order determinism, and goldens over a generic, a nested and a Kotlin fixture.

### What I did
- `core/.../render/Listing.kt`: new `MemberSort` enum (`KIND`/`NAME`/`DECLARING`
  + `fromFlag`), `MemberListingOptions.sort` (default `KIND`), `MemberRow.memberName`
  (raw name, JSON-untouched). `buildMemberListing` branches into `kindGroups`
  (T-010 layout, untouched), `declaringGroups` (alphabetical declaring, kind-minor)
  and `nameGroups` (strict global flat order via run-length groups — first draft
  grouped by `(declaring, kind)` which re-sorted methods-before-fields; a failing
  example test caught it, fixed to run-length runs so flattening is globally sorted).
- `index/.../service/JdxService.kt`: `MemberFilters.sort` (default `KIND`; keeps the
  thin-adapter `MemberQuery` arity stable, D-004), mapped into `MemberListingOptions`.
- `cli`: `ReadCommandSupport.sortOf` + validation now accepts all three orders
  (bogus values are exit 3); both commands pass the sort through filters and gained
  accurate `--help` text.
- Tests: 6 core examples (`MemberSortTest`); 2 consolidated thousand-case properties
  (shared laws per random sort; layout laws over all three sorts — first draft had 6
  properties and blew the 30 s tier-1 budget at 37.5 s, consolidated to 2 without
  dropping the 1,000-case minimum, back to 23.6 s); CLI tier-1 sort-plumbing tests
  (replacing the `--sort name exits 3` test); tier-2 `SortOrdersGoldenTest` (18 files:
  Generics, TrafficLight$1, KotlinMembers × 3 sorts × text/JSON; `kind` files are
  byte-identical to the existing default goldens) + tier-2 service tests (every sort
  exit 0, deterministic, same row set; outline covered).
- Golden fixture swap: first draft used `Nesting$Inner` (2 rows, all orders identical);
  replaced with `TrafficLight$1`, which differentiates all three orders (L-057).

### Decisions made
- D-033 (member `--sort` order semantics) in `docs/decisions/D-026-050.md` + index row.

### Tasks moved
- T-062: TODO → WIP (`b7d0271`) → DONE.

### Lessons distilled
- L-057 (golden fixtures must visibly differentiate the new behaviour).

### What works now (and how to verify it yourself)
```bash
mv ~/.config/jdx ~/.config/jdx.shelved  # T-066 ambient-workspace caveat, restore after
./gradlew check   # tiers 1+2 green (tier-1 23.6 s of 30 s)
./gradlew soak    # green except pre-existing JavapCorpusSoakTest `$$` red (T-065)
./gradlew :cli:tier2Test --tests "dev.jdx.cli.commands.SortOrdersGoldenTest"  # 18 goldens
git diff cli/src/test/resources/golden/members-sort/  # TrafficLight$1 differs 3 ways; kind == members/ goldens
```

### What is broken / half-done
- Nothing from this task. Pre-existing, untouched: T-066 (`ReadCommandsTest`
  hermeticity — 2 reds with ambient `~/.config/jdx`, proven stashed-clean),
  T-065 (`$$` names — soak red quoted below, same signature as sessions 26–28).

### Open questions / blockers
- None.

### Next action
- **T-063** (share the fixture-jar resolution helpers) — lowest-numbered unblocked
  TODO after T-062; T-064, T-065, T-066, T-068, T-069 also open.
