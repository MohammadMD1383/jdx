package dev.jdx.core.resolve

import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.ClassTypeSignature
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.KotlinMethodView
import dev.jdx.core.model.KotlinPropertyView
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.ThrowsSignature
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.Visibility
import dev.jdx.core.model.kotlinViewKey
import dev.jdx.core.model.typeNameFromBinaryName
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Example tests for the `--inherited` core (PROPOSAL.md §9.3, T-009): linearisation,
 * generic substitution, override collapse, JLS visibility, synthetic filtering and
 * constructor handling. Generative invariants live in [MemberResolverPropertyTest];
 * the real-JDK spot-check lives in `:index` (`MemberResolverJrtTest`).
 */
class MemberResolverTest {

    private val objectInfo = testClass("java.lang.Object")

    // -- linearisation ----------------------------------------------------------

    @Test
    fun `linearisation visits superclass then interfaces breadth first`() {
        val base = testClass("t.Base", superclass = "java.lang.Object")
        val mid = testClass("t.Mid", superclass = "t.Base")
        val i1 = testInterface("t.I1")
        val i2 = testInterface("t.I2")
        val target = testClass("t.Target", superclass = "t.Mid", interfaces = listOf("t.I1", "t.I2"))
        val lookup = mapLookup(objectInfo, base, mid, i1, i2, target)

        val resolved = MemberResolver.resolve(target, lookup)

        resolved.linearization.map { it.type.binaryName to it.depth } shouldBe listOf(
            "t.Target" to 0,
            "t.Mid" to 1,
            "t.I1" to 1,
            "t.I2" to 1,
            "t.Base" to 2,
            "java.lang.Object" to 3,
        )
    }

    @Test
    fun `linearisation puts java lang Object last`() {
        val mid = testClass("t.Mid", superclass = "java.lang.Object")
        val target = testClass("t.Target", superclass = "t.Mid")
        val lookup = mapLookup(objectInfo, mid, target)

        val resolved = MemberResolver.resolve(target, lookup)

        resolved.linearization.last().type.binaryName shouldBe "java.lang.Object"
    }

    @Test
    fun `linearisation is cycle safe and deterministic`() {
        // A superclass cycle is malformed input the resolver must survive, never loop on.
        val a = testClass("t.A", superclass = "t.B", methods = listOf(publicMethod("a", "()V")))
        val b = testClass("t.B", superclass = "t.A", methods = listOf(publicMethod("b", "()V")))
        val lookup = mapLookup(a, b)

        val first = MemberResolver.resolve(a, lookup)
        val second = MemberResolver.resolve(a, lookup)

        first.linearization.map { it.type.binaryName } shouldBe listOf("t.A", "t.B")
        first shouldBe second
        first.methods.map { it.member.name }.toSet() shouldBe setOf("a", "b")
    }

    // -- generic substitution ----------------------------------------------------

    @Test
    fun `StringList reports add of String not of E`() {
        // class ArrayList<E> { boolean add(E); } ; class StringList extends ArrayList<String>
        val arrayList = testClass(
            binary = "t.ArrayList",
            superclass = "java.lang.Object",
            genericSignature = "<E:Ljava/lang/Object;>Ljava/lang/Object;",
            methods = listOf(publicMethod("add", "(Ljava/lang/Object;)Z", "(TE;)Z")),
        )
        val stringList = testClass(
            binary = "t.StringList",
            superclass = "t.ArrayList",
            genericSignature = "Lt/ArrayList<Ljava/lang/String;>;",
        )
        val lookup = mapLookup(objectInfo, arrayList, stringList)

        val resolved = MemberResolver.resolve(stringList, lookup)

        val add = resolved.methods.single { it.member.name == "add" }
        add.declaringType.binaryName shouldBe "t.ArrayList"
        add.substitutedSignature?.signature shouldBe "(Ljava/lang/String;)Z"
    }

