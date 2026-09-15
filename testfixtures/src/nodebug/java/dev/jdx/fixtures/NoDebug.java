package dev.jdx.fixtures;

/**
 * Compiled with {@code -g:none} (see {@code testfixtures/build.gradle.kts}, the {@code noDebug}
 * source set): no line numbers, no parameter names, no local variable table. Readers must fall
 * back to synthesised names — and say so — instead of failing.
 */
@ExpectedMembers({
    "public dev.jdx.fixtures.NoDebug()",
    "public int add(int, int)",
    "public static java.lang.String describe(java.lang.String)",
})
public class NoDebug {
    public int add(int first, int second) {
        int sum = first + second;
        return sum;
    }

    public static String describe(String input) {
        return "value=" + input;
    }
}
