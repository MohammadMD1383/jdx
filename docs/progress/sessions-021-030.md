# Session log — shard 3: sessions 21–30

Append-only session log, **10 sessions per shard** (D-023). `docs/PROGRESS.md` holds the
`CURRENT STATE` handoff, the shard index, and the entry template — this file only holds
entries. Newest entry first; never edit or delete a past entry — if one turned out to be
wrong, say so in a *new* entry.

---

## Session 21 — 2026-09-16 — Shared property-test generators + missing §4 properties (T-055)
**Agent:** Muse Spark (via opencode) · **Branch:** none (on `main` at `104b2d5`) ·
**Commits:** `104b2d5` (claim), this session's work (to commit with this entry)

### Goal
Implement T-055 — the lowest-numbered unblocked TODO: kotest-property shared generators
(`TypeName`/`JvmDescriptor`/`GenericSignature`) plus every TESTING.md §4 property at
≥1,000 cases, with seed-pinning documented.

### What I did
1. Claimed T-055 (`TODO`→`WIP`, commit `104b2d5`) before coding.
2. **Test-first, RED:** wrote `GeneratorsTest` + three property suites against
   non-existent `gen/` APIs (`Unresolved reference`, the right reason), then GREEN.
3. **New `core/.../gen/` files:** `TypeNameGenerators.kt` (`arbTypeName`,
   `arbFieldTypeName`, `arbJvmDescriptor`; empty packages, unicode segments, 3-deep
   nesting whose binary names carry `$`, 1–3-dim arrays), `SignatureGenerators.kt`
   (all three wildcard kinds, type variables incl. empty-class-bound params, nested
   generics, inner classes with own args, void returns, throws), `PropertySupport.kt`
   (`JDX_PROPERTY_ITERATIONS = 1_000`, `pinnedConfig(seed)` + seed-workflow KDoc).
4. **New properties, all 1,000 cases:** `TypeNamePropertyTest` (binaryName +
   field-descriptor fixed points), `JvmDescriptorPropertyTest` (descriptor fixed point
   + never-throws), `GenericSignaturePropertyTest` (`parse` + `parseClass` fixed points
   + never-throws), `print(parse(s))` stability in `SymbolRefPropertyTest`,
   `GeneratorsTest` (fixed-seed nastiness coverage + `pinnedConfig` API pin).
   Raised the three 500-case `MemberListingPropertyTest` properties to 1,000.
5. **Real production bug, caught by the new never-throws property on its first run:**
   `JvmDescriptor.parse("L1C)1JQLB)Q.;")` threw `IllegalArgumentException` (empty
   segment) instead of returning null — `parseDescriptorType` handed untrusted
   class-file text to throwing `typeNameFromBinaryName`. Fixed at that boundary
   (catch → null, matching its own KDoc); regression strings pinned in
   `JvmDescriptorTest`'s malformed list. Same run caught void-array generation
   (`[[[V]`); array elements now exclude `VOID` (L-037).
6. **Generator bugs the tests caught:** eager Arb construction overflows on any
   non-decreasing recursion (depth-0 base must build nothing recursive); no
   single-arity `Arb.bind` in kotest 6 (use `.map`); `Arb.of(emptyList())` throws
   (L-036).
7. `./gradlew check --offline` green (**586 tests**, 0 failures: 469 tier-1 + 117
   tier-2), `./gradlew soak --offline` green (38 s). Tier-1 aggregate 7.7 s → 18.0 s
   across 469 tests — inside the 30 s budget, but headroom is now 12 s.
8. **Pre-existing red, not mine:** 2 `ReadCommandsTest` failures come from this
   machine's real `~/.config/jdx/active-workspace` (`fx`, dated 15:51, a prior
   session's e2e leak) combined with the test's real-home `FileWorkspaceStore`
   default. Proven by shelving `~/.config/jdx`: green without it, red with it —
   all runs above used the shelved state. Filed as **T-066**, lessons as **L-038**;
   ambient state restored untouched. TESTING.md §4 documents the seed pin one-liner.

### Decisions made
None at D-level. Judgement calls in code KDoc: `$` appears only as the nesting
separator in generated names (bare segments forbid it per the `ClassInfo` invariant
and D-025); `checkAll(1_000, pinnedConfig(seed), arb)` is the pin form (verified
against the kotest 6 `checkAll(int, PropTestConfig, Gen)` overload); a left-behind
seed pin is a test that stopped generating (remove after the fix).

### Tasks moved
- T-055: TODO → WIP → DONE.
- T-066 added (TODO) — `ReadCommandsTest` hermeticity to machine workspaces.

### Lessons distilled
**L-036** (kotest 6 eager generators: recursion must bottom out at construction;
no 1-arity bind; `of(empty)` throws), **L-037** (null-on-malformed parsers must not
call throwing constructors; void is not a field type), **L-038** (command tests
must inject the workspace store) in `docs/lessons/L-026-050.md`; tag index updated.

### What works now (and how to verify it yourself)
```bash
mv ~/.config/jdx /tmp/jdx-shelved   # only if an active workspace exists (see L-038); restore after
./gradlew check --offline           # tiers 1+2 green (586 tests, 0 failures)
./gradlew soak --offline            # tier 3 green
./gradlew :core:test --offline --tests "dev.jdx.core.gen.*" --tests "dev.jdx.core.model.*PropertyTest"
mv /tmp/jdx-shelved ~/.config/jdx
```

### What is broken / half-done
Nothing in T-055 scope. Known, owned elsewhere: T-066 (`ReadCommandsTest` red when
`~/.config/jdx/active-workspace` exists — this machine is in that state, so a bare
`./gradlew check` shows 2 failures in `:cli:test` until T-066 lands); T-064/T-065
(indexer perf gap, JFR `$$` names) untouched.

### Open questions / blockers
None. **Push needs owner go-ahead (D-012)** — session 21 commits unpushed
(claim `104b2d5` + this work).

### Next action
**T-016** (project auto-discovery) — lowest unblocked TODO; or **T-066** (small,
hermetic, unblocks a green `check` on this machine).
