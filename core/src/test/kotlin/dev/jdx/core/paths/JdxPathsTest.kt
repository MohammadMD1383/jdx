package dev.jdx.core.paths

import java.nio.file.Path
import org.junit.jupiter.api.Test
import io.kotest.matchers.shouldBe

class JdxPathsTest {

    private val home: Path = Path.of("/home/alice")

    @Test
    fun `linux cache defaults to XDG or dot dir`() {
        JdxPaths.cacheRoot(home, JdxOs.LINUX, emptyMap()) shouldBe home.resolve(".cache/jdx")
        JdxPaths.cacheRoot(home, JdxOs.LINUX, mapOf("XDG_CACHE_HOME" to "/tmp/xdg-cache")) shouldBe
            Path.of("/tmp/xdg-cache/jdx")
    }

    @Test
    fun `macos cache lives under Library Caches`() {
        JdxPaths.cacheRoot(home, JdxOs.MACOS, emptyMap()) shouldBe home.resolve("Library/Caches/jdx")
    }

    @Test
    fun `windows cache lives under LOCALAPPDATA`() {
        val env = mapOf("LOCALAPPDATA" to "C:\\Users\\alice\\AppData\\Local")
        JdxPaths.cacheRoot(Path.of("C:\\Users\\alice"), JdxOs.WINDOWS, env) shouldBe
            Path.of("C:\\Users\\alice\\AppData\\Local/jdx/cache")
    }

    @Test
    fun `explicit and JDX env override OS defaults`() {
        val explicit = Path.of("/tmp/custom-cache")
        JdxPaths.cacheRoot(home, JdxOs.LINUX, emptyMap(), explicit) shouldBe explicit
        JdxPaths.cacheRoot(home, JdxOs.MACOS, mapOf("JDX_CACHE_DIR" to "/tmp/jdx-cache")) shouldBe
            Path.of("/tmp/jdx-cache")
    }

    @Test
    fun `os detection branches in one place`() {
        JdxPaths.detectOs("Linux") shouldBe JdxOs.LINUX
        JdxPaths.detectOs("Mac OS X") shouldBe JdxOs.MACOS
        JdxPaths.detectOs("Windows 11") shouldBe JdxOs.WINDOWS
    }

    @Test
    fun `linux config defaults to XDG or dot dir`() {
        JdxPaths.configRoot(home, JdxOs.LINUX, emptyMap()) shouldBe home.resolve(".config/jdx")
        JdxPaths.configRoot(home, JdxOs.LINUX, mapOf("XDG_CONFIG_HOME" to "/tmp/xdg-config")) shouldBe
            Path.of("/tmp/xdg-config/jdx")
    }

    @Test
    fun `macos and windows config roots are native`() {
        JdxPaths.configRoot(home, JdxOs.MACOS, emptyMap()) shouldBe
            home.resolve("Library/Application Support/jdx")
        val winHome = Path.of("C:\\Users\\alice")
        val env = mapOf("APPDATA" to "C:\\Users\\alice\\AppData\\Roaming")
        JdxPaths.configRoot(winHome, JdxOs.WINDOWS, env) shouldBe
            Path.of("C:\\Users\\alice\\AppData\\Roaming/jdx")
    }

    @Test
    fun `runtime dir prefers XDG then TMPDIR then cache run`() {
        val cache = Path.of("/home/alice/.cache/jdx")
        JdxPaths.runtimeDir(home, JdxOs.LINUX, mapOf("XDG_RUNTIME_DIR" to "/run/user/1000"), cache) shouldBe
            Path.of("/run/user/1000/jdx")
        JdxPaths.runtimeDir(
            home, JdxOs.LINUX, mapOf("TMPDIR" to "/tmp"), cache, uid = "1000",
        ) shouldBe Path.of("/tmp/jdx-1000")
        JdxPaths.runtimeDir(home, JdxOs.LINUX, emptyMap(), cache) shouldBe cache.resolve("run")
        JdxPaths.runtimeDir(
            home, JdxOs.MACOS, mapOf("TMPDIR" to "/var/folders/zz"), cache, uid = "501",
        ) shouldBe Path.of("/var/folders/zz/jdx-501")
        val winCache = Path.of("C:\\Users\\alice\\AppData\\Local/jdx/cache")
        JdxPaths.runtimeDir(
            Path.of("C:\\Users\\alice"), JdxOs.WINDOWS,
            mapOf("LOCALAPPDATA" to "C:\\Users\\alice\\AppData\\Local"), winCache,
        ) shouldBe Path.of("C:\\Users\\alice\\AppData\\Local/jdx/run")
    }

    @Test
    fun `JDX runtime env wins everywhere`() {
        val cache = Path.of("/tmp/cache")
        JdxPaths.runtimeDir(home, JdxOs.LINUX, mapOf("JDX_RUNTIME_DIR" to "/tmp/jdx-run"), cache) shouldBe
            Path.of("/tmp/jdx-run")
    }

    @Test
    fun `legacy helpers name the dot dirs`() {
        JdxPaths.legacyCacheRoot(home) shouldBe home.resolve(".cache/jdx")
        JdxPaths.legacyConfigRoot(home) shouldBe home.resolve(".config/jdx")
        JdxPaths.needsMigration(home.resolve(".cache/jdx"), home.resolve(".cache/jdx")) shouldBe false
        JdxPaths.needsMigration(
            home.resolve(".cache/jdx"), home.resolve("Library/Caches/jdx"),
        ) shouldBe true
    }
}
