---
date: 2026-11-25
---

# Why a command-line Java decompiler for AI agents

Agents reading JVM code burn context on unzip, javap, and whole files. jdx answers one question per command, from the jar, the sources, or the JDK.

## One question, one answer

An agent in a JVM codebase keeps asking what an IDE answers for a person. Each of these is one command, and the line it prints names the symbol canonically, so the next command is a copy-paste:

- What can I call on this type? `jdx members com.google.gson.Gson --inherited`
- What does this one method do? `jdx body 'com.google.gson.Gson#toJson(Object)'`
- Who implements this interface? `jdx hierarchy java.util.Map`
- What does an upgrade break? `jdx diff old.jar new.jar`

If the name is ambiguous, jdx exits 2 and lists every candidate. It does not guess. A missing symbol exits 1.

## Bytecode for structure, sources for the body

Members, signatures, and callers are read from bytecode with ASM. The class is never loaded, so static initialisers never run. Method bodies, parameter names, and docs come from a `-sources.jar` or a source directory when one is there. With no sources, jdx decompiles with Vineflower. If that fails, the answer is a javap-style disassembly, and the output says which you are looking at.

Kotlin classes are read from `@Metadata` when the class file has it. When that metadata is missing or corrupt, you get the JVM view.

The same answers are a CLI, an MCP server (`jdx mcp`), a daemon, and an HTTP API.

## What it will not pretend to do

jdx does not remap obfuscated names. It does not run Gradle or Maven to discover a project. It does not dump a whole file when the question was one member. Those limits are the product: a smaller answer an agent can check.

The [introduction](../guide/introduction.md) and the [quickstart](../guide/quickstart.md) are the short path. [Use with AI agents](../guide/ai-agents.md) covers wiring. [Output, JSON and exit codes](../guide/output.md) is the contract for the exit codes above. [MCP, daemon, HTTP and batch](../guide/serving.md) is how the other interfaces start.
