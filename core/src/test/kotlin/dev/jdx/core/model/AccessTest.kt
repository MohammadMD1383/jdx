package dev.jdx.core.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Tests for [Access] and [AccessFlag] — the JVM access-flag model, JVMS tables 4.1-B/4.6-A/4.7-A. */
class AccessTest {

    @Test
    fun `visibility derives from the jls modifiers with package-private as the default`() {
        Access.of(AccessFlag.PUBLIC).visibility shouldBe Visibility.PUBLIC
        Access.of(AccessFlag.PROTECTED, AccessFlag.STATIC).visibility shouldBe Visibility.PROTECTED
        Access.of(AccessFlag.PRIVATE, AccessFlag.FINAL).visibility shouldBe Visibility.PRIVATE
        Access.NONE.visibility shouldBe Visibility.PACKAGE_PRIVATE
    }

    @Test
    fun `the jvm access mask round-trips through of`() {
        Access.of(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL).mask shouldBe 0x0019
        Access.of(AccessFlag.ABSTRACT).mask shouldBe 0x0400
    }

    @Test
    fun `bridge and varargs share their mask bits with volatile and transient by jvm design`() {
        // 0x0040 is ACC_VOLATILE for fields and ACC_BRIDGE for methods; 0x0080 is
        // ACC_TRANSIENT / ACC_VARARGS. `Access` stores a mask and defers the meaning to
        // the query — this test pins that documented behaviour.
        val method = Access.of(AccessFlag.SYNTHETIC, AccessFlag.BRIDGE, AccessFlag.VARARGS)
        method.has(AccessFlag.BRIDGE) shouldBe true
        method.has(AccessFlag.VARARGS) shouldBe true
        method.has(AccessFlag.VOLATILE) shouldBe true // same bit, 0x0040
        method.has(AccessFlag.TRANSIENT) shouldBe true // same bit, 0x0080
        method.mask shouldBe 0x10C0
    }

    @Test
    fun `has reports absent flags`() {
        Access.of(AccessFlag.PUBLIC).has(AccessFlag.STATIC) shouldBe false
        Access.NONE.has(AccessFlag.PUBLIC) shouldBe false
    }

    @Test
    fun `none is the zero mask`() {
        Access.NONE.mask shouldBe 0
    }
}