    @Test
    fun `type variables of the target itself survive unsubstituted`() {
        // class MyList<E> extends ArrayList<E> — resolving MyList still shows E.
        val arrayList = testClass(
            binary = "t.ArrayList",
            superclass = "java.lang.Object",
            genericSignature = "<E:Ljava/lang/Object;>Ljava/lang/Object;",
            methods = listOf(publicMethod("add", "(Ljava/lang/Object;)Z", "(TE;)Z")),
        )
        val myList = testClass(
            binary = "t.MyList",
            superclass = "t.ArrayList",
            genericSignature = "<E:Ljava/lang/Object;>Lt/ArrayList<TE;>;",
        )
        val lookup = mapLookup(objectInfo, arrayList, myList)

        val resolved = MemberResolver.resolve(myList, lookup)

        resolved.methods.single { it.member.name == "add" }
            .substitutedSignature?.signature shouldBe "(TE;)Z"
    }

    @Test
    fun `method type parameters shadow class type variables`() {
        // class Box<E> { <T> T identity(T); } resolved under Box<String> keeps T.
        val box = testClass(
            binary = "t.Box",
            superclass = "java.lang.Object",
            genericSignature = "<E:Ljava/lang/Object;>Ljava/lang/Object;",
            methods = listOf(
                publicMethod(
                    "identity",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    "<T:Ljava/lang/Object;>(TT;)TT;",
                ),
            ),
        )
        val stringBox = testClass(
            binary = "t.StringBox",
            superclass = "t.Box",
            genericSignature = "Lt/Box<Ljava/lang/String;>;",
        )
        val lookup = mapLookup(objectInfo, box, stringBox)

        val resolved = MemberResolver.resolve(stringBox, lookup)

        resolved.methods.single { it.member.name == "identity" }
            .substitutedSignature?.signature shouldBe "<T:Ljava/lang/Object;>(TT;)TT;"
    }

    @Test
    fun `raw supertypes erase to Object`() {
        val genericBase = testClass(
            binary = "t.GenericBase",
            superclass = "java.lang.Object",
            genericSignature = "<E:Ljava/lang/Object;>Ljava/lang/Object;",
            methods = listOf(publicMethod("get", "()Ljava/lang/Object;", "()TE;")),
        )
        // No generic signature at all: the raw-type edge.
        val rawChild = testClass(binary = "t.RawChild", superclass = "t.GenericBase")
        val lookup = mapLookup(objectInfo, genericBase, rawChild)

        val resolved = MemberResolver.resolve(rawChild, lookup)

        resolved.methods.single { it.member.name == "get" }
            .substitutedSignature?.signature shouldBe "()Ljava/lang/Object;"
    }

    // -- override collapse ---------------------------------------------------------

    @Test
    fun `overrides collapse to the nearest declaration with overridden types recorded`() {
        val base = testClass(
            "t.Base",
            superclass = "java.lang.Object",
            methods = listOf(publicMethod("foo", "()V"), publicMethod("bar", "()V")),
        )
        val mid = testClass(
            "t.Mid",
            superclass = "t.Base",
            methods = listOf(publicMethod("foo", "()V")),
        )
        val target = testClass("t.Target", superclass = "t.Mid")
        val lookup = mapLookup(objectInfo, base, mid, target)

        val resolved = MemberResolver.resolve(target, lookup)

        val foo = resolved.methods.single { it.member.name == "foo" }
        foo.declaringType.binaryName shouldBe "t.Mid"
        foo.overriddenTypes.map { it.binaryName } shouldBe listOf("t.Base")
        resolved.methods.single { it.member.name == "bar" }
            .declaringType.binaryName shouldBe "t.Base"
    }

    @Test
    fun `overloads with different descriptors coexist`() {
        val base = testClass(
            "t.Base",
            superclass = "java.lang.Object",
            methods = listOf(publicMethod("over", "()V"), publicMethod("over", "(I)V")),
        )
        val target = testClass(
            "t.Target",
            superclass = "t.Base",
            methods = listOf(publicMethod("over", "()V")),
        )
        val lookup = mapLookup(objectInfo, base, target)

        val resolved = MemberResolver.resolve(target, lookup)

        val overloads = resolved.methods.filter { it.member.name == "over" }
        overloads.size shouldBe 2
        overloads.single { it.member.descriptor.descriptor == "()V" }
            .declaringType.binaryName shouldBe "t.Target"
        overloads.single { it.member.descriptor.descriptor == "(I)V" }
            .declaringType.binaryName shouldBe "t.Base"
    }

