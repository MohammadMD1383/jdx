# AGENTS.md — `app/`

Fat-jar assembly + the `jdx` POSIX launcher + AppCDS archive.

## Key files

- `src/main/scripts/jdx` — resolves the JDK (`JAVA_HOME` → `java` on `PATH` →
  `/usr/lib/jvm/default`; one human-readable line + exit 6 when unusable, never
  assumes `JAVA_HOME`), applies one-shot flags
  (`-TieredStopAtLevel=1`, serial GC, `-Xshare:auto`), passes
  `-XX:SharedArchiveFile=<fixed jdx.jsa path>` only when the archive exists next
  to the fat jar (missing/stale degrades to a plain run, never fatal).
- `build.gradle.kts` — `generateCdsClassList` + `createCdsArchive` (incremental,
  config-cache safe: plain-`File` captures only), wired into `installDist`.

## Rules

- Train AppCDS from a **single** run with the broadest class set — never merge
  `DumpLoadedClassList` outputs (lambda-proxy `id:` suffixes are run-specific;
  merged lists break `-Xshare:dump` with `Duplicated ID`).
- `install.sh` (repo root) stays per-user: refuses root, refuses to clobber an
  unrelated `jdx` without `--force`.
- Launcher behaviour is pinned by present/absent exec-args tests — flag order
  (`-Xshare:auto` before `-XX:SharedArchiveFile`) is what makes stale archives
  degrade instead of fail.
