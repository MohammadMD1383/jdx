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

## Deferred follow-ups (none — the list is empty as of session 85)

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

*Last updated: 2026-09-24 (session 85).*
