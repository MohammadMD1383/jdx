# install-release.ps1 - install `jdx` from a GitHub Release zip (per-user, Windows).
#
# Windows twin of install-release.sh (issue #92). Downloads the versioned
# `jdx-<version>-windows.zip` asset from GitHub Releases (Explorer/Defender are
# zip-first; the POSIX script keeps the bare `.tar.gz` for Linux/macOS) and
# unpacks it into `%LOCALAPPDATA%\Programs\jdx` (docs/PROPOSAL.md section 17.1). The
# install directory is added to the user PATH so `jdx` resolves via `jdx.bat`
# (PATHEXT); no symlink and no Administrator rights.
#
# Usage:
#   irm https://raw.githubusercontent.com/MohammadMD1383/jdx/main/install-release.ps1 | iex
#   ./install-release.ps1 [-Version v0.2.0] [-Force] [-Dir DIR] [-BinDir DIR] [-Tarball FILE]
#
# Env overrides: JDX_VERSION (tag like v0.2.0, or `latest`), JDX_REPO
# (default MohammadMD1383/jdx), JDX_INSTALL_DIR, JDX_BIN_DIR, JDX_TARBALL
# (local .zip for offline installs and tests - skips the download).
# JDX_SKIP_USER_PATH=1 skips the persistent user-PATH mutation (tests / CI);
# the script still reports when the bin dir is missing from PATH.
#
# Writes a `.jdx-release` marker (repo + tag) into the install dir: that is
# how `jdx upgrade` knows this is a release install and which repo it tracks.
#
# Per-user only: never requires Administrator (an elevated shell still lands
# under LOCALAPPDATA, with a note). Refuses to overwrite an existing, unrelated
# `jdx.cmd`/`jdx.bat` shim unless -Force is given.

[CmdletBinding()]
param(
    [string]$Version = $(if ($env:JDX_VERSION) { $env:JDX_VERSION } else { 'latest' }),
    [string]$Repo = $(if ($env:JDX_REPO) { $env:JDX_REPO } else { 'MohammadMD1383/jdx' }),
    [string]$Dir = $(if ($env:JDX_INSTALL_DIR) { $env:JDX_INSTALL_DIR } else { $null }),
    [string]$BinDir = $(if ($env:JDX_BIN_DIR) { $env:JDX_BIN_DIR } else { $null }),
    [string]$Tarball = $(if ($env:JDX_TARBALL) { $env:JDX_TARBALL } else { $null }),
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
    [Console]::Error.WriteLine("install-release.ps1: $Message")
    exit 1
}

if ($Help) {
    Write-Output 'usage: ./install-release.ps1 [-Version v0.2.0] [-Force] [-Dir DIR] [-BinDir DIR] [-Tarball FILE]'
    exit 0
}

