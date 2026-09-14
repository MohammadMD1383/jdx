#!/bin/sh
# install.sh — link the built `jdx` launcher into ~/.local/bin (T-004).
#
# Per-user only: never requires root, and refuses to run as root. Refuses to overwrite an
# existing, unrelated `jdx` on PATH unless --force is given — clobbering another tool
# silently is the one unforgivable sin of an installer.
#
# Usage: ./install.sh [--force]

die() {
    printf 'install.sh: %s\n' "$*" >&2
    exit 1
}

force=0
for argument in "$@"; do
    case $argument in
        --force) force=1 ;;
        -h|--help)
            printf 'usage: ./install.sh [--force]\n'
            exit 0
            ;;
        *) die "unknown argument '$argument' (usage: ./install.sh [--force])" ;;
    esac
done

[ "$(id -u)" -ne 0 ] || die "refusing to run as root — jdx installs per-user into ~/.local/bin"

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
launcher=$repo_root/app/build/jdx

[ -f "$launcher" ] || die "no built launcher at '$launcher' — build it first: ./gradlew :app:installDist"
[ -x "$launcher" ] || die "'$launcher' is not executable — rebuild with ./gradlew :app:installDist"
jar_found=0
for jar in "$repo_root"/app/build/libs/jdx-*-all.jar; do
    [ -f "$jar" ] && jar_found=1
done
[ "$jar_found" -eq 1 ] || die "no jdx fat jar in '$repo_root/app/build/libs' — rebuild with ./gradlew :app:installDist"

bin_dir=$HOME/.local/bin
mkdir -p "$bin_dir" || die "cannot create '$bin_dir'"

target=$bin_dir/jdx
if [ -e "$target" ] || [ -L "$target" ]; then
    if [ -L "$target" ] && [ "$(readlink -- "$target")" = "$launcher" ]; then
        : # already ours; fall through and re-link (idempotent refresh)
    elif [ -d "$target" ]; then
        die "'$target' is a directory — refusing to replace it even with --force"
    elif [ "$force" -eq 0 ]; then
        die "'$target' already exists and is not this jdx build — rerun with --force to replace it"
    fi
    rm -f -- "$target" || die "cannot remove '$target'"
fi
ln -s -- "$launcher" "$target" || die "cannot link '$target' -> '$launcher'"

case ":$PATH:" in
    *":$bin_dir:"*) ;;
    *) printf 'note: %s is not on your PATH\n' "$bin_dir" >&2 ;;
esac
printf 'installed: %s -> %s\n' "$target" "$launcher"
