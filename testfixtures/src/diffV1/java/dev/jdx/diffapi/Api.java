package dev.jdx.diffapi;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * The baseline of the `jdx diff` corpus (issue #23, TESTING.md §11.2).
 *
 * <p>Every declaration here exists so that <em>some</em> rule in
 * {@code core/diff/CompatRule.kt} fires against the v2 version of this class. The v2
 * twin is a separate source tree in a separate package, not a patched copy of this
 * one, so the delta is readable by a human reviewing a failing rule-coverage test.
 *
 * <p>Which rules this class targets:
 * {@code MEMBER_REMOVED}, {@code MEMBER_VISIBILITY_NARROWED},
 * {@code MEMBER_MADE_FINAL}, {@code STATIC_TO_INSTANCE}, {@code PARAMETER_TYPE_CHANGED},
 * {@code RETURN_TYPE_CHANGED}, {@code FIELD_TYPE_CHANGED}, {@code CHECKED_EXCEPTION_ADDED},
 * {@code GENERIC_SIGNATURE_CHANGED}, {@code VARARGS_CHANGED}, {@code DEPRECATED_ADDED},
 * {@code ANNOTATION_ADDED}, {@code FIELD_CONSTANT_VALUE_CHANGED},
 * {@code ANNOTATION_DEFAULT_CHANGED}, {@code SUPERTYPE_ADDED},
 * {@code INTERFACE_METHOD_ADDED} (in {@link Contract}).
 *
 * <p>Never loaded into a test JVM (D-017) — only read as bytes from the jar.
 */
@SuppressWarnings("deprecation")
public class Api implements Serializable, Contract {

    /** Target for {@code FIELD_TYPE_CHANGED} and {@code FIELD_CONSTANT_VALUE_CHANGED}. */
    public static final int LIMIT = 10;

    /** Target for {@code FIELD_TYPE_CHANGED}: an int here, a long in v2. */
    public int counter = 1;

    /** Target for {@code MEMBER_REMOVED}: gone in v2. */
    public String legacy() {
        return "legacy";
    }

    /** Target for {@code MEMBER_VISIBILITY_NARROWED}: public here, protected in v2. */
    public String narrowed() {
        return "narrowed";
    }

    /** Target for {@code MEMBER_MADE_FINAL}: not final here, final in v2. */
    public String sealable() {
        return "sealable";
    }

    /** Target for {@code STATIC_TO_INSTANCE}: static here, an instance method in v2. */
    public static String shared() {
        return "shared";
    }

    /** Target for {@code PARAMETER_TYPE_CHANGED}: a {@code String} here, an int in v2. */
    public String retyped(String value) {
        return value;
    }

    /** Target for {@code RETURN_TYPE_CHANGED}: an int here, a long in v2. */
    public int width() {
        return counter;
    }

    /** Target for {@code VARARGS_CHANGED}: fixed arity here, varargs in v2. */
    public String spread(String value) {
        return value;
    }

    /** Target for {@code CHECKED_EXCEPTION_ADDED}: declares nothing here. */
    public String checked() {
        return "checked";
    }

    /** Target for {@code GENERIC_SIGNATURE_CHANGED}: {@code List<String>} here, raw in v2. */
    public List<String> echoed(List<String> input) {
        return input;
    }

    /** Target for {@code THROWS_REMOVED} and {@code ANNOTATION_DEFAULT_CHANGED}. */
    @Deprecated
    public String stale() throws IOException {
        return "stale";
    }

    /** Target for {@code DEPRECATED_ADDED}: not deprecated here, deprecated in v2. */
    public String fresh() {
        return "fresh";
    }

    /** Target for {@code ANNOTATION_ADDED}: bare here, annotated in v2. */
    public String marked() {
        return "marked";
    }

    /** Target for {@code SUPERTYPE_ADDED}: v2 adds {@code Extra} to this list. */
    public Map<String, String> table() {
        return Map.of();
    }

    /** Target for {@code INTERFACE_METHOD_ADDED} on {@link Contract}, declared here. */
    @Override
    public String required() {
        return "required";
    }
}
