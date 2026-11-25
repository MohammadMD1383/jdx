---
date: 2026-11-25
---

# Reading Minecraft mod internals with jdx

jdx reads a Minecraft mod jar in the names the file already stores. It does not remap. Pass the Loom jar with `--jars`. Diff only inside one mapping namespace.

## The names in the file are the names you get

v1 does not read Tiny, SRG, or ProGuard mappings, and it does not rename classes. Yarn, Mojmap, intermediary, and obfuscated names come back as they are stored. Pass the jar Fabric Loom or NeoForge ModDevGradle already wrote. An obfuscated jar stays obfuscated.

jdx never runs Gradle or Maven. Inside a project, with no classpath flags, it discovers `build/classes` and dependency jars named in the build scripts. The remapped Minecraft jar is usually not one of those coordinates, so pass it with `--jars`. `--no-jdk` leaves the JDK out, so usages and callers are not full of `java.*`.

```bash
jdx members net.minecraft.world.item.ItemStack --declared --kind method --limit 20 --jars "$MAPPED_JAR" --no-jdk
```

`jdx signature` prints each overload as a canonical ref. Paste that ref into `jdx body` or `jdx source`. Do not retype parameter types by hand: Yarn and Mojmap spell them differently, and the jar you passed is the authority.

`jdx callers` finds call sites. Pass every jar that might contain a call. It is override-aware unless you pass `--exact`, which keeps invoke-owner matching. Picking the class and the injection point is the mixin guide linked below.

## Diff two jars that already share names

`jdx diff` takes the two artifacts as its arguments. It has no `--jars` flag, and it does not remap. Both sides must already use the same names. Compare two Yarn jars, or two Mojmap jars, or two builds of your mod compiled in one namespace. A Yarn jar against a Mojmap jar, or an intermediary jar against a named jar, shares almost no names. The report then looks like nearly every member was removed and re-added, and that comparison is meaningless.

```bash
jdx diff "$OLD_MAPPED_JAR" "$NEW_MAPPED_JAR" --severity breaking --limit 40
```

The docs build has no Minecraft jar, so these recipes are not executed. The same commands against an ordinary library jar are in the guides below.

- [Inspect a Minecraft mod jar](../guide/minecraft-mods.md)
- [See what changed between two Minecraft or mod versions](../guide/minecraft-version-diff.md)
- [Find a mixin target](../guide/minecraft-mixin-targets.md)
- [Classpath and workspaces](../guide/classpath.md)
