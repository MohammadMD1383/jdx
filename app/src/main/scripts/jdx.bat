@echo off
rem jdx launcher -- Windows batch launcher (Phase 2a, issue #46, part of #43).
rem
rem The cmd.exe twin of the POSIX `jdx` script: the supported entry point for
rem Windows end users. It is intentionally dumb: no config, no colour, no
rem argument parsing -- the JVM owns all of that. Its three jobs:
rem
rem   1. find a Java 21+ runtime:  %JAVA_HOME%  ->  `java.exe` on PATH  ->
rem      registry (JavaSoft / Adoptium JavaHome values)  ->
rem      %ProgramFiles%\Java probes. A broken explicitly-set JAVA_HOME is a
rem      loud error, never a silent fall-through to PATH (mirrors the POSIX
rem      launcher, D-026). JAVA_HOME is never assumed: an unset JAVA_HOME
rem      simply skips to PATH probing.
rem   2. fail with one human-readable line on stderr -- never a stack trace --
rem      if it cannot (missing JDK, JDK too old, missing build). Exit code 6
rem      (D-015/D-026), the same contract as the POSIX launcher.
rem   3. run the fat jar with the one-shot CLI JVM flags (PROPOSAL.md §15):
rem      -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:auto
rem      plus -XX:SharedArchiveFile when the AppCDS archive (jdx.jsa, shipped
rem      by :app:installDist) sits next to the fat jar. A missing or stale
rem      archive degrades to the plain run via -Xshare:auto, never a hard
rem      failure -- so this is a presence check only, no version probing.
rem
rem The fat jar is located relative to this script's own directory (%~dp0
rem keeps a trailing backslash), so the install directory can live anywhere
rem (per-user default %LOCALAPPDATA%\Programs\jdx, Phase 0 decision).
rem
rem NOTE: keep the one-shot flag set and their order identical to the POSIX
rem launcher and jdx.ps1 -- flag order (-Xshare:auto before
rem -XX:SharedArchiveFile) is what makes stale archives degrade instead of
rem fail, and it is pinned by WindowsLauncherScriptTest.

setlocal EnableExtensions

set JDX_MIN_JAVA=21

rem --- locate the fat jar next to this script ---
set JDX_JAR=
for %%J in ("%~dp0libs\jdx-*-all.jar") do if exist "%%J" set "JDX_JAR=%%J"
if not defined JDX_JAR (
  1>&2 echo jdx: no jdx fat jar in '%~dp0libs' -- build it first: gradlew.bat :app:installDist
  exit /b 6
)

set JAVA_EXE=

rem 1. An explicitly set JAVA_HOME that is broken is a loud error, never a
rem    silent fall-through to PATH.
if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
  ) else (
    1>&2 echo jdx: JAVA_HOME is set to '%JAVA_HOME%' but %JAVA_HOME%\bin\java.exe is not an executable
    exit /b 6
  )
)

rem 2. java.exe on PATH.
if not defined JAVA_EXE (
  for /f "delims=" %%J in ('where java.exe 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%J"
)

rem 3. Registry: vendor-neutral JavaHome values under the well-known JDK keys.
if not defined JAVA_EXE (
  for %%K in (
    "HKLM\SOFTWARE\JavaSoft\JDK"
    "HKLM\SOFTWARE\Eclipse Adoptium\JDK"
  ) do (
    for /f "tokens=2*" %%a in ('reg query %%K /s 2^>nul ^| findstr /i JavaHome') do (
      if "%%a"=="REG_SZ" if not defined JAVA_EXE if exist "%%b\bin\java.exe" set "JAVA_EXE=%%b\bin\java.exe"
    )
  )
)

rem 4. %ProgramFiles%\Java probes (Oracle / Temurin default layout).
rem    Single-line IFs: %ProgramFiles(x86)% contains a closing paren, which
rem    would end a parenthesised block early, so no block form here.
if not defined JAVA_EXE for /d %%d in ("%ProgramFiles%\Java\*") do if not defined JAVA_EXE if exist "%%d\bin\java.exe" set "JAVA_EXE=%%d\bin\java.exe"
if not defined JAVA_EXE if defined ProgramFiles(x86) for /d %%d in ("%ProgramFiles(x86)%\Java\*") do if not defined JAVA_EXE if exist "%%d\bin\java.exe" set "JAVA_EXE=%%d\bin\java.exe"

if not defined JAVA_EXE (
  1>&2 echo jdx: no Java runtime found -- set JAVA_HOME, add java to PATH, or install a JDK ^(jdx needs Java %JDX_MIN_JAVA%+^)
  exit /b 6
)

rem --- version gate: accept exactly major 21 or newer. ---
rem tokens=3 on `openjdk version "21.0.8" ...` is the quoted version; a
rem non-zero `java -version` leaves JDX_RAWVER unset (findstr matches nothing).
for /f "tokens=3" %%v in ('"%JAVA_EXE%" -version 2^>^&1 ^| findstr /i version') do if not defined JDX_RAWVER set "JDX_RAWVER=%%v"
if not defined JDX_RAWVER (
  1>&2 echo jdx: could not run '"%JAVA_EXE%" -version' -- is it a working JDK?
  exit /b 6
)
set "JDX_VER=%JDX_RAWVER:"=%"
if "%JDX_RAWVER%"=="%JDX_VER%" (
  1>&2 echo jdx: no version string in output of '"%JAVA_EXE%" -version': %JDX_RAWVER%
  exit /b 6
)
rem Legacy 1.x (1.8.0_452 -> major 8); modern (21.0.8, 22-ea -> up to the
rem first dot, dash, or underscore).
echo %JDX_VER% | findstr /b "1\." >nul 2>&1
if %ERRORLEVEL% equ 0 (
  for /f "tokens=2 delims=." %%m in ("%JDX_VER%") do set "JDX_MAJOR=%%m"
) else (
  for /f "delims=.-_" %%m in ("%JDX_VER%") do set "JDX_MAJOR=%%m"
)
if not defined JDX_MAJOR (
  1>&2 echo jdx: unrecognised Java version '%JDX_VER%' reported by '"%JAVA_EXE%"'
  exit /b 6
)
set JDX_NONNUM=
for /f "delims=0123456789" %%c in ("%JDX_MAJOR%") do set "JDX_NONNUM=%%c"
if defined JDX_NONNUM (
  1>&2 echo jdx: unrecognised Java version '%JDX_VER%' reported by '"%JAVA_EXE%"'
  exit /b 6
)
if %JDX_MAJOR% LSS %JDX_MIN_JAVA% (
  1>&2 echo jdx: jdx requires Java %JDX_MIN_JAVA%+, but '"%JAVA_EXE%"' is version '%JDX_VER%' -- set JAVA_HOME to a newer JDK
  exit /b 6
)

rem --- AppCDS archive: presence check only; -Xshare:auto below makes a stale
rem     archive a warning, never a failure, so no version probing here. ---
set JDX_CDS=
if exist "%~dp0libs\jdx.jsa" set "JDX_CDS=-XX:SharedArchiveFile=%~dp0libs\jdx.jsa"

if defined JDX_CDS (
  "%JAVA_EXE%" -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:auto "%JDX_CDS%" -jar "%JDX_JAR%" %*
) else (
  "%JAVA_EXE%" -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:auto -jar "%JDX_JAR%" %*
)
exit /b %ERRORLEVEL%
