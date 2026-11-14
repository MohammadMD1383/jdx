@file:JvmMultifileClass
@file:JvmName("KotlinMultifile")

package dev.jdx.fixtures

/**
 * Multi-file facade part A (issue #12): bodies + `KmPackage` live on the part
 * class (`KotlinMultifile__KotlinMultifileAKt`); the umbrella
 * `KotlinMultifile` is `MULTI_FILE_FACADE` with no package carrier.
 */
fun multiPartA(): String = "a"

@JvmName("multiRenamed")
fun multiOriginal(value: Int): Int = value + 1
