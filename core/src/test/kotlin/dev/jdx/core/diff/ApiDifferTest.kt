package dev.jdx.core.diff

import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.AnnotationInfo
import dev.jdx.core.model.KotlinMethodView
import dev.jdx.core.model.KotlinPropertyView
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.typeNameFromBinaryName
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The differ, rule by rule (issue #23).
 *
 * Every test names one difference and asserts the rules that fired — most assert
 * **exactly one** rule, which is what keeps a "helpful" extra finding from creeping
 * in. The severities themselves are pinned once, in
 * `every rule carries the severity the catalogue promises`, so a rule cannot be
 * quietly re-classified by the differ.
 */
class ApiDifferTest {

    // -- the floor ---------------------------------------------------------------

    @Test
    fun `an unchanged artifact yields no findings at all`() {
        val snapshot = snapshotOf("a.jar", type("com.example.Foo", members = listOf(method("bar"), field("baz"))))
        ApiDiffer.diff(snapshot, snapshot).findings shouldBe emptyList()
        ApiDiffer.diff(snapshot, snapshot).identical shouldBe true
    }

    @Test
    fun `the two type counts travel with the diff, not just the findings`() {
        val old = snapshotOf("a.jar", type("com.example.A"), type("com.example.B"))
        val new = snapshotOf("b.jar", type("com.example.A"), type("com.example.C"))
        val diff = ApiDiffer.diff(old, new)
        diff.oldTypeCount shouldBe 2
        diff.newTypeCount shouldBe 2
    }

    // -- types -------------------------------------------------------------------

    @Test
    fun `a type only the old artifact has is TYPE_REMOVED, and its members are not listed`() {
        val kept = type("com.example.Kept")
        val old = snapshotOf("a.jar", kept, type("com.example.Gone", members = listOf(method("a"), method("b"))))
        val new = snapshotOf("b.jar", kept)
        rulesBetween(old, new) shouldBe listOf(CompatRule.TYPE_REMOVED)
    }

    @Test
    fun `a type only the new artifact has is TYPE_ADDED`() {
        val old = snapshotOf("a.jar", type("com.example.Kept"))
        val new = snapshotOf("b.jar", type("com.example.Kept"), type("com.example.Fresh"))
        rulesBetween(old, new) shouldBe listOf(CompatRule.TYPE_ADDED)
    }

    @Test
    fun `a class that became an interface is TYPE_KIND_CHANGED naming both kinds`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", kind = TypeKind.CLASS))
        val new = snapshotOf("b.jar", type("com.example.Foo", kind = TypeKind.INTERFACE))
        val finding = onlyFindingBetween(old, new)
        finding.rule shouldBe CompatRule.TYPE_KIND_CHANGED
        finding.detail shouldBe "class -> interface"
    }

    @Test
    fun `a Kotlin object that became a companion object is a kind change too`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", kind = TypeKind.OBJECT))
        val new = snapshotOf("b.jar", type("com.example.Foo", kind = TypeKind.COMPANION))
        rulesBetween(old, new) shouldBe listOf(CompatRule.TYPE_KIND_CHANGED)
    }

    @Test
    fun `a public type that went package-private is TYPE_VISIBILITY_NARROWED`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", access = PUBLIC))
        val new = snapshotOf("b.jar", type("com.example.Foo", access = Access.NONE))
        val finding = onlyFindingBetween(old, new)
        finding.rule shouldBe CompatRule.TYPE_VISIBILITY_NARROWED
        finding.detail shouldBe "public -> package_private"
    }

    @Test
    fun `a type that got wider is not a narrowing`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", access = Access.of(AccessFlag.PRIVATE)))
        val new = snapshotOf("b.jar", type("com.example.Foo", access = PUBLIC))
        rulesBetween(old, new) shouldBe emptyList()
    }

    @Test
    fun `final added to a type is TYPE_MADE_FINAL, final removed is FINAL_REMOVED`() {
        val open = type("com.example.Foo", access = PUBLIC)
        val sealed = type("com.example.Foo", access = Access.of(AccessFlag.PUBLIC, AccessFlag.FINAL))
        rulesBetween(snapshotOf("a.jar", open), snapshotOf("b.jar", sealed)) shouldBe
            listOf(CompatRule.TYPE_MADE_FINAL)
        rulesBetween(snapshotOf("a.jar", sealed), snapshotOf("b.jar", open)) shouldBe
            listOf(CompatRule.FINAL_REMOVED)
    }

    @Test
    fun `a dropped superclass or superinterface is one SUPERTYPE_REMOVED each`() {
        val old = snapshotOf(
            "a.jar",
            type(
                "com.example.Foo",
                superclass = STRING,
                interfaces = listOf(SERIALIZABLE, CLONEABLE),
            ),
        )
        val new = snapshotOf("b.jar", type("com.example.Foo", superclass = STRING))
        val findings = findingsBetween(old, new)
        findings.map { it.rule } shouldBe listOf(CompatRule.SUPERTYPE_REMOVED, CompatRule.SUPERTYPE_REMOVED)
        findings.map { it.detail } shouldBe listOf("java.io.Serializable", "java.lang.Cloneable")
    }

    @Test
    fun `an added supertype is SUPERTYPE_ADDED, not a removal`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", superclass = OBJECT))
        val new = snapshotOf("b.jar", type("com.example.Foo", superclass = STRING))
        // `java.lang.Object` going away is not a loss — see the next test.
        rulesBetween(old, new) shouldBe listOf(CompatRule.SUPERTYPE_ADDED)
    }

    @Test
    fun `a class that stopped naming Object did not lose a supertype`() {
        // A class → interface change, or a class that now names a real superclass, both
        // drop `java.lang.Object` from the header. Every class has it, so reporting the
        // removal would be a false alarm on a change that is already reported as
        // TYPE_KIND_CHANGED or SUPERTYPE_ADDED.
        val asClass = snapshotOf("a.jar", type("com.example.Foo", superclass = OBJECT))
        val asInterface = snapshotOf("b.jar", type("com.example.Foo", kind = TypeKind.INTERFACE))
        rulesBetween(asClass, asInterface) shouldBe listOf(CompatRule.TYPE_KIND_CHANGED)
        val onEnum = snapshotOf("b.jar", type("com.example.Foo", kind = TypeKind.ENUM, superclass = STRING))
        rulesBetween(asClass, onEnum) shouldBe listOf(CompatRule.TYPE_KIND_CHANGED, CompatRule.SUPERTYPE_ADDED)
    }

    // -- member removals and the inheritance check --------------------------------

    @Test
    fun `a removed method is MEMBER_REMOVED naming its canonical ref`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", members = listOf(method("gone", listOf(STRING)))))
        val new = snapshotOf("b.jar", type("com.example.Foo"))
        val finding = onlyFindingBetween(old, new)
        finding.rule shouldBe CompatRule.MEMBER_REMOVED
        finding.ref shouldBe "com.example.Foo#gone(java.lang.String)"
    }

    @Test
    fun `a member that moved to a supertype in the same artifact is not reported as removed`() {
        val base = type("com.example.Base")
        val old = snapshotOf("a.jar", base, type("com.example.Foo", members = listOf(method("inherited"))))
        val new = snapshotOf(
            "b.jar",
            type("com.example.Base", members = listOf(method("inherited", ref = "com.example.Base#inherited()"))),
            type("com.example.Foo", superclass = BASE_SUPERCLASS),
        )
        val findings = findingsBetween(old, new)
        findings.map { it.rule } shouldBe listOf(
            CompatRule.MEMBER_ADDED,
            CompatRule.SUPERTYPE_ADDED,
            CompatRule.MEMBER_MOVED_TO_SUPERTYPE,
        )
        findings.last().detail shouldBe "declared by com.example.Base"
    }

    @Test
    fun `a removal stays breaking, with the uncheckable supertype named, when a supertype is missing`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", members = listOf(method("gone"))))
        // `com.example.Base` is named as a supertype but is not in the artifact.
        val new = snapshotOf("b.jar", type("com.example.Foo", superclass = BASE_SUPERCLASS))
        val removal = findingsBetween(old, new).single { it.rule == CompatRule.MEMBER_REMOVED }
        removal.detail shouldBe "supertype com.example.Base is not in b.jar; inheritance not checked"
    }

    @Test
    fun `inheritance is checked through a whole chain, not just direct supertypes`() {
        val grandParent = type("com.example.GrandParent")
        val parent = type("com.example.Parent", superclass = GRANDPARENT_SUPERCLASS)
        val old = snapshotOf("a.jar", grandParent, parent, type("com.example.Foo", members = listOf(method("deep"))))
        val new = snapshotOf(
            "b.jar",
            type("com.example.GrandParent", members = listOf(method("deep", ref = "com.example.GrandParent#deep()"))),
            parent,
            type("com.example.Foo", superclass = PARENT_SUPERCLASS),
        )
        val moved = findingsBetween(old, new).single { it.rule == CompatRule.MEMBER_MOVED_TO_SUPERTYPE }
        moved.detail shouldBe "declared by com.example.GrandParent"
    }

    @Test
    fun `an interface that does not declare the member does not satisfy the inheritance check`() {
        val marker = type("com.example.Marker", kind = TypeKind.INTERFACE)
        val old = snapshotOf(
            "a.jar",
            marker,
            type("com.example.Foo", interfaces = listOf(MARKER_SUPERCLASS), members = listOf(method("gone"))),
        )
        val new = snapshotOf("b.jar", marker, type("com.example.Foo", interfaces = listOf(MARKER_SUPERCLASS)))
        findingsBetween(old, new).map { it.rule } shouldBe listOf(CompatRule.MEMBER_REMOVED)
    }

    @Test
    fun `a removal never carries a caveat just for extending Object`() {
        // Every ordinary class extends `java.lang.Object`, and that type is never inside
        // a third-party jar. Counting it as "missing" would put an "inheritance not
        // checked" detail on every removal in every report.
        val old = snapshotOf("a.jar", type("com.example.Foo", superclass = OBJECT, members = listOf(method("gone"))))
        val new = snapshotOf("b.jar", type("com.example.Foo", superclass = OBJECT))
        val finding = onlyFindingBetween(old, new)
        finding.rule shouldBe CompatRule.MEMBER_REMOVED
        finding.detail shouldBe ""
    }

    @Test
    fun `the other universal roots are as quiet as java lang Object`() {
        UNIVERSAL_ROOTS.forEach { root ->
            val old = snapshotOf("a.jar", type("com.example.Foo", superclass = root, members = listOf(method("gone"))))
            val new = snapshotOf("b.jar", type("com.example.Foo", superclass = root))
            onlyFindingBetween(old, new).detail shouldBe ""
        }
    }

    @Test
    fun `a private removal is a removal, full stop, because nothing inherits it`() {
        val hidden = method("gone", access = Access.of(AccessFlag.PRIVATE))
        val old = snapshotOf(
            "a.jar",
            type("com.example.Foo", superclass = BASE_SUPERCLASS, members = listOf(hidden)),
        )
        // `com.example.Base` is absent, so a public removal would have to say so. A
        // private one cannot be inherited (JLS §8.2), so the caveat would be a lie.
        val new = snapshotOf("b.jar", type("com.example.Foo", superclass = BASE_SUPERCLASS))
        val finding = onlyFindingBetween(old, new)
        finding.rule shouldBe CompatRule.MEMBER_REMOVED
        finding.detail shouldBe ""
    }

    @Test
    fun `a universal root never masks a real missing supertype`() {
        // Both sides still *name* `java.io.Serializable`, which neither artifact holds,
        // and both extend `java.lang.Object`, which is universal. The caveat must name
        // the real unknown, not be swallowed by the root.
        val withBoth = type(
            "com.example.Foo",
            superclass = OBJECT,
            interfaces = listOf(SERIALIZABLE),
            members = listOf(method("gone")),
        )
        val old = snapshotOf("a.jar", withBoth)
        val new = snapshotOf(
            "b.jar",
            type("com.example.Foo", superclass = OBJECT, interfaces = listOf(SERIALIZABLE)),
        )
        val removal = findingsBetween(old, new).single { it.rule == CompatRule.MEMBER_REMOVED }
        removal.detail shouldBe "supertype java.io.Serializable is not in b.jar; inheritance not checked"
    }

    @Test
    fun `a supertype the new type dropped is not a missing supertype, the member really is gone`() {
        // The walk starts from the *new* type: a class that no longer implements
        // `Serializable` cannot have inherited anything through it, so there is nothing
        // to check and no caveat to print.
        val old = snapshotOf(
            "a.jar",
            type(
                "com.example.Foo",
                superclass = OBJECT,
                interfaces = listOf(SERIALIZABLE),
                members = listOf(method("gone")),
            ),
        )
        val new = snapshotOf("b.jar", type("com.example.Foo", superclass = OBJECT))
        val rules = findingsBetween(old, new).map { it.rule }
        rules shouldBe listOf(CompatRule.SUPERTYPE_REMOVED, CompatRule.MEMBER_REMOVED)
        findingsBetween(old, new).single { it.rule == CompatRule.MEMBER_REMOVED }.detail shouldBe ""
    }

    @Test
    fun `an enum field of its own type is only a constant when it is public static final`() {
        // The three flags are what `javac` always emits, so no real enum can distinguish
        // them — but the snapshot is read from arbitrary bytecode, and a hand-crafted
        // class file can put a public instance field of the enum's own type in there. The
        // rule must be decided by the flags, not assumed from the kind alone.
        val owner = type("com.example.Light", kind = TypeKind.ENUM)
        val selfTyped = LIGHT
        fun removedFinding(vararg flags: AccessFlag): CompatRule {
            val old = snapshotOf(
                "a.jar",
                owner.copy(
                    members = mapOf(
                        ApiFieldKey("odd", selfTyped) to ApiField(
                            key = ApiFieldKey("odd", selfTyped),
                            access = Access.of(*flags),
                            canonicalRef = "com.example.Light#odd",
                        ),
                    ),
                ),
            )
            val new = snapshotOf("b.jar", owner)
            return onlyFindingBetween(old, new).rule
        }
        removedFinding(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL) shouldBe
            CompatRule.ENUM_CONSTANT_REMOVED
        removedFinding(AccessFlag.STATIC, AccessFlag.FINAL) shouldBe CompatRule.MEMBER_REMOVED
        removedFinding(AccessFlag.PUBLIC, AccessFlag.FINAL) shouldBe CompatRule.MEMBER_REMOVED
        removedFinding(AccessFlag.PUBLIC, AccessFlag.STATIC) shouldBe CompatRule.MEMBER_REMOVED
    }

    @Test
    fun `an enum field of another type is never a constant, whatever the flags`() {
        val owner = type("com.example.Light", kind = TypeKind.ENUM)
        val old = snapshotOf(
            "a.jar",
            owner.copy(
                members = mapOf(
                    ApiFieldKey("helper", STRING) to ApiField(
                        key = ApiFieldKey("helper", STRING),
                        access = Access.of(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL),
                        canonicalRef = "com.example.Light#helper",
                    ),
                ),
            ),
        )
        onlyFindingBetween(old, snapshotOf("b.jar", owner)).rule shouldBe CompatRule.MEMBER_REMOVED
    }

    @Test
    fun `two additions sharing a shape are not paired with one removal either`() {
        // The mirror of the ambiguous-bucket test: pairing by name+arity alone would have
        // to choose, and choosing is a guess.
        val old = snapshotOf("a.jar", type("com.example.Foo", members = listOf(method("f", listOf(STRING)))))
        val new = snapshotOf(
            "b.jar",
            type("com.example.Foo", members = listOf(method("f", listOf(INT)), method("f", listOf(LONG)))),
        )
        rulesBetween(old, new) shouldBe listOf(CompatRule.MEMBER_REMOVED, CompatRule.MEMBER_ADDED, CompatRule.MEMBER_ADDED)
    }

    @Test
    fun `a field whose generic signature changed reports it, and a field constant too`() {
        val old = snapshotOf(
            "a.jar",
            type(
                "com.example.Box",
                members = listOf(
                    field("items", typeNameFromBinaryName("java.util.List")),
                    field("size", INT, constantValue = "1"),
                ),
            ),
        )
        val new = snapshotOf(
            "b.jar",
            type(
                "com.example.Box",
                members = listOf(
                    field("items", typeNameFromBinaryName("java.util.List"), genericSignature = "Ljava/util/List<Ljava/lang/String;>;"),
                    field("size", INT, constantValue = "2"),
                ),
            ),
        )
        val findings = findingsBetween(old, new)
        // Generic signature is SUSPICIOUS, the constant value is INFO, so severity orders
        // them — not the order the two attributes happen to be read in.
        findings.map { it.rule } shouldBe listOf(
            CompatRule.GENERIC_SIGNATURE_CHANGED,
            CompatRule.FIELD_CONSTANT_VALUE_CHANGED,
        )
        // Both name the field, and the field ref carries no type, so the details have to
        // distinguish them or the report has two identical lines.
        findings.map { it.ref }.distinct() shouldBe listOf("com.example.Box#items", "com.example.Box#size")
    }

    @Test
    fun `a field annotation is compared exactly like a method annotation`() {
        // The field is present on both sides, so this is the annotation changing rather
        // than the member being removed.
        val annotated = field("tagged", annotations = listOf(AnnotationInfo(TYPE_NAME("com.example.Marker"))))
        val bare = field("tagged")
        val old = snapshotOf("a.jar", type("com.example.Foo", members = listOf(annotated)))
        val new = snapshotOf("b.jar", type("com.example.Foo", members = listOf(bare)))
        val findings = findingsBetween(old, new)
        findings.map { it.rule } shouldBe listOf(CompatRule.ANNOTATION_REMOVED)
        findings.single().detail shouldBe "com.example.Marker"
    }

    @Test
    fun `a Kotlin view that does not hide a Continuation keeps the full arity`() {
        // `kotlinArity` must subtract the hidden parameter only when the view says the
        // tail really is a Continuation; a view without that flag must leave the JVM
        // parameter list alone, or two unrelated members would pair.
        val continuation = typeNameFromBinaryName("kotlin.coroutines.Continuation")
        val old = snapshotOf(
            "a.jar",
            type(
                "com.example.Foo",
                isKotlin = true,
                members = listOf(
                    method(
                        "f",
                        listOf(continuation),
                        kotlin = KotlinMethodView(displayName = "f"),
                    ),
                ),
            ),
        )
        // Arity 1 on the old side, so nothing pairs with a zero-argument `f`.
        val new = snapshotOf(
            "b.jar",
            type("com.example.Foo", isKotlin = true, members = listOf(method("f"))),
        )
        rulesBetween(old, new) shouldBe listOf(CompatRule.MEMBER_REMOVED, CompatRule.MEMBER_ADDED)
    }

    @Test
    fun `the finding order breaks ties on type, ref and detail, not just severity`() {
        // Each comparator component is load-bearing: without a tiebreak on `type` two
        // findings of the same rule could swap between runs, and `--json` bytes would stop
        // being reproducible.
        val severity = listOf(
            DiffFinding(CompatRule.MEMBER_ADDED, "com.example.Zeta", "com.example.Zeta#a"),
            DiffFinding(CompatRule.MEMBER_ADDED, "com.example.Alpha", "com.example.Alpha#z"),
            DiffFinding(CompatRule.MEMBER_ADDED, "com.example.Alpha", "com.example.Alpha#a"),
        )
        val ordered = severity.sortedWith(DIFF_FINDING_ORDER)
        ordered.map { it.type } shouldBe listOf("com.example.Alpha", "com.example.Alpha", "com.example.Zeta")
        ordered.map { it.ref } shouldBe listOf("com.example.Alpha#a", "com.example.Alpha#z", "com.example.Zeta#a")

        val sameRef = listOf(
            DiffFinding(CompatRule.MEMBER_ADDED, "com.example.Foo", "com.example.Foo#a", "second"),
            DiffFinding(CompatRule.MEMBER_ADDED, "com.example.Foo", "com.example.Foo#a", "first"),
        )
        sameRef.sortedWith(DIFF_FINDING_ORDER).map { it.detail } shouldBe listOf("first", "second")
    }

    @Test
    fun `the artifact labels and the identical verdict are read straight off the diff`() {
        val diff = ApiDiffer.diff(snapshotOf("old.jar", type("com.example.A")), snapshotOf("new.jar", type("com.example.A")))
        diff.oldArtifact shouldBe "old.jar"
        diff.newArtifact shouldBe "new.jar"
        diff.identical shouldBe true
        DiffCounts.of(diff).total shouldBe 0
    }

    @Test
    fun `a compiler's own bookkeeping annotation is never reported as an API change`() {
        // `kotlin.Metadata` changes value on *every* edit to a Kotlin declaration, so
        // reporting it would put a line that is always true on every Kotlin diff and bury
        // the real finding. Unlike a JetBrains `@Nullable`, nobody compiles against it.
        val withMetadata = AnnotationInfo(TYPE_NAME("kotlin.Metadata"), mapOf("k" to "1", "d1" to "[]"))
        val withOtherMetadata = AnnotationInfo(TYPE_NAME("kotlin.Metadata"), mapOf("k" to "2", "d1" to "[]"))
        val real = AnnotationInfo(TYPE_NAME("com.example.Marker"), mapOf("level" to "1"))
        val realChanged = AnnotationInfo(TYPE_NAME("com.example.Marker"), mapOf("level" to "2"))

        // Type level: metadata churn alone produces nothing at all.
        rulesBetween(
            snapshotOf("a.jar", type("com.example.Foo", isKotlin = true, annotations = listOf(withMetadata))),
            snapshotOf("b.jar", type("com.example.Foo", isKotlin = true, annotations = listOf(withOtherMetadata))),
        ) shouldBe emptyList()

        // And it does not mask a real annotation change on the same declaration.
        rulesBetween(
            snapshotOf("a.jar", type("com.example.Foo", annotations = listOf(withMetadata, real))),
            snapshotOf("b.jar", type("com.example.Foo", annotations = listOf(withOtherMetadata, realChanged))),
        ) shouldBe listOf(CompatRule.ANNOTATION_VALUES_CHANGED)

        // Member level too: a real annotation still reports.
        val old = snapshotOf(
            "a.jar",
            type(
                "com.example.Foo",
                isKotlin = true,
                members = listOf(method("f", annotations = listOf(withMetadata))),
            ),
        )
        val new = snapshotOf(
            "b.jar",
            type("com.example.Foo", isKotlin = true, members = listOf(method("f"))),
        )
        rulesBetween(old, new) shouldBe emptyList()
    }

    @Test
    fun `a removed enum constant is ENUM_CONSTANT_REMOVED, a removed plain field is not`() {
        val old = snapshotOf(
            "a.jar",
            type(
                "com.example.Light",
                kind = TypeKind.ENUM,
                members = listOf(field("RED", LIGHT, Access.of(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL))),
            ),
        )
        val new = snapshotOf("b.jar", type("com.example.Light", kind = TypeKind.ENUM))
        onlyFindingBetween(old, new).rule shouldBe CompatRule.ENUM_CONSTANT_REMOVED
    }

    @Test
    fun `a static field of an enum that is not its own type is an ordinary removal`() {
        val old = snapshotOf(
            "a.jar",
            type(
                "com.example.Light",
                kind = TypeKind.ENUM,
                members = listOf(field("LABEL", STRING, Access.of(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.FINAL))),
            ),
        )
        val new = snapshotOf("b.jar", type("com.example.Light", kind = TypeKind.ENUM))
        onlyFindingBetween(old, new).rule shouldBe CompatRule.MEMBER_REMOVED
    }

    @Test
    fun `an added method is MEMBER_ADDED and an added field too`() {
        val old = snapshotOf("a.jar", type("com.example.Foo"))
        val new = snapshotOf("b.jar", type("com.example.Foo", members = listOf(method("fresh"), field("count"))))
        rulesBetween(old, new) shouldBe listOf(CompatRule.MEMBER_ADDED, CompatRule.MEMBER_ADDED)
    }

    @Test
    fun `an abstract method added to an interface breaks implementors, a concrete one does not`() {
        val empty = type("com.example.Api", kind = TypeKind.INTERFACE)
        val withAbstract = type(
            "com.example.Api",
            kind = TypeKind.INTERFACE,
            members = listOf(method("must", access = Access.of(AccessFlag.PUBLIC, AccessFlag.ABSTRACT))),
        )
        val withDefault = type("com.example.Api", kind = TypeKind.INTERFACE, members = listOf(method("maybe")))
        rulesBetween(snapshotOf("a.jar", empty), snapshotOf("b.jar", withAbstract)) shouldBe
            listOf(CompatRule.INTERFACE_METHOD_ADDED)
        rulesBetween(snapshotOf("a.jar", empty), snapshotOf("b.jar", withDefault)) shouldBe
            listOf(CompatRule.MEMBER_ADDED)
    }

    @Test
    fun `an abstract method added to a class is MEMBER_ADDED, not an interface requirement`() {
        val old = snapshotOf("a.jar", type("com.example.Foo"))
        val new = snapshotOf(
            "b.jar",
            type(
                "com.example.Foo",
                members = listOf(method("must", access = Access.of(AccessFlag.PUBLIC, AccessFlag.ABSTRACT))),
            ),
        )
        rulesBetween(old, new) shouldBe listOf(CompatRule.MEMBER_ADDED)
    }

    @Test
    fun `an abstract method added to an interface as static is additive`() {
        val old = snapshotOf("a.jar", type("com.example.Api", kind = TypeKind.INTERFACE))
        val new = snapshotOf(
            "b.jar",
            type(
                "com.example.Api",
                kind = TypeKind.INTERFACE,
                members = listOf(
                    method("helper", access = Access.of(AccessFlag.PUBLIC, AccessFlag.STATIC, AccessFlag.ABSTRACT)),
                ),
            ),
        )
        rulesBetween(old, new) shouldBe listOf(CompatRule.MEMBER_ADDED)
    }

    // -- retyping: pairing a removal with an addition --------------------------------

    @Test
    fun `a method whose parameter type changed is one PARAMETER_TYPE_CHANGED, naming the new ref`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", members = listOf(method("f", listOf(STRING)))))
        val new = snapshotOf("b.jar", type("com.example.Foo", members = listOf(method("f", listOf(LONG)))))
        val finding = onlyFindingBetween(old, new)
        finding.rule shouldBe CompatRule.PARAMETER_TYPE_CHANGED
        finding.ref shouldBe "com.example.Foo#f(java.lang.String)"
        finding.detail shouldBe "now: com.example.Foo#f(long)"
    }

    @Test
    fun `a field whose type changed is one FIELD_TYPE_CHANGED naming both types`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", members = listOf(field("n", INT))))
        val new = snapshotOf("b.jar", type("com.example.Foo", members = listOf(field("n", LONG))))
        val finding = onlyFindingBetween(old, new)
        finding.rule shouldBe CompatRule.FIELD_TYPE_CHANGED
        finding.ref shouldBe "com.example.Foo#n"
        // A field's ref carries no type, so the detail has to — printing the ref twice
        // would tell the reader nothing.
        finding.detail shouldBe "int -> long"
    }

    @Test
    fun `an arity change is a removal and an addition, never a retyping`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", members = listOf(method("f", listOf(STRING)))))
        val new = snapshotOf("b.jar", type("com.example.Foo", members = listOf(method("f", listOf(STRING, INT)))))
        rulesBetween(old, new) shouldBe listOf(CompatRule.MEMBER_REMOVED, CompatRule.MEMBER_ADDED)
    }

    @Test
    fun `a method and a field sharing a name are never paired with each other`() {
        val old = snapshotOf(
            "a.jar",
            type("com.example.Foo", members = listOf(method("f", listOf(STRING)), field("f", INT))),
        )
        val new = snapshotOf(
            "b.jar",
            type("com.example.Foo", members = listOf(method("f", listOf(LONG)), field("f", LONG))),
        )
        // Buckets are keyed by kind, so the field pair is found and the method pair too.
        rulesBetween(old, new) shouldBe listOf(CompatRule.FIELD_TYPE_CHANGED, CompatRule.PARAMETER_TYPE_CHANGED)
    }

    @Test
    fun `an ambiguous shape bucket is left unpaired rather than guessed`() {
        // Two same-arity removals and one addition of the same arity: which removal
        // became the addition is unknowable, so nothing is paired.
        val old = snapshotOf(
            "a.jar",
            type("com.example.Foo", members = listOf(method("f", listOf(STRING)), method("f", listOf(LONG)))),
        )
        val new = snapshotOf("b.jar", type("com.example.Foo", members = listOf(method("f", listOf(INT), INT))))
        rulesBetween(old, new) shouldBe listOf(
            CompatRule.MEMBER_REMOVED,
            CompatRule.MEMBER_REMOVED,
            CompatRule.MEMBER_ADDED,
        )
    }

    @Test
    fun `an arity change disambiguates the bucket and pairs only the matching one`() {
        val old = snapshotOf(
            "a.jar",
            type("com.example.Foo", members = listOf(method("f", listOf(STRING)), method("f", listOf(STRING, INT)))),
        )
        val new = snapshotOf("b.jar", type("com.example.Foo", members = listOf(method("f", listOf(LONG), INT))))
        rulesBetween(old, new) shouldBe listOf(CompatRule.PARAMETER_TYPE_CHANGED, CompatRule.MEMBER_REMOVED)
    }

    @Test
    fun `a covariant return change is RETURN_TYPE_CHANGED alone, not a removal plus an addition`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", members = listOf(method("f", returnType = OBJECT))))
        val new = snapshotOf("b.jar", type("com.example.Foo", members = listOf(method("f", returnType = STRING))))
        val finding = onlyFindingBetween(old, new)
        finding.rule shouldBe CompatRule.RETURN_TYPE_CHANGED
        finding.detail shouldBe "java.lang.Object -> java.lang.String"
    }

    // -- access and modifiers on a member -------------------------------------------

    @Test
    fun `a member that got less visible is MEMBER_VISIBILITY_NARROWED, naming both levels`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", members = listOf(method("f", access = PUBLIC))))
        val new = snapshotOf(
            "b.jar",
            type("com.example.Foo", members = listOf(method("f", access = Access.of(AccessFlag.PROTECTED)))),
        )
        val finding = onlyFindingBetween(old, new)
        finding.rule shouldBe CompatRule.MEMBER_VISIBILITY_NARROWED
        finding.detail shouldBe "public -> protected"
    }

    @Test
    fun `final added to a method or field is MEMBER_MADE_FINAL, and dropping it is FINAL_REMOVED`() {
        val openMethod = type("com.example.Foo", members = listOf(method("f"), field("n", INT)))
        val sealedMethod = type(
            "com.example.Foo",
            members = listOf(
                method("f", access = Access.of(AccessFlag.PUBLIC, AccessFlag.FINAL)),
                field("n", INT, access = Access.of(AccessFlag.PUBLIC, AccessFlag.FINAL)),
            ),
        )
        rulesBetween(snapshotOf("a.jar", openMethod), snapshotOf("b.jar", sealedMethod)) shouldBe
            listOf(CompatRule.MEMBER_MADE_FINAL, CompatRule.MEMBER_MADE_FINAL)
        rulesBetween(snapshotOf("a.jar", sealedMethod), snapshotOf("b.jar", openMethod)) shouldBe
            listOf(CompatRule.FINAL_REMOVED, CompatRule.FINAL_REMOVED)
    }

    @Test
    fun `a static to instance flip and its inverse are two named rules`() {
        val instance = type("com.example.Foo", members = listOf(method("f")))
        val static = type(
            "com.example.Foo",
            members = listOf(method("f", access = Access.of(AccessFlag.PUBLIC, AccessFlag.STATIC))),
        )
        rulesBetween(snapshotOf("a.jar", static), snapshotOf("b.jar", instance)) shouldBe
            listOf(CompatRule.STATIC_TO_INSTANCE)
        rulesBetween(snapshotOf("a.jar", instance), snapshotOf("b.jar", static)) shouldBe
            listOf(CompatRule.INSTANCE_TO_STATIC)
    }

    @Test
    fun `a method that became abstract is ABSTRACT_ADDED, and concrete again is ABSTRACT_REMOVED`() {
        val concrete = type("com.example.Foo", members = listOf(method("f")))
        val abstract = type(
            "com.example.Foo",
            members = listOf(method("f", access = Access.of(AccessFlag.PUBLIC, AccessFlag.ABSTRACT))),
        )
        rulesBetween(snapshotOf("a.jar", concrete), snapshotOf("b.jar", abstract)) shouldBe
            listOf(CompatRule.ABSTRACT_ADDED)
        rulesBetween(snapshotOf("a.jar", abstract), snapshotOf("b.jar", concrete)) shouldBe
            listOf(CompatRule.ABSTRACT_REMOVED)
    }

    @Test
    fun `an abstract class that became concrete is ABSTRACT_REMOVED`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", access = Access.of(AccessFlag.PUBLIC, AccessFlag.ABSTRACT)))
        val new = snapshotOf("b.jar", type("com.example.Foo", access = PUBLIC))
        rulesBetween(old, new) shouldBe listOf(CompatRule.ABSTRACT_REMOVED)
    }

    @Test
    fun `synchronized, native, transient and varargs each have their own direction`() {
        val plain = type("com.example.Foo", members = listOf(method("f"), field("n", INT)))
        val decorated = type(
            "com.example.Foo",
            members = listOf(
                method("f", synchronized = true, native = true, varargs = true),
                field("n", INT, transient = true),
            ),
        )
        rulesBetween(snapshotOf("a.jar", plain), snapshotOf("b.jar", decorated)) shouldBe
            listOf(CompatRule.TRANSIENT_CHANGED, CompatRule.VARARGS_CHANGED, CompatRule.NATIVE_ADDED, CompatRule.SYNCHRONIZED_ADDED)
        rulesBetween(snapshotOf("a.jar", decorated), snapshotOf("b.jar", plain)) shouldBe
            listOf(CompatRule.TRANSIENT_CHANGED, CompatRule.VARARGS_CHANGED, CompatRule.NATIVE_REMOVED, CompatRule.SYNCHRONIZED_REMOVED)
    }

    // -- throws, signatures, names -------------------------------------------------

    @Test
    fun `a possibly-checked exception added to throws is SUSPICIOUS, an unchecked one is not reported`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", members = listOf(method("f"))))
        val checked = snapshotOf(
            "b.jar",
            type("com.example.Foo", members = listOf(method("f", throws = listOf(IO_EXCEPTION)))),
        )
        val finding = onlyFindingBetween(old, checked)
        finding.rule shouldBe CompatRule.CHECKED_EXCEPTION_ADDED
        finding.detail shouldBe "java.io.IOException"
        val unchecked = snapshotOf(
            "b.jar",
            type("com.example.Foo", members = listOf(method("f", throws = listOf(RUNTIME_EXCEPTION)))),
        )
        rulesBetween(old, unchecked) shouldBe emptyList()
    }

    @Test
    fun `a dropped throws entry is THROWS_REMOVED`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", members = listOf(method("f", throws = listOf(IO_EXCEPTION)))))
        val new = snapshotOf("b.jar", type("com.example.Foo", members = listOf(method("f"))))
        rulesBetween(old, new) shouldBe listOf(CompatRule.THROWS_REMOVED)
    }

    @Test
    fun `a generic signature change is reported only when the erased shape held still`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", members = listOf(method("f", returnType = STRING))))
        val new = snapshotOf(
            "b.jar",
            type("com.example.Foo", members = listOf(method("f", returnType = STRING, genericSignature = "()Ljava/util/List<Ljava/lang/String;>;"))),
        )
        onlyFindingBetween(old, new).rule shouldBe CompatRule.GENERIC_SIGNATURE_CHANGED
        // Same change plus a return change: the return change already tells the story.
        val retyped = snapshotOf(
            "b.jar",
            type(
                "com.example.Foo",
                members = listOf(
                    method("f", returnType = OBJECT, genericSignature = "()Ljava/util/List<Ljava/lang/String;>;"),
                ),
            ),
        )
        rulesBetween(old, retyped) shouldBe listOf(CompatRule.RETURN_TYPE_CHANGED)
    }

    @Test
    fun `parameter names, their loss, and the Kotlin severity are three distinct rules`() {
        val named = type("com.example.Foo", members = listOf(method("f", listOf(INT), parameterNames = listOf("count"))))
        val renamed = type("com.example.Foo", members = listOf(method("f", listOf(INT), parameterNames = listOf("total"))))
        val anonymous = type("com.example.Foo", members = listOf(method("f", listOf(INT), parameterNames = listOf(null))))

        val renameFinding = onlyFindingBetween(snapshotOf("a.jar", named), snapshotOf("b.jar", renamed))
        renameFinding.rule shouldBe CompatRule.PARAMETER_NAME_CHANGED
        renameFinding.detail shouldBe "count -> total"

        val lostFinding = onlyFindingBetween(snapshotOf("a.jar", named), snapshotOf("b.jar", anonymous))
        lostFinding.rule shouldBe CompatRule.PARAMETER_NAMES_LOST
        lostFinding.detail shouldBe "1 names"

        val kotlinNamed = type(
            "com.example.Foo",
            isKotlin = true,
            members = listOf(method("f", listOf(INT), parameterNames = listOf("count"))),
        )
        val kotlinRenamed = type(
            "com.example.Foo",
            isKotlin = true,
            members = listOf(method("f", listOf(INT), parameterNames = listOf("total"))),
        )
        onlyFindingBetween(snapshotOf("a.jar", kotlinNamed), snapshotOf("b.jar", kotlinRenamed)).rule shouldBe
            CompatRule.KOTLIN_PARAMETER_NAME_CHANGED
    }

    @Test
    fun `a constant value and an annotation default each report their change`() {
        val old = snapshotOf(
            "a.jar",
            type("com.example.Foo", members = listOf(field("N", INT, constantValue = "1"), method("v", annotationDefault = "1"))),
        )
        val new = snapshotOf(
            "b.jar",
            type("com.example.Foo", members = listOf(field("N", INT, constantValue = "2"), method("v", annotationDefault = "2"))),
        )
        val findings = findingsBetween(old, new)
        findings.map { it.rule } shouldBe listOf(CompatRule.FIELD_CONSTANT_VALUE_CHANGED, CompatRule.ANNOTATION_DEFAULT_CHANGED)
        findings.map { it.detail } shouldBe listOf("1 -> 2", "1 -> 2")
    }

    // -- Kotlin -------------------------------------------------------------------

    @Test
    fun `a JVM rename of one Kotlin declaration is KOTLIN_NAME_CHANGED, not a removal and an addition`() {
        val old = snapshotOf(
            "a.jar",
            type(
                "com.example.Foo",
                isKotlin = true,
                members = listOf(
                    method(
                        "renamedForJvm",
                        returnType = STRING,
                        kotlin = KotlinMethodView(displayName = "greet", displayReturn = "kotlin.String"),
                        ref = "com.example.Foo#greet()",
                    ),
                ),
            ),
        )
        // The `@JvmName` is gone, so the JVM name is the Kotlin name again and the
        // member no longer needs a view at all.
        val new = snapshotOf("b.jar", type("com.example.Foo", isKotlin = true, members = listOf(method("greet", returnType = STRING))))
        val finding = onlyFindingBetween(old, new)
        finding.rule shouldBe CompatRule.KOTLIN_NAME_CHANGED
        finding.detail shouldBe "JVM name renamedForJvm -> greet"
    }

    @Test
    fun `a Java member whose name changed is a removal and an addition, never a Kotlin rename`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", members = listOf(method("before"))))
        val new = snapshotOf("b.jar", type("com.example.Foo", members = listOf(method("after"))))
        rulesBetween(old, new) shouldBe listOf(CompatRule.MEMBER_REMOVED, CompatRule.MEMBER_ADDED)
    }

    @Test
    fun `gaining and losing suspend is KOTLIN_SUSPEND_CHANGED in both directions`() {
        val plain = type("com.example.Foo", isKotlin = true, members = listOf(method("f", returnType = STRING)))
        val suspended = type(
            "com.example.Foo",
            isKotlin = true,
            members = listOf(
                method(
                    "f",
                    listOf(typeNameFromBinaryName("kotlin.coroutines.Continuation")),
                    returnType = OBJECT,
                    // `stripTrailingContinuation` is what makes the Kotlin arity 0, and
                    // `KotlinMembers` sets it for every real `suspend` function.
                    kotlin = KotlinMethodView(
                        displayName = "f",
                        stripTrailingContinuation = true,
                        markSuspend = true,
                        displayReturn = "kotlin.String",
                    ),
                ),
            ),
        )
        val added = onlyFindingBetween(snapshotOf("a.jar", plain), snapshotOf("b.jar", suspended))
        added.rule shouldBe CompatRule.KOTLIN_SUSPEND_CHANGED
        added.detail shouldBe "suspend added"
        val removed = onlyFindingBetween(snapshotOf("a.jar", suspended), snapshotOf("b.jar", plain))
        removed.rule shouldBe CompatRule.KOTLIN_SUSPEND_CHANGED
        removed.detail shouldBe "suspend removed"
    }

    @Test
    fun `a Kotlin display type change under one erasure is KOTLIN_NULLABILITY_CHANGED`() {
        val nullable = type(
            "com.example.Foo",
            isKotlin = true,
            members = listOf(
                method(
                    "f",
                    returnType = STRING,
                    kotlin = KotlinMethodView(displayName = "f", displayReturn = "kotlin.String?"),
                ),
            ),
        )
        val nonNull = type(
            "com.example.Foo",
            isKotlin = true,
            members = listOf(
                method("f", returnType = STRING, kotlin = KotlinMethodView(displayName = "f", displayReturn = "kotlin.String")),
            ),
        )
        val finding = onlyFindingBetween(snapshotOf("a.jar", nullable), snapshotOf("b.jar", nonNull))
        finding.rule shouldBe CompatRule.KOTLIN_NULLABILITY_CHANGED
        finding.detail shouldBe "kotlin.String? -> kotlin.String"
    }

    @Test
    fun `a Kotlin default value appearing and disappearing are two rules, not one`() {
        val withDefault = type(
            "com.example.Foo",
            isKotlin = true,
            members = listOf(
                method(
                    "f",
                    listOf(INT),
                    returnType = STRING,
                    kotlin = KotlinMethodView(displayName = "f", defaultArgIndices = setOf(0)),
                ),
            ),
        )
        val withoutDefault = type("com.example.Foo", isKotlin = true, members = listOf(method("f", listOf(INT), returnType = STRING)))
        val removed = onlyFindingBetween(snapshotOf("a.jar", withDefault), snapshotOf("b.jar", withoutDefault))
        removed.rule shouldBe CompatRule.KOTLIN_DEFAULT_ARG_REMOVED
        removed.detail shouldBe "parameter 1"
        val added = onlyFindingBetween(snapshotOf("a.jar", withoutDefault), snapshotOf("b.jar", withDefault))
        added.rule shouldBe CompatRule.KOTLIN_DEFAULT_ARG_ADDED
    }

    @Test
    fun `a val that became a var is KOTLIN_PROPERTY_BECAME_MUTABLE, and the other way is not`() {
        val valProperty = type(
            "com.example.Foo",
            isKotlin = true,
            members = listOf(
                field(
                    "name",
                    STRING,
                    kotlin = KotlinPropertyView(propertyName = "name", isVar = false, displayType = "kotlin.String"),
                ),
            ),
        )
        val varProperty = type(
            "com.example.Foo",
            isKotlin = true,
            members = listOf(
                field(
                    "name",
                    STRING,
                    kotlin = KotlinPropertyView(propertyName = "name", isVar = true, displayType = "kotlin.String"),
                ),
            ),
        )
        onlyFindingBetween(snapshotOf("a.jar", valProperty), snapshotOf("b.jar", varProperty)).rule shouldBe
            CompatRule.KOTLIN_PROPERTY_BECAME_MUTABLE
        rulesBetween(snapshotOf("a.jar", varProperty), snapshotOf("b.jar", valProperty)) shouldBe emptyList()
    }

    @Test
    fun `a member with no Kotlin view on either side reports nothing Kotlin-specific`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", members = listOf(method("f", returnType = STRING))))
        val new = snapshotOf("b.jar", type("com.example.Foo", members = listOf(method("f", returnType = OBJECT))))
        rulesBetween(old, new) shouldBe listOf(CompatRule.RETURN_TYPE_CHANGED)
    }

    // -- annotations ---------------------------------------------------------------

    @Test
    fun `an annotation added, removed or re-valued are three separate rules`() {
        val bare = type("com.example.Foo", members = listOf(method("f")))
        val withOverride = type(
            "com.example.Foo",
            members = listOf(method("f", annotations = listOf(AnnotationInfo(TYPE_NAME("java.lang.Override"))))),
        )
        val withDeprecated = type(
            "com.example.Foo",
            members = listOf(
                method(
                    "f",
                    annotations = listOf(AnnotationInfo(TYPE_NAME("java.lang.Deprecated"), mapOf("forRemoval" to "true"))),
                ),
            ),
        )
        rulesBetween(snapshotOf("a.jar", bare), snapshotOf("b.jar", withOverride)) shouldBe
            listOf(CompatRule.ANNOTATION_ADDED)
        rulesBetween(snapshotOf("a.jar", withOverride), snapshotOf("b.jar", bare)) shouldBe
            listOf(CompatRule.ANNOTATION_REMOVED)
        rulesBetween(snapshotOf("a.jar", withOverride), snapshotOf("b.jar", withDeprecated)) shouldBe
            listOf(CompatRule.ANNOTATION_ADDED, CompatRule.ANNOTATION_REMOVED)
        rulesBetween(
            snapshotOf("a.jar", withDeprecated),
            snapshotOf(
                "b.jar",
                type(
                    "com.example.Foo",
                    members = listOf(
                        method("f", annotations = listOf(AnnotationInfo(TYPE_NAME("java.lang.Deprecated"), mapOf("forRemoval" to "false")))),
                    ),
                ),
            ),
        ) shouldBe listOf(CompatRule.ANNOTATION_VALUES_CHANGED)
    }

    @Test
    fun `a deprecation is its own rule, and an annotation carrying Deprecated does not double-report`() {
        val plain = type("com.example.Foo")
        val deprecatedType = type("com.example.Foo", deprecated = true)
        rulesBetween(snapshotOf("a.jar", plain), snapshotOf("b.jar", deprecatedType)) shouldBe
            listOf(CompatRule.DEPRECATED_ADDED)
        rulesBetween(snapshotOf("a.jar", deprecatedType), snapshotOf("b.jar", plain)) shouldBe
            listOf(CompatRule.DEPRECATED_REMOVED)
    }

    // -- ordering, counts, and the severity contract --------------------------------

    @Test
    fun `ordered findings put every breaking change before every suspicious one`() {
        val old = snapshotOf("a.jar", type("com.example.Foo", members = listOf(method("gone"), method("f"))))
        val new = snapshotOf(
            "b.jar",
            type("com.example.Foo", members = listOf(method("f", throws = listOf(IO_EXCEPTION)), method("fresh"))),
        )
        val ordered = ApiDiffer.diff(old, new).ordered
        ordered.map { it.rule } shouldBe listOf(
            CompatRule.MEMBER_REMOVED,
            CompatRule.CHECKED_EXCEPTION_ADDED,
            CompatRule.MEMBER_ADDED,
        )
    }

    @Test
    fun `the order is total, so two runs order the same findings the same way`() {
        val old = snapshotOf(
            "a.jar",
            type("com.example.Foo", members = listOf(method("b"), method("a"), field("z"))),
        )
        val new = snapshotOf("b.jar", type("com.example.Foo"))
        val forward = ApiDiffer.diff(old, new).ordered
        val reversed = ApiDiffer.diff(old, new).findings.reversed().sortedWith(DIFF_FINDING_ORDER)
        forward.map { it.ref } shouldBe reversed.map { it.ref }
    }

    @Test
    fun `counts are read off the findings, never supplied`() {
        val old = snapshotOf(
            "a.jar",
            type("com.example.Foo", members = listOf(method("gone"), method("f", throws = listOf(IO_EXCEPTION)))),
            type("com.example.Gone"),
        )
        val new = snapshotOf(
            "b.jar",
            type("com.example.Foo", members = listOf(method("f"))),
            type("com.example.Fresh"),
        )
        val counts = DiffCounts.of(ApiDiffer.diff(old, new))
        // MEMBER_REMOVED + TYPE_REMOVED are breaking; THROWS_REMOVED and TYPE_ADDED are
        // informational. The tally is derived, so it must match the list exactly.
        counts.breaking shouldBe 2
        counts.suspicious shouldBe 0
        counts.informational shouldBe 2
        counts.typesAdded shouldBe 1
        counts.typesRemoved shouldBe 1
        counts.total shouldBe 4
        counts.typesOld shouldBe 2
        counts.typesNew shouldBe 2
    }

    @Test
    fun `every rule carries the severity the catalogue promises`() {
        // The catalogue groups BREAKING first, then SUSPICIOUS, then INFO. A rule moving
        // between groups is a contract change, and this is where it gets caught.
        val severities = CompatRule.entries.map { it.severity }
        severities shouldBe severities.sortedBy { it.ordinal }
        DiffSeverity.entries.map { it.name } shouldBe listOf("BREAKING", "SUSPICIOUS", "INFO")
        DiffSeverity.entries.size shouldBe 3
        // All three bands are actually used, and every rule is reachable from a diff.
        CompatRule.entries.map { it.severity }.distinct() shouldBe DiffSeverity.entries.toList()
        // Rule ids are the enum names, and no two share one: an agent filters on them.
        CompatRule.entries.map { it.name }.distinct().size shouldBe CompatRule.entries.size
    }

    @Test
    fun `a finding's severity is derived from its rule, so the two cannot disagree`() {
        val finding = DiffFinding(CompatRule.MEMBER_REMOVED, "com.example.Foo", "com.example.Foo#f()")
        finding.severity shouldBe DiffSeverity.BREAKING
        finding.detail shouldBe ""
        CompatRule.entries.forEach { rule ->
            DiffFinding(rule, "T", "R").severity shouldBe rule.severity
        }
    }

    // -- gates and flags ------------------------------------------------------------

    @Test
    fun `the gate trips on breaking findings only when asked to`() {
        DiffGate(FailOn.NONE, breakingCount = 3, findingCount = 9).tripped shouldBe false
        DiffGate(FailOn.NONE, breakingCount = 3, findingCount = 9).exitCode shouldBe 0
        DiffGate(FailOn.BREAKING, breakingCount = 1, findingCount = 9).tripped shouldBe true
        DiffGate(FailOn.BREAKING, breakingCount = 1, findingCount = 9).exitCode shouldBe 1
        DiffGate(FailOn.BREAKING, breakingCount = 0, findingCount = 9).tripped shouldBe false
        DiffGate(FailOn.BREAKING, breakingCount = 0, findingCount = 9).exitCode shouldBe 0
        DiffGate(FailOn.ANY, breakingCount = 0, findingCount = 1).tripped shouldBe true
        DiffGate(FailOn.ANY, breakingCount = 0, findingCount = 0).tripped shouldBe false
    }

    @Test
    fun `flag parsing is total and case-insensitive, and never guesses`() {
        FailOn.fromFlag("breaking") shouldBe FailOn.BREAKING
        FailOn.fromFlag("ANY") shouldBe FailOn.ANY
        FailOn.fromFlag("none") shouldBe FailOn.NONE
        FailOn.fromFlag("") shouldBe null
        FailOn.fromFlag("maybe") shouldBe null
        ApiSurface.fromFlag("All") shouldBe ApiSurface.ALL
        ApiSurface.fromFlag("private") shouldBe null
        SeverityFilter.fromFlag("breaking") shouldBe SeverityFilter.BREAKING
        SeverityFilter.fromFlag("suspicious") shouldBe SeverityFilter.SUSPICIOUS
        SeverityFilter.fromFlag("all") shouldBe SeverityFilter.ALL
        SeverityFilter.fromFlag("info") shouldBe null
    }

    @Test
    fun `a severity filter threshold is cumulative, so suspicious means breaking too`() {
        SeverityFilter.ALL.threshold shouldBe DiffSeverity.INFO
        SeverityFilter.SUSPICIOUS.threshold shouldBe DiffSeverity.SUSPICIOUS
        SeverityFilter.BREAKING.threshold shouldBe DiffSeverity.BREAKING
        val all = listOf(
            DiffFinding(CompatRule.MEMBER_REMOVED, "T", "R"),
            DiffFinding(CompatRule.CHECKED_EXCEPTION_ADDED, "T", "R"),
            DiffFinding(CompatRule.MEMBER_ADDED, "T", "R"),
        )
        all.count { it.severity <= SeverityFilter.BREAKING.threshold } shouldBe 1
        all.count { it.severity <= SeverityFilter.SUSPICIOUS.threshold } shouldBe 2
        all.count { it.severity <= SeverityFilter.ALL.threshold } shouldBe 3
    }

    @Test
    fun `a possibly-checked throw is classified without loading anything`() {
        isPossiblyChecked(IO_EXCEPTION) shouldBe true
        isPossiblyChecked(RUNTIME_EXCEPTION) shouldBe false
        isPossiblyChecked(TYPE_NAME("java.lang.Error")) shouldBe false
        isPossiblyChecked(TYPE_NAME("java.lang.IllegalStateException")) shouldBe true
    }
}

// -- values used across the supertype tests ----------------------------------------

private val LIGHT: TypeName = typeNameFromBinaryName("com.example.Light")
private val CLONEABLE: TypeName = typeNameFromBinaryName("java.lang.Cloneable")
private val BASE_SUPERCLASS: TypeName = typeNameFromBinaryName("com.example.Base")
private val PARENT_SUPERCLASS: TypeName = typeNameFromBinaryName("com.example.Parent")
private val GRANDPARENT_SUPERCLASS: TypeName = typeNameFromBinaryName("com.example.GrandParent")
private val MARKER_SUPERCLASS: TypeName = typeNameFromBinaryName("com.example.Marker")

/**
 * The universal roots a *real* class can name besides `java.lang.Object`: the
 * implicit superclass of an enum, a record and a throwable. Each one must be as
 * quiet as `java.lang.Object`, or the same caveat comes back under a different name.
 */
private val UNIVERSAL_ROOTS: List<TypeName> = listOf(
    "java.lang.Object",
    "java.lang.Enum",
    "java.lang.Record",
    "java.lang.Throwable",
    "java.lang.annotation.Annotation",
).map { typeNameFromBinaryName(it) }

private fun TYPE_NAME(binary: String): TypeName = typeNameFromBinaryName(binary)
