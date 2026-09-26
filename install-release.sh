#!/bin/sh
# install-release.sh — install `jdx` from a GitHub Release tarball (per-user).
#
# Remote installer for released versions. The repo-root `install.sh` next to
# this file is the source-build installer (symlinks a local `./gradlew
# :app:installDist` build); this script downloads a versioned
# `jdx-<version>.tar.gz` asset from GitHub Releases instead — no build needed.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/MohammadMD1383/jdx/main/install-release.sh | bash
#   curl -fsSL .../install-release.sh | bash -s -- --version v0.2.0
#   ./install-release.sh [--version v0.2.0] [--force] [--dir ~/.local/share/jdx] [--bin-dir ~/.local/bin]
#
# Env overrides: JDX_VERSION (a tag like v0.2.0, or `latest`), JDX_REPO
# (default MohammadMD1383/jdx), JDX_INSTALL_DIR, JDX_BIN_DIR, JDX_TARBALL
# (local .tar.gz for offline installs and tests — skips the download).
#
# Writes a `.jdx-release` marker (repo + tag) into the install dir: that is
# how `jdx upgrade` knows this is a release install and which repo it tracks.
#
# Per-user only: never requires root, and refuses to run as root. Refuses to
# overwrite an existing, unrelated `jdx` on PATH unless --force is given.

die() {
    printf 'install-release.sh: %s\n' "$*" >&2
    exit 1
}

version=${JDX_VERSION:-latest}
repo=${JDX_REPO:-MohammadMD1383/jdx}
install_dir=${JDX_INSTALL_DIR:-$HOME/.local/share/jdx}
bin_dir=${JDX_BIN_DIR:-$HOME/.local/bin}
tarball_override=${JDX_TARBALL:-}
force=0

while [ "$#" -gt 0 ]; do
    case $1 in
        --version)
            [ "$#" -ge 2 ] || die "--version needs a value (usage: ./install-release.sh [--version v0.2.0] [--force])"
            version=$2
            shift 2
            ;;
        --version=*) version=${1#--version=}; shift ;;
        --force) force=1; shift ;;
        --dir)
            [ "$#" -ge 2 ] || die "--dir needs a value"
            install_dir=$2
            shift 2
            ;;
        --dir=*) install_dir=${1#--dir=}; shift ;;
        --bin-dir)
            [ "$#" -ge 2 ] || die "--bin-dir needs a value"
            bin_dir=$2
            shift 2
            ;;
        --bin-dir=*) bin_dir=${1#--bin-dir=}; shift ;;
        --tarball)
            [ "$#" -ge 2 ] || die "--tarball needs a value"
            tarball_override=$2
            shift 2
            ;;
        --tarball=*) tarball_override=${1#--tarball=}; shift ;;
        -h|--help)
            printf 'usage: ./install-release.sh [--version v0.2.0] [--force] [--dir DIR] [--bin-dir DIR] [--tarball FILE]\n'
            exit 0
            ;;
        *) die "unknown argument '$1' (usage: ./install-release.sh [--version v0.2.0] [--force])" ;;
    esac
done

[ "$(id -u)" -ne 0 ] || die "refusing to run as root — jdx installs per-user into ~/.local/bin"

command -v tar >/dev/null 2>&1 || die "the 'tar' tool is required but not on PATH"

download() {
    # download <url> <dest>
    if command -v curl >/dev/null 2>&1; then
        if curl --help 2>/dev/null | grep -q ' --proto'; then
            curl -fsSL --proto '=https' --tlsv1.2 --retry 3 -o "$2" "$1" || return 1
        else
            # Older curl (stock macOS before --proto existed): same fetch, without
            # the protocol pin the old binary does not understand.
            curl -fsSL --retry 3 -o "$2" "$1" || return 1
        fi
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$2" "$1" || return 1
    else
        die "neither 'curl' nor 'wget' found — install one of them first"
    fi
}

# Portable temp creation: bare `mktemp` / `mktemp -d` need `-t prefix` on older
# macOS, so every call tries the plain form first and falls back to `-t`.
make_temp_file() {
    mktemp 2>/dev/null || mktemp -t jdx 2>/dev/null
}

make_temp_dir() {
    mktemp -d 2>/dev/null || mktemp -d -t jdx 2>/dev/null
}

verify_checksum() {
    # verify_checksum <dir> <checksum-file>: check a .sha256 file with whatever
    # verifier exists — sha256sum, shasum (macOS), or Windows CertUtil (Git-Bash).
    # A published checksum that cannot be checked is a loud failure, never a
    # silent skip: an unverified binary is worse than no binary.
    v_dir=$1
    v_sum=$2
    v_status=1
    if command -v sha256sum >/dev/null 2>&1; then
        (cd "$v_dir" && sha256sum -c "$v_sum")
        v_status=$?
    elif command -v shasum >/dev/null 2>&1; then
        (cd "$v_dir" && shasum -a 256 -c "$v_sum")
        v_status=$?
    elif command -v CertUtil >/dev/null 2>&1 || command -v certutil >/dev/null 2>&1; then
        if command -v CertUtil >/dev/null 2>&1; then v_cert=CertUtil; else v_cert=certutil; fi
        # CertUtil prints the hex digest alone on its second line (upper-case,
        # space-padded); the .sha256 file carries "<hex>  <name>".
        v_expected=$(cut -d ' ' -f 1 "$v_sum")
        v_actual=$("$v_cert" -hashfile "$tarball" SHA256 2>/dev/null \
            | sed -n '2p' | tr -d ' \r' | tr 'A-Z' 'a-z')
        if [ -n "$v_expected" ] && [ -n "$v_actual" ] && [ "$v_expected" = "$v_actual" ]; then
            v_status=0
        else
            v_status=1
        fi
        unset v_cert v_expected v_actual
    else
        die "cannot verify '$v_sum' — no sha256sum, shasum, or CertUtil on PATH"
    fi
    unset v_dir v_sum
    return "$v_status"
}

