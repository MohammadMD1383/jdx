# AGENTS.md — `core/`

Pure domain module: model, symbol refs, resolution, rendering, RPC codec.
**No IO, no project-local or third-party runtime dependencies** (JSON is hand-rolled
via `JsonEscape` — reuse it, never a second escaper). `explicitApi()` is on.

## Key files

- `model/` — `ClassInfo` (+ Kotlin views), `MemberInfo`, `TypeName`,
  `GenericSignature`, `JvmDescriptor`, `SymbolRef`, `Reference` (edge kinds),
  `Warning` (codes), `Provenance`, `KotlinView`.
- `ref/` — `SymbolRefParser` (generous input) + `SymbolRefPrinter` (canonical output).
- `resolve/MemberResolver` — inheritance + generic substitution; the highest-value
  algorithm here, heavily commented, no cleverness.
- `render/` — text + JSON over one result model (`Listing`, `Signatures`, `Body`,
  `Source`, `Doc`, `Usages`, `Hierarchy`, `Calls`, `Samples`, `Envelope`, `Errors`,
  `TokenBudget`, `Ansi`). `--json` must carry everything text shows.
- `rpc/RpcProtocol` — v1 wire: NDJSON requests, deterministic key order, responses
  **are** the `--json` envelope (no second shape). `decode` never throws.
- Test generators live in `src/test/.../gen/` — reuse across families.

## Grammar rules (`SymbolRefParser`, pinned by property tests)

- `$` always separates nesting; leading lowercase dot-segments are the package, the
  final segment is always the class. `parse(print(ref)) == ref` is a pinned property.
- `.` is a member separator only with a param list or `<init>`/`<clinit>`; fields need
  `#` or `::`. `MemberSymbolRef.parameterTypes`: `null` = all overloads, `[]` = zero
  params (`Gson#toString()` ≠ `Gson#toString`).
- `(...)` means parameters unspecified. Any `*` in a member-less ref is a package glob.

## Gotchas (distilled)

- `GenericSignature.parseClass` for class-file contexts — a lone-superclass signature
  parses as a field under the lenient entry, silently dropping generics (JVMS §4.7.9.1).
  Any `as?` nullable on well-formed input is a silent-drop bug; pin the shape.
- Sorted views must never be positionally zipped with their source — pair in source
  order, then sort the pairs.
- Mutation gate ≥ 80%: redundant validation reads as thoroughness but measures as a
  gap — one authoritative check per input (a second identical guard is unkillable).
- PIT boundary mutator on `c in "chars"` drops the set's *first* char — test it.
- kotest 6 generators build eagerly: recursive `Arb` families must bottom out at
  construction; no single-arity `Arb.bind(x)` (use `x.map`); empty case is
  `Arb.of(listOf(emptyList()))`. No `:` in backtick test names.
