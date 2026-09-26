# AGENTS.md — `app/`

Fat-jar assembly + the `jdx` POSIX launcher + AppCDS archive.

## Key files

- `src/main/scripts/jdx` — resolves the JDK (`JAVA_HOME` → `java` on `PATH` →
  `/usr/libexec/java_home` on macOS → `/usr/lib/jvm/default` or `$JDX_JVM_DEFAULT_DIR`; one human-readable line + exit 6 when unusable, never
  assumes `JAVA_HOME`), applies one-shot flags
  (`-TieredStopAtLevel=1`, serial GC, `-Xshare:auto`), passes
  `-XX:SharedArchiveFile=<fixed jdx.jsa path>` only when the archive exists next
  to the fat jar (missing/stale degrades to a plain run, never fatal).
  `$JDX_JAVA_HOME_HELPER` overrides the macOS helper path (test seam).
  Installer scripts use no `--` anywhere: BSD mkdir/cp/chmod reject it.
- `build.gradle.kts` — `generateCdsClassList` + `createCdsArchive` (incremental,
  config-cache safe: plain-`File` captures only), wired into `installDist`.

## Rules

- Train AppCDS from a **single** run with the broadest class set — never merge
  `DumpLoadedClassList` outputs (lambda-proxy `id:` suffixes are run-specific;
  merged lists break `-Xshare:dump` with `Duplicated ID`).
- `install.sh` (repo root) stays per-user: refuses root, refuses to clobber an
  unrelated `jdx` without `--force`.
- Releases: pushing tag `vX.Y.Z` on `main` runs `.github/workflows/release.yml`
  (version via `-PjdxVersion`, tarball `jdx-<version>.tar.gz` with the launcher +
  `libs/` layout this module builds, checksums, `gh release create`). The remote
  installer `install-release.sh` (repo root) follows the same per-user rules as
  `install.sh` and is pinned by `InstallReleaseScriptTest` (fake `--tarball`).
- Launcher behaviour is pinned by present/absent exec-args tests — flag order
  (`-Xshare:auto` before `-XX:SharedArchiveFile`) is what makes stale archives
  degrade instead of fail.
