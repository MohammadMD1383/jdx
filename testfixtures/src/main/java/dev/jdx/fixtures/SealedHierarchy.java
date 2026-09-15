package dev.jdx.fixtures;

/** A sealed interface with record implementations: {@code PermittedSubclasses} attribute. */
@ExpectedMembers({})
public sealed interface SealedHierarchy permits SealedHierarchy.Circle, SealedHierarchy.Rect {
    @ExpectedMembers({
        "private final double radius",
        "public dev.jdx.fixtures.SealedHierarchy$Circle(double)",
        "public final java.lang.String toString()",
        "public final int hashCode()",
        "public final boolean equals(java.lang.Object)",
        "public double radius()",
    })
    record Circle(double radius) implements SealedHierarchy { }

    @ExpectedMembers({
        "private final double width",
        "private final double height",
        "public dev.jdx.fixtures.SealedHierarchy$Rect(double, double)",
        "public final java.lang.String toString()",
        "public final int hashCode()",
        "public final boolean equals(java.lang.Object)",
        "public double width()",
        "public double height()",
    })
    record Rect(double width, double height) implements SealedHierarchy { }
}
