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

## Next tasks (all DONE — board empty as of session 83)

No open tasks. New work gets the next free number per the board rule; the
rows below are kept as the closing record.

| Item | State | Notes |
|---|---|---|
| T-083 test-source + Java warnings-as-errors | DONE (session 82) | Last task; `allWarningsAsErrors` on test compilations, `-opt-in` flag, Java `-Werror` (D-064) |
| T-084 `doctor` daemon aliveness | DONE (session 83) | Probes each `.sock` with `health`: running → OK, stale → WARN (D-065, L-113) |

## Deferred follow-ups (no task number yet — file one when starting)

- **Warm text output:** T-042 serves `--json` warm; text stays in-process in
  v1 (no text wire, D-059). Lift with a text wire or a JSON→Outcome parser
  when the human-first path needs daemon speed.

- **Daemon `.log` retention:** idle shutdown and `stop` sweep socket + pid
  files but keep the `.log` sibling as evidence. No rotation or `gc`
  coverage yet — revisit if logs grow (daemon is chatty only at startup).

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

*Last updated: 2026-09-24 (session 83).*