if (-not $Dir) {
    if (-not $env:LOCALAPPDATA) {
        Write-InstallError 'LOCALAPPDATA is unset - pass -Dir or set JDX_INSTALL_DIR'
    }
    $Dir = Join-Path $env:LOCALAPPDATA 'Programs\jdx'
}
if (-not $BinDir) {
    $BinDir = $Dir
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

# Warn when elevated, but do not refuse: Windows runners (and many user shells
# launched via "Run as administrator") are elevated while LOCALAPPDATA still
# resolves to the interactive user's profile, so a hard refuse would block the
# documented per-user default and break CI. The install target stays under
# %LOCALAPPDATA%\Programs\jdx (or an explicit -Dir); it never writes into
# Program Files. Offline -Tarball tests on non-Windows hosts skip this branch
# ($JdxIsWindows is false under Linux/macOS pwsh).
if ($script:JdxIsWindows -and (Test-IsAdministrator)) {
    [Console]::Error.WriteLine('note: running elevated - still installing per-user into the LOCALAPPDATA target (not Program Files)')
}

function Get-LatestTag([string]$Repository) {
    $api = "https://api.github.com/repos/$Repository/releases/latest"
    try {
        $response = Invoke-WebRequest -Uri $api -UseBasicParsing
    } catch {
        Write-InstallError "could not query '$api' - pass -Version explicitly"
    }
    if ($response.Content -match '"tag_name"\s*:\s*"([^"]+)"') {
        return $Matches[1]
    }
    Write-InstallError "could not parse the latest release tag from '$api' - pass -Version explicitly"
}

function Get-Sha256Hex([string]$Path) {
    return (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLowerInvariant()
}

function Test-Checksum([string]$ArchivePath, [string]$ChecksumPath) {
    $expectedLine = (Get-Content -LiteralPath $ChecksumPath -TotalCount 1)
    $expected = ($expectedLine -split '\s+')[0].Trim().ToLowerInvariant()
    if (-not $expected) { return $false }
    $actual = Get-Sha256Hex $ArchivePath
    return $expected -eq $actual
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
    $updated = if (($current.TrimEnd().EndsWith(';')) -or ($current -eq '')) {
        "$current$Entry"
    } else {
        "$current;$Entry"
    }
    [Environment]::SetEnvironmentVariable('Path', $updated, 'User')
    # Make the new entry visible to this process and any child `jdx version`.
    if (-not (($env:Path -split ';') | Where-Object { [string]::Equals($_, $Entry, [StringComparison]::OrdinalIgnoreCase) })) {
        $env:Path = "$Entry;$env:Path"
    }
    return $true
}

function Test-PathContains([string]$Entry) {
    foreach ($part in @($env:Path -split ';')) {
        if ([string]::Equals($part, $Entry, [StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }
    }
    if ($script:JdxIsWindows -and $env:JDX_SKIP_USER_PATH -ne '1') {
        $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        if ($userPath) {
            foreach ($part in @($userPath -split ';')) {
                if ([string]::Equals($part, $Entry, [StringComparison]::OrdinalIgnoreCase)) {
                    return $true
                }
            }
        }
    }
    return $false
}

function Write-Shim([string]$ShimPath, [string]$LauncherPath) {
    $content = @"
@echo off
rem jdx PATH shim - forwards to the release install (install-release.ps1).
"$LauncherPath" %*
"@
    # cmd.exe shims are CRLF (*.bat / *.cmd eol=crlf).
    $crlf = $content -replace "`r`n", "`n" -replace "`n", "`r`n"
    [System.IO.File]::WriteAllText($ShimPath, $crlf)
}

$tmpRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("jdx-install-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmpRoot | Out-Null
try {
    $archive = $null
    if ($Tarball) {
        if (-not (Test-Path -LiteralPath $Tarball -PathType Leaf)) {
            Write-InstallError "tarball '$Tarball' not found"
        }
        $extension = [System.IO.Path]::GetExtension($Tarball).ToLowerInvariant()
        if ($extension -ne '.zip') {
            Write-InstallError "tarball '$Tarball' must end in .zip (Windows installer is zip-only; use install-release.sh for .tar.gz)"
        }
        $archive = $Tarball
        # Offline installs carry no release tag; recover it from the asset name
        # (jdx-<bare>.zip or jdx-<bare>-windows.zip) so the .jdx-release marker
        # stays truthful for `jdx upgrade`.
        $base = [System.IO.Path]::GetFileName($Tarball)
        if ($base -match '^jdx-(.+)\.zip$') {
            $bare = $Matches[1]
            if ($bare -match '^(.*)-(linux|macos|windows)$') {
                $bare = $Matches[1]
            }
            if ($bare -match '^[0-9]+(\.[0-9]+)*$') {
                $Version = "v$bare"
            }
        }
    } else {
        if ($Version -eq 'latest') {
            $Version = Get-LatestTag $Repo
            Write-Output "installing jdx $Version (latest)"
        }
        if ($Version.StartsWith('v')) {
            $bare = $Version.Substring(1)
        } else {
            $bare = $Version
            $Version = "v$Version"
        }
        if ($bare -notmatch '^[0-9]+(\.[0-9]+)*$') {
            Write-InstallError "version '$bare' is not numeric X.Y.Z (expected e.g. v0.2.0)"
        }
        $baseUrl = "https://github.com/$Repo/releases/download/$Version"
        $assetName = "jdx-$bare-windows.zip"
        $archive = Join-Path $tmpRoot $assetName
        try {
            Invoke-WebRequest -Uri "$baseUrl/$assetName" -OutFile $archive -UseBasicParsing
        } catch {
            Write-InstallError "could not download '$baseUrl/$assetName' - does release $Version exist?"
        }
        $checksumFile = Join-Path $tmpRoot "$assetName.sha256"
        try {
            Invoke-WebRequest -Uri "$baseUrl/$assetName.sha256" -OutFile $checksumFile -UseBasicParsing
            if (-not (Test-Checksum $archive $checksumFile)) {
                Write-InstallError "checksum mismatch for '$assetName'"
            }
        } catch {
            [Console]::Error.WriteLine('warning: no checksum file published - skipping verification')
        }
    }

    $extractDir = Join-Path $tmpRoot 'extract'
    New-Item -ItemType Directory -Path $extractDir | Out-Null
    Expand-Archive -LiteralPath $archive -DestinationPath $extractDir -Force
    $staged = Join-Path $extractDir 'jdx'
    if (-not (Test-Path -LiteralPath $staged -PathType Container)) {
        Write-InstallError "unexpected archive layout - expected a top-level 'jdx/' directory"
    }
    $launcherBat = Join-Path $staged 'jdx.bat'
    if (-not (Test-Path -LiteralPath $launcherBat -PathType Leaf)) {
        Write-InstallError "archive has no 'jdx/jdx.bat' launcher"
    }
    $jarMatches = @(Get-ChildItem -Path (Join-Path $staged 'libs') -Filter 'jdx-*-all.jar' -File -ErrorAction SilentlyContinue)
    if ($jarMatches.Count -eq 0) {
        Write-InstallError "archive has no 'jdx/libs/jdx-*-all.jar' fat jar"
    }

    if (Test-Path -LiteralPath $Dir) {
        Remove-Item -LiteralPath $Dir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $Dir -Force | Out-Null
    Copy-Item -Path (Join-Path $staged '*') -Destination $Dir -Recurse -Force

    $marker = Join-Path $Dir '.jdx-release'
    $markerText = "repo=$Repo`ntag=$Version`n"
    [System.IO.File]::WriteAllText($marker, $markerText)

    $installedLauncher = Join-Path $Dir 'jdx.bat'
    New-Item -ItemType Directory -Path $BinDir -Force | Out-Null

    # When -BinDir equals the install dir, `jdx.bat` itself is the entry point
    # and the PATH shim is "add InstallDir to user PATH". A distinct -BinDir
    # gets a small jdx.cmd forwarder so upgrade can replace InstallDir in place.
    $shimPath = $null
    if (-not [string]::Equals(
            [System.IO.Path]::GetFullPath($BinDir),
            [System.IO.Path]::GetFullPath($Dir),
            [StringComparison]::OrdinalIgnoreCase)) {
        $shimPath = Join-Path $BinDir 'jdx.cmd'
        if ((Test-Path -LiteralPath $shimPath) -or (Test-Path -LiteralPath (Join-Path $BinDir 'jdx.bat'))) {
            $existing = if (Test-Path -LiteralPath $shimPath) { $shimPath } else { Join-Path $BinDir 'jdx.bat' }
            $existingText = if (Test-Path -LiteralPath $existing -PathType Leaf) {
                Get-Content -LiteralPath $existing -Raw
            } else {
                ''
            }
            $ours = $existingText -match [regex]::Escape($installedLauncher)
            if ((Test-Path -LiteralPath $existing -PathType Container)) {
                Write-InstallError "'$existing' is a directory - refusing to replace it even with -Force"
            }
            if (-not $ours -and -not $Force) {
                Write-InstallError "'$existing' already exists and is not this jdx install - rerun with -Force to replace it"
            }
            Remove-Item -LiteralPath $existing -Force
        }
        Write-Shim -ShimPath $shimPath -LauncherPath $installedLauncher
    }

    $pathEntry = $BinDir
    $onPath = Test-PathContains $pathEntry
    if (-not $onPath) {
        $added = Add-UserPathEntry $pathEntry
        if ($added) {
            Write-Output "note: added '$pathEntry' to the user PATH (open a new shell if ``jdx`` is not found)"
        } else {
            [Console]::Error.WriteLine("note: $pathEntry is not on your PATH")
        }
    }

    $probe = if ($shimPath) { $shimPath } else { $installedLauncher }
    $versionLine = $null
    try {
        $versionLine = & $probe version 2>$null
        if ($LASTEXITCODE -ne 0) { $versionLine = $null }
    } catch {
        $versionLine = $null
    }
    if ($versionLine) {
        Write-Output "installed: $probe -> $installedLauncher ($versionLine)"
    } else {
        Write-Output "installed: $probe -> $installedLauncher"
        [Console]::Error.WriteLine('warning: could not run `jdx version` - check your JDK (jdx needs Java 21+)')
    }
}
finally {
    if (Test-Path -LiteralPath $tmpRoot) {
        Remove-Item -LiteralPath $tmpRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
