# jdx launcher -- PowerShell (Phase 2a, issue #46, part of #43).
#
# The PowerShell twin of jdx.bat: same JDK discovery, same one-shot flags,
# same exit-6 contract. jdx.bat stays the default entry point (it runs under
# cmd.exe with no execution-policy step); this script is for PowerShell-first
# users: `powershell -ExecutionPolicy Bypass -File jdx.ps1 ...`, or once with
# `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`.
#
# NOTE: keep the one-shot flag set and their order identical to jdx.bat and
# the POSIX launcher -- flag order (-Xshare:auto before
# -XX:SharedArchiveFile) is what makes stale archives degrade instead of
# fail, and it is pinned by WindowsLauncherScriptTest.

$ErrorActionPreference = 'Stop'
$JdxMinJava = 21

function Fail-Jdx([string]$Message) {
  [Console]::Error.WriteLine("jdx: $Message")
  exit 6
}

$JdxHome = Split-Path -Parent $MyInvocation.MyCommand.Path
$JdxJar = Get-ChildItem -Path (Join-Path $JdxHome 'libs\jdx-*-all.jar') -File -ErrorAction SilentlyContinue |
  Select-Object -First 1
if (-not $JdxJar) { Fail-Jdx "no jdx fat jar in '$JdxHome\libs' -- build it first: gradlew.bat :app:installDist" }

$JavaExe = $null

# 1. An explicitly set JAVA_HOME that is broken is a loud error, never a
#    silent fall-through to PATH (mirrors the POSIX launcher, D-026).
if ($env:JAVA_HOME) {
  $JavaHomeExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
  if (-not (Test-Path $JavaHomeExe)) { Fail-Jdx "JAVA_HOME is set to '$($env:JAVA_HOME)' but $JavaHomeExe is not an executable" }
  $JavaExe = $JavaHomeExe
}

# 2. java.exe on PATH.
if (-not $JavaExe) {
  $JavaExe = (Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
}

# 3. Registry: vendor-neutral JavaHome values under the well-known JDK keys.
if (-not $JavaExe) {
  foreach ($Key in 'HKLM:\SOFTWARE\JavaSoft\JDK', 'HKLM:\SOFTWARE\Eclipse Adoptium\JDK') {
    if ($JavaExe) { break }
    Get-ChildItem -Path $Key -ErrorAction SilentlyContinue | ForEach-Object {
      $JdkHome = (Get-ItemProperty -Path $_.PSPath -Name JavaHome -ErrorAction SilentlyContinue).JavaHome
      if (-not $JavaExe -and $JdkHome) {
        $Candidate = Join-Path $JdkHome 'bin\java.exe'
        if (Test-Path $Candidate) { $JavaExe = $Candidate }
      }
    }
  }
}

# 4. %ProgramFiles%\Java probes (Oracle / Temurin default layout).
if (-not $JavaExe) {
  foreach ($Base in @($env:ProgramFiles, ${env:ProgramFiles(x86)}) | Where-Object { $_ }) {
    if ($JavaExe) { break }
    $JavaDir = Join-Path $Base 'Java'
    if (Test-Path $JavaDir) {
      Get-ChildItem -Path $JavaDir -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        $Candidate = Join-Path $_.FullName 'bin\java.exe'
        if (-not $JavaExe -and (Test-Path $Candidate)) { $JavaExe = $Candidate }
      }
    }
  }
}

if (-not $JavaExe) { Fail-Jdx "no Java runtime found -- set JAVA_HOME, add java to PATH, or install a JDK (jdx needs Java $JdxMinJava+)" }

# Version gate: accept exactly major 21 or newer; legacy 1.x maps to its
# second component (1.8.0_452 -> 8).
$VersionOutput = & $JavaExe -version 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { Fail-Jdx "could not run '$JavaExe -version' -- is it a working JDK?" }
$VersionMatch = [regex]::Match($VersionOutput, 'version "([^"]+)"')
if (-not $VersionMatch.Success) {
  $FirstLine = ($VersionOutput -split "`r?`n" | Where-Object { $_ -ne '' } | Select-Object -First 1)
  Fail-Jdx "no version string in output of '$JavaExe -version': $FirstLine"
}
$Ver = $VersionMatch.Groups[1].Value
if ($Ver -match '^1\.') { $Major = ($Ver -split '\.')[1] } else { $Major = ($Ver -split '[.\-_]')[0] }
$MajorNum = 0
if (-not [int]::TryParse($Major, [ref]$MajorNum)) { Fail-Jdx "unrecognised Java version '$Ver' reported by '$JavaExe'" }
if ($MajorNum -lt $JdxMinJava) { Fail-Jdx "jdx requires Java $JdxMinJava+, but '$JavaExe' is version '$Ver' -- set JAVA_HOME to a newer JDK" }

# AppCDS archive: presence check only -- -Xshare:auto below makes a stale
# archive a warning, never a failure, so no version probing here.
$Flags = @('-XX:TieredStopAtLevel=1', '-XX:+UseSerialGC', '-Xshare:auto')
$Cds = Join-Path $JdxHome 'libs\jdx.jsa'
if (Test-Path $Cds) { $Flags += "-XX:SharedArchiveFile=$Cds" }
& $JavaExe @Flags -jar $JdxJar.FullName @args
exit $LASTEXITCODE
