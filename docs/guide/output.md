# Output, JSON & exit codes

jdx output is designed to be read by a language model with a finite context window: compact text by default, a complete JSON envelope on request, and exit codes an agent can branch on without parsing prose.

## Text output

- **Two-space indentation**, no box drawing or tables — they cost tokens and tokenize badly.
- **A leading kind word** on every entity line (`class`, `method`, `field`, `constructor`).
- **Grouping headers** instead of repeating the declaring type on every line.
- **Explicit truncation**: a footer says how much was shown and which flag shows the rest.
- **Provenance**: a `source:` line names the artifact and whether the answer came from
  sources, bytecode or a decompiler.
- **A `next:` line** on single-entity answers with the most useful follow-up command.
- **No colour when piped.** ANSI colour only on a terminal (`--no-color` turns it off there too).

```console exec
$ jdx members java.util.List --limit 3
```

## Warnings

Anything an agent should know but did not ask for is a named warning, never silent: duplicate
classes across jars (shading), sources that do not match the binary, corrupt class files,
multi-release variants, unresolvable supertypes, project-discovery fallbacks. Each has a stable
code (`SOURCES_VERSION_MISMATCH`, `MULTI_RELEASE_VARIANT`, …) in both text and JSON.

## JSON output

Add `--json` to any command (before or after the command name). The envelope is the same for
the CLI, `jdx batch`, the daemon, the HTTP API and MCP, and carries exactly the information of
the text output:

```console exec
$ jdx members java.util.List --limit 2 --json
```

| Field | Meaning |
|---|---|
| `jdx` | envelope version (`1`) |
| `ok` | `true` on success; `false` carries `error.code` and, when ambiguous, `candidates[]` |
| `command`, `query` | what was asked |
| `result` | the command-specific answer |
| `truncated` | `shown` / `total` / `hint` when output was cut |
| `warnings` | `{code, message}` objects |
| `provenance` | where each answer came from: artifact, origin, file, lines |

## Exit codes

Exit codes are a public contract — they do not change between releases.

| Code | Meaning | What an agent should do |
|---|---|---|
| `0` | success, results found | use the answer |
| `1` | valid query, nothing found | read the `did you mean` list or widen the classpath |
| `2` | ambiguous reference | retry with one of the printed candidates |
| `3` | usage or argument error | fix the command (wrong flag, member ref where a type is required, …) |
| `4` | no index or workspace resolved | pass `--jars` / `--coord` / `-w`, or run inside a project |
| `5` | artifact read error | the jar is corrupt or unreadable |
| `6` | internal error | report a bug |

The one documented exception is `jdx diff --fail-on`, which exits `1` when its gate trips
([API diff](api-diff.md#gate-a-pull-request)).

## Bounded by default

Listings stop at `--limit` (50 for most commands) and say so. For the biggest listings,
`--brief` drops headers and provenance, and `--max-lines N` sets a hard line budget — useful
when an agent is short on context. Neither changes `--json` output.

## Deterministic

The same inputs give byte-identical output: everything is sorted, and there are no timestamps,
absolute paths or hash values in default output. Agents can cache answers, and diffs of jdx
output are meaningful.