resolve_latest() {
    # resolve_latest: print the latest release tag via the GitHub API (no jq).
    api="https://api.github.com/repos/$repo/releases/latest"
    tmp=$(make_temp_file) || die "cannot create temp file"
    download "$api" "$tmp" || die "could not query '$api' — pass --version explicitly"
    tag=$(sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$tmp" | head -n 1)
    rm -f -- "$tmp"
    [ -n "$tag" ] || die "could not parse the latest release tag from '$api' — pass --version explicitly"
    printf '%s' "$tag"
}

tmp_dir=$(make_temp_dir) || die "cannot create temp directory"
trap 'rm -rf -- "$tmp_dir"' EXIT INT TERM

tarball=
if [ -n "$tarball_override" ]; then
    [ -f "$tarball_override" ] || die "tarball '$tarball_override' not found"
    tarball=$tarball_override
    # Offline installs carry no release tag; recover it from the asset name
    # (jdx-<bare>.tar.gz) so the .jdx-release marker stays truthful.
    base=$(basename "$tarball_override")
    case $base in
        jdx-*.tar.gz)
            bare=${base#jdx-}
            bare=${bare%.tar.gz}
            case $bare in
                ''|*[!0-9.]*) ;;
                *) version="v$bare" ;;
            esac
            ;;
    esac
    unset base bare
else
    if [ "$version" = "latest" ]; then
        version=$(resolve_latest)
        printf 'installing jdx %s (latest)\n' "$version"
    fi
    case $version in
        v*) bare=${version#v} ;;
        *) bare=$version; version="v$version" ;;
    esac
    case $bare in
        ''|*[!0-9.]*)
            die "version '$bare' is not numeric X.Y.Z (expected e.g. v0.2.0)"
            ;;
    esac
    base_url="https://github.com/$repo/releases/download/$version"
    tarball="$tmp_dir/jdx-$bare.tar.gz"
    download "$base_url/jdx-$bare.tar.gz" "$tarball" \
        || die "could not download '$base_url/jdx-$bare.tar.gz' — does release $version exist?"
    if download "$base_url/jdx-$bare.tar.gz.sha256" "$tarball.sha256" 2>/dev/null; then
        verify_checksum "$tmp_dir" "$tarball.sha256" \
            || die "checksum mismatch for 'jdx-$bare.tar.gz'"
    else
        printf 'warning: no checksum file published — skipping verification\n' >&2
    fi
fi

# The tarball carries a top-level `jdx/` directory (launcher + libs/), matching
# the layout `:app:installDist` produces under app/build.
tar -tzf "$tarball" | head -n 1 | grep -q '^jdx/' \
    || die "unexpected tarball layout — expected a top-level 'jdx/' directory"

mkdir -p -- "$install_dir" || die "cannot create '$install_dir'"
tar -xzf "$tarball" -C "$tmp_dir" || die "cannot extract '$tarball'"
[ -x "$tmp_dir/jdx/jdx" ] || die "tarball has no executable 'jdx/jdx' launcher"
jar_found=0
for jar in "$tmp_dir"/jdx/libs/jdx-*-all.jar; do
    [ -f "$jar" ] && jar_found=1
done
[ "$jar_found" -eq 1 ] || die "tarball has no 'jdx/libs/jdx-*-all.jar' fat jar"

rm -rf -- "$install_dir" || die "cannot clear '$install_dir'"
mkdir -p -- "$install_dir" || die "cannot create '$install_dir'"
cp -r -- "$tmp_dir/jdx/." "$install_dir/" || die "cannot copy into '$install_dir'"
chmod +x -- "$install_dir/jdx" || die "cannot make '$install_dir/jdx' executable"
# Marker for `jdx upgrade`: which repo this install tracks (and that it IS a
# release install — source builds have no marker and are never self-updated).
printf 'repo=%s\ntag=%s\n' "$repo" "$version" > "$install_dir/.jdx-release" \
    || die "cannot write '$install_dir/.jdx-release'"

mkdir -p -- "$bin_dir" || die "cannot create '$bin_dir'"
target=$bin_dir/jdx
if [ -e "$target" ] || [ -L "$target" ]; then
    if [ -L "$target" ] && [ "$(readlink "$target")" = "$install_dir/jdx" ]; then
        : # already ours; fall through and re-link (idempotent refresh)
    elif [ -d "$target" ]; then
        die "'$target' is a directory — refusing to replace it even with --force"
    elif [ "$force" -eq 0 ]; then
        die "'$target' already exists and is not this jdx install — rerun with --force to replace it"
    fi
    rm -f -- "$target" || die "cannot remove '$target'"
fi
ln -s -- "$install_dir/jdx" "$target" || die "cannot link '$target' -> '$install_dir/jdx'"

case ":$PATH:" in
    *":$bin_dir:"*) ;;
    *) printf 'note: %s is not on your PATH\n' "$bin_dir" >&2 ;;
esac
if "$target" version >/dev/null 2>&1; then
    printf 'installed: %s -> %s (%s)\n' "$target" "$install_dir/jdx" "$("$target" version)"
else
    printf 'installed: %s -> %s\n' "$target" "$install_dir/jdx"
    printf 'warning: could not run `jdx version` — check your JDK (jdx needs Java 21+)\n' >&2
fi
