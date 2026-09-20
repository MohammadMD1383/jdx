package dev.jdx.decompile

import java.nio.file.Path
import java.util.jar.Manifest
import org.jetbrains.java.decompiler.api.Decompiler
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IResultSaver
import org.jetbrains.java.decompiler.struct.DirectoryContextSource

/**
 * The single Vineflower call site in the project. Loaded either inside the
 * isolated [ChildFirstClassLoader] (normal path, via
 * [VineflowerDecompiler.loadIsolatedRunner]) or in-process (fallback) — both
 * go through the loader-agnostic [VineflowerRunner] interface, so no
 * Vineflower type ever crosses a classloader boundary.
 *
 * Two probe-learned facts this class depends on (verified against 1.12.0):
 * a staged single class must enter through [DirectoryContextSource] (the
 * single-file source is package-private), and directory inputs report through
 * [IResultSaver.saveClassFile] — not `saveClassEntry`.
 */
internal class VineflowerRunnerImpl : VineflowerRunner {

    override fun decompile(stagedDir: Path, libraries: List<Path>): Map<String, String> {
        val saver = CapturingSaver()
        val builder = Decompiler.builder()
            .inputs(DirectoryContextSource(null, stagedDir.toFile()))
            .output(saver)
            .logger(IFernflowerLogger.NO_OP)
        val libs = libraries.mapNotNull { runCatching { it.toFile() }.getOrNull() }
            .filter { it.exists() }
        if (libs.isNotEmpty()) {
            builder.libraries(*libs.toTypedArray())
        }
        builder.build().decompile()
        return saver.classes
    }

    private class CapturingSaver : IResultSaver {
        val classes: MutableMap<String, String> = linkedMapOf()

        override fun saveClassFile(
            path: String,
            qualifiedName: String,
            entryName: String,
            content: String,
            mapping: IntArray?,
        ) {
            classes[qualifiedName] = content
        }

        override fun saveFolder(path: String): Unit = Unit

        override fun copyFile(source: String, path: String, entryName: String): Unit = Unit

        override fun saveClassEntry(
            path: String,
            archiveName: String,
            qualifiedName: String,
            entryName: String,
            content: String,
        ) {
            classes[qualifiedName] = content
        }

        override fun createArchive(path: String, archiveName: String, manifest: Manifest?): Unit = Unit

        override fun saveDirEntry(path: String, archiveName: String, entryName: String): Unit = Unit

        override fun copyEntry(source: String, path: String, archiveName: String, entryName: String): Unit = Unit

        override fun closeArchive(path: String, archiveName: String): Unit = Unit

        override fun close(): Unit = Unit
    }
}
