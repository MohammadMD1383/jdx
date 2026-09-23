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
| **Last updated** | 2026-09-23 (session 80) |
| **Repository** | <https://github.com/MohammadMD1383/jdx> (public, Apache-2.0) |
| **Phase** | Design complete; **M0–M6 DONE**; **M7 planned sequence DONE** (T-047 + T-048 + T-049 + T-050 + T-051 + T-052 DONE, T-060 done early, T-080 DONE session 80; T-081/T-083 low priority remain) |
| **Next task** | **T-081** (`@Metadata`-aware mismatch pairing) or T-083 (test-source/Java warnings-as-errors) per owner direction — both low priority. Offered T-084 release task (version/changelog/packaging), awaiting direction. |
| **Task count** | 83 tasks (T-001…T-083): all DONE except T-081 (Kotlin-aware mismatch pairing, filed session 65, low priority) and T-083 (test-source/Java warnings-as-errors, filed session 76) |
| **Build status** | **Green.** `./gradlew build` passes. Runnable `jdx`: `./gradlew :app:installDist` → `app/build/jdx`. Live commands: `version`, `doctor`, `show`, `members`, `outline`, `search`, `resolve`, `ls`, `tree`, `ws`, `cache`, `daemon start\|stop\|status\|restart`, `mcp [-w]`, `serve [-w] [--port] [--bind]`, `body`, `source`, `signature`, `doc`, `usages` (+`--src`), `hierarchy`/`implementors`, `callers`/`calls --depth`, `samples --prefer-sources`, `bench [--iterations N]` — text+JSON, `--coord`/`--fetch`/`--repo`, JDK via `jrt:/` + `src.zip`, workspaces + auto-discovery. `members`/`outline`/`signature` take `--view kotlin\|jvm` (raw JVM projection). `body`/`source`/`doc` serve `.kt` member bodies + KDoc through the side-loaded PSI compiler (degrade to decompile/javap without it). **M6 so far:** `core/.../rpc/RpcProtocol.kt` fixes the v1 wire contract (NDJSON requests, responses are the existing `--json` envelope); `server/.../DaemonServer.kt` serves it over `$XDG_RUNTIME_DIR/jdx/<hash>-v1.sock` with idle shutdown — `health`/`version` internal, all 17 read queries dispatched to `JdxService` (T-082, byte-identical to one-shot); `cli/.../DaemonClient.kt` forwards `--json` queries to the daemon when it answers (T-042, `--no-daemon` forces in-process, text stays cold in v1); `mcp/.../McpTools.kt` + `McpSession.kt` + `McpServer.kt` serve all 20 wire commands as typed `jdx_*` tools over stdio (T-043, `jdx mcp [-w name]`, schemas generated from the tool table, answers byte-identical to `--json`); `server/.../HttpServer.kt` + `cli/.../ServeCommand.kt` serve the same contract over HTTP (T-044, `jdx serve [-w name] [--port 7070] [--bind 127.0.0.1]`, `GET /v1/<command>`, `POST /v1/batch`, `GET /v1/health`, localhost by default, bodies byte-identical to `--json`); `cli/.../commands/BatchCommand.kt` answers NDJSON `RpcRequest` lines from stdin with one envelope per line (T-045, `jdx batch`, roots resolved once, `version`/`health` internal, `doctor` in-process, exit = max query exit); `cli/.../parity/AdapterParityTest.kt` pins the §9 contract over all three (T-046, closes M6: all 17 read commands + JDK sample + failure branches byte-identical across CLI `--json`/HTTP/MCP, plus a 100-case hostile parity property). |
| **Test status** | **Tiers 1–2 green** (`check`: 0 failures, sessions 76–79, incl. the `lint` gate, the T-048 AppCDS pins and the T-050 bench smoke); **tier 3 green except** `JavapCorpusSoakTest`, which reds only on JDK-internal synthetic `access$` members (proven pre-existing on the stashed-clean tree, same drift family as sessions 53/55/56/58; Kotlin re-inclusion via `--view jvm` contributed zero mismatches over 112 classes); mutation tier 4 green (`core` 91 % vs the 80 gate, last measured session 55; the new `dev.jdx.core.rpc` package measures 94 %, session 66). Gates new this session: `allWarningsAsErrors` on every main `compileKotlin` (zero warnings), `explicitApi()` on `core`/`index`/`sources`/`decompile`/`mcp`/`server` (`cli` excluded — 99 violations, no Kotlin consumers), root `lint` (trailing WS, tabs, bare TODO, console IO in library mains) wired into every `check` — all with planted-violation negative proofs. **Caveat:** `verifyTier1Budget` is red on this machine at the default 30 s budget (pre-existing machine variance, 0 test failures — not a test failure; `check` passes with `-Ptier1.budget=10000`). |
| **Docs** | Sharded append-only logs (D-023): sessions → `docs/progress/` (`sessions-051-060.md` full at 60, `sessions-061-070.md` full at 70, `sessions-071-080.md` full at 80), decisions → `docs/decisions/` (shard `D-051-075.md` open at D-062), lessons → `docs/lessons/` (`L-076-100.md` full at L-100, `L-101-125.md` open at L-109). New decisions/lessons go to the newest shard, never into session entries. |
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
| `docs/progress/sessions-051-060.md` | 51–60 | full |
| `docs/progress/sessions-061-070.md` | 61–70 | full |
| `docs/progress/sessions-071-080.md` | 71–80 | full |
| `docs/progress/sessions-081-090.md` | 81–90 | open |

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
- [ ] `open-items.md` is current (finished items moved out, new ones recorded — always on)
- [ ] The **CURRENT STATE** block at the top of this file is true
- [ ] New decisions are in `docs/DECISIONS.md`'s newest shard, not buried in this log
- [ ] New lessons are in `docs/LESSONS.md`'s newest shard, not buried in this log
- [ ] Your entry's "how to verify it yourself" commands actually work when pasted
