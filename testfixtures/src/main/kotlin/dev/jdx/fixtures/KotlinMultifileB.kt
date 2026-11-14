@file:JvmMultifileClass
@file:JvmName("KotlinMultifile")

package dev.jdx.fixtures

/**
 * Multi-file facade part B (issue #12): suspend + plain members on a second
 * part sharing the `KotlinMultifile` umbrella.
 */
suspend fun multiSuspend(id: String): String = "multi:$id"

fun multiPartB(): Int = 2
