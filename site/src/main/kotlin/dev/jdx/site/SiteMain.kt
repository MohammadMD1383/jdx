package dev.jdx.site

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.SimpleFileServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import kotlin.system.exitProcess

/**
 * Entry point of `:site:buildSite` and `:site:serveSite`. Arguments are `--name value` pairs
 * (see site/build.gradle.kts); a build failure prints every problem and exits 1.
 */
fun main(args: Array<String>) {
    val arguments = parseArguments(args)
    arguments["serve"]?.let { directory ->
        serve(File(directory), arguments.getValue("base-url"), arguments["port"]?.toInt() ?: 8000)
        return
    }
    val outputDir = File(arguments.getValue("out"))
    val workDir = File(arguments.getValue("work"))
    val sandbox = File(workDir, "sandbox").also { it.deleteRecursively(); it.mkdirs() }
    val options = SiteOptions(
        repoRoot = File(arguments.getValue("repo")),
        outputDir = outputDir,
        baseUrl = arguments.getValue("base-url").let { if (it.endsWith("/")) it else "$it/" },
        version = arguments["version"],
        releasesJson = arguments["releases"]?.let(::File),
        googleVerification = arguments["google-verification"],
        bingVerification = arguments["bing-verification"],
    )
    val runner = ProcessJdxRunner(
        launcher = File(arguments.getValue("jdx")),
        sandboxDir = sandbox,
        javaHome = System.getProperty("java.home"),
    )
    val started = System.nanoTime()
    try {
        val output = SiteBuilder(options, runner).build()
        SiteBuilder.write(output, outputDir)
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        val bytes = output.files.values.sumOf { it.size }
        println("site: ${output.pages.size} pages, ${output.files.size} files, ${bytes / 1024} KiB -> $outputDir (%.1fs)".format(seconds))
    } catch (failure: SiteBuildException) {
        System.err.println("site build failed:\n${failure.message}")
        exitProcess(1)
    }
}

private fun parseArguments(args: Array<String>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val name = args[index].removePrefix("--")
        require(args[index].startsWith("--") && index + 1 < args.size) { "expected --name value at '${args[index]}'" }
        result[name] = args[index + 1]
        index += 2
    }
    return result
}

/** Local preview at the same base path as production, so root-relative links behave identically. */
private fun serve(directory: File, baseUrl: String, port: Int) {
    val basePath = URI(baseUrl).path.ifEmpty { "/" }
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.createContext(basePath.removeSuffix("/").ifEmpty { "/" }, SimpleFileServer.createFileHandler(directory.absoluteFile.toPath()))
    if (basePath != "/") {
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Location", basePath)
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
    }
    server.start()
    println("site: serving $directory at http://127.0.0.1:$port$basePath (Ctrl+C to stop)")
}