    @Test
    fun `fields hide by name with hidden types recorded`() {
        val base = testClass(
            "t.Base",
            superclass = "java.lang.Object",
            fields = listOf(
                publicField("x", "I"),
                publicField("kept", "I"),
            ),
        )
        val target = testClass(
            "t.Target",
            superclass = "t.Base",
            fields = listOf(publicField("x", "Ljava/lang/String;")),
        )
        val lookup = mapLookup(objectInfo, base, target)

        val resolved = MemberResolver.resolve(target, lookup)

        val x = resolved.fields.single { it.member.name == "x" }
        x.declaringType.binaryName shouldBe "t.Target"
        x.member.type shouldBe typeNameFromBinaryName("java.lang.String")
        x.hiddenTypes.map { it.binaryName } shouldBe listOf("t.Base")
        resolved.fields.single { it.member.name == "kept" }
            .declaringType.binaryName shouldBe "t.Base"
    }

    // -- visibility ------------------------------------------------------------------

    private fun visibilitySuper(): dev.jdx.core.model.ClassInfo = testClass(
        binary = "a.Super",
        superclass = "java.lang.Object",
        methods = listOf(
            MethodInfo(
                "priv",
                testMethodDescriptor("()V"),
                Access.of(AccessFlag.PRIVATE),
            ),
            // No flags at all: package-private.
            MethodInfo("pkg", testMethodDescriptor("()V"), Access.NONE),
            MethodInfo(
                "prot",
                testMethodDescriptor("()V"),
                Access.of(AccessFlag.PROTECTED),
            ),
            publicMethod("pub", "()V"),
        ),
    )

    @Test
    fun `private supertype members are always excluded`() {
        val target = testClass("b.Target", superclass = "a.Super")
        val lookup = mapLookup(objectInfo, visibilitySuper(), target)

        val names = MemberResolver.resolve(target, lookup).methods.map { it.member.name }.toSet()

        (names.contains("priv")) shouldBe false
        // Cross-package: public and protected survive, package-private does not.
        names shouldBe setOf("pub", "prot")
    }

    @Test
    fun `package private members cross only into the same package`() {
        val otherPackage = testClass("b.Target", superclass = "a.Super")
        val samePackage = testClass("a.Target", superclass = "a.Super")

        val otherNames = MemberResolver.resolve(otherPackage, mapLookup(objectInfo, visibilitySuper(), otherPackage))
            .methods.map { it.member.name }.toSet()
        val sameNames = MemberResolver.resolve(samePackage, mapLookup(objectInfo, visibilitySuper(), samePackage))
            .methods.map { it.member.name }.toSet()

        (otherNames.contains("pkg")) shouldBe false
        (sameNames.contains("pkg")) shouldBe true
        // Public and protected cross packages; private crosses nowhere.
        (otherNames.contains("pub")) shouldBe true
        (otherNames.contains("prot")) shouldBe true
        (otherNames.contains("priv")) shouldBe false
    }

    @Test
    fun `declared private members of the target itself are kept`() {
        val target = testClass(
            "t.Target",
            superclass = "java.lang.Object",
            methods = listOf(
                MethodInfo("hidden", testMethodDescriptor("()V"), Access.of(AccessFlag.PRIVATE)),
            ),
        )

        val resolved = MemberResolver.resolve(target, mapLookup(objectInfo, target))

        resolved.methods.map { it.member.name } shouldBe listOf("hidden")
        resolved.methods.single().member.access.visibility shouldBe Visibility.PRIVATE
    }

    // -- synthetic / bridge ------------------------------------------------------------

