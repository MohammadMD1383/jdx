# Built by agents

jdx is a tool for AI agents that is itself written almost entirely by AI agents, working under rules a human owner sets. This page describes how that works — the guardrails are what make the result trustworthy.

## Agents do not share memory — so the repository has to

Each contribution starts from a cold context. Nothing an earlier agent "knew" survives unless
it is written down, so the repository is organised to be resumable by a stranger:

- [`AGENTS.md`](../../AGENTS.md) is the entry point: what the project is, the non-negotiable
  principles, decisions the owner has locked (and how to reverse one), exit codes, build and
  test rules.
- Every module has its own `AGENTS.md` with its key files, invariants and known gotchas — an
  agent reads only the notes for the code it touches.
- [`CONTRIBUTING.md`](../../CONTRIBUTING.md) defines "done" for a command: behaviour, adapters,
  text and JSON renderers, exit codes, truncation, golden tests, a generative test family,
  documentation and an issue update.
- [GitHub Issues](https://github.com/MohammadMD1383/jdx/issues) are the roadmap and the list
  of known limitations.

A note that goes stale is treated as a bug: a change that invalidates a note updates it in the
same commit.

## Tests that do not trust examples

Hand-written examples only test what the author thought of. jdx requires a *generative* test
family for every behaviour, on top of examples:

- **Differential** — answers are checked against `javap` across real jars.
- **Property-based** — e.g. every printed reference parses back to itself.
- **Metamorphic** — equivalent queries must give equivalent answers.
- **Fault injection** — corrupt jars, truncated class files, unreadable caches.
- **Corpus soak** — thousands of real jars, including a large obfuscated one.
- **Mutation testing** — the core must kill at least 80 % of mutants; line-coverage gates on
  the other engine modules.

The full strategy is in the [testing guide](../TESTING.md).

## Dogfooding

Agents working on jdx use jdx on jdx — its own jars, its dependencies, the JDK — instead of
`grep` and `javap`. A wrong answer, crash or missing capability found that way is filed as an
issue in the same session. The tool improves exactly where agents struggle with it.

## Docs that cannot drift

This website is generated from the repository on every change to `main`, and it is built to
fail rather than lie:

- every command example on it is **executed** against the freshly built jdx, and the build fails
  if a command no longer works or its exit code changed;
- the command and MCP reference pages are generated from `jdx --help` and the MCP server itself;
- every `jdx <command> --flag` mentioned in the docs is checked against the real help text;
- every internal link and anchor is resolved.

So when an agent changes the CLI and forgets the docs, the site build goes red on the pull
request instead of publishing something stale.

## The human's job

The owner sets direction, settles design questions, approves anything outward-facing (releases,
public repository changes) and reviews the results. Agents are expected to ask when something is
genuinely ambiguous rather than guess — the same rule jdx itself follows.
