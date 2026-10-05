# Introduction

jdx is an IDE for AI agents, as a command line tool. It answers the questions an IDE answers for a human — what members a class has, what a method does, who calls it, what implements an interface, what an upgrade breaks — for any JVM jar, Maven coordinate, class or source directory, and the JDK itself.

## Why it exists

An agent working on a Java or Kotlin codebase constantly needs to know things about code it
cannot see: the library jars on the classpath and the JDK. Without jdx it pieces the answers
together with `unzip`, `javap`, `grep` and by reading thousand-line source files into its
context window. That is slow, noisy, and burns the context the agent needs for the actual task.

jdx replaces each of those multi-step hunts with one command that returns one precise answer:

```console exec
$ jdx show java.util.HashMap
```

Every answer names its symbols canonically and suggests the next command (`next:`), so an agent
moves from question to question by copy-paste instead of reconstruction.

## What it can answer

| Question | Command | IDE gesture |
|---|---|---|
| What is this type? | `jdx show` | Go to declaration |
| What can I call on it? | `jdx members` | Code completion after `.` |
| What does this method do? | `jdx body` | Open the method |
| What does the javadoc say? | `jdx doc` | Quick documentation |
| Who uses this? | `jdx usages` | Find usages |
| Who implements this? | `jdx hierarchy`, `jdx implementors` | Type hierarchy |
| Who calls this, what does it call? | `jdx callers`, `jdx calls` | Call hierarchy |
| How is it used in practice? | `jdx samples` | *(no IDE equivalent)* |
| What does this upgrade break? | `jdx diff` | *(no IDE equivalent)* |

The [command catalogue](../COMMANDS.md) lists all of them.

## Design principles

These are product guarantees, not aspirations — each is enforced by tests:

- **Minimal correct output.** Bounded by default, never a file dump when a member was asked
  for. Truncation is explicit and says which flag shows the rest.
- **Never guesses.** An ambiguous reference exits `2` and lists every candidate as a
  copy-pasteable reference. Guessing wrong is worse than asking.
- **Provenance on every answer.** Which jar, whether the text came from sources, bytecode or a
  decompiler — and which one — so the agent can judge confidence.
- **Deterministic.** Same inputs, byte-identical output. No timestamps, no absolute paths.
- **Meaningful exit codes**, so agents branch without parsing prose ([details](output.md#exit-codes)).
- **Degrade, don't fail.** No sources jar? It decompiles — and labels the result as reconstructed.
- **Read-only.** Bytecode is parsed with ASM; inspected classes are never loaded, so no code
  from the jars you inspect ever runs.

## Where to go next

1. [Install jdx](installation.md) (one command, JDK 21+).
2. Run the [quickstart](quickstart.md) — five minutes against the JDK and a Maven library.
3. [Connect your AI agent](ai-agents.md) over MCP or the CLI.
