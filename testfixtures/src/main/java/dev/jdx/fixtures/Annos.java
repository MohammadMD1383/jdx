package dev.jdx.fixtures;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@ExpectedMembers({
    "public abstract java.lang.String value()",
})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Tags.class)
@interface Tag {
    String value();
}

@ExpectedMembers({
    "public abstract dev.jdx.fixtures.Tag[] value()",
})
@Retention(RetentionPolicy.RUNTIME)
@interface Tags {
    Tag[] value();
}

@ExpectedMembers({
    "public abstract java.lang.String[] names()",
    "public abstract int[] codes()",
    "public abstract java.lang.Class<?>[] types()",
})
@Retention(RetentionPolicy.RUNTIME)
@interface Matrix {
    String[] names() default {};

    int[] codes() default {};

    Class<?>[] types() default {};
}

/** Repeatable and array-valued annotations, including {@code Class}-valued elements. */
@Tag("alpha")
@Tag("beta")
@Matrix(names = {"x", "y"}, codes = {1, 2}, types = {String.class, int.class})
@ExpectedMembers({
    "public dev.jdx.fixtures.Annos()",
    "public void tagged()",
})
public class Annos {
    @Tag("single")
    public void tagged() { }
}
