/**
 * # `:decompile`
 *

 * Reconstructs readable source for classes that ship without a sources jar.
 *
 * `DecompilerEngine` has two implementations: **Vineflower** (default — readable Java) and
 * **javap** (exact JVM instructions, for when the question really is about bytecode). D-002.
 *
 * ## Non-negotiable
 *
 * Decompiled output is **always labelled as reconstructed**. An agent that knows a body was
 * decompiled will not quote it as canonical; an agent that does not, will. Never let
 * decompiler output reach a renderer without its `Provenance`.
 *
 * Vineflower is loaded lazily in an isolated classloader — the common path (sources exist, no
 * decompilation needed) must not pay for it. Results are cached on disk keyed by
 * `(class hash, engine, engine version)`, because agents typically ask for several members of
 * the same class in a row.
 */
package dev.jdx.decompile
