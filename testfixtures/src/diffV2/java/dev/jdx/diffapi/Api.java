package dev.jdx.diffapi;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The candidate version of the `jdx diff` corpus (issue #23, TESTING.md §11.2) — the
 * twin of {@code dev.jdx.diffapi.Api}, written out separately so the delta between the two
 * is what a human reads when a rule-coverage test fails.
 *
 * <p>Against v1 this fires: {@code MEMBER_REMOVED} ({@link #legacy()}),
 * {@code MEMBER_VISIBILITY_NARROWED} ({@link #narrowed()}),
 * {@code MEMBER_MADE_FINAL} ({@link #sealable()}),
 * {@code STATIC_TO_INSTANCE} ({@link #shared()}),
 * {@code PARAMETER_TYPE_CHANGED} ({@link #retyped(int)}),
 * {@code RETURN_TYPE_CHANGED} ({@link #width()}),
 * {@code FIELD_TYPE_CHANGED} ({@link #counter}), {@code CHECKED_EXCEPTION_ADDED}
 * ({@link #checked()}), {@code GENERIC_SIGNATURE_CHANGED} ({@link #echoed(List)}),
 * {@code VARARGS_CHANGED} ({@link #spread(String...)}),
 * {@code THROWS_REMOVED} + {@code ANNOTATION_DEFAULT_CHANGED} ({@link #stale()}),
 * {@code DEPRECATED_ADDED} ({@link #fresh()}), {@code ANNOTATION_ADDED}
 * ({@link #marked()}), {@code FIELD_CONSTANT_VALUE_CHANGED} ({@link #LIMIT}),
 * {@code SUPERTYPE_ADDED} ({@link Extra}), {@code MEMBER_ADDED} ({@link #born()}).
 *
 * <p>Never loaded into a test JVM (D-017) — only read as bytes from the jar.
 */
public class Api implements Contract, Extra {

    /** Target for {@code FIELD_CONSTANT_VALUE_CHANGED}: 10 in v1, 20 here. */
    public static final int LIMIT = 20;

    /** Target for {@code FIELD_TYPE_CHANGED}: an int in v1, a long here. */
    public long counter = 2L;

    /** Target for {@code MEMBER_VISIBILITY_NARROWED}: public in v1, protected here. */
    protected String narrowed() {
        return "narrowed";
    }

    /** Target for {@code MEMBER_MADE_FINAL}: not final in v1, final here. */
    public final String sealable() {
        return "sealable";
    }

    /** Target for {@code STATIC_TO_INSTANCE}: static in v1, an instance method here. */
    public String shared() {
        return "shared";
    }

    /** Target for {@code PARAMETER_TYPE_CHANGED}: a {@code String} in v1, an int here. */
    public String retyped(int value) {
        return String.valueOf(value);
    }

    /** Target for {@code RETURN_TYPE_CHANGED}: an int in v1, a long here. */
    public long width() {
        return counter;
    }

    /** Target for {@code VARARGS_CHANGED}: fixed arity in v1, varargs here. */
    public String spread(String... values) {
        return String.join(",", values);
    }

    /** Target for {@code CHECKED_EXCEPTION_ADDED}: declares nothing in v1. */
    public String checked() throws IOException {
        return "checked";
    }

    /** Target for {@code GENERIC_SIGNATURE_CHANGED}: raw here, {@code List<String>} in v1. */
    @SuppressWarnings("rawtypes")
    public List echoed(List input) {
        return input;
    }

    /** Target for {@code THROWS_REMOVED} (no {@code throws} here) and {@code MEMBER_REMOVED}. */
    public String stale() {
        return "stale";
    }

    /** Target for {@code DEPRECATED_ADDED}: not deprecated in v1, deprecated here. */
    @Deprecated
    public String fresh() {
        return "fresh";
    }

    /** Target for {@code ANNOTATION_ADDED}: bare in v1, annotated here. */
    @Deprecated
    public String marked() {
        return "marked";
    }

    /** Target for {@code MEMBER_ADDED}: no counterpart in v1. */
    public String born() {
        return "born";
    }

    /** Target for {@code SUPERTYPE_ADDED}: {@code Extra} is not in v1's supertype list. */
    public Map<String, String> table() {
        return Map.of();
    }

    /** Declared on both sides, so it must report nothing. */
    @Override
    public String required() {
        return "required";
    }

    /** Only in v2, and only because {@link Contract} added it — nothing to report here. */
    @Override
    public String alsoRequired() {
        return "also-required";
    }

    /** Only in v2, and only because {@link Extra} is new — nothing to report here. */
    @Override
    public String extra() {
        return "extra";
    }
}