    @Test
    fun `bridge and synthetic members hide by default and show on request`() {
        val superType = testClass(
            binary = "t.Super",
            superclass = "java.lang.Object",
            methods = listOf(
                publicMethod("real", "()V"),
                MethodInfo(
                    "bridge",
                    testMethodDescriptor("()V"),
                    Access.of(AccessFlag.PUBLIC, AccessFlag.BRIDGE, AccessFlag.SYNTHETIC),
                ),
            ),
            fields = listOf(
                publicField("real", "I"),
                FieldInfo(
                    "this\$0",
                    typeNameFromBinaryName("t.Outer"),
                    Access.of(AccessFlag.SYNTHETIC),
                ),
            ),
        )
        val target = testClass("t.Target", superclass = "t.Super")
        val lookup = mapLookup(objectInfo, testClass("t.Outer", superclass = "java.lang.Object"), superType, target)

        val default = MemberResolver.resolve(target, lookup)
        default.methods.map { it.member.name }.toSet() shouldBe setOf("real")
        default.fields.map { it.member.name }.toSet() shouldBe setOf("real")

        val withSynthetic = MemberResolver.resolve(
            target,
            lookup,
            MemberResolutionOptions(includeSynthetic = true),
        )
        withSynthetic.methods.map { it.member.name }.toSet() shouldBe setOf("real", "bridge")
        withSynthetic.fields.map { it.member.name }.toSet() shouldBe setOf("real", "this\$0")
    }

    // -- constructors --------------------------------------------------------------------

    @Test
    fun `constructors are not inherited and clinit never appears`() {
        val voidDescriptor = JvmDescriptor.Method(
            emptyList(),
            TypeName.PrimitiveType(dev.jdx.core.model.JvmPrimitive.VOID),
        )
        val superType = testClass(
            "t.Super",
            superclass = "java.lang.Object",
            methods = listOf(
                MethodInfo("<init>", voidDescriptor, Access.of(AccessFlag.PUBLIC)),
                MethodInfo("<clinit>", voidDescriptor, Access.NONE),
            ),
        )
        val target = testClass(
            "t.Target",
            superclass = "t.Super",
            methods = listOf(
                MethodInfo("<init>", voidDescriptor, Access.of(AccessFlag.PUBLIC)),
                MethodInfo("<clinit>", voidDescriptor, Access.NONE),
            ),
        )
        val lookup = mapLookup(objectInfo, superType, target)

        val resolved = MemberResolver.resolve(target, lookup)

        val ctors = resolved.methods.filter { it.member.name == "<init>" }
        ctors.size shouldBe 1
        ctors.single().declaringType.binaryName shouldBe "t.Target"
        resolved.methods.none { it.member.name == "<clinit>" } shouldBe true
    }

    // -- degradation -----------------------------------------------------------------------

    @Test
    fun `missing supertypes are skipped and reported`() {
        val target = testClass(
            "t.Target",
            superclass = "t.Missing",
            methods = listOf(publicMethod("own", "()V")),
        )

        val resolved = MemberResolver.resolve(target, mapLookup(objectInfo, target))

        resolved.methods.map { it.member.name } shouldBe listOf("own")
        resolved.missingSupertypes.map { it.binaryName } shouldBe listOf("t.Missing")
    }

    @Test
    fun `dollar nested supertype edges in generic signatures resolve as nesting`() {
        // kotlinc emits nested supertypes in the binary `$` form inside generic
        // signatures (real shape from androidx, found by the T-056 soak: a class
        // implementing `SavedStateRegistry$SavedStateProvider` carries the edge
        // `Landroidx/savedstate/SavedStateRegistry$SavedStateProvider;`). The
        // resolver must read the `$` as nesting — building the edge name
        // without splitting threw `name segment ... contains a separator` and
        // turned the whole query into exit 6.
        val provider = testInterface("androidx.savedstate.SavedStateRegistry\$SavedStateProvider")
        val target = testClass(
            binary = "t.Delegate",
            superclass = "java.lang.Object",
            genericSignature =
                "Ljava/lang/Object;Landroidx/savedstate/SavedStateRegistry\$SavedStateProvider;",
        )
        val lookup = mapLookup(objectInfo, provider, target)

        val resolved = MemberResolver.resolve(target, lookup)

        val edge = resolved.linearization.single { it.type.simpleName == "SavedStateProvider" }
        edge.type.nestedNames shouldBe listOf("SavedStateRegistry", "SavedStateProvider")
        edge.type.binaryName shouldBe "androidx.savedstate.SavedStateRegistry\$SavedStateProvider"
    }

