package dev.jdx.fixtures

/**
 * The Kotlin corpus (TESTING.md §11): every Kotlin shape whose JVM projection misleads a
 * bytecode-only reader — `suspend` (hidden `Continuation` parameter), properties (getter/setter
 * synthesis), default arguments (`$default` stubs), `internal` (name mangling), nullable types
 * (annotations, not descriptors), `object`/`companion` (static bridges), `value` classes
 * (erasure), `inline`/`reified` (call-site specialisation).
 *
 * File-facade members (`KotlinShapesKt`: [extensionGreeting], [isInstanceOf]) carry no
 * annotation — `@ExpectedMembers` targets classes. Their expectations live in
 * `FixtureCorpusTest` instead.
 */
typealias UserId = String

@ExpectedMembers(
    "private final java.lang.String name",
    "private int count",
    "private java.lang.String nickname",
    "public dev.jdx.fixtures.KotlinData(java.lang.String, int)",
    "public dev.jdx.fixtures.KotlinData(java.lang.String, int, int, kotlin.jvm.internal.DefaultConstructorMarker)",
    "public final java.lang.String getName()",
    "public final int getCount()",
    "public final void setCount(int)",
    "public final java.lang.String getGreeting()",
    "public final java.lang.String getNickname()",
    "public final void setNickname(java.lang.String)",
    "public final java.lang.String component1()",
    "public final int component2()",
    "public final dev.jdx.fixtures.KotlinData copy(java.lang.String, int)",
    "public static dev.jdx.fixtures.KotlinData copy\$default(dev.jdx.fixtures.KotlinData, java.lang.String, int, int, java.lang.Object)",
    "public java.lang.String toString()",
    "public int hashCode()",
    "public boolean equals(java.lang.Object)",
)
data class KotlinData(val name: String, var count: Int = 0) {
    val greeting: String
        get() = "hi $name"

    var nickname: String? = null
}

@ExpectedMembers(
    "public static final dev.jdx.fixtures.KotlinMembers\$Companion Companion",
    "public static final int VERSION",
    "public dev.jdx.fixtures.KotlinMembers()",
    "public final int renamedForJvm(int)",
    "public final java.lang.Object fetch(java.lang.String, kotlin.coroutines.Continuation<? super java.lang.String>)",
    "public final java.lang.String withDefault(int, java.lang.String)",
    "public static java.lang.String withDefault\$default(dev.jdx.fixtures.KotlinMembers, int, java.lang.String, int, java.lang.Object)",
    "public final java.lang.Integer nullable(java.lang.String)",
    "public final void internalHelper\$testfixtures()",
    "public final java.lang.String withDefault(int)",
    "public static final dev.jdx.fixtures.KotlinMembers create()",
)
class KotlinMembers {
    @ExpectedMembers(
        "private dev.jdx.fixtures.KotlinMembers\$Companion()",
        "public final dev.jdx.fixtures.KotlinMembers create()",
        "public dev.jdx.fixtures.KotlinMembers\$Companion(kotlin.jvm.internal.DefaultConstructorMarker)",
    )
    companion object {
        @JvmStatic
        fun create(): KotlinMembers = KotlinMembers()

        const val VERSION = 1
    }

    @JvmName("renamedForJvm")
    fun originalName(value: Int): Int = value * 2

    suspend fun fetch(id: UserId): String = "user:$id"

    @JvmOverloads
    fun withDefault(first: Int, second: String = "d"): String = "$first$second"

    fun nullable(input: String?): Int? = input?.length

    internal fun internalHelper() {
    }
}

/** Extension function: a static method on the file facade with the receiver first. */
fun KotlinMembers.extensionGreeting(): String = "hello"

/** Sealed hierarchy with a `data object` leaf. */
@ExpectedMembers(
    "private dev.jdx.fixtures.KotlinSealed()",
    "public dev.jdx.fixtures.KotlinSealed(kotlin.jvm.internal.DefaultConstructorMarker)",
)
sealed class KotlinSealed {
    @ExpectedMembers(
        "private final int x",
        "public dev.jdx.fixtures.KotlinSealed\$Sub(int)",
        "public final int getX()",
        "public final int component1()",
        "public final dev.jdx.fixtures.KotlinSealed\$Sub copy(int)",
        "public static dev.jdx.fixtures.KotlinSealed\$Sub copy\$default(dev.jdx.fixtures.KotlinSealed\$Sub, int, int, java.lang.Object)",
        "public java.lang.String toString()",
        "public int hashCode()",
        "public boolean equals(java.lang.Object)",
    )
    data class Sub(val x: Int) : KotlinSealed()

    @ExpectedMembers(
        "public static final dev.jdx.fixtures.KotlinSealed\$Empty INSTANCE",
        "private dev.jdx.fixtures.KotlinSealed\$Empty()",
        "public java.lang.String toString()",
        "public int hashCode()",
        "public boolean equals(java.lang.Object)",
    )
    data object Empty : KotlinSealed()
}

/** A `value` class: erased to its underlying type on the JVM. */
@ExpectedMembers(
    "private final java.lang.String id",
    "public final java.lang.String getId()",
    "public static java.lang.String toString-impl(java.lang.String)",
    "public java.lang.String toString()",
    "public static int hashCode-impl(java.lang.String)",
    "public int hashCode()",
    "public static boolean equals-impl(java.lang.String, java.lang.Object)",
    "public boolean equals(java.lang.Object)",
    "private dev.jdx.fixtures.UserIdBox(java.lang.String)",
    "public static java.lang.String constructor-impl(java.lang.String)",
    "public static final dev.jdx.fixtures.UserIdBox box-impl(java.lang.String)",
    "public final java.lang.String unbox-impl()",
    "public static final boolean equals-impl0(java.lang.String, java.lang.String)",
)
@JvmInline
value class UserIdBox(val id: String)

/** An `object` singleton with mutable state behind a plain method. */
@ExpectedMembers(
    "public static final dev.jdx.fixtures.KotlinRegistry INSTANCE",
    "private static final java.util.List<java.lang.Object> entries",
    "private dev.jdx.fixtures.KotlinRegistry()",
    "public final void register(java.lang.Object)",
)
object KotlinRegistry {
    private val entries = mutableListOf<Any>()

    fun register(entry: Any) {
        entries.add(entry)
    }
}

/** `inline` + `reified`: no class-file trace of `T` at the call site. */
inline fun <reified T> isInstanceOf(value: Any): Boolean = value is T
