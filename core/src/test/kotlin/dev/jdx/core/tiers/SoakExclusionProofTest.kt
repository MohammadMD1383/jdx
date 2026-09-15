package dev.jdx.core.tiers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The deliberate proof that a `soak`-tagged test cannot run in tier 1 or tier 2 (T-053).
 *
 * `@Tag("soak")` excludes this class from `test` (tier 1) and from `tier2Test` (tier 2, via
 * `check`): if the exclusion ever broke, this test would execute without the `jdx.tier`
 * system property — which only the `soakTest` task sets — and fail the build loudly instead
 * of silently slowing the fast loop. Conversely, a green `./gradlew soak` proves the soak
 * task does pick soak-tagged tests up. Either direction of breakage turns red; there is no
 * silent mode.
 */
@Tag("soak")
class SoakExclusionProofTest {

    @Test
    fun `soak-tagged tests run only under the soak task`() {
        assertEquals(
            "soak",
            System.getProperty("jdx.tier"),
            "A @Tag(\"soak\") test executed outside `./gradlew soak`: " +
                "tier exclusion is broken (T-053). `soakTest` is the only task " +
                "that sets -Djdx.tier=soak.",
        )
    }
}
