package dev.jdx.fixtures;

/**
 * Covariant overrides. {@code javac} emits a synthetic bridge method in {@code Child} for each
 * one ({@code public Number size()} delegating to {@code public Integer size()}), so a reader
 * that ignores {@code ACC_BRIDGE} reports every method twice.
 */
@ExpectedMembers({
    "public dev.jdx.fixtures.CovariantOverrides()",
})
public class CovariantOverrides {
    @ExpectedMembers({
        "public dev.jdx.fixtures.CovariantOverrides$Base()",
        "public java.lang.Number size()",
        "public dev.jdx.fixtures.CovariantOverrides$Base copy()",
    })
    public static class Base {
        public Number size() {
            return 0;
        }

        public Base copy() {
            return this;
        }
    }

    @ExpectedMembers({
        "public dev.jdx.fixtures.CovariantOverrides$Child()",
        "public java.lang.Integer size()",
        "public dev.jdx.fixtures.CovariantOverrides$Child copy()",
        "public dev.jdx.fixtures.CovariantOverrides$Base copy()",
        "public java.lang.Number size()",
    })
    public static class Child extends Base {
        @Override
        public Integer size() {
            return 0;
        }

        @Override
        public Child copy() {
            return this;
        }
    }
}
