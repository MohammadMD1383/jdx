package dev.jdx.fixtures;

/**
 * Every nesting shape: static-nested, inner, local and anonymous classes, each with its own
 * class file ({@code Nesting$StaticNested}, {@code Nesting$Inner}, {@code Nesting$1Local},
 * {@code Nesting$1}).
 */
@ExpectedMembers({
    "public dev.jdx.fixtures.Nesting()",
    "public java.lang.Object make()",
})
public class Nesting {
    @ExpectedMembers({
        "public dev.jdx.fixtures.Nesting$StaticNested()",
        "public int nestedValue()",
    })
    public static class StaticNested {
        public int nestedValue() {
            return 1;
        }
    }

    @ExpectedMembers({
        "final dev.jdx.fixtures.Nesting this$0",
        "public dev.jdx.fixtures.Nesting$Inner(dev.jdx.fixtures.Nesting)",
        "public dev.jdx.fixtures.Nesting outer()",
    })
    public class Inner {
        public Nesting outer() {
            return Nesting.this;
        }
    }

    public Object make() {
        class Local {
            int localValue() {
                return 2;
            }
        }
        Local local = new Local();
        Runnable anon = new Runnable() {
            @Override
            public void run() { }
        };
        return local.localValue() == 2 ? anon : anon;
    }
}
