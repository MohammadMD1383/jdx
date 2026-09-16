package dev.jdx.core.render

import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.resolve.publicField
import dev.jdx.core.resolve.publicMethod
import dev.jdx.core.resolve.testFieldSignature
import dev.jdx.core.resolve.testMethodDescriptor
import dev.jdx.core.resolve.testMethodSignature
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * One-line source-style signatures with fully-qualified types (T-010, D-028).
 *
 * Types print fully qualified (`$`-joined nesting) so every line is mechanically
 * canonical — two types sharing a simple name must never print identically.
 * Unknown parameter names degrade to `argN` per the proposal's ladder (§16).
 */
class SignatureLineTest {

    @Test
    fun `plain method with named parameters`() {
        val member = publicMethod("add", "(II)I").copy(
            parameterNames = listOf("a", "b"),
        )
        SignatureLines.methodLine(member) shouldBe "public int add(int a, int b)"
    }

    @Test
    fun `unknown parameter names degrade to argN`() {
        val member = publicMethod("add", "(II)I").copy(
            parameterNames = listOf(null, null),
        )
        SignatureLines.methodLine(member) shouldBe "public int add(int arg0, int arg1)"
    }

    @Test
    fun `mixed known and unknown names keep what the class file had`() {
        val member = publicMethod(
            "toJson",
            "(Ljava/lang/Object;Ljava/lang/Appendable;)V",
        ).copy(parameterNames = listOf("src", null))
        SignatureLines.methodLine(member) shouldBe
            "public void toJson(java.lang.Object src, java.lang.Appendable arg1)"
    }

    @Test
    fun `generic substitution shows the substituted types`() {
        // class StringList extends ArrayList<String>: add(E) resolves to add(String).
        val member = publicMethod(
            "add",
            "(Ljava/lang/Object;)Z",
            genericSignature = "(TE;)Z",
        )
        val substituted = testMethodSignature("(Ljava/lang/String;)Z")
        SignatureLines.methodLine(member, substituted) shouldBe
            "public boolean add(java.lang.String arg0)"
    }

    @Test
    fun `method type parameters print before the return type`() {
        val member = publicMethod(
            "fromJson",
            "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
            genericSignature = "<T:Ljava/lang/Object;>(Ljava/lang/String;Ljava/lang/Class<TT;>;)TT;",
        )
        SignatureLines.methodLine(member) shouldBe
            "public <T> T fromJson(java.lang.String arg0, java.lang.Class<T> arg1)"
    }

    @Test
    fun `varargs print with ellipsis on the last parameter`() {
        val member = publicMethod("of", "([Ljava/lang/String;)V").copy(
            access = Access.of(AccessFlag.PUBLIC, AccessFlag.VARARGS),
            parameterNames = listOf("elements"),
        )
        SignatureLines.methodLine(member) shouldBe "public void of(java.lang.String... elements)"
    }

    @Test
    fun `throws types print after the parameter list`() {
        val member = MethodInfo(
            name = "read",
            descriptor = testMethodDescriptor("()V"),
            access = Access.of(AccessFlag.PUBLIC),
            throwsTypes = listOf(
                dev.jdx.core.model.typeNameFromBinaryName("java.io.IOException"),
            ),
        )
        SignatureLines.methodLine(member) shouldBe "public void read() throws java.io.IOException"
    }

    @Test
    fun `constructor prints the simple class name`() {
        val member = MethodInfo(
            name = "<init>",
            descriptor = testMethodDescriptor("(Ljava/lang/String;I)V"),
            access = Access.of(AccessFlag.PUBLIC),
            parameterNames = listOf("name", "size"),
        )
        SignatureLines.methodLine(member, declaringSimpleName = "Widget") shouldBe
            "public Widget(java.lang.String name, int size)"
    }

    @Test
    fun `modifiers print in JLS order without ACC_SUPER noise`() {
        val member = publicMethod("run", "()V").copy(
            access = Access.of(
                AccessFlag.PUBLIC,
                AccessFlag.STATIC,
                AccessFlag.FINAL,
                AccessFlag.SYNCHRONIZED,
            ),
        )
        SignatureLines.methodLine(member) shouldBe "public static final synchronized void run()"
    }

    @Test
    fun `field prints modifiers type name and constant value`() {
        val member = publicField("serialVersionUID", "J").copy(
            access = Access.of(AccessFlag.PRIVATE, AccessFlag.STATIC, AccessFlag.FINAL),
            constantValue = "1",
        )
        SignatureLines.fieldLine(member) shouldBe "private static final long serialVersionUID = 1"
    }

    @Test
    fun `generic field prints the substituted type`() {
        val member = publicField(
            "names",
            "Ljava/util/List;",
            genericSignature = "Ljava/util/List<Ljava/lang/String;>;",
        )
        val substituted = testFieldSignature("Ljava/util/List<Ljava/lang/String;>;")
        SignatureLines.fieldLine(member, substituted) shouldBe
            "public java.util.List<java.lang.String> names"
    }

    @Test
    fun `annotation element default prints after the signature`() {
        val member = publicMethod("names", "()[Ljava/lang/String;").copy(annotationDefault = "{}")
        SignatureLines.methodLine(member) shouldBe
            "public java.lang.String[] names() default {}"
    }

    @Test
    fun `wildcard bounds print source-style`() {
        val member = publicMethod(
            "addAll",
            "(Ljava/util/Collection;)Z",
            genericSignature = "(Ljava/util/Collection<+TT;>;)Z",
        )
        // Unsubstituted: the type variable stays, the wildcard bound prints.
        SignatureLines.methodLine(member) shouldBe
            "public boolean addAll(java.util.Collection<? extends T> arg0)"
    }

    @Test
    fun `erased overloads keep descriptors distinct in text`() {
        // The canonical ref (not the signature line) carries disambiguation;
        // the line itself just renders what the class file declares.
        val erased = publicMethod("get", "()Ljava/lang/Object;")
        SignatureLines.methodLine(erased) shouldBe "public java.lang.Object get()"
    }
}
