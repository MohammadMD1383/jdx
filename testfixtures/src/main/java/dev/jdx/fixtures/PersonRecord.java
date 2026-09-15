package dev.jdx.fixtures;

/** A record with a compact constructor: accessors, canonical constructor and validation. */
@ExpectedMembers({
    "private final java.lang.String name",
    "private final int age",
    "public dev.jdx.fixtures.PersonRecord(java.lang.String, int)",
    "public final java.lang.String toString()",
    "public final int hashCode()",
    "public final boolean equals(java.lang.Object)",
    "public java.lang.String name()",
    "public int age()",
})
public record PersonRecord(String name, int age) {
    public PersonRecord {
        if (age < 0) {
            throw new IllegalArgumentException("age");
        }
    }
}
