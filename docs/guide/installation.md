# Installation

jdx installs per user with one command, needs only a Java 21+ runtime, and runs on Linux, macOS and Windows.

## Requirements

- **JDK 21 or later.** jdx finds it through `JAVA_HOME`, then `java` on `PATH`, then the usual
  platform locations (`/usr/libexec/java_home` on macOS, the registry and `%ProgramFiles%\Java`
  on Windows). If none works it prints one line saying what to set.
- No root or administrator rights. jdx refuses to install as root.

## Install the latest release

### Linux and macOS

```bash
curl -fsSL https://raw.githubusercontent.com/MohammadMD1383/jdx/main/install-release.sh | bash
```

This downloads the latest release from
[GitHub Releases](https://github.com/MohammadMD1383/jdx/releases), installs it into
`~/.local/share/jdx` and links `~/.local/bin/jdx`. Make sure `~/.local/bin` is on your `PATH`.

Installer options (pass them after `bash -s --`):

| Option | Effect |
|---|---|
| `--version v1.2.0` | Install a specific release tag instead of the latest |
| `--dir <path>` | Install directory (default `~/.local/share/jdx`) |
| `--bin-dir <path>` | Where the `jdx` link goes (default `~/.local/bin`) |
| `--force` | Replace an unrelated `jdx` already on `PATH` |
| `--tarball <file>` | Install from a downloaded `.tar.gz` or `.zip` (offline installs) |

The same settings are available as environment variables: `JDX_VERSION`, `JDX_INSTALL_DIR`,
`JDX_BIN_DIR`, `JDX_TARBALL`.

### Windows

```powershell
irm https://raw.githubusercontent.com/MohammadMD1383/jdx/main/install-release.ps1 | iex
```

This downloads the `jdx-<version>-windows.zip` asset, installs it into
`%LOCALAPPDATA%\Programs\jdx`, and adds that directory to the user `PATH` so
`jdx` resolves via `jdx.bat`. No Administrator rights are needed (an elevated
shell still installs per-user into `LOCALAPPDATA`, never into Program Files).
Offline / pinned installs:

```powershell
./install-release.ps1 -Version v1.2.0
./install-release.ps1 -Tarball .\jdx-1.2.0-windows.zip
```

| Option | Effect |
|---|---|
| `-Version v1.2.0` | Install a specific release tag instead of the latest |
| `-Dir <path>` | Install directory (default `%LOCALAPPDATA%\Programs\jdx`) |
| `-BinDir <path>` | Optional separate shim directory (writes `jdx.cmd`; default is `-Dir` on `PATH`) |
| `-Force` | Replace an unrelated `jdx.cmd` shim |
| `-Tarball <file>` | Install from a downloaded `.zip` (offline installs) |

Env overrides match the POSIX installer (`JDX_VERSION`, `JDX_INSTALL_DIR`,
`JDX_BIN_DIR`, `JDX_TARBALL`). Set `JDX_SKIP_USER_PATH=1` to skip the persistent
user-`PATH` mutation (tests / CI).

Every release ships per-OS bundles — `jdx-<version>-<os>.tar.gz` and `.zip` for `linux`,
`macos` and `windows`, each with a `.sha256` checksum and a startup-time archive trained on that
OS. It also ships one MCP Bundle, `jdx-mcp-<version>.mcpb`, described below.

Releases are not code-signed, so Windows SmartScreen and macOS Gatekeeper warn on first run.
Clear the warning with `Unblock-File jdx.bat` (PowerShell) or
`xattr -d com.apple.quarantine <install-dir>/jdx` (macOS).

## MCP Bundle

Clients that install an [MCP Bundle](https://github.com/modelcontextprotocol/mcpb) (a `.mcpb`
file) can load jdx from the release asset
`jdx-mcp-<version>.mcpb` on [GitHub Releases](https://github.com/MohammadMD1383/jdx/releases).
The bundle carries the POSIX launcher, the Windows launchers, the fat jar, and the startup
archive, laid out so the host starts `bin/jdx` with argument `mcp` over stdio, and
`bin/jdx.bat` with argument `mcp` on Windows.

Install a Java 21 or later runtime and leave it on `PATH` or in `JAVA_HOME`. jdx finds that
runtime the same way the [requirements](#requirements) above describe.

The server still reads a stored workspace. Create one with `jdx ws create` before asking
about a jar. Wiring for clients that launch a command instead of a bundle is in
[An MCP server for Java and Kotlin bytecode](java-mcp-server.md).

The [Official MCP Registry](https://registry.modelcontextprotocol.io) entry is
`io.github.MohammadMD1383/jdx` (`server.json` in the repository). It points at that release
asset. A maintainer publishes the entry after the release exists; the steps are in
[the release section of the design spec](../PROPOSAL.md#172-release-assets-and-signing-issue-48).

## Verify

```bash
jdx version
jdx doctor
```

`jdx doctor` prints one `ok` / `warn` / `fail` row per check: JDK, `javap`, cache, config,
index, Kotlin module, startup archive, daemon, workspace and agent setup. It exits `6` when a
check fails, so scripts can gate on it.

## Upgrade

Release installs update themselves:

```bash
jdx upgrade --check     # is there a newer release?
jdx upgrade             # install it
jdx upgrade --version v1.2.0
```

See the [changelog](https://mohammadmd1383.github.io/jdx/changelog/) for what changed.

## Build from source

```bash
git clone https://github.com/MohammadMD1383/jdx && cd jdx
./gradlew :app:installDist && ./install.sh
```

On Windows (from a checkout):

```powershell
.\gradlew.bat :app:installDist
.\install.ps1
```

The Gradle wrapper is the only build entry point — no system Gradle or Maven is needed. See
[`CONTRIBUTING.md`](../../CONTRIBUTING.md) for the development loop.

## Uninstall

Optionally wipe the regenerable index cache first, then delete the install directory and the
link (defaults shown):

```bash
# Linux / macOS
jdx cache clear
rm -rf ~/.local/share/jdx ~/.local/bin/jdx
```

```powershell
# Windows
jdx cache clear
Remove-Item -Recurse -Force "$env:LOCALAPPDATA\Programs\jdx"
# then remove that directory from the user PATH if you added it via install-release.ps1
```

## Next

Run the [quickstart](quickstart.md), then [connect your AI agent](ai-agents.md).
