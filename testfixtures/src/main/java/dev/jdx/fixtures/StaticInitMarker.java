package dev.jdx.fixtures;

/**
 * The D-017 proof: this class must <em>never</em> be loaded into a test JVM. Its static
 * initialiser writes a marker file, so any test that triggers initialisation — loading the
 * class, reading an annotation off it, even {@code Class.forName} — leaves evidence.
 *
 * <p>Rules for this file's neighbours:
 *
 * <ul>
 *   <li>Never reference this class from test code. Not reflectively, not in a comment that
 *       becomes an import.
 *   <li>Read its bytes with {@code Fixtures.classBytes(...)} (a zip read), never with a
 *       {@code ClassLoader}.
 *   <li>{@code javap} is safe: it parses class files without running initialisers.
 * </ul>
 */
@ExpectedMembers({
    "static final java.lang.String MARKER_PATH",
    "public dev.jdx.fixtures.StaticInitMarker()",
    "public static java.lang.String markerPath()",
    "public int answer()",
})
public class StaticInitMarker {
    static final String MARKER_PATH =
            System.getProperty("java.io.tmpdir") + "/jdx-fixture-static-init-marker";

    static {
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of(MARKER_PATH), "StaticInitMarker.<clinit> ran\n");
        } catch (Exception ignored) {
            // Best effort: the proof only needs the file to appear when initialisation runs.
        }
    }

    public static String markerPath() {
        return MARKER_PATH;
    }

    public int answer() {
        return 42;
    }
}
