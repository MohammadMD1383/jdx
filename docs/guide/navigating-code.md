# Usages, hierarchy & calls

Find who uses a symbol, what implements a type, who calls a method and what it calls, and real call sites to learn from — across every jar on the classpath, not just one.

## Find usages: `usages`

One row per referencing method, named canonically so the next command is a copy-paste:

```console exec max-lines=14
$ jdx usages com.google.gson.JsonNull --limit 8 --coord com.google.code.gson:gson:2.14.0
```

Narrow by edge kind with `--kind`:

| `--kind` | Finds |
|---|---|
| `call` | method calls |
| `read` / `write` | field reads and writes |
| `new` | constructor call sites |
| `ref` | any other reference (casts, `instanceof`, class literals, signatures) |
| `throw` | methods declaring the type in `throws` (declaration-level, not `ATHROW` throw sites) |
| `annotation` | classes and members carrying the annotation |

`--in` / `--exclude` filter by artifact (jar file name, JDK module or source directory), and
`--src <dir>` adds your project's source directories — those hits are textual mentions with
line numbers. `--context N` shows a short source window around each call site when paired
sources (or `--src`) are available; `0` (default) is location-only (`File.java:line`).

## Type hierarchy: `hierarchy` and `implementors`

Supertypes upward (the full chain, interfaces included) and subtypes downward across the whole
classpath:

```console exec
$ jdx hierarchy com.google.gson.JsonElement --coord com.google.code.gson:gson:2.14.0
```

`--up` / `--down` pick a direction, `--direct` stops at one level, `--depth N` caps it.
`jdx implementors <type>` is `hierarchy --down`:

```console exec max-lines=10
$ jdx implementors com.google.gson.TypeAdapterFactory --direct --coord com.google.code.gson:gson:2.14.0
```

## Call hierarchy: `callers` and `calls`

```console exec max-lines=12
$ jdx callers 'com.google.gson.JsonParser#parseReader(com.google.gson.stream.JsonReader)' --coord com.google.code.gson:gson:2.14.0
```

`--depth N` follows the graph transitively (cycle-safe, cycles are marked). `jdx calls` goes
the other way; add `--external-only` to see only what a method needs from *other* artifacts —
its dependency footprint.

## Learn from real code: `samples`

The fastest way to learn an API is to read code that already uses it. `samples` ranks real call
sites by how exemplary they are — non-test before test, non-generated before generated, fuller
overloads first — and shows the enclosing method when sources are available. Each snippet is
capped at 15 lines by default (`--max-lines N` to raise it; the truncation marker names the total):

```console exec max-lines=24
$ jdx samples 'com.google.gson.JsonParser#parseString(String)' --coord com.google.code.gson:gson:2.14.0
```

## Limitations

- Graph queries re-scan the bytecode roots on each query (there is no persistent graph index
  yet); the daemon and MCP server keep the scan warm.
- `callers` is override-aware by default (issue #6): querying an interface/base method also
  finds call sites through overrides, and querying an override also finds polymorphic call
  sites through declaring supertypes. Pass `--exact` for invoke-owner matching only.
  `calls` stays bytecode-exact — an invoke instruction names one owner, so callees are not
  expanded to the override set.
- Usages found in source directories are textual (`ref` kind only).
- `usages --kind throw` reads `throws` declarations, not `ATHROW` sites (throw-site attribution needs data-flow analysis and is deferred to future work).

Open limitations are tracked in [GitHub Issues](https://github.com/MohammadMD1383/jdx/issues).
