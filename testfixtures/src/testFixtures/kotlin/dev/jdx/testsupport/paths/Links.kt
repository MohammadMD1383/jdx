package dev.jdx.testsupport.paths

import java.nio.file.Files
import java.nio.file.Path

/**
 * Links [link] to [target], copying when the host cannot symlink.
 *
 * Windows without Developer Mode (or without `SeCreateSymbolicLinkPrivilege`)
 * refuses `createSymbolicLink`: tests staging fixture jars (the Kotlin sidecar
 * set) would skip there, hollowing the suites and the coverage gates that
 * count on them. A copy reads identically — only slower and fatter — so the
 * link stays first choice and the copy is the fallback, never a skip. A
 * failed copy throws: missing disk is a failure, not an assumption.
 */
public fun symlinkOrCopy(link: Path, target: Path) {
    try {
        Files.createSymbolicLink(link, target)
        return
    } catch (e: UnsupportedOperationException) {
        // Fall through to the copy below.
    } catch (e: java.io.IOException) {
        // Fall through to the copy below (Windows privilege refusal lands here).
    } catch (e: SecurityException) {
        // Fall through to the copy below.
    }
    runCatching { Files.deleteIfExists(link) }
    Files.copy(target, link)
}
