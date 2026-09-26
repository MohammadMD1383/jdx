# AGENTS.md — `decompile/`

Decompilation engines behind one seam. Vineflower is the default (readable Java);
`javap` is the exact alternate (raw opcodes for genuinely-bytecode questions).
`explicitApi()` is on.

## Key files

- `DecompilerEngine` — the interface; `VineflowerDecompiler` (single-class, workspace
  jars as library context, on-disk cache, wall-clock timeout, lazy isolated loading)
  and `JavapDecompiler` (`javap -c -p -s` over staged class bytes; JDK resolved
  `JAVA_HOME` → `java.home` → `PATH`, mirroring `doctor`, with `javap.exe`/`.cmd`/`.bat`
  probed first on Windows via `toolNames`).
- `DecompileCache` — on-disk cache of reconstructions. `Staging` — materialises the
  winning class bytes for engine input. `JavapOutput` — disassembly shaping.

## Rules

- Serving ladder: paired sources → Vineflower → `javap` → signatures → not found.
  A default-ladder Vineflower failure (timeout/crash/unparseable) auto-retries via
  `javap`; forced `--engine vineflower` stays strict (failure exits 1 naming the
  `--engine javap` hatch). Provenance always names the engine; decompiled output is
  labelled reconstructed.
- Overload ambiguity is decided from bytecode before any decompilation runs
  (bytecode-authoritative). Decompiled text carries no javadoc — `doc` has no
  decompiled path.
