# API diff for upgrades

Before bumping a dependency, ask what the new version breaks. `jdx diff` compares the public API of two artifacts — jars, class directories or Maven coordinates — and names every difference as a rule with a severity, with a gate for CI.

## Compare two versions

```console exec max-lines=20
$ jdx diff com.google.code.gson:gson:2.11.0 com.google.code.gson:gson:2.14.0 --fetch
```

Each side can be a jar file, a class directory, a glob or a `group:artifact:version`
coordinate (with `--fetch` to download it). `diff` reads bytecode only — no sources, no
decompilation, no workspace.

## Rules and severities

Every finding names exactly one rule, so tools can filter on the rule rather than the prose:

- **breaking** — an already-compiled caller can fail to link or run: `MEMBER_REMOVED`,
  `RETURN_TYPE_CHANGED`, `PARAMETER_TYPE_CHANGED`, `MEMBER_VISIBILITY_NARROWED`,
  `STATIC_TO_INSTANCE`, `INTERFACE_METHOD_ADDED`, `TYPE_REMOVED`, …
- **suspicious** — binary-compatible by the letter of the JLS, but recompiling, the Kotlin
  view or behaviour can still break: `CHECKED_EXCEPTION_ADDED`, `GENERIC_SIGNATURE_CHANGED`,
  `MEMBER_MOVED_TO_SUPERTYPE`, `KOTLIN_NULLABILITY_CHANGED`, `KOTLIN_SUSPEND_CHANGED`, …
- **info** — additive or cosmetic: `MEMBER_ADDED`, `TYPE_ADDED`, `DEPRECATED_ADDED`,
  `FIELD_CONSTANT_VALUE_CHANGED`, …

A removed member paired with a matching added one is reported once as a retyped signature, not
as a removal plus an addition — and an ambiguous pairing is never guessed.

The full rule set is in the [design specification](../PROPOSAL.md#77-public-api-diff).

Useful flags:

| Flag | Effect |
|---|---|
| `--severity breaking\|suspicious\|all` | how much to print (the tally always covers everything) |
| `--visibility public\|all` | public + protected surface (default), or every declared member |
| `--include-synthetic` | also compare bridge and synthetic members |
| `--limit N` | cap the findings shown (default 200; the cut tail is the least severe) |

## Gate a pull request

`--fail-on breaking` (or `--fail-on any`) turns the report into an exit status: `1` when the
gate trips, `0` otherwise. This is the one documented exception to the
[exit-code table](output.md#exit-codes): `diff` has no "not found" outcome, so `1` means
"the gate tripped". The verdict is also in the JSON envelope as `result.gate`.

```console exec max-lines=6
$ jdx diff com.google.code.gson:gson:2.11.0 com.google.code.gson:gson:2.14.0 --severity breaking --fail-on breaking
```

In GitHub Actions:

```yaml
- name: Public API must not break
  run: jdx diff "com.example:mylib:$(git describe --tags --abbrev=0 | tr -d v)" build/libs/mylib.jar --fetch --fail-on breaking
```
