# install.ps1 — shim the built Windows `jdx.bat` onto the user PATH (issue #92).
#
# Windows twin of install.sh. Per-user only: never requires Administrator (an
# elevated shell still lands under USERPROFILE\.local\bin, with a note).
# Refuses to overwrite an existing, unrelated `jdx.cmd` without -Force —
# clobbering another tool silently is the one unforgivable sin of an installer.
#
# Usage: ./install.ps1 [-Force] [-BinDir DIR]
#
# Env overrides: JDX_BIN_DIR (shim directory; default %USERPROFILE%\.local\bin),
# JDX_SKIP_USER_PATH=1 (skip the persistent user-PATH mutation — tests / CI).
#
# Creates a small `jdx.cmd` forwarder (no symlink — Windows Developer Mode is
# not assumed) pointing at `app\build\jdx.bat` from a `:app:installDist` build.

[CmdletBinding()]
param(
    [string]$BinDir = $(if ($env:JDX_BIN_DIR) { $env:JDX_BIN_DIR } else { $null }),
    [switch]$Force,
    [switch]$Help
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Windows PowerShell 5.1 has no $IsWindows (that is PowerShell 7+); detect both.
$script:JdxIsWindows = if (Test-Path Variable:IsWindows) {
    [bool]$IsWindows
} else {
    $env:OS -like '*Windows*'
}

function Write-InstallError([string]$Message) {
    [Console]::Error.WriteLine("install.ps1: $Message")
    exit 1
}

if ($Help) {
    Write-Output 'usage: ./install.ps1 [-Force] [-BinDir DIR]'
    exit 0
}

function Test-IsAdministrator {
    try {
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
        $principal = New-Object Security.Principal.WindowsPrincipal($identity)
        return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    } catch {
        return $false
    }
}

# Warn when elevated, but do not refuse — see install-release.ps1 (same rationale:
# elevated shells are common on Windows, and the default target stays under the
# interactive user's profile).
if ($script:JdxIsWindows -and (Test-IsAdministrator)) {
    [Console]::Error.WriteLine('note: running elevated — still installing per-user into the USERPROFILE\.local\bin target')
}

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$launcher = Join-Path $repoRoot 'app\build\jdx.bat'
if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
    Write-InstallError "no built launcher at '$launcher' — build it first: gradlew.bat :app:installDist"
}
$jarMatches = @(Get-ChildItem -Path (Join-Path $repoRoot 'app\build\libs') -Filter 'jdx-*-all.jar' -File -ErrorAction SilentlyContinue)
if ($jarMatches.Count -eq 0) {
    Write-InstallError "no jdx fat jar in '$(Join-Path $repoRoot 'app\build\libs')' — rebuild with gradlew.bat :app:installDist"
}

# AppCDS archive (T-048): the launcher resolves jar + jdx.jsa relative to its
# own directory, so a missing archive is not fatal — warn only.
$cds = Join-Path $repoRoot 'app\build\libs\jdx.jsa'
if (-not (Test-Path -LiteralPath $cds -PathType Leaf)) {
    [Console]::Error.WriteLine('note: no AppCDS archive at app\build\libs\jdx.jsa — cold start without AppCDS (rebuild with gradlew.bat :app:installDist; see `jdx doctor` appcds row)')
}

if (-not $BinDir) {
    if (-not $env:USERPROFILE -and -not $env:HOME) {
        Write-InstallError 'USERPROFILE/HOME is unset — pass -BinDir or set JDX_BIN_DIR'
    }
    # Not `$home` — that collides with the read-only automatic `$Home` variable
    # (PowerShell is case-insensitive for variable names).
    $userHome = if ($env:USERPROFILE) { $env:USERPROFILE } else { $env:HOME }
    $BinDir = Join-Path $userHome '.local\bin'
}

New-Item -ItemType Directory -Path $BinDir -Force | Out-Null
$target = Join-Path $BinDir 'jdx.cmd'
$launcherFull = [System.IO.Path]::GetFullPath($launcher)

if (Test-Path -LiteralPath $target) {
    if (Test-Path -LiteralPath $target -PathType Container) {
        Write-InstallError "'$target' is a directory — refusing to replace it even with -Force"
    }
    $existing = Get-Content -LiteralPath $target -Raw
    $ours = $existing -match [regex]::Escape($launcherFull)
    if (-not $ours -and -not $Force) {
        Write-InstallError "'$target' already exists and is not this jdx build — rerun with -Force to replace it"
    }
    Remove-Item -LiteralPath $target -Force
}

$shim = @"
@echo off
rem jdx PATH shim — forwards to the source-build launcher (install.ps1).
"$launcherFull" %*
"@
$crlf = $shim -replace "`r`n", "`n" -replace "`n", "`r`n"
[System.IO.File]::WriteAllText($target, $crlf)

function Test-PathContains([string]$Entry) {
    foreach ($part in @($env:Path -split ';')) {
        if ([string]::Equals($part, $Entry, [StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }
    }
    return $false
}

function Add-UserPathEntry([string]$Entry) {
    if ($env:JDX_SKIP_USER_PATH -eq '1') {
        return $false
    }
    if (-not $script:JdxIsWindows) {
        return $false
    }
    $current = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ($null -eq $current) { $current = '' }
    $parts = @($current -split ';' | Where-Object { $_ -ne '' })
    foreach ($part in $parts) {
        if ([string]::Equals($part, $Entry, [StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }
    }
    $updated = if ($current.TrimEnd().EndsWith(';') -or $current -eq '') {
        "$current$Entry"
    } else {
        "$current;$Entry"
    }
    [Environment]::SetEnvironmentVariable('Path', $updated, 'User')
    if (-not (Test-PathContains $Entry)) {
        $env:Path = "$Entry;$env:Path"
    }
    return $true
}

if (-not (Test-PathContains $BinDir)) {
    $added = Add-UserPathEntry $BinDir
    if ($added) {
        Write-Output "note: added '$BinDir' to the user PATH (open a new shell if ``jdx`` is not found)"
    } else {
        [Console]::Error.WriteLine("note: $BinDir is not on your PATH")
    }
}

Write-Output "installed: $target -> $launcherFull"