    // -- T-060: substitution killers ------------------------------------------------
    //
    // PIT found the generic-field path, the all-shadowed path, multi-parameter
    // ordering and throws substitution uncovered-or-unasserted. Each test below
    // pins the substituted shape exactly.

    @Test
    fun `a generic field inherits substituted`() {
        // class Box<T> { T value; } under Box<String> shows String.
        // Kills the field-substitution mutants (304/305) and the `?.let` guard
        // (76): skipping substitution leaves T, substituting null crashes.
        val box = testClass(
            binary = "t.Box",
            superclass = "java.lang.Object",
            genericSignature = "<T:Ljava/lang/Object;>Ljava/lang/Object;",
            fields = listOf(publicField("value", "Ljava/lang/Object;", "TT;")),
        )
        val stringBox = testClass(
            binary = "t.StringBox",
            superclass = "t.Box",
            genericSignature = "Lt/Box<Ljava/lang/String;>;",
        )
        val lookup = mapLookup(objectInfo, box, stringBox)

        val resolved = MemberResolver.resolve(stringBox, lookup)

        val field = resolved.fields.single { it.member.name == "value" }
        field.declaringType.binaryName shouldBe "t.Box"
        field.depth shouldBe 1
        field.member.genericSignature?.signature shouldBe "TT;"
        field.substitutedSignature?.signature shouldBe "Ljava/lang/String;"
    }

    @Test
    fun `a non-generic field resolves with no substituted signature`() {
        val holder = testClass(
            binary = "t.Holder",
            superclass = "java.lang.Object",
            fields = listOf(publicField("count", "I")),
        )
        val lookup = mapLookup(objectInfo, holder)

        val resolved = MemberResolver.resolve(holder, lookup)

        val field = resolved.fields.single { it.member.name == "count" }
        field.substitutedSignature shouldBe null
    }

    @Test
    fun `two type parameters substitute in order`() {
        // class Pair<K, V> { K first(); V second(); } under Pair<String, Integer>:
        // swapping the arguments (the `index` increment mutant) is observable.
        val pair = testClass(
            binary = "t.Pair",
            superclass = "java.lang.Object",
            genericSignature = "<K:Ljava/lang/Object;V:Ljava/lang/Object;>Ljava/lang/Object;",
            methods = listOf(
                publicMethod("first", "()Ljava/lang/Object;", "()TK;"),
                publicMethod("second", "()Ljava/lang/Object;", "()TV;"),
            ),
        )
        val stringInt = testClass(
            binary = "t.StringInt",
            superclass = "java.lang.Object",
            interfaces = listOf("t.Pair"),
            genericSignature = "Ljava/lang/Object;Lt/Pair<Ljava/lang/String;Ljava/lang/Integer;>;",
        )
        val lookup = mapLookup(objectInfo, pair, stringInt)

        val resolved = MemberResolver.resolve(stringInt, lookup)

        resolved.methods.single { it.member.name == "first" }
            .substitutedSignature?.signature shouldBe "()Ljava/lang/String;"
        resolved.methods.single { it.member.name == "second" }
            .substitutedSignature?.signature shouldBe "()Ljava/lang/Integer;"
    }

    @Test
    fun `a method shadowing every class variable keeps its signature whole`() {
        // class Box<T> { <T> T id(T); }: the shadowed environment is empty, so
        // the declared signature returns as-is (the 292 early return).
        val box = testClass(
            binary = "t.Box",
            superclass = "java.lang.Object",
            genericSignature = "<T:Ljava/lang/Object;>Ljava/lang/Object;",
            methods = listOf(
                publicMethod(
                    "id",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    "<T:Ljava/lang/Object;>(TT;)TT;",
                ),
            ),
        )
        val stringBox = testClass(
            binary = "t.StringBox",
            superclass = "t.Box",
            genericSignature = "Lt/Box<Ljava/lang/String;>;",
        )
        val lookup = mapLookup(objectInfo, box, stringBox)

        val resolved = MemberResolver.resolve(stringBox, lookup)

        resolved.methods.single { it.member.name == "id" }
            .substitutedSignature?.signature shouldBe "<T:Ljava/lang/Object;>(TT;)TT;"
    }

