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

## Next tasks (in board order — pick from `docs/TASKS.md`)

| Item | State | Notes |
|---|---|---|
| T-043 MCP stdio server | TODO, **next** | Unblocked: wire contract (T-040) live |
| T-043 MCP stdio server | TODO | Depends on T-040 |
| T-044 HTTP/JSON server | TODO | Depends on T-040 |
| T-045 `jdx batch` | TODO | Depends on T-040 |
| T-046 adapter parity test | TODO, closes M6 | Depends on T-042…T-044 |
| T-047…T-052 M7 polish (token budgets, AppCDS, `help --agent`, `bench`, README/install, lint/gates) | TODO, coarse | Expand when M6 closes |
| T-080 Kotlin sidecar fetch · T-081 Kotlin-aware mismatch pairing | TODO, low priority | Filed session 65; M6 stays next |

## Deferred follow-ups (no task number yet — file one when starting)

- **Warm text output:** T-042 serves `--json` warm; text stays in-process in
  v1 (no text wire, D-059). Lift with a text wire or a JSON→Outcome parser
  when the human-first path needs daemon speed.

- **`doctor` daemon aliveness:** the `daemon` row still counts `.sock`
  files; now that the T-041 daemon answers `health`, it could probe each
  socket and report running/stale. Left out of T-041 (not in acceptance).
- **Daemon `.log` retention:** idle shutdown and `stop` sweep socket + pid
  files but keep the `.log` sibling as evidence. No rotation or `gc`
  coverage yet — revisit if logs grow (daemon is chatty only at startup).

## Known test / environment caveats (pre-existing, not failures)

- `JavapCorpusSoakTest` reds only on JDK-internal synthetic `access$`
  members (proven pre-existing on the stashed-clean tree; drift family from
  sessions 53/55/56/58).
- `verifyTier1Budget` is red on this machine (machine variance, 0 test
  failures — not a test failure).
- `indexed artifacts` in `daemon status` reports 0 by design with live
  roots (no persistent index in T-082; D-043 precedent).

---

*Last updated: 2026-09-23 (session 69).*
