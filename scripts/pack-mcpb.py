#!/usr/bin/env python3
"""Pack the jdx MCP Bundle and stamp Official MCP Registry metadata.

The bundle is not a runtime surface. ``jdx`` itself is unchanged. This script
stages one distribution (the launchers, the fat jar, and the AppCDS archive
when the build produced one) behind ``mcpb/manifest.json`` and asks the
official ``mcpb`` CLI to validate and pack it.

Layout inside the ``.mcpb`` (a zip):

    manifest.json
    bin/jdx            POSIX launcher, executable
    bin/jdx.bat        Windows launcher
    bin/jdx.ps1
    bin/libs/jdx-*-all.jar
    bin/libs/jdx.jsa   when :app:installDist trained one

Hosts launch ``bin/jdx mcp`` over stdio, and ``bin/jdx.bat mcp`` on Windows
(``platform_overrides.win32``). The launcher looks for ``libs/`` next to
itself, so the jar lives in ``bin/libs/``. A Java 21+ runtime must already be
on ``PATH`` or in ``JAVA_HOME``. The MCPB schema's ``compatibility.runtimes``
accepts only ``node`` and ``python`` (manifest spec 0.3 / 0.4, checked
2026-11-22), so that requirement is written in the manifest description and
long description, and ``compatibility.platforms`` lists linux, darwin, win32.

Version is the project version: whatever ``jdx version`` prints for the
launcher being packed. Gradle supplies it from ``-PjdxVersion`` and otherwise
uses ``0.1.0-SNAPSHOT`` (``build.gradle.kts``). The git copies of
``mcpb/manifest.json`` and ``server.json`` keep that default. A release build
rewrites the version only in the staged manifest, then ``stamp`` writes a
publishable ``server.json`` whose ``fileSha256`` is the packed bundle.

Tool names and descriptions are copied from ``jdx mcp`` ``tools/list``.
``sync-tools`` writes them into ``mcpb/manifest.json``. ``pack`` fails when
the committed list has drifted.

Requires a built distribution (``./gradlew :app:installDist``) and the
official CLI (``mcpb`` on ``PATH``, or ``npx --yes @anthropic-ai/mcpb``).

    python3 scripts/pack-mcpb.py sync-tools
    python3 scripts/pack-mcpb.py pack --strict-server-json
    python3 scripts/pack-mcpb.py stamp --mcpb jdx-mcp-1.2.3.mcpb --version 1.2.3 --out server.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST_PATH = ROOT / "mcpb" / "manifest.json"
SERVER_JSON_PATH = ROOT / "server.json"

GITHUB_REPO = "MohammadMD1383/jdx"
SERVER_SCHEMA = "https://static.modelcontextprotocol.io/schemas/2025-12-11/server.schema.json"
# Registry description, 98 characters. The schema rejects anything over 100.
SERVER_DESCRIPTION = (
    "IDE for AI agents on JVM code: members, method bodies, usages and call graphs for jars and the JDK"
)
SERVER_NAME = "io.github.MohammadMD1383/jdx"
SERVER_TITLE = "jdx"
WEBSITE_URL = "https://mohammadmd1383.github.io/jdx/"
REPOSITORY_URL = f"https://github.com/{GITHUB_REPO}"
PLACEHOLDER_SHA256 = "0" * 64

ENTRY_POINT = "bin/jdx"
WINDOWS_ENTRY = "bin/jdx.bat"


def fail(message: str) -> None:
    print(f"pack-mcpb: {message}", file=sys.stderr)
    raise SystemExit(1)


def release_asset_url(version: str) -> str:
    """GitHub Release asset URL. The path contains ``mcp`` (registry rule)."""
    url = f"{REPOSITORY_URL}/releases/download/v{version}/jdx-mcp-{version}.mcpb"
    if "mcp" not in url:
        fail(f"asset URL {url} does not contain 'mcp'")
    return url


def read_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        fail(f"missing {path.relative_to(ROOT)}")
    except json.JSONDecodeError as error:
        fail(f"{path.relative_to(ROOT)} is not JSON: {error}")


def write_json(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_sha256_sidecar(artifact: Path, digest: str) -> Path:
    """``<hex>  <filename>`` — the same line ``sha256sum -c`` accepts for the other assets."""
    sidecar = artifact.with_name(artifact.name + ".sha256")
    sidecar.write_text(f"{digest}  {artifact.name}\n", encoding="utf-8")
    return sidecar


def mcpb_argv() -> list[str]:
    override = os.environ.get("MCPB")
    if override:
        return override.split()
    found = shutil.which("mcpb")
    if found:
        return [found]
    npx = shutil.which("npx")
    if not npx:
        fail("mcpb is not on PATH and npx is missing; install Node.js and @anthropic-ai/mcpb")
    return [npx, "--yes", "@anthropic-ai/mcpb"]


def run(argv: list[str], *, input_bytes: bytes | None = None, cwd: Path | None = None, timeout: int = 180) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(
            argv,
            input=input_bytes,
            cwd=cwd,
            capture_output=True,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired:
        fail(f"timed out after {timeout}s: {' '.join(argv)}")


def launcher_version(launcher: Path) -> str:
    result = run([str(launcher), "version"], timeout=120)
    text = result.stdout.decode("utf-8", errors="replace")
    for line in text.splitlines():
        prefix = "jdx version "
        if line.startswith(prefix):
            version = line[len(prefix):].strip()
            if version:
                return version
    stderr = result.stderr.decode("utf-8", errors="replace").strip()
    fail(
        f"`{launcher} version` did not report a version (exit {result.returncode}).\n"
        f"stdout:\n{text}\nstderr:\n{stderr}"
    )


def list_tools(launcher: Path) -> list[dict]:
    """Speak initialize + tools/list. Names and descriptions come from the server, unchanged."""
    requests = [
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "jdx-mcpb", "version": "0"},
            },
        },
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
    ]
    payload = "".join(json.dumps(item) + "\n" for item in requests).encode("utf-8")
    result = run([str(launcher), "mcp"], input_bytes=payload, timeout=120)
    stdout = result.stdout.decode("utf-8", errors="replace")
    tools_payload = None
    for line in stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if message.get("id") == 2:
            tools_payload = message
            break
    if tools_payload is None:
        stderr = result.stderr.decode("utf-8", errors="replace").strip()
        fail(f"`jdx mcp` did not answer tools/list (exit {result.returncode}).\nstderr:\n{stderr}")
    try:
        raw_tools = tools_payload["result"]["tools"]
    except (KeyError, TypeError):
        fail(f"tools/list response has no result.tools: {json.dumps(tools_payload)[:500]}")
    tools = []
    seen = set()
    for tool in raw_tools:
        name = tool.get("name")
        description = tool.get("description")
        if not isinstance(name, str) or not name:
            fail(f"tools/list returned a tool without a name: {tool!r}")
        if not isinstance(description, str) or not description.strip():
            fail(f"tools/list tool {name} has no description")
        if name in seen:
            fail(f"tools/list returned {name} twice")
        seen.add(name)
        tools.append({"name": name, "description": description})
    if not tools:
        fail("tools/list returned no tools")
    return tools


def manifest_tools(manifest: dict) -> list[dict]:
    tools = manifest.get("tools")
    if not isinstance(tools, list):
        fail("mcpb/manifest.json tools must be an array")
    cleaned = []
    for tool in tools:
        if not isinstance(tool, dict) or set(tool) - {"name", "description"}:
            fail(f"manifest tool must have only name and description: {tool!r}")
        cleaned.append({"name": tool.get("name"), "description": tool.get("description")})
    return cleaned


def resolve_launcher(explicit: str | None) -> Path:
    launcher = Path(explicit) if explicit else ROOT / "app" / "build" / "jdx"
    if not launcher.is_file():
        fail(f"launcher not found at {launcher}. Build it first: ./gradlew :app:installDist")
    return launcher


def dist_dir(launcher: Path) -> Path:
    """``:app:installDist`` writes ``jdx`` next to ``libs/`` under ``app/build``."""
    return launcher.resolve().parent


def sync_tools(launcher: Path) -> None:
    manifest = read_json(MANIFEST_PATH)
    live = list_tools(launcher)
    current = manifest_tools(manifest)
    if current == live:
        print(f"mcpb/manifest.json tools already match jdx mcp ({len(live)} tools)")
        return
    manifest["tools"] = live
    write_json(MANIFEST_PATH, manifest)
    print(f"updated mcpb/manifest.json tools from jdx mcp ({len(live)} tools)")


def check_tools_match(manifest: dict, live: list[dict]) -> None:
    current = manifest_tools(manifest)
    if current == live:
        return
    current_names = [tool["name"] for tool in current]
    live_names = [tool["name"] for tool in live]
    fail(
        "mcpb/manifest.json tools differ from `jdx mcp` tools/list "
        f"(manifest {current_names}, server {live_names}). "
        "Refresh them with: python3 scripts/pack-mcpb.py sync-tools"
    )


def check_server_template(manifest: dict, launcher_version_text: str, strict: bool) -> None:
    """The committed server.json is a template. ``stamp`` fills a real sha256 at release."""
    if len(SERVER_DESCRIPTION) > 100:
        fail(f"server description is {len(SERVER_DESCRIPTION)} characters; the registry maximum is 100")
    server = read_json(SERVER_JSON_PATH)
    version = server.get("version")
    problems = []

    def expect(actual, wanted, label: str) -> None:
        if actual != wanted:
            problems.append(f"{label}: {actual!r} (expected {wanted!r})")

    expect(server.get("$schema"), SERVER_SCHEMA, "$schema")
    expect(server.get("name"), SERVER_NAME, "name")
    expect(server.get("title"), SERVER_TITLE, "title")
    expect(server.get("description"), SERVER_DESCRIPTION, "description")
    expect(server.get("websiteUrl"), WEBSITE_URL, "websiteUrl")
    repository = server.get("repository") or {}
    expect(repository.get("url"), REPOSITORY_URL, "repository.url")
    expect(repository.get("source"), "github", "repository.source")
    expect(version, manifest.get("version"), "version (must match mcpb/manifest.json)")
    packages = server.get("packages")
    if not isinstance(packages, list) or len(packages) != 1:
        problems.append("packages must be a one-element array")
        packages = []
    if packages:
        package = packages[0]
        expect(package.get("registryType"), "mcpb", "packages[0].registryType")
        expect(package.get("identifier"), release_asset_url(str(version)), "packages[0].identifier")
        expect(package.get("version"), version, "packages[0].version")
        expect(package.get("fileSha256"), PLACEHOLDER_SHA256, "packages[0].fileSha256")
        transport = package.get("transport") or {}
        expect(transport.get("type"), "stdio", "packages[0].transport.type")
        identifier = package.get("identifier") or ""
        if "mcp" not in identifier:
            problems.append(f"packages[0].identifier {identifier!r} does not contain 'mcp'")
    if strict and version != launcher_version_text:
        problems.append(
            f"version {version!r} does not match this build ({launcher_version_text!r}). "
            "The git template tracks the Gradle default; a release build stamps a copy instead."
        )
    if problems:
        fail("server.json\n  " + "\n  ".join(problems))


def stage_distribution(launcher: Path, version: str, live_tools: list[dict], stage: Path) -> None:
    if stage.exists():
        shutil.rmtree(stage)
    bin_dir = stage / "bin"
    libs_dir = bin_dir / "libs"
    libs_dir.mkdir(parents=True)
    source_dist = dist_dir(launcher)
    for name in ("jdx", "jdx.bat", "jdx.ps1"):
        source = source_dist / name
        if not source.is_file():
            fail(f"distribution is missing {source}")
        shutil.copy2(source, bin_dir / name)
    jars = sorted(source_dist.glob("libs/jdx-*-all.jar"))
    if len(jars) != 1:
        fail(f"expected exactly one fat jar in {source_dist / 'libs'}, found {[jar.name for jar in jars]}")
    shutil.copy2(jars[0], libs_dir / jars[0].name)
    archive = source_dist / "libs" / "jdx.jsa"
    if archive.is_file():
        shutil.copy2(archive, libs_dir / archive.name)
    launcher_path = bin_dir / "jdx"
    launcher_path.chmod(launcher_path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    if not launcher_path.read_text(encoding="utf-8").startswith("#!/bin/sh"):
        fail("staged bin/jdx is not the POSIX launcher")

    manifest = read_json(MANIFEST_PATH)
    manifest["version"] = version
    manifest["tools"] = live_tools
    server = manifest.get("server") or {}
    if server.get("type") != "binary" or server.get("entry_point") != ENTRY_POINT:
        fail(f"manifest server must be type binary with entry_point {ENTRY_POINT}")
    mcp_config = server.get("mcp_config") or {}
    win32 = (mcp_config.get("platform_overrides") or {}).get("win32") or {}
    if mcp_config.get("args") != ["mcp"] or win32.get("args") != ["mcp"]:
        fail("manifest mcp_config args must be [\"mcp\"] on every platform")
    if not str(mcp_config.get("command", "")).endswith("/" + ENTRY_POINT):
        fail(f"manifest command must launch {ENTRY_POINT}")
    if not str(win32.get("command", "")).endswith("/" + WINDOWS_ENTRY):
        fail(f"manifest win32 command must launch {WINDOWS_ENTRY}")
    description = manifest.get("description") or ""
    long_description = manifest.get("long_description") or ""
    if "Java 21" not in description or "JAVA_HOME" not in description:
        fail("manifest description must state that Java 21+ is required on PATH or JAVA_HOME")
    if "Java 21" not in long_description or "JAVA_HOME" not in long_description:
        fail("manifest long_description must state the Java 21+ requirement")
    write_json(stage / "manifest.json", manifest)


def smoke_staged_launcher(stage: Path, version: str) -> None:
    launched = stage / ENTRY_POINT
    result = run([str(launched), "version"], timeout=120)
    text = result.stdout.decode("utf-8", errors="replace")
    expected = f"jdx version {version}"
    if expected not in text.splitlines():
        stderr = result.stderr.decode("utf-8", errors="replace")
        fail(f"staged {ENTRY_POINT} version smoke failed.\nstdout:\n{text}\nstderr:\n{stderr}")


def verify_packed(archive: Path, version: str, live_tools: list[dict]) -> None:
    with zipfile.ZipFile(archive) as bundle:
        names = set(bundle.namelist())
        required = {"manifest.json", "bin/jdx", "bin/jdx.bat", "bin/jdx.ps1"}
        missing = required - names
        if missing:
            fail(f"{archive.name} is missing {sorted(missing)}")
        jars = [name for name in names if name.startswith("bin/libs/jdx-") and name.endswith("-all.jar")]
        if len(jars) != 1:
            fail(f"{archive.name} should contain one fat jar, found {jars}")
        info = bundle.getinfo("bin/jdx")
        mode = (info.external_attr >> 16) & 0o777
        if mode & 0o111 == 0:
            fail(f"bin/jdx in {archive.name} is not executable (mode {mode:o})")
        packed = json.loads(bundle.read("manifest.json").decode("utf-8"))
    if packed.get("version") != version:
        fail(f"packed manifest version {packed.get('version')!r} != {version!r}")
    if packed.get("tools") != live_tools:
        fail("packed manifest tools differ from jdx mcp tools/list")


def command_pack(args: argparse.Namespace) -> None:
    launcher = resolve_launcher(args.launcher)
    version = launcher_version(launcher)
    live = list_tools(launcher)
    manifest = read_json(MANIFEST_PATH)
    check_tools_match(manifest, live)
    check_server_template(manifest, version, strict=args.strict_server_json)
    stage_root = Path(args.stage) if args.stage else ROOT / "build" / "mcpb" / "stage"
    stage_distribution(launcher, version, live, stage_root)
    smoke_staged_launcher(stage_root, version)
    out = Path(args.out) if args.out else ROOT / "build" / "mcpb" / f"jdx-mcp-{version}.mcpb"
    out.parent.mkdir(parents=True, exist_ok=True)
    if out.exists():
        out.unlink()
    mcpb = mcpb_argv()
    validated = run(mcpb + ["validate", str(stage_root)], timeout=180)
    sys.stdout.write(validated.stdout.decode("utf-8", errors="replace"))
    sys.stderr.write(validated.stderr.decode("utf-8", errors="replace"))
    if validated.returncode != 0:
        fail(f"mcpb validate failed (exit {validated.returncode})")
    packed = run(mcpb + ["pack", str(stage_root), str(out)], timeout=180)
    sys.stdout.write(packed.stdout.decode("utf-8", errors="replace"))
    sys.stderr.write(packed.stderr.decode("utf-8", errors="replace"))
    if packed.returncode != 0:
        fail(f"mcpb pack failed (exit {packed.returncode})")
    if not out.is_file():
        fail(f"mcpb pack did not write {out}")
    verify_packed(out, version, live)
    digest = sha256_file(out)
    sidecar = write_sha256_sidecar(out, digest)
    print(f"packed {out}")
    print(f"sha256 {digest}")
    print(f"wrote {sidecar}")


def command_stamp(args: argparse.Namespace) -> None:
    artifact = Path(args.mcpb)
    if not artifact.is_file():
        fail(f"mcpb not found: {artifact}")
    version = args.version
    if not version:
        with zipfile.ZipFile(artifact) as bundle:
            version = json.loads(bundle.read("manifest.json").decode("utf-8")).get("version")
    if not version or not isinstance(version, str):
        fail("pass --version or pack a manifest that already carries one")
    with zipfile.ZipFile(artifact) as bundle:
        packed_version = json.loads(bundle.read("manifest.json").decode("utf-8")).get("version")
    if packed_version != version:
        fail(f"packed manifest version {packed_version!r} != {version!r}")
    digest = sha256_file(artifact)
    if digest == PLACEHOLDER_SHA256:
        fail("sha256 of the bundle is the placeholder; refusing to stamp it")
    server = read_json(SERVER_JSON_PATH)
    server["version"] = version
    packages = server.get("packages")
    if not isinstance(packages, list) or len(packages) != 1:
        fail("server.json packages must be a one-element array")
    package = packages[0]
    package["registryType"] = "mcpb"
    package["identifier"] = release_asset_url(version)
    package["version"] = version
    package["fileSha256"] = digest
    package["transport"] = {"type": "stdio"}
    if server.get("description") != SERVER_DESCRIPTION or len(server["description"]) > 100:
        fail("server.json description drifted from the registry text")
    if "mcp" not in package["identifier"]:
        fail("stamped identifier does not contain 'mcp'")
    destination = Path(args.out) if args.out else None
    rendered = json.dumps(server, indent=2, ensure_ascii=False) + "\n"
    if destination:
        destination.write_text(rendered, encoding="utf-8")
        print(f"wrote {destination}")
    else:
        sys.stdout.write(rendered)
    sidecar = write_sha256_sidecar(artifact, digest)
    # Always print the digest on stderr so a redirected server.json stays pure JSON.
    print(f"{digest}  {artifact.name}", file=sys.stderr)
    print(f"wrote {sidecar}", file=sys.stderr)
    print("publish: mcp-publisher login github && mcp-publisher publish", file=sys.stderr)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Pack the jdx MCP Bundle and stamp server.json.")
    sub = parser.add_subparsers(dest="command", required=True)

    sync = sub.add_parser("sync-tools", help="Copy jdx mcp tools/list into mcpb/manifest.json")
    sync.add_argument("--launcher", help="Path to the jdx launcher (default app/build/jdx)")

    pack = sub.add_parser("pack", help="Validate and pack jdx-mcp-<version>.mcpb")
    pack.add_argument("--launcher", help="Path to the jdx launcher (default app/build/jdx)")
    pack.add_argument("--out", help="Output .mcpb path (default build/mcpb/jdx-mcp-<version>.mcpb)")
    pack.add_argument("--stage", help="Stage directory (default build/mcpb/stage)")
    pack.add_argument(
        "--strict-server-json",
        action="store_true",
        help="Also require server.json's version to equal this build (PR CI, default Gradle version)",
    )

    stamp = sub.add_parser("stamp", help="Fill server.json version and fileSha256 from a packed .mcpb")
    stamp.add_argument("--mcpb", required=True, help="Packed jdx-mcp-<version>.mcpb")
    stamp.add_argument("--version", help="Project version (default: the version inside the bundle)")
    stamp.add_argument("--out", help="Where to write the stamped server.json (default: stdout)")
    return parser


def main(argv: list[str] | None = None) -> None:
    args = build_parser().parse_args(argv)
    if args.command == "sync-tools":
        sync_tools(resolve_launcher(args.launcher))
    elif args.command == "pack":
        command_pack(args)
    elif args.command == "stamp":
        command_stamp(args)
    else:
        fail(f"unknown command {args.command}")


if __name__ == "__main__":
    main()
