package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.BaseCliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.jdx.index.artifact.MultiRelease

/**
 * `--release` / `--multi-release` on every command that opens artifacts.
 *
 * Spelled like javac (`--release`) and javap (`--multi-release`): one option,
 * two names. Omitted, the running JDK feature is selected — the same bytes
 * and the same `MULTI_RELEASE_VARIANT` sentence as before this flag existed.
 * `8` selects base entries only. Below [MultiRelease.MIN_SELECTABLE] is exit 3.
 *
 * Not a root flag: `jdx --release 11 mcp` would claim a selection the server
 * does not apply. An explicit value forces the cold path ([dev.jdx.cli.WarmRoots]);
 * the warm daemon, MCP and HTTP stay on the running JDK.
 */
internal fun BaseCliktCommand<*>.releaseOption() =
    option(
        "--release",
        "--multi-release",
        help = "Multi-release jar feature to serve, matching javap --multi-release. " +
            "The highest META-INF/versions/N at or below N wins; 8 selects base entries only. " +
            "Omitted, the running JDK feature is used. " +
            "Values below ${MultiRelease.MIN_SELECTABLE} are a usage error. " +
            "An explicit value stays in-process; the warm daemon keeps the running JDK.",
    ).int()
