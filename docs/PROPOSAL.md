# `jdx` — An IDE for Agents

**Proposal / design specification — v1.0**
Date: 2026-09-13 · Status: **approved scope, pending implementation**

---

## Table of contents

1. [Problem statement](#1-problem-statement)
2. [What an IDE actually gives a human](#2-what-an-ide-actually-gives-a-human)
3. [What an agent needs that an IDE does not provide](#3-what-an-agent-needs-that-an-ide-does-not-provide)
4. [Product goals and non-goals](#4-product-goals-and-non-goals)
5. [Conceptual model](#5-conceptual-model)
6. [Symbol reference grammar](#6-symbol-reference-grammar)
7. [Command surface](#7-command-surface)
8. [Output design](#8-output-design)
9. [Architecture](#9-architecture)
10. [The index](#10-the-index)
11. [Sources, bytecode and decompilation](#11-sources-bytecode-and-decompilation)
12. [Kotlin support](#12-kotlin-support)
13. [Classpath resolution](#13-classpath-resolution)
14. [Interfaces: CLI, MCP, daemon, HTTP](#14-interfaces-cli-mcp-daemon-http)
15. [Performance plan](#15-performance-plan)
16. [Error handling, exit codes, degradation](#16-error-handling-exit-codes-degradation)
17. [Security and safety](#17-security-and-safety)
18. [Testing strategy](#18-testing-strategy)
19. [Dependencies and licensing](#19-dependencies-and-licensing)
20. [Delivery plan / milestones](#20-delivery-plan--milestones)
21. [Deferred to phase 2](#21-deferred-to-phase-2)
22. [Risks](#22-risks)
23. [Appendix A: worked agent session](#appendix-a-worked-agent-session)
24. [Appendix B: full flag reference](#appendix-b-full-flag-reference)

---

## 1. Problem statement

When an AI agent works on a JVM project, most of its wall-clock time and most of its context
window are consumed not by writing code but by **discovering what the code around it can do**.

The current state of the art for an agent investigating a library is a sequence like:

```bash
unzip -l ~/.gradle/caches/.../gson-2.14.0.jar | grep -i gson
unzip -p ~/.gradle/caches/.../gson-2.14.0-sources.jar com/google/gson/Gson.java | head -200
javap -p -cp gson-2.14.0.jar com.google.gson.Gson
javap -c -p -cp gson-2.14.0.jar com.google.gson.Gson | sed -n '/toJson/,/^$/p'
grep -rn "TypeAdapter" src/
```

Every step of that is a problem:

| Symptom | Cost |
|---|---|
| `unzip -p ... \| head -200` on a 1,400-line file | Either the answer is cut off, or 1,400 lines of mostly-irrelevant code land in context |
| `javap` prints erased signatures (`java.util.List` not `List<String>`) | The agent reasons about the wrong types |
| `javap -c` prints opcodes | The agent must mentally decompile to understand behaviour |
| Inherited members are invisible | The agent must manually walk `extends`/`implements` chains, one `javap` per level, to answer "what can I call on this?" |
| No sources jar | The agent gives up or guesses the API |
| "Who calls this?" | `grep` across jars is impossible; across source it's noisy and misses dynamic/compiled uses |
| Every question costs a new shell round-trip | Latency and token cost multiply |

The underlying issue: **an agent is doing by hand what an IDE does with an index.** IntelliJ
solved this for humans twenty years ago. Nothing exposes it to a process that speaks stdin
and stdout.

`jdx` is that exposure.

---

## 2. What an IDE actually gives a human

Enumerating this explicitly matters, because the goal is parity — not "a nicer `javap`".
IntelliJ IDEA provides, over any jar on the classpath, with or without sources:

| IDE capability | Human gesture | `jdx` equivalent |
|---|---|---|
| Code completion on a receiver | type `foo.` | `jdx members <type> --inherited` |
| Parameter info | `Ctrl+P` inside `(` | `jdx signature '<type>#<method>'` |
| Quick documentation | `Ctrl+Q` / `F1` | `jdx doc '<symbol>'` |
| Go to declaration | `Ctrl+Click` | `jdx show <type>` / `jdx body '<member>'` |
| Go to implementation | `Ctrl+Alt+Click` | `jdx hierarchy <type> --implementors` |
| Type hierarchy | `Ctrl+H` | `jdx hierarchy <type>` |
| Call hierarchy | `Ctrl+Alt+H` | `jdx callers` / `jdx calls` |
| Find usages | `Alt+F7` | `jdx usages <symbol>` |
| Search everywhere / Go to class / symbol | `Shift Shift`, `Ctrl+N`, `Ctrl+Alt+Shift+N` | `jdx search <pattern>` |
| File structure popup | `Ctrl+F12` | `jdx outline <type>` |
| Decompile a class without sources | open a `.class` | `jdx body` / `jdx source` (Vineflower) |
| Attach/browse sources jar | automatic | automatic (sources jar detection) |
| Show inherited members in completion | default on | `--inherited`, default on |
| External library browser | Project view → External Libraries | `jdx ls` / `jdx tree` |
| Show bytecode | *Show Bytecode* action | `jdx body --engine javap` |
| Analyze dependencies of a class | *Analyze → Dependencies* | `jdx calls --depth N` |

All of the above are in scope for v1 except where §21 says otherwise.

---

## 3. What an agent needs that an IDE does not provide

Parity is the floor, not the ceiling. An agent is not a human and has different constraints.
These features have **no IDE equivalent** and exist purely because the consumer is a language
model:

### 3.1 Context-window economy
An IDE renders into a scrollable window; wasted pixels are free. For an agent, every token is
paid for and crowds out reasoning. So:

- **`--max-lines` / `--brief`** — bounded output with an explicit continuation hint:
  `… 47 more methods. Narrow with --grep or --from <supertype>.`
- Signature-only modes that omit bodies and docs by default.
- **No decorative output.** No banners, no box drawing, no repeated file paths.
- Deduplicated overload rendering where the difference is only one parameter.

### 3.2 Machine-legible failure
A human reads "not found" and adapts. An agent needs a branch it can take without parsing
prose: distinct **exit codes** for *not found*, *ambiguous*, *no index*, *bad usage* (§16),
and an `--json` envelope that carries the same information structurally.

### 3.3 Never-guess semantics
IntelliJ can pop a chooser dialog. A CLI cannot. So an ambiguous reference is a **first-class
result**, not an error: `jdx` exits `2` and prints every candidate as an exact,
copy-pasteable canonical reference. The agent's retry is then mechanical.

### 3.4 Self-describing next actions
Every rendered symbol carries the exact reference string needed to query it further. An agent
should never have to *construct* a reference by string surgery — it copies one it was handed.

### 3.5 Provenance and confidence
The agent must know whether it is reading ground truth or a reconstruction:

```
source: gson-2.14.0-sources.jar (com/google/gson/Gson.java:412-430)
```
versus
```
source: decompiled by vineflower from gson-2.14.0.jar (no sources jar)   ⚠ reconstructed
```

An agent that knows a body is decompiled will not quote it as canonical in a code review.
An agent that does not, will.

### 3.6 Real usage examples
`jdx samples '<symbol>'` returns **actual call sites from the indexed classpath**, trimmed to
a few lines of surrounding context. "How is this API really used?" answered with evidence
rather than the model's recollection of a README. This is often more valuable than the
javadoc, and no IDE surfaces it in this form.

### 3.7 Batch execution
JVM start-up amortized across many questions:

```bash
jdx batch --json <<'Q'
members com.google.gson.Gson --inherited
body com.google.gson.Gson#toJson(Object)
usages com.google.gson.TypeAdapter --limit 10
Q
```
One process, one index load, N results as JSON lines. Cuts a 10-question investigation from
~4 s of process start-up to ~0.4 s.

### 3.8 A self-installing instruction block
`jdx help --agent` prints a compact, paste-ready cheat sheet designed to be dropped into a
`CLAUDE.md`, system prompt, or MCP tool description — so an agent learns the tool without the
user writing documentation.

### 3.9 Idempotent, cacheable, deterministic
Identical output for identical input means the *agent's own* prompt cache stays warm across
turns, and golden-file testing is possible.

---

## 4. Product goals and non-goals

### Goals
- **G1** Answer any "what is this / what can I call / what does it do / who uses it" question
  about JVM code on a classpath, in one command, in minimal tokens.
- **G2** Work with sources jars, plain jars, class dirs, source dirs, Maven coordinates, and
  the JDK itself — uniformly, with no configuration in the common case.
- **G3** Resolve inherited members transitively so an agent never walks a hierarchy by hand.
- **G4** Be fast enough to be called dozens of times in a single agent task.
- **G5** Be equally usable from a shell, from MCP, and over HTTP.
- **G6** Be honest: always state provenance, never fabricate, never silently truncate.

### Non-goals
- **N1** Not a compiler, type-checker, or language server for *your* code being edited.
  (`jdx` reads a classpath; it does not do incremental compilation or diagnostics.)
- **N2** Not a refactoring or code-modification tool. **Strictly read-only** with respect to
  user code.
- **N3** Not a build tool. It reads Gradle/Maven *outputs and caches*; it does not run builds
  or resolve full dependency graphs with conflict resolution.
- **N4** Not a general decompiler UI. Decompilation is a means to answer questions.
- **N5** Not multi-language. JVM only (Java, Kotlin, and the JVM projection of anything else
  — Scala/Groovy/Clojure classes are readable as bytecode, but get no language-specific view).

---

## 5. Conceptual model

Four nouns. Everything in the tool is expressed in terms of them.

### 5.1 Root
A place where code can be found. Exactly six kinds:

| Root kind | Example | Provides |
|---|---|---|
| `BinaryJar` | `gson-2.14.0.jar` | classes (bytecode) |
| `SourcesJar` | `gson-2.14.0-sources.jar` | `.java` / `.kt` text |
| `ClassDir` | `build/classes/java/main` | classes (bytecode) |
| `SourceDir` | `src/main/kotlin` | `.java` / `.kt` text |
| `JrtModules` | the running JDK via `jrt:/` | JDK classes (+ `lib/src.zip` for JDK sources) |
| `MavenCoord` | `com.google.code.gson:gson:2.14.0` | resolved to a `BinaryJar` + `SourcesJar` pair |

### 5.2 Artifact
A `BinaryJar` **paired with** its matching `SourcesJar` when one can be found, identified by
a **content hash** of the binary. Pairing rules, in order:
1. Sibling file `<name>-sources.jar`.
2. Gradle cache layout: same `modules-2/files-2.1/<group>/<artifact>/<version>/*/` parent.
3. Maven cache layout: same directory in `~/.m2/repository`.
4. Explicit `--sources <path>` override.
5. Sources embedded in the binary jar itself (some artifacts ship `.java` alongside `.class`).

The content hash is the cache key, so an artifact indexed once is reused by every workspace
that contains it. Re-indexing `gson` for a second project is free.

### 5.3 Workspace
A named, ordered list of roots plus its resolution settings. Workspaces are how an agent
avoids passing a 2,000-entry classpath on every invocation:

```bash
jdx ws create mc --jars '~/.gradle/caches/**/*.jar' --src ./src/main/java --jdk
jdx -w mc members net.minecraft.world.item.ItemStack
```

Ordering matters: earlier roots shadow later ones, exactly like a JVM classpath, so
`jdx` reports the class the JVM would actually load and **warns on duplicate FQNs across
artifacts** (`shaded`/`relocated` jars are a real and frequent source of agent confusion).

Stored as TOML in `~/.config/jdx/workspaces/<name>.toml`. Human-editable and diffable.

### 5.4 Symbol
A type, method, field, constructor, package, or module — each with a **canonical reference**
(§6), a declaring artifact, a kind, and a resolved signature. The symbol is the unit every
command consumes and emits.

---

## 6. Symbol reference grammar

Designed to be **generous on input, canonical on output**.

```
ref        := typeRef [ memberSep memberRef ]
typeRef    := [ coord '/' ] [ package '.' ] simpleName { ('$'|'.') nestedName }
coord      := group ':' artifact ':' version          (Maven coordinate prefix)
memberSep  := '#' | '::' | '.'
memberRef  := ( name | '<init>' | '<clinit>' ) [ params ]
params     := '(' [ paramList ] ')' [ ':' returnType ]
           |  descriptor                               (exact JVM descriptor)
paramList  := param { ',' param }
param      := simpleTypeName | fqTypeName | primitiveName | param '[]' | '...'
```

### Accepted forms, all valid

| Input | Meaning |
|---|---|
| `Gson` | short type name → resolve across workspace; exit `2` if ambiguous |
| `com.google.gson.Gson` | exact type |
| `com.google.gson.Gson$1` | anonymous class |
| `java.util.Map.Entry` | nested via `.` (normalizes to `Map$Entry`) |
| `com.google.code.gson:gson:2.14.0/com.google.gson.Gson` | coordinate-scoped |
| `Gson#toJson` | all overloads of `toJson` |
| `Gson#toJson(Object)` | one overload, simple param names |
| `Gson#toJson(java.lang.Object)` | one overload, fully qualified |
| `Gson#toJson(Object):String` | disambiguated by return type (needed for bridge methods) |
| `Gson#toJson(Ljava/lang/Object;)Ljava/lang/String;` | exact descriptor — never ambiguous |
| `Gson::toJson` | `::` separator |
| `Gson#<init>(Excluder,FieldNamingStrategy)` | constructor |
| `Gson#excluder` | field |
| `com.google.gson.*` | package glob (for `search`, `ls`) |

### Canonical output form
`jdx` always *prints* the most specific unambiguous form:
`com.google.gson.Gson#toJson(java.lang.Object)`
adding `:returnType` only when required to disambiguate bridge/covariant overloads.

### Ambiguity protocol
```
$ jdx body 'Gson#toJson'
ambiguous: 4 candidates for Gson#toJson
  com.google.gson.Gson#toJson(java.lang.Object)
  com.google.gson.Gson#toJson(java.lang.Object, java.lang.reflect.Type)
  com.google.gson.Gson#toJson(com.google.gson.JsonElement)
  com.google.gson.Gson#toJson(java.lang.Object, java.lang.Appendable)
hint: re-run with one of the refs above
$ echo $?
2
```

---

## 7. Command surface

### 7.1 Inspection — the core loop

#### `jdx show <type>`
The "class card". Kind, modifiers, generic declaration, supertype chain, interfaces,
enclosing/nested types, annotations, deprecation, origin artifact, source availability,
Kotlin-ness, and a **member count summary** (not the members themselves).

```
class com.google.gson.Gson
  package com.google.gson · gson-2.14.0.jar · sources: yes · java
  public final class Gson
  extends java.lang.Object
  nested: Gson$FutureTypeAdapter, Gson$1
  members: 39 methods (14 public), 12 fields (0 public), 2 constructors
  doc: This is the main class for using Gson. Gson is typically used by first
       constructing a Gson instance and then invoking toJson or fromJson…
next: jdx members com.google.gson.Gson --inherited
```

#### `jdx members <type>`
**The most important command** — the `.` completion equivalent.

- `--inherited` *(default: on)* — include members from superclasses and interfaces,
  grouped and labelled by declaring type, with overridden members collapsed.
- `--declared` — only members declared on this exact type.
- `--kind method|field|ctor|all`, `--static`, `--instance`
- `--access public|protected|package|private|all` *(default: `public` + `protected`)*
- `--from <supertype>` — only members inherited from a specific supertype
- `--grep <regex>` — filter by name
- `--with-doc` — include first javadoc sentence per member
- `--brief` — signatures only: bare member rows, no group headers, no
  provenance block (warnings and footers stay); mutually exclusive with
  `--with-doc`; text only, `--json` unaffected
- `--max-lines N` — cap text lines with a continuation footer; text only,
  `--json` unaffected
- `--sort name|kind|declaring` *(default: `kind` then `name`)*
- `--include-synthetic` — bridge/synthetic/lambda members, hidden by default

Overloads of the same name are grouped. Inherited `java.lang.Object` members are collapsed to
one line by default (`+ 9 from java.lang.Object (--from java.lang.Object to expand)`) because
they are almost never what the agent wants and cost ~200 tokens every call.

#### `jdx signature <member-ref>`
Just the signature(s), with real parameter names, generics, throws, annotations, and default
values. The "parameter info" popup. One line per overload.

#### `jdx doc <symbol>`
Javadoc/KDoc only, rendered to plain text: HTML stripped, `{@link}`/`{@code}` unwrapped,
`@param`/`@return`/`@throws`/`@since`/`@deprecated` preserved as a tidy block. **Inherited
documentation is pulled from the nearest supertype** that documents the method (matching
IntelliJ's quick-doc), labelled as such.

#### `jdx body <member-ref>`
The headline feature: *"give me the body of `org.some.lib.AClass.someMethod(int,int)`"*.

Resolution order:
1. Sources jar/dir → exact AST node range → verbatim text (**ground truth**).
2. No sources → Vineflower decompilation of just the enclosing class, method extracted.
3. `--engine javap` → raw JVM instructions.

Flags: `--engine vineflower|javap`, `--with-doc`, `--with-signature`, `--context N`
(surrounding lines), `--line-numbers`, `--max-lines N`.

Also accepts a **type** ref, in which case it prints the whole type body — which is exactly
`jdx source` with the type's range.

#### `jdx source <type>`
Whole source file or a slice: `--lines 100:180`, `--around <member-ref> --context 20`.
Decompiles if no sources exist. This is the escape hatch when a body is not enough.

#### `jdx outline <type>`
One dense line per member — the *File Structure* popup. Cheapest way to see a class's shape.
Takes the same `--brief` / `--max-lines` text budgets as `members`.

### 7.2 Search and navigation

#### `jdx search <pattern>`
Symbol search across the workspace.
- Matching: glob (`*Http*Client`), regex (`--regex`), **camel-hump** (`HMap` → `HashMap`,
  `gJson` → `getJson`) — IntelliJ's `Ctrl+N` behaviour.
- `--kind class|interface|enum|record|annotation|method|field|package|module`
- `--in <artifact-glob>`, `--package <glob>`, `--limit N` *(default 50)*
- `--fuzzy` — Levenshtein fallback when nothing matches exactly

#### `jdx resolve <name>`
"What is this identifier?" — takes an unqualified or partial name and lists every candidate
with its kind and artifact. The disambiguator an agent reaches for when it sees an unknown
symbol in a stack trace or a code snippet.

#### `jdx imports <type>`
The import statement(s) to add, plus a warning when several artifacts provide the same FQN.

#### `jdx ls [<package-glob>]` / `jdx tree <artifact>`
Browse an artifact's packages and types. `--depth N`, `--counts`.

### 7.3 The relationship graph

#### `jdx hierarchy <type>`
Supertypes upward (full transitive chain, interfaces included) and subtypes/implementors
downward across the whole workspace.
`--up`, `--down`, `--depth N`, `--direct` (one level), `--in <artifact-glob>`.

```
java.util.Map
  ↑ (none)
  ↓ implementors in workspace (37, showing 10)
    java.util.HashMap                      java.base
    java.util.LinkedHashMap                java.base  (extends HashMap)
    java.util.TreeMap                      java.base
    com.google.gson.internal.LinkedTreeMap gson-2.14.0.jar
    …
```

#### `jdx usages <symbol>`
Find Usages across **all workspace jars plus the project's own source dirs**.
- `--kind call|ref|impl|override|read|write|new|throw|annotation|all`
- `--in <artifact-glob>`, `--exclude <glob>`, `--limit N`, `--context N`
- Groups by artifact, then by declaring method, with the canonical ref of each call site.

#### `jdx callers <method>` / `jdx calls <method>`
Incoming / outgoing call edges. `--depth N` walks the tree (cycle-safe, with `…(cycle)`
markers). This lets an agent trace an execution path across jars without decompiling every
class along the way.

#### `jdx samples <symbol>`
Real call sites as usage examples, source-rendered when sources are available, ranked by
"exemplariness" (prefers non-test, non-generated, short enclosing methods, and call sites
that use more of the API's parameters). `--limit N` *(default 3)*.

#### `jdx overrides <method>` / `jdx implementors <type>`
Convenience aliases over `hierarchy`/`usages` because they are so frequently wanted.

### 7.4 Workspace and cache management

```
jdx ws create <name> [--jars <glob|path>…] [--src <dir>…] [--coord <g:a:v>…] [--jdk]
jdx ws list | info <name> | remove <name> | add <name> <root> | use <name>
jdx index [-w <name>] [--force]        build / refresh the index
jdx cache info | gc | clear
jdx doctor                             environment diagnostics
jdx version [--json]
jdx help --agent                       paste-ready cheat sheet for an agent prompt
```

### 7.5 Serving

```
jdx mcp                                MCP server over stdio
jdx daemon start|stop|status|restart   background index host, 5-min idle auto-shutdown
jdx serve [--port 7070] [--bind 127.0.0.1]   HTTP/JSON API
jdx batch [--json]                     read queries from stdin, one result per line
```

### 7.6 Global flags

`-w/--workspace`, `--jars`, `--src`, `--coord`, `--jdk/--no-jdk`, `--json`, `--no-color`,
`--max-lines`, `--brief`, `--verbose`, `--quiet`, `--no-daemon`, `--timeout`, `--cache-dir`.

---

## 8. Output design

### 8.1 Text (default)
Optimised for a language model reading it:

- **Two-space indentation**, never box-drawing or tables — they cost tokens and tokenize badly.
- **Leading kind word** on every entity line (`class`, `method`, `field`) so the model never
  has to infer structure from punctuation.
- **Grouping headers** instead of repeating the declaring type on every line.
- **A `next:` line** on single-entity output, suggesting the highest-value follow-up command.
- **Truncation is always explicit**, always with the flag that would reveal the rest.
- **ANSI colour only when stdout is a TTY**; never when piped (agents always get plain text).

### 8.2 JSON (`--json`)
A stable envelope, versioned, identical across CLI / HTTP / MCP:

```json
{
  "jdx": 1,
  "ok": true,
  "command": "members",
  "query": "com.google.gson.Gson",
  "result": { "...": "command-specific" },
  "truncated": { "shown": 50, "total": 97, "hint": "--limit 200" },
  "warnings": [
    {"code": "SOURCES_VERSION_MISMATCH", "message": "sources jar does not match binary"}
  ],
  "provenance": [
    {"symbol": "com.google.gson.Gson", "artifact": "gson-2.14.0.jar",
     "origin": "sources", "file": "com/google/gson/Gson.java", "lines": [412, 430]}
  ]
}
```

`ok: false` carries `error.code` matching the exit-code taxonomy, plus `candidates[]` for the
ambiguous case — so an HTTP/MCP client gets the same disambiguation protocol as the CLI.

### 8.3 Truncation policy
Never cut mid-entity. Prefer dropping whole low-value groups (inherited `Object` members,
synthetic members, private members) before dropping high-value ones, and always report what
was dropped and how to get it.

---

## 9. Architecture

### 9.1 Modules (Gradle multi-project)

```
core/       Pure Kotlin domain: Symbol/Type/Member model, SymbolRef parser+printer,
            hierarchy & member-resolution algorithms, rendering (text + JSON),
            truncation/token-budget logic.  No file or network IO.  Fully unit-testable.

index/      ASM class reading, @Metadata decoding, reference-edge extraction,
            SQLite store, incremental indexer, artifact hashing & sources pairing.

sources/    Sources-jar/dir access, JavaParser integration, Kotlin PSI integration
            (behind an interface, loaded lazily in an isolated classloader),
            symbol→(file,range) mapping, body extraction.

decompile/  DecompilerEngine interface; Vineflower and javap implementations;
            single-class decompilation with caching.

cli/        Clikt command tree; flag parsing; renderer selection; exit codes;
            daemon client (transparently forwards to a running daemon).

mcp/        MCP stdio server; tool schema generation from the same command metadata
            the CLI uses (single source of truth — no schema drift).

server/     HTTP/JSON server (JDK built-in com.sun.net.httpserver — zero dependency)
            and the daemon host with idle-timeout supervision.

app/        Fat-jar assembly, AppCDS archive generation, `jdx` launcher script, install.
```

**Hard rule:** `cli`, `mcp` and `server` are *adapters*. They contain argument parsing and
transport only. All behaviour lives in `core`/`index`/`sources`/`decompile` behind a
`JdxService` facade, so the four interfaces cannot drift apart.

### 9.2 The service facade

```kotlin
interface JdxService {
    fun show(ref: TypeRef, opts: ShowOptions): Result<ClassCard>
    fun members(ref: TypeRef, opts: MemberOptions): Result<MemberListing>
    fun body(ref: MemberRef, opts: BodyOptions): Result<CodeBlock>
    fun search(pattern: Pattern, opts: SearchOptions): Result<SymbolHits>
    fun usages(ref: SymbolRef, opts: UsageOptions): Result<UsageListing>
    fun hierarchy(ref: TypeRef, opts: HierarchyOptions): Result<Hierarchy>
    fun callGraph(ref: MemberRef, dir: Direction, opts: CallOptions): Result<CallTree>
    // …
}
```

One implementation over a live index; one implementation that is an RPC client to the daemon.
The CLI picks between them transparently.

### 9.3 Member resolution algorithm (the `--inherited` core)

This is the piece that saves agents the most work, so it is specified precisely:

1. Load the target type's `ClassInfo` from the index.
2. Compute the **linearized supertype set**: BFS over `superclass` then `interfaces`,
   recording depth and path. Cycle-safe. Includes `java.lang.Object` last.
3. For each supertype, substitute **generic type arguments** down the chain, so
   `class StringList extends ArrayList<String>` reports `boolean add(String)`, not `add(E)`.
   (Signature attribute → type-variable environment → substitution.)
4. Collect members, applying JLS visibility rules from the perspective of the *query*:
   `private` members of supertypes are excluded; package-private only when same package.
5. **Collapse overrides**: a member with the same name+erased-descriptor as one already
   collected from a nearer type is dropped, but recorded as `overridden in <type>` metadata.
6. Drop bridge/synthetic members unless `--include-synthetic`.
7. For Kotlin types, map the collected JVM members back onto Kotlin declarations
   (properties instead of getter/setter pairs, default-arg synthetics folded in).
8. Group by declaring type, sort, render.

---

## 10. The index

### 10.1 Why persistent
"Find usages" and "implementors" require scanning **every class in the workspace** for
references to a target. For a Minecraft-scale workspace (~20k classes plus dependencies) that
is seconds per query if done live, and milliseconds if done from an index. The index is also
what makes camel-hump symbol search viable.

### 10.2 Storage: SQLite, content-hash keyed, globally shared

One database: `~/.cache/jdx/index/v1.db` (WAL mode — many readers, one writer).

Every row is scoped by `artifact_id`, which is the **content hash of the binary jar**.
Consequences, all good:
- Indexing `gson` once serves every workspace that contains it.
- A workspace is just an ordered list of `artifact_id`s — cheap to create, cheap to switch.
- Cache invalidation is trivial: if the hash changed, it is a different artifact.
- `jdx cache gc` drops artifacts no workspace references and none was used recently.

### 10.3 Schema (abbreviated)

```sql
artifact(id, hash, path, sources_path, kind, jar_mtime, jar_size, indexed_at, schema_ver)
class   (id, artifact_id, fqn, simple_name, package, access, kind, signature,
         super_id, source_file, is_kotlin, deprecated, outer_id)
iface   (class_id, iface_fqn)                      -- implements edges
member  (id, class_id, name, descriptor, signature, access, kind,
         param_names, throws, default_value, deprecated)
annot   (owner_kind, owner_id, annot_fqn, values)
ktmeta  (class_id, blob)                           -- decoded @Metadata
doc     (owner_kind, owner_id, text, tags)         -- javadoc/KDoc extracted from sources
srcmap  (owner_kind, owner_id, file, start_line, end_line, start_col, end_col)
ref     (from_member_id, to_fqn, to_member, kind, line)   -- the usage/call graph
name_idx(simple_name, camel_humps, class_id)       -- search acceleration
```

Indices on `class.fqn`, `class.simple_name`, `member.name`, `ref.to_fqn`,
`ref.to_member`, `iface.iface_fqn`, `name_idx.camel_humps`.

### 10.4 Indexing pipeline

Per artifact, parallel across artifacts using **virtual threads**:

1. Hash the jar (streaming, `SHA-256` truncated to 128 bits — enough, and fast).
2. If `artifact.hash` already present with a matching `schema_ver`, skip entirely.
3. Stream entries; for each `.class`:
   - `ClassReader` with `SKIP_FRAMES` (we *need* debug info for parameter names, so not
     `SKIP_DEBUG`).
   - Extract class/member/annotation rows.
   - Extract reference edges from the method bodies via a lightweight `MethodVisitor`
     (`visitMethodInsn`, `visitFieldInsn`, `visitTypeInsn`, `visitInvokeDynamicInsn`,
     `visitLdcInsn` for class constants) — no full control-flow analysis.
   - Decode `@Metadata` if present.
4. If a sources jar is paired, index it **lazily on first source query** rather than eagerly —
   source parsing (especially Kotlin PSI) is an order of magnitude more expensive than ASM,
   and most queries never need it. Source indexing populates `doc` and `srcmap`.
5. Commit in batches inside one transaction per artifact.

**Incremental behaviour:** a changed `ClassDir`/`SourceDir` (the user's own project, which
changes constantly) is re-indexed per-file by mtime, not wholesale.

### 10.5 Estimated cost
Rough, to be validated against `minecraft-client.jar` during M2:
- Index rate target: **≥ 3,000 classes/second** for the bytecode pass on this machine.
- Index size target: **≤ 6 %** of jar size (bytecode pass), dominated by the `ref` table.
- Query latency target: **< 15 ms** warm for any single-type query; **< 150 ms** for
  `usages` over a 20k-class workspace.

---

## 11. Sources, bytecode and decompilation

### 11.1 The truth model — "bytecode is the skeleton, sources are the flesh"

| Fact | Authority |
|---|---|
| Which members exist | **bytecode** (includes compiler-generated members) |
| Modifiers, erased descriptors, generic signatures | **bytecode** |
| Supertypes, interfaces, annotations | **bytecode** |
| Parameter *names* | sources → else `MethodParameters` attr → else `LocalVariableTable` → else `arg0` |
| Method bodies | sources → else decompiled |
| Javadoc / KDoc | **sources only** (jars strip it) |
| Inline comments | **sources only** |

This ordering matters: a sources jar can be **stale or mismatched** (a real and common
packaging bug). By taking structure from the binary, `jdx` cannot be lied to about the API.
When a member in the binary has no counterpart in the sources — or vice versa — it emits:

```
⚠ SOURCES_VERSION_MISMATCH: gson-2.14.0-sources.jar declares Gson#toJson(Object, Writer)
  which is absent from gson-2.14.0.jar. Structure shown from bytecode.
```

Synthetic members present only in bytecode (bridges, `access$000`, lambda bodies,
`$VALUES`) are hidden by default rather than reported as a mismatch.

### 11.2 Decompilation

**Vineflower** (Apache-2.0, the actively maintained Fernflower fork; already present in the
local Gradle cache since Fabric Loom uses it) is bundled and is the default engine.

- Decompiles **one class at a time** with the workspace as the classpath context, so generics
  and inherited-method resolution come out correct.
- Decompiled output is **cached** on disk keyed by `(class hash, engine, engine version)` —
  decompiling a large class costs 50–500 ms, and agents ask for several members of the same
  class in a row.
- Loaded in an **isolated, lazily-created classloader**, so the common path (no decompilation
  needed) never pays for it.
- Method extraction from decompiled text reuses the JavaParser path: the decompiled class is
  parsed, the requested member's node range is taken. This keeps `jdx body` output identical
  in shape whether it came from real sources or a decompiler.

**javap** (`--engine javap`) shells out to the system `javap` (resolved from `JAVA_HOME`, the
running JVM's home, then `PATH`) for cases where the agent wants exact opcodes: verifying a
compiler optimisation, reading a `switch` bootstrap, confirming a constant value. Output is
passed through with minimal reformatting.

**Always labelled.** Decompiled output is never presented as if it were the original source.

---

## 12. Kotlin support

Kotlin's JVM projection is *misleading*: `String?` and `String` are the same JVM type,
`suspend fun` gains a hidden `Continuation` parameter and returns `Object`, properties become
`getX`/`setX` pairs, default arguments become `foo$default` synthetics, and `data class`
generates `componentN`/`copy`. An agent reading the JVM view of a Kotlin library **will write
wrong code**.

### 12.1 Binaries — `@Metadata`
Decoded with `kotlin-metadata-jvm`. `jdx` renders true Kotlin declarations:

```
class kotlinx.coroutines.flow.FlowKt
  public suspend fun <T> Flow<T>.collect(collector: FlowCollector<T>): Unit
  public fun <T> flowOf(vararg elements: T): Flow<T>
  public val <T> Flow<T>.replayCache: List<T>
```
instead of the JVM view (`Object collect(Flow, FlowCollector, Continuation)`).

Specifically recovered: nullability, variance, `suspend`, `inline`/`reified`, extension
receivers, properties (with backing-field/accessor detail), default arguments, `data`/`sealed`
/`value`/`object`/`companion`, type aliases, and `@JvmName` mappings.

`--view jvm` forces the Java projection when the agent genuinely needs the JVM truth (e.g.
calling Kotlin from Java, or reading a stack trace).

### 12.2 Sources — full PSI parsing
Per the user's decision, `.kt` files are parsed with **`kotlin-compiler-embeddable`** into a
real PSI tree, rather than by lightweight brace matching. This buys exactness on the hard
cases (nested string templates containing braces, multi-line raw strings, trailing lambdas,
`when` blocks, expression-bodied functions, nested local functions) and enables accurate
Kotlin-aware source-level usages.

**Cost and mitigation.** `kotlin-compiler-embeddable` is ~55 MB and takes ~0.6–1.0 s to
initialise. Therefore it is:
- behind the `sources` module's `SourceParser` interface;
- **loaded lazily**, only when a `.kt` file must actually be parsed;
- loaded in an **isolated `URLClassLoader`** so its shaded Guava/Intellij-platform classes
  cannot collide with anything else;
- initialised **once per daemon lifetime** — with the daemon running (the normal case for an
  agent doing repeated queries), the cost is paid once and then never again;
- shipped in a **separate jar fetched/verified on first use**, not baked into the main fat
  jar, so a user who never inspects Kotlin sources never downloads it. (`jdx doctor` reports
  whether the Kotlin source module is installed.)

Java sources use **JavaParser**, which is small and fast and needs none of this.

---

## 13. Classpath resolution

Resolution order when a command runs. First match wins; `--jars`/`--src` always merge in.

1. **Explicit flags**: `--jars <path|glob|dir>`, `--src <dir>`, `--coord <g:a:v>`, `--sources`.
2. **`-w <name>`** — a named workspace.
3. **`JDX_WORKSPACE` env var**, then the workspace set by `jdx ws use`.
4. **Project auto-discovery** — walk up from CWD looking for `settings.gradle(.kts)`,
   `build.gradle(.kts)`, `pom.xml`, or `.idea/`. On a hit, assemble a workspace from:
   - the project's own `src/main/{java,kotlin}` and `build/classes/**`;
   - dependency jars found in `~/.gradle/caches/modules-2/files-2.1/**` and
     `~/.m2/repository/**` **that the project's lock/metadata files actually reference**,
     falling back to a broader cache scan with a warning if the reference set can't be
     determined (we do not run Gradle — see N3);
   - the JDK.
   The derived workspace is cached under `~/.cache/jdx/auto/<project-hash>.toml` and
   invalidated when the build files change.
5. **JDK stdlib** via `jrt-fs` on the running JDK — always appended unless `--no-jdk`, with
   `$JAVA_HOME/lib/src.zip` paired as its sources root, so `jdx members java.util.HashMap`
   works with zero configuration, with real JDK source and javadoc.

**Maven coordinates** (`--coord g:a:v`, or a `g:a:v/` ref prefix) are resolved against
`~/.gradle/caches` and `~/.m2` first; if absent, fetched from the configured
repositories (`--repo` mirrors in flag order, Maven Central last) into
`~/.cache/jdx/m2/`, **including the `-sources.jar`**, with checksum
verification. This lets an agent evaluate a library the project does not yet depend on.
Network fetching is opt-in per invocation via `--fetch` (or `fetch = true` in config) so the
tool never surprises anyone with network traffic.

---

## 14. Interfaces: CLI, MCP, daemon, HTTP

All four are thin adapters over `JdxService`. They share one command/flag metadata table, so
adding a command makes it appear in all four automatically.

### 14.1 CLI (always available)
Clikt-based. One-shot. Transparently uses the daemon if one is running (and `--no-daemon`
forces in-process). This is the baseline every agent can use with zero setup.

### 14.2 MCP server — `jdx mcp`
JSON-RPC over stdio, exposing each command as a typed MCP tool with a generated JSON schema.
Why it matters: the agent gets structured arguments and tool descriptions instead of guessing
shell syntax, cannot make quoting mistakes with `#` and `(`, and receives structured content
back. For the stated goal — *an IDE for agents* — this is the highest-leverage interface.

Tools exposed (v1): `jdx_show`, `jdx_members`, `jdx_signature`, `jdx_doc`, `jdx_body`,
`jdx_source`, `jdx_outline`, `jdx_search`, `jdx_resolve`, `jdx_usages`, `jdx_hierarchy`,
`jdx_callers`, `jdx_calls`, `jdx_samples`, `jdx_workspace`.

The MCP server holds the index in memory for its lifetime — so it is effectively a
per-session daemon, and repeated queries are instant.

### 14.3 Daemon — `jdx daemon`
A background JVM holding a hot index (and, once touched, a warm Kotlin PSI environment),
listening on a **unix domain socket** at `$XDG_RUNTIME_DIR/jdx/<workspace-hash>.sock`.

- The CLI auto-spawns it on first use and becomes a thin client: per-query latency drops from
  ~250 ms to ~10–20 ms.
- **Idle shutdown after 5 minutes** of no requests (user-specified; `--idle <duration>` to
  change, `0` to disable). Simple `ScheduledExecutorService` resetting on each request.
- Version-stamped socket path, so an upgraded `jdx` never talks to a stale daemon.
- `jdx daemon status` reports uptime, workspace, memory, indexed artifacts, query count.

### 14.4 HTTP/JSON — `jdx serve`
Built on the JDK's own `com.sun.net.httpserver` (zero dependencies), bound to `127.0.0.1` by
default. `GET /v1/members?type=…&inherited=true`, `POST /v1/batch`, `GET /v1/health`, etc.
Serves a different audience from MCP — non-MCP agents, editor plugins, scripts, notebooks,
and remote use — and shares the exact JSON envelope of `--json`. Separate port/socket from
the daemon; the two do not interfere.

### 14.5 The shared wire contract (v1)
All four adapters speak one request format, so a payload is written and parsed in exactly one
place: `core/.../rpc/RpcProtocol.kt` is normative, this section is the summary.

A **request** is one line of JSON, newline-terminated (`\n`). Keys are in fixed order and
`params` is sorted by key, so equal requests are byte-equal:

```json
{"jdx":1,"command":"members","query":"com.google.gson.Gson","params":{"inherited":"true"}}
```

- `command` is one of the read-only queries — `show` `members` `outline` `body` `source`
  `signature` `doc` `search` `resolve` `ls` `tree` `usages` `hierarchy` `implementors`
  `callers` `calls` `samples` — plus `version`, `doctor` and `health`. The state-changing
  commands (`ws`, `cache`) are deliberately **not** on the wire in v1: one client of a shared
  daemon must not be able to reconfigure the others.
- `params` values are text, the same spelling the CLI flags use. Typed clients (MCP schemas,
  HTTP query strings) may send JSON numbers and booleans; the decoder canonicalises them.
- Decoding is generous where it is safe — surrounding whitespace, a trailing `\r\n`, absent
  `jdx`/`query`/`params`, and unknown keys (so a later version may add fields) are all
  accepted — and strict where a guess would be dangerous: an unknown command, a version it
  does not speak, a wrongly-typed field, a truncated line or trailing content are refused
  outright rather than half-understood. Decoding never throws, on any input.

A **response** is not a new shape: it is the `--json` envelope of §8.2 verbatim, one per line.
That is what makes the adapter-parity guarantee (`docs/TESTING.md` §9) structural rather than
a per-adapter pile of goldens.

---

## 15. Performance plan

| Path | Target | Technique |
|---|---|---|
| Cold CLI, simple query | ≤ 250 ms | AppCDS archive; lazy module loading; no eager index scan |
| Warm (daemon/MCP) query | ≤ 20 ms | hot index, prepared statements, in-memory LRU |
| First index of a 20k-class jar | ≤ 8 s | virtual-thread fan-out, ASM `SKIP_FRAMES`, batched inserts |
| Re-run on unchanged workspace | ~0 ms | content-hash short-circuit |
| `usages` over 20k classes | ≤ 150 ms | indexed `ref.to_fqn` / `ref.to_member` |
| Decompile a large class | ≤ 500 ms first, ~0 cached | on-disk decompilation cache |

Additional measures:
- **Lazy source indexing** — never parse sources until a source-level query arrives.
- **Lazy classloaders** for Vineflower and Kotlin PSI.
- **`-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:auto`** in the launcher for the
  one-shot path (classic CLI JVM tuning; irrelevant and disabled for the daemon).
- A `jdx bench` command, tagged out of normal test runs, measuring against the local
  `minecraft-client.jar` so regressions are caught.

---

## 16. Error handling, exit codes, degradation

### Exit codes
`0` ok · `1` not found · `2` ambiguous · `3` usage error · `4` no index/workspace ·
`5` artifact read error · `6` internal error.

### Degradation ladder
Never fail where a lesser answer exists — but **always label the fallback**:

```
sources jar → decompiled (vineflower) → javap → signatures-only-from-bytecode → not found
Kotlin metadata → JVM projection
param names from source → MethodParameters → LocalVariableTable → arg0, arg1
javadoc on member → inherited javadoc from supertype → none
```

### "Did you mean"
On a miss, `jdx` suggests candidates by camel-hump match, then Levenshtein distance, then
simple-name match in other packages — capped at 5, each printed as a canonical ref:

```
not found: com.google.gson.JsonParse
did you mean:
  com.google.gson.JsonParser          gson-2.14.0.jar
  com.google.gson.JsonParseException  gson-2.14.0.jar
```

### Warnings, never silent
Duplicate FQNs across artifacts (shading!), sources/binary mismatch, corrupt class files,
unsupported class-file major versions, multi-release jar variants, unresolvable supertypes
(a hierarchy edge the workspace cannot provide — inherited members may be incomplete),
project-discovery fallback (auto-discovery found the project but not its dependency set —
dependency jars omitted, `PROJECT_DISCOVERY_FALLBACK`), unnameable class names (a class file
whose own name carries an empty `$`-separated segment — JFR `Exception$JB$$Assertion` shapes —
skipped with `UNNAMEABLE_CLASS`, never `CORRUPT_CLASS`), sealed/hidden classes —
all surface as named warning codes in both text and JSON.

---

## 17. Security and safety

- **Strictly read-only** with respect to user code. `jdx` writes only inside its own cache
  (`~/.cache/jdx`) and config (`~/.config/jdx`) directories.
- **No code from an inspected jar is ever executed.** ASM parses; it does not load. Class
  initialisers never run. This is a meaningful property — agents inspect untrusted artifacts.
- **Zip-slip / zip-bomb hardened**: entry-name normalisation, path traversal rejection,
  decompressed-size caps, entry-count caps.
- **Network is opt-in** (`--fetch`), localhost-bound by default (`jdx serve`), with checksum
  verification on downloaded artifacts.
- **Resource limits**: decompilation and source parsing run under a wall-clock timeout, so a
  pathological class cannot hang an agent's tool call.
- **No telemetry.**

---

## 18. Testing strategy

> **Full strategy: [`docs/TESTING.md`](TESTING.md).** Summarised here; that document is
> authoritative and is required reading before writing tests.

This is a project whose worst bugs are *"the real world contains a jar shaped like **that**"*
bugs — not logic errors in code we wrote. Tests written from imagination cannot find those.
So TDD is the floor, not the ceiling: it is **mandatory for `core`** (which is dependency-free
and has no excuse for being hard to test), and it is paired with five families of test that
**generate their own cases** instead of relying on anyone to enumerate them.

| Family | What it catches |
|---|---|
| **Unit (TDD)** | logic errors in rules we defined — `core`, test-first, no exceptions |
| **Property-based** | edge cases nobody imagined (ref round-trips, descriptor parsing, resolver invariants, truncation) |
| **Differential / oracle** | wrong bytecode interpretation — member sets and descriptors diffed against `javap -p -s` for every fixture and a sampled slice of the real corpus |
| **Golden / approval** | accidental output changes in every renderer, text and JSON |
| **Metamorphic** | inconsistency between related answers (`hierarchy --up` ⟺ `hierarchy --down`, `callers` ⟺ `calls`, `--inherited` ⊇ `--declared`, run-twice determinism) |
| **Fault injection** | crashes on malformed input — truncated classes, zip-slip, zip bombs, mismatched sources jars, `-g:none`, decompiler timeouts |
| **Corpus soak** | real-world shapes we never imagined — every command over the ~2,183 local jars, asserting *invariants* (never crash, always valid JSON, always deterministic) rather than values |
| **Parity / contract** | front-end drift — CLI `--json`, HTTP and MCP must return byte-identical payloads |
| **Performance regression** | silent slowdowns against the §15 budgets |
| **Mutation (Pitest)** | tests that run code without asserting anything — `core` gated at ≥ 80 % mutation score |

Two oracles do most of the work and cost almost nothing: **`javap`**, which tells us the
ground truth for any class file without us writing it down, and **compile-back testing** for
decompilation (decompiled source must recompile to the same API surface — a far more useful
assertion than pinning decompiler text that changes every release).

Tests run in four tiers so the inner loop stays fast: `test` (< 30 s, the TDD loop),
`check` (< 3 min, pre-commit), `soak` (corpus, tagged off by default), and
`bench`/`mutationTest` (on demand). A tier-1 suite that creeps past 30 s kills TDD, and then
it kills testing — that budget is enforced.

## 19. Dependencies and licensing

| Dependency | Purpose | License |
|---|---|---|
| Kotlin stdlib | language | Apache-2.0 |
| ASM | bytecode reading | BSD-3-Clause |
| kotlin-metadata-jvm | `@Metadata` decoding | Apache-2.0 |
| JavaParser | `.java` parsing | Apache-2.0 (dual w/ LGPL — we take Apache-2.0) |
| kotlin-compiler-embeddable | `.kt` PSI parsing (lazy, side-loaded) | Apache-2.0 |
| Vineflower | decompilation | Apache-2.0 |
| sqlite-jdbc (Xerial) | index storage | Apache-2.0 |
| Clikt | CLI parsing | Apache-2.0 |
| kotlinx-serialization-json | JSON envelope | Apache-2.0 |
| MCP Kotlin SDK | MCP transport *(fallback: ~300 LoC hand-rolled JSON-RPC)* | MIT |
| JDK `com.sun.net.httpserver` | HTTP server | — (JDK builtin) |
| JUnit 5 + kotlin-test | tests | EPL-2.0 / Apache-2.0 |

All permissive; all compatible with shipping `jdx` itself under **Apache-2.0**.
Bundled third-party notices go in `NOTICE`.

---

## 20. Delivery plan / milestones

Each milestone ends with a working, committed, demoable binary.

| # | Milestone | Contents | Demo gate |
|---|---|---|---|
| **M0** | Skeleton | Gradle multi-project, version catalog, fat jar, `jdx` launcher, `jdx version`, `jdx doctor`, CI-less local test task | `./jdx doctor` prints a clean environment report |
| **M1** | Read path | Artifact loading + sources pairing, ASM class model, symbol-ref parser/printer, `show`, `outline`, `members --inherited` with generic substitution, text + JSON renderers, JDK jrt root | `jdx members java.util.HashMap --inherited` matches IntelliJ's completion list |
| **M2** | Index | SQLite store, content-hash keying, parallel indexer, `search` (glob/regex/camel-hump), `ls`/`tree`, workspaces, project auto-discovery, `cache` cmds | gson + JDK indexed; `jdx search 'H*Map'` instant |
| **M3** | Bodies | Sources jars, JavaParser, srcmap, `body`, `source`, `signature`, `doc` (incl. inherited doc), Vineflower engine + cache, `javap` engine | `jdx body 'com.google.gson.Gson#toJson(Object)'` from sources **and** from a sources-less jar |
| **M4** | Graph | Reference-edge extraction, `usages`, `hierarchy`, `implementors`, `callers`, `calls --depth`, `samples`, project source-dir usages | `jdx usages` on a Minecraft symbol under 150 ms |
| **M5** | Kotlin | `@Metadata` rendering, Kotlin member mapping, `--view jvm`, side-loaded Kotlin PSI source parsing | `jdx members` on a coroutines class shows `suspend fun` and nullability |
| **M6** | Serving | Daemon + unix socket + 5-min idle shutdown, transparent CLI client, MCP server, HTTP server, `batch` | Same query answered identically via CLI, MCP, and HTTP |
| **M7** | Polish | Token budgets, did-you-mean, warnings, AppCDS, `help --agent`, README, install script, `bench` | Cold query ≤ 250 ms; docs complete |

**Phase-2 backlog** is §21. `docs/PROGRESS.md` tracks live status against this table.

---

## 21. Deferred to phase 2

Explicitly out of v1 scope, recorded so they are not lost:

- **`jdx diff a.jar b.jar`** — public-API diff with binary-compatibility warnings, for
  dependency upgrades. *(User deferred this in the v1-extras decision.)*
- **Mappings / remapping** — Minecraft-style obfuscated jars: read Tiny/SRG/ProGuard mapping
  files so `jdx` can answer in either namespace. High value on this particular machine.
- **Resources & metadata inspection** — `META-INF/services`, `module-info`, manifest
  attributes, SPI providers, resource listing and extraction.
- **Annotation-driven views** — "show me every `@Deprecated(forRemoval=true)` in this
  dependency", "all Spring `@Bean` factories", "all JUnit `@Test`s".
- **`--since` / API-level reporting** — which JDK release introduced a member.
- **Dataflow-lite** — `jdx flow <method>` for a simplified reaching-values view.
- **Multi-release jar variant selection** beyond warning.
- **Scala/Groovy language views.**
- **Publishing** — Homebrew/AUR packaging, GitHub Releases with a native-image build.

---

## 22. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| `kotlin-compiler-embeddable` bloat/startup (~55 MB, ~1 s) | Slow cold path, big install | Side-loaded jar, isolated lazy classloader, warm in daemon, `doctor` reports it. Fallback lexer extractor kept behind a flag. |
| Decompiler output is wrong or unreadable on hard methods | Agent reasons from bad code | Always labelled as reconstructed; `--engine javap` escape hatch; phase-2 second engine |
| Index staleness on rapidly-changing project dirs | Wrong answers | Content hashing for jars, per-file mtime for dirs, `--force`, hash in `doctor` |
| Global SQLite DB grows unbounded | Disk usage | `cache gc`, per-artifact eviction by last-use, `cache info` |
| Shaded/duplicate FQNs across a 2000-jar classpath | Silently wrong class shown | Classpath-order shadowing + explicit duplicate warning with all providers listed |
| Scope creep (this document is large) | Never ships | Strict milestone gating; M1–M3 alone already deliver the headline value |
| Output format churn breaking agent prompts | Agents mis-parse | `jdx: 1` envelope version; golden tests; changes to text layout treated as breaking |

---

## Appendix A: worked agent session

The scenario from the original brief — *"I want the body of `org.some.lib.AClass.someMethod(int,int)`"* — plus the investigation around it.

```console
$ jdx ws create app --auto            # discovers the Gradle project + its cached jars + JDK
workspace 'app' created · 214 artifacts · 18,443 classes · indexed in 4.1s

$ jdx search 'JsonAdapter' --kind class
class  com.google.gson.annotations.JsonAdapter    gson-2.14.0.jar   annotation
class  com.google.gson.internal.bind.JsonAdapterAnnotationTypeAdapterFactory  gson-2.14.0.jar
2 results

$ jdx members com.google.gson.Gson --inherited --grep 'json'
methods on com.google.gson.Gson
  public <T> T fromJson(String, Class<T>) throws JsonSyntaxException
  public <T> T fromJson(String, Type) throws JsonSyntaxException
  public <T> T fromJson(JsonElement, Class<T>) throws JsonSyntaxException
  public String toJson(Object)
  public String toJson(Object, Type)
  public void toJson(Object, Appendable) throws JsonIOException
  public JsonElement toJsonTree(Object)
+ 9 inherited from java.lang.Object (--from java.lang.Object)
7 of 39 methods shown (filtered by --grep 'json')

$ jdx body 'com.google.gson.Gson#toJson(Object)'
com.google.gson.Gson#toJson(java.lang.Object)
  source: gson-2.14.0-sources.jar · com/google/gson/Gson.java:722-728
  public String toJson(Object src) {
    if (src == null) {
      return toJson(JsonNull.INSTANCE);
    }
    return toJson(src, src.getClass());
  }
next: jdx body 'com.google.gson.Gson#toJson(java.lang.Object, java.lang.reflect.Type)'

$ jdx usages 'com.google.gson.Gson#toJson(Object)' --limit 5
5 of 23 usages
app/src/main/java (2)
  com.example.api.UserController#serialize(User)           UserController.java:44   call
  com.example.api.ErrorMapper#toBody(Throwable)            ErrorMapper.java:19      call
gson-2.14.0.jar (3)
  com.google.gson.Gson#toJson(Object, Appendable)          call
  com.google.gson.internal.Streams#write(JsonElement, …)   call
  …

$ jdx hierarchy com.google.gson.TypeAdapter --down --in 'app/**'
com.google.gson.TypeAdapter<T>
  ↓ subclasses in app/**
    com.example.json.InstantAdapter      extends TypeAdapter<java.time.Instant>
    com.example.json.MoneyAdapter        extends TypeAdapter<com.example.Money>

$ jdx samples 'com.google.gson.TypeAdapter#write' --limit 1
example from app/src/main/java · com.example.json.InstantAdapter#write:21-24
  @Override public void write(JsonWriter out, Instant value) throws IOException {
    if (value == null) { out.nullValue(); return; }
    out.value(DateTimeFormatter.ISO_INSTANT.format(value));
  }

$ jdx body 'net.minecraft.world.item.ItemStack#getMaxStackSize()'     # no sources jar
net.minecraft.world.item.ItemStack#getMaxStackSize()
  source: decompiled by vineflower 1.11 from minecraft-client.jar   ⚠ reconstructed
  public int getMaxStackSize() {
    return this.getOrDefault(DataComponents.MAX_STACK_SIZE, 1);
  }
next: jdx body '…#getMaxStackSize()' --engine javap   (exact bytecode)
```

Seven questions answered, no file dumped, nothing grepped, total output a few hundred tokens.
The equivalent investigation with `unzip`/`javap`/`grep` is roughly 20 commands and tens of
thousands of tokens.

---

## Appendix B: full flag reference

*(Maintained as commands land; `jdx help --agent` is generated from the same metadata and is
the authoritative machine-readable version.)*

### Global
```
-w, --workspace <name>      use a named workspace
    --jars <path|glob|dir>  add binary roots (repeatable)
    --src <dir>             add source roots (repeatable)
    --coord <g:a:v>         add a Maven coordinate root (repeatable)
    --repo <url>            add a Maven repository base URL for --coord fetches (repeatable, T-069)
    --sources <path>        explicitly pair a sources jar
    --jdk / --no-jdk        include the JDK stdlib (default: include)
    --fetch                 allow network fetches from Maven repositories (Central last by default)
    --json                  structured output
    --no-color              disable ANSI even on a TTY
    --max-lines <n>         bound output size
    --brief                 minimal output (signatures only, no doc)
-v, --verbose / -q, --quiet
    --no-daemon             force in-process execution
    --timeout <duration>    per-operation wall-clock cap
    --cache-dir <path>      override ~/.cache/jdx
```

### Per-command highlights
```
show      <type> [-w …] [--jars …] [--no-jdk] [--json] [--no-color]
outline   <type> [--kind --static/--instance --access --from --grep
          --include-synthetic --limit --brief --max-lines --view kotlin|jvm] [-w …] [--jars …] [--no-jdk] [--json] [--no-color]
members   --inherited/--declared --kind --static --instance --access --from
          --grep --with-doc --brief --max-lines --sort --view kotlin|jvm --include-synthetic
          [-w …] [--jars …] [--no-jdk] [--json] [--no-color]
body      --engine vineflower|javap --with-doc --with-signature --context N
          --line-numbers --max-lines
source    --lines A:B --around <ref> --context N --line-numbers --max-lines
          --engine vineflower|javap
signature <member> [--include-synthetic --limit --view kotlin|jvm]
doc       --inherited/--no-inherited --raw --max-lines
search    --kind --regex --fuzzy --in --package --limit
resolve   <name> [--limit]
ls        [package-glob] [--limit]
tree      [artifact-glob] [--depth --counts --limit]
usages    --kind call|ref|impl|override|read|write|new|throw|annotation|all
          --in --exclude --limit --context --src
          [-w …] [--jars …] [--no-jdk] [--json] [--no-color]
hierarchy --up --down --direct --depth N --in --exclude --limit
          [-w …] [--jars …] [--no-jdk] [--json] [--no-color]
implementors [--direct --depth N --in --exclude --limit]
          [-w …] [--jars …] [--no-jdk] [--json] [--no-color]
callers   --depth N --in --exclude --limit
          [-w …] [--jars …] [--no-jdk] [--json] [--no-color]
calls     --depth N --in --exclude --external-only --limit
          [-w …] [--jars …] [--no-jdk] [--json] [--no-color]
samples   --limit N --prefer-sources
          [-w …] [--jars …] [--no-jdk] [--json] [--no-color]
ws        create|list|info|remove|add|use   --auto --jars --src --coord --repo --jdk
index     --force -w
cache     info|gc|clear [--cache-dir --json]  (gc: --dry-run)
daemon    start|stop|status|restart --idle <duration>
serve     [-w …] --port --bind
mcp       [-w …] (stdio; per-call workspace override)
batch     --json
help      [--agent] [--json]
```
