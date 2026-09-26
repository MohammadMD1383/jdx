# AGENTS.md — `app/`

Fat-jar assembly + the `jdx` launchers (POSIX + Windows) + AppCDS archive.

## Key files

- `src/main/scripts/jdx` — POSIX launcher: resolves the JDK (`JAVA_HOME` → `java` on `PATH` →
  `/usr/libexec/java_home` on macOS → `/usr/lib/jvm/default` or `$JDX_JVM_DEFAULT_DIR`; one human-readable line + exit 6 when unusable, never
  assumes `JAVA_HOME`), applies one-shot flags
  (`-TieredStopAtLevel=1`, serial GC, `-Xshare:auto`), passes
  `-XX:SharedArchiveFile=<fixed jdx.jsa path>` only when the archive exists next
  to the fat jar (missing/stale degrades to a plain run, never fatal).
  `$JDX_JAVA_HOME_HELPER` overrides the macOS helper path (test seam).
  Installer scripts use no `--` anywhere: BSD mkdir/cp/chmod reject it.
- `src/main/scripts/jdx.bat` (+ `jdx.ps1`) — Windows launchers (#46): same
  contract as the POSIX script (same flags in the same stale-safe order, same
  exit-6 failures, fat jar + `jdx.jsa` resolved relative to the script dir).
  JDK discovery is `%JAVA_HOME%\bin\java.exe` (loud error when set-but-broken,
  never a silent fall-through) → `where java.exe` → registry
  (`JavaSoft` / `Adoptium` `JavaHome` values) → `%ProgramFiles%\Java` (+
  `ProgramFiles(x86)`) probes; minimum Java 21 with legacy `1.x` mapping.
  `jdx.bat` is the default entry point (no execution-policy step); `jdx.ps1`
  is the PowerShell twin. The `.bat` is committed CRLF (`*.bat text eol=crlf`).
- `build.gradle.kts` — `generateCdsClassList` + `createCdsArchive` (incremental,
  config-cache safe: plain-`File` captures only), wired into `installDist`.
  `buildJavaExe` probes `bin/java.exe` first so CDS training runs on a Windows
  host; `installDist` ships all three launchers (`build/jdx[.bat|.ps1]`).

## Rules

- Train AppCDS from a **single** run with the broadest class set — never merge
  `DumpLoadedClassList` outputs (lambda-proxy `id:` suffixes are run-specific;
  merged lists break `-Xshare:dump` with `Duplicated ID`).
- `install.sh` (repo root) stays per-user: refuses root, refuses to clobber an
  unrelated `jdx` without `--force`.
- Releases: pushing tag `vX.Y.Z` on `main` runs `.github/workflows/release.yml`
  as a three-OS matrix (Windows builds via `gradlew.bat`, each leg smokes its
  own launcher), publishing per-OS `jdx-<version>-<os>.{tar.gz,zip}` + `.sha256`
  (plus bare Linux aliases for the `install-release.sh` / `jdx upgrade` default
  URLs) with a per-leg `jdx.jsa` trained on that runner; releases are unsigned
  (SmartScreen/Gatekeeper unblock notes in `docs/PROPOSAL.md` §17.2). The remote
  installer `install-release.sh` (repo root) follows the same per-user rules as
  `install.sh`, accepts `.tar.gz` and `.zip` through `--tarball`, and is pinned
  by `InstallReleaseScriptTest` for both extensions.
- Launcher behaviour is pinned by present/absent exec-args tests — flag order
  (`-Xshare:auto` before `-XX:SharedArchiveFile`) is what makes stale archives
  degrade instead of fail. The Windows launchers carry the same pin
  (`WindowsLauncherScriptTest`): tier-1 pins the `.bat` statically (no cmd.exe
  on a Linux host); tier-2 execs `jdx.ps1` for real under pwsh against a stub
  `bin/java.exe` on any host, and execs the real `jdx.bat` on Windows CI (#52).
