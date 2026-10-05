# Installation

jdx installs per user with one command, needs only a Java 21+ runtime, and runs on Linux, macOS and Windows.

## Requirements

- **JDK 21 or later.** jdx finds it through `JAVA_HOME`, then `java` on `PATH`, then the usual
  platform locations (`/usr/libexec/java_home` on macOS, the registry and `%ProgramFiles%\Java`
  on Windows). If none works it prints one line saying what to set.
- No root or administrator rights. jdx refuses to install as root.

## Install the latest release

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

### Windows and macOS

Every release ships per-OS bundles — `jdx-<version>-<os>.tar.gz` and `.zip` for `linux`,
`macos` and `windows`, each with a `.sha256` checksum and a startup-time archive trained on that
OS. On Windows, prefer the `-windows.zip` asset and install it with
`sh install-release.sh --tarball jdx-<version>-windows.zip`.

Releases are not code-signed, so Windows SmartScreen and macOS Gatekeeper warn on first run.
Clear the warning with `Unblock-File jdx.bat` (PowerShell) or
`xattr -d com.apple.quarantine <install-dir>/jdx` (macOS).

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

The Gradle wrapper is the only build entry point — no system Gradle or Maven is needed. See
[`CONTRIBUTING.md`](../../CONTRIBUTING.md) for the development loop.

## Uninstall

Optionally wipe the regenerable index cache first, then delete the install directory and the
link (defaults shown):

```bash
jdx cache clear
rm -rf ~/.local/share/jdx ~/.local/bin/jdx
```

## Next

Run the [quickstart](quickstart.md), then [connect your AI agent](ai-agents.md).
