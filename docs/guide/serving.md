# MCP, daemon, HTTP & batch

The one-shot CLI starts in about a quarter of a second. When an agent asks dozens of questions, keep jdx warm instead: an MCP server, a background daemon, a local HTTP/JSON API, or a batch of queries in one process. All four are thin adapters over the same engine and speak the same JSON envelope.

## MCP server: `jdx mcp`

```bash
jdx mcp                 # serve the default workspace over stdio
jdx mcp -w myapp        # serve a named workspace
```

MCP clients start it as a child process (`jdx setup` writes that config for you — see
[Use with AI agents](ai-agents.md)). The server holds the index in memory for the session, so
repeated queries are instant, and each query is a typed `jdx_*` tool with a JSON schema — the
agent never has to quote `#` or `(` for a shell. The tool list is on the
[MCP tools](https://mohammadmd1383.github.io/jdx/docs/reference/mcp-tools/) page.

## Daemon: `jdx daemon`

A background JVM with a hot index, listening on a local socket. While it runs, ordinary `jdx`
commands for that workspace are forwarded to it automatically — per-query latency drops from
roughly 250 ms to roughly 20 ms.

```bash
jdx daemon start --workspace myapp   # idempotent; waits until it answers
jdx -w myapp members com.example.Service
jdx daemon status --workspace myapp  # uptime, memory, indexed artifacts, query count
jdx daemon stop --workspace myapp
```

`status` reports `indexed artifacts: 0` on purpose while graph queries are
live-roots-first (no persistent index yet — [issue #5](https://github.com/MohammadMD1383/jdx/issues/5));
the row and the health/`--json` `indexMode` field say `live-roots-first` so a
healthy daemon is not mistaken for an empty one ([issue #4](https://github.com/MohammadMD1383/jdx/issues/4)).
`GET /v1/health` and MCP `jdx_health` carry the same shape.

It shuts itself down after 5 minutes without requests (`--idle 30m`, or `--idle 0` to
disable). `--no-daemon` on any command forces in-process execution.

Warm text on the wire is always plain (the daemon has no TTY). The one-shot CLI
re-colorizes that text locally when stdout is a terminal and `--no-color` is off —
same gate as cold — so demos match while piped warm stays byte-identical to piped
cold ([issue #17](https://github.com/MohammadMD1383/jdx/issues/17)). `--json` is
never colourised.

## HTTP/JSON API: `jdx serve`

For editor plugins, scripts, notebooks and non-MCP agents:

```bash
jdx serve -w myapp --port 7070
curl 'http://127.0.0.1:7070/v1/members?query=com.example.Service&limit=20'
curl -X POST --data-binary @queries.ndjson http://127.0.0.1:7070/v1/batch
curl http://127.0.0.1:7070/v1/health
```

`GET /v1/<command>?query=…&<flag>=…` exists for every query command, answers are the exact
`--json` envelopes, and the server binds to `127.0.0.1` unless `--bind` says otherwise.

## Batch: `jdx batch`

Many queries, one process, one index load. Feed one JSON request per line on stdin; get one
envelope per line on stdout:

```console exec
$ echo '{"command":"show","query":"java.util.Map"}' | jdx batch
```

Request lines look like `{"command":"members","query":"…","params":{"limit":"5"}}`; `params`
uses the same names as the CLI flags. A failing query rides in its own envelope and never stops
the stream; the exit code is the highest exit code of any query.

## Performance

| Path | Target |
|---|---|
| Cold CLI, simple query | ≤ 250 ms |
| Warm query (daemon or MCP) | ≤ 20 ms |
| Re-run on an unchanged workspace | ~0 (content-hash short-circuit) |

`jdx bench` runs a fixed workload against your classpath and prints the timings next to these
targets.
