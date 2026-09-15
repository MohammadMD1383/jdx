package dev.jdx.fixtures;

/**
 * Varargs, {@code synchronized}/{@code native}/{@code strictfp}, {@code @Deprecated(forRemoval)},
 * and non-public members: the flag combinations readers must reproduce exactly.
 */
@ExpectedMembers({
    "private static final long serialVersionUID",
    "public dev.jdx.fixtures.VarargsAndModifiers()",
    "public static java.lang.String join(java.lang.String, java.lang.String...)",
    "public synchronized void syncMethod()",
    "public native int nativeMethod()",
    "public double fp(double)",
    "public void oldApi()",
    "void packagePrivate()",
    "private void hidden()",
})
public class VarargsAndModifiers {
    private static final long serialVersionUID = 1L;

    public static String join(String separator, String... parts) {
        return String.join(separator, parts);
    }

    public synchronized void syncMethod() { }

    public native int nativeMethod();

    // Declared `strictfp`, but `javap -p` prints no flag: since JEP 306 (JDK 17) every
    // floating-point operation is strict by default and `javac` no longer emits `ACC_STRICT`.
    // The entry below is the printed truth, not the source spelling.
    public strictfp double fp(double x) {
        return x * 1.5;
    }

    @Deprecated(forRemoval = true, since = "99")
    public void oldApi() { }

    void packagePrivate() { }

    private void hidden() { }
}