    @Test
    fun `a target declaring its own generic method keeps it as is`() {
        // The target's environment is empty: substitution is the identity (290).
        val box = testClass(
            binary = "t.Box",
            superclass = "java.lang.Object",
            genericSignature = "<T:Ljava/lang/Object;>Ljava/lang/Object;",
            methods = listOf(
                publicMethod(
                    "id",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    "<T:Ljava/lang/Object;>(TT;)TT;",
                ),
            ),
        )
        val lookup = mapLookup(objectInfo, box)

        val resolved = MemberResolver.resolve(box, lookup)

        resolved.methods.single { it.member.name == "id" }
            .substitutedSignature?.signature shouldBe "<T:Ljava/lang/Object;>(TT;)TT;"
    }

    @Test
    fun `a method level variable survives beside a substituted class variable`() {
        // class Box<E> { <T> T mix(T, E); } under Box<String>: T stays, E binds.
        val box = testClass(
            binary = "t.Box",
            superclass = "java.lang.Object",
            genericSignature = "<E:Ljava/lang/Object;>Ljava/lang/Object;",
            methods = listOf(
                publicMethod(
                    "mix",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    "<T:Ljava/lang/Object;>(TT;TE;)TT;",
                ),
            ),
        )
        val stringBox = testClass(
            binary = "t.StringBox",
            superclass = "t.Box",
            genericSignature = "Lt/Box<Ljava/lang/String;>;",
        )
        val lookup = mapLookup(objectInfo, box, stringBox)

        val resolved = MemberResolver.resolve(stringBox, lookup)

        resolved.methods.single { it.member.name == "mix" }
            .substitutedSignature?.signature shouldBe "<T:Ljava/lang/Object;>(TT;Ljava/lang/String;)TT;"
    }

    @Test
    fun `a generic throws variable substitutes with the method`() {
        // `void clear() throws E` under Box<String> throws String (ClassThrows).
        val box = testClass(
            binary = "t.Box",
            superclass = "java.lang.Object",
            genericSignature = "<E:Ljava/lang/Object;>Ljava/lang/Object;",
            methods = listOf(publicMethod("clear", "()V", "()V^TE;")),
        )
        val stringBox = testClass(
            binary = "t.StringBox",
            superclass = "t.Box",
            genericSignature = "Lt/Box<Ljava/lang/String;>;",
        )
        val lookup = mapLookup(objectInfo, box, stringBox)

        val resolved = MemberResolver.resolve(stringBox, lookup)

        val throws = resolved.methods.single { it.member.name == "clear" }
            .substitutedSignature?.throwsSignatures?.single()
        throws shouldBe ThrowsSignature.ClassThrows(
            ClassTypeSignature("java.lang", "String", emptyList(), emptyList()),
        )
    }

    @Test
    fun `an unmapped throws variable keeps its name`() {
        // `void read() throws U` with U bound nowhere stays a variable throw.
        val box = testClass(
            binary = "t.Box",
            superclass = "java.lang.Object",
            genericSignature = "<E:Ljava/lang/Object;>Ljava/lang/Object;",
            methods = listOf(publicMethod("read", "()V", "()V^TU;")),
        )
        val stringBox = testClass(
            binary = "t.StringBox",
            superclass = "t.Box",
            genericSignature = "Lt/Box<Ljava/lang/String;>;",
        )
        val lookup = mapLookup(objectInfo, box, stringBox)

        val resolved = MemberResolver.resolve(stringBox, lookup)

        resolved.methods.single { it.member.name == "read" }
            .substitutedSignature?.throwsSignatures?.single() shouldBe
            ThrowsSignature.TypeVariableThrows("U")
    }

    @Test
    fun `substitution flows transitively through a non-generic middle`() {
        // class StringList extends ArrayList<String>; class Mine extends StringList:
        // the middle declares no type variables of its own (the 247 early path),
        // yet `add` still resolves to String transitively.
        val arrayList = testClass(
            binary = "t.ArrayList",
            superclass = "java.lang.Object",
            genericSignature = "<E:Ljava/lang/Object;>Ljava/lang/Object;",
            methods = listOf(publicMethod("add", "(Ljava/lang/Object;)Z", "(TE;)Z")),
        )
        val stringList = testClass(
            binary = "t.StringList",
            superclass = "t.ArrayList",
            genericSignature = "Lt/ArrayList<Ljava/lang/String;>;",
        )
        val mine = testClass(binary = "t.Mine", superclass = "t.StringList")
        val lookup = mapLookup(objectInfo, arrayList, stringList, mine)

        val resolved = MemberResolver.resolve(mine, lookup)

        resolved.methods.single { it.member.name == "add" }
            .substitutedSignature?.signature shouldBe "(Ljava/lang/String;)Z"
    }

