# Use with AI agents

Wire jdx into Claude Code, Cursor, Codex, GitHub Copilot, Antigravity, OpenCode, Kilo Code, Cline or any MCP client with one command — or let any shell-capable agent call the CLI directly.

## Option 1: MCP, wired by `jdx setup` (recommended)

`jdx mcp` is a Model Context Protocol server over stdio. It exposes every query as a typed
tool, so the agent never fights shell quoting of `#` and `(`, and it keeps the index hot in
memory for the whole session — repeated questions are instant.

`jdx setup` writes the right config entry for your agent:

```bash
jdx setup --agent claude-code --scope project   # .mcp.json in this checkout
jdx setup --agent cursor --scope system         # ~/.cursor/mcp.json for every project
```

Then restart the agent. `--scope project` writes into the current checkout (commit it to share
with your team); `--scope system` writes the user-global config. Setup merges into existing
config and never clobbers unrelated entries; re-running is a no-op.

Check or undo it at any time:

```console exec
$ jdx setup --agent claude-code --scope project --check   # exit 1
```

`--check` exits `1` when jdx is not wired yet (as above) and `0` when it is; `--remove`
uninstalls cleanly. Per-agent instructions and the exact files written are on the
[integrations pages](https://mohammadmd1383.github.io/jdx/integrations/).

## Option 2: MCP, configured by hand

Any MCP client that launches stdio servers works with this entry:

```json
{"mcpServers": {"jdx": {"command": "jdx", "args": ["mcp"]}}}
```

Add `"args": ["mcp", "-w", "myproject"]` to serve a named workspace. The tool list, with every
parameter, is on the [MCP tools](https://mohammadmd1383.github.io/jdx/docs/reference/mcp-tools/) page.

## Option 3: the CLI plus a cheat sheet

Every agent that can run shell commands can use jdx directly. Paste the output of
`jdx help --agent` into `CLAUDE.md`, `AGENTS.md`, `.cursorrules` or a system prompt:

```console exec max-lines=8
$ jdx help --agent
```

The full block is on the [agent cheat sheet](https://mohammadmd1383.github.io/jdx/docs/reference/cheat-sheet/) page.

## Tips that make agents better at it

- **Teach the exit codes.** `1` means *not found* (read the `did you mean` list), `2` means
  *ambiguous* (retry with one of the printed candidates), `4` means no classpath resolved.
  See [exit codes](output.md#exit-codes).
- **Prefer `body` over `source`.** A method body is tens of lines; a source file is thousands.
- **Name the classpath once** with a [workspace](classpath.md#named-workspaces) and start the
  MCP server or daemon on it.
- **Use `--json`** when the agent post-processes output; it carries exactly the same
  information as text.
- **Follow the `next:` line.** Single-entity answers end with the most useful follow-up command.
