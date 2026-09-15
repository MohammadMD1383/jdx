package dev.jdx.fixtures;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Carries the expected API of a fixture class, so adding a fixture adds test coverage
 * automatically (TESTING.md §5.2).
 *
 * <p>Convention (enforced by {@code FixtureCorpusTest}):
 *
 * <ul>
 *   <li>One entry per declared field, method and constructor, <b>exactly</b> as {@code javap -p}
 *       prints the member line: modifiers plus fully-qualified signature, no trailing {@code ;},
 *       no leading whitespace. Constructors are listed, with the fully-qualified class name.
 *   <li>Synthetic members ({@code this$0}, bridge methods, {@code $default} stubs,
 *       {@code $VALUES}) are listed like any other member — the differential harness (T-056)
 *       compares against {@code --include-synthetic}, so the annotation must not hide them.
 *   <li>The class initializer ({@code static {}}) is never listed: it is not a member.
 *   <li>A class declaring no members carries an explicit empty list — every source-declared
 *       fixture type must carry this annotation (anonymous/local classes, file facades and
 *       compiler-generated companions are exempt; see the test's denylist).
 * </ul>
 *
 * <p>Differential and corpus tests read these entries from the class bytes or the sources jar —
 * never by loading the class (D-017; see {@code StaticInitMarker}).
 */
@ExpectedMembers({
    "public abstract java.lang.String[] value()",
})
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExpectedMembers {
    String[] value();
}