    // -- T-037: --view jvm ------------------------------------------------------------
    //
    // The JVM projection ignores every Kotlin view: folded accessors and backing
    // fields show as plain JVM members, no view attaches, no property synthesises.

    @Test
    fun `jvmView reveals folded accessors and backing fields with no views attached`() {
        val target = kotlinishClass()
        val lookup = mapLookup(objectInfo, target)

        val kotlin = MemberResolver.resolve(target, lookup)
        kotlin.methods.map { it.member.name }.toSet() shouldBe setOf("renamedForJvm")
        kotlin.methods.single().kotlinView?.displayName shouldBe "originalName"
        kotlin.fields shouldBe emptyList()
        kotlin.properties.map { it.property.propertyName } shouldBe listOf("name")

        val jvm = MemberResolver.resolve(target, lookup, MemberResolutionOptions(jvmView = true))
        jvm.methods.map { it.member.name }.toSet() shouldBe
            setOf("getName", "setName", "renamedForJvm")
        jvm.methods.none { it.kotlinView != null } shouldBe true
        jvm.fields.map { it.member.name } shouldBe listOf("name")
        jvm.properties shouldBe emptyList()
    }

    @Test
    fun `jvmView keeps the JVM name on renamed methods`() {
        val target = kotlinishClass()
        val lookup = mapLookup(objectInfo, target)

        val jvm = MemberResolver.resolve(target, lookup, MemberResolutionOptions(jvmView = true))

        val renamed = jvm.methods.single { it.member.name == "renamedForJvm" }
        renamed.kotlinView shouldBe null
    }

    @Test
    fun `jvmView still honours includeSynthetic for real synthetics`() {
        val base = kotlinishClass()
        val target = base.copy(
            methods = base.methods + MethodInfo(
                "\$default",
                testMethodDescriptor("()V"),
                Access.of(AccessFlag.PUBLIC, AccessFlag.SYNTHETIC),
            ),
        )
        val lookup = mapLookup(objectInfo, target)

        val hidden = MemberResolver.resolve(target, lookup, MemberResolutionOptions(jvmView = true))
        hidden.methods.map { it.member.name }.toSet() shouldBe
            setOf("getName", "setName", "renamedForJvm")

        val shown = MemberResolver.resolve(
            target,
            lookup,
            MemberResolutionOptions(jvmView = true, includeSynthetic = true),
        )
        shown.methods.map { it.member.name }.toSet() shouldBe
            setOf("getName", "setName", "renamedForJvm", "\$default")
    }

    /** A Kotlin-shaped class: a folded `var name` plus a `@JvmName`-renamed method. */
    private fun kotlinishClass(): ClassInfo = testClass(
        binary = "t.Kotlin",
        superclass = "java.lang.Object",
        fields = listOf(publicField("name", "Ljava/lang/String;")),
        methods = listOf(
            publicMethod("getName", "()Ljava/lang/String;"),
            publicMethod("setName", "(Ljava/lang/String;)V"),
            publicMethod("renamedForJvm", "(I)I"),
        ),
    ).copy(
        kotlinMethodViews = mapOf(
            kotlinViewKey("renamedForJvm", "(I)I") to KotlinMethodView(displayName = "originalName"),
        ),
        kotlinProperties = mapOf(
            "name" to KotlinPropertyView(
                propertyName = "name",
                isVar = true,
                displayType = "java.lang.String",
            ),
        ),
        kotlinHiddenMethods = setOf(
            kotlinViewKey("getName", "()Ljava/lang/String;"),
            kotlinViewKey("setName", "(Ljava/lang/String;)V"),
        ),
        kotlinHiddenFields = setOf("name"),
    )
}
