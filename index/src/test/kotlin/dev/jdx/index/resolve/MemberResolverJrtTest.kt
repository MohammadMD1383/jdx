package dev.jdx.index.resolve

import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.GenericSignature
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.Visibility
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.resolve.MemberResolver
import dev.jdx.index.artifact.ArtifactLoader
import dev.jdx.index.asm.AsmClassReader
import dev.jdx.index.asm.ClassReadResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-2 spot-check for member resolution (T-009 acceptance: "`java.util.HashMap`
 * inherited completion list matches IntelliJ"). Reads real JDK classes from the running
 * runtime's `jrt:/` — no jar corpus needed, so this runs on any machine with a JDK —
 * and resolves them with the production [AsmClassReader] + [MemberResolver] pair.
 */
@Tag("tier2")
class MemberResolverJrtTest {

    /** A [MemberResolver] lookup backed by the running JDK, cached per binary name. */
    private class JrtLookup : (TypeName) -> ClassInfo? {
        private val root = ArtifactLoader.openJdk()
        private val cache = mutableMapOf<String, ClassInfo?>()

        override fun invoke(name: TypeName): ClassInfo? {
            val classType = name as? TypeName.ClassType ?: return null
            return cache.getOrPut(classType.binaryName) { read(classType) }
        }

        private fun read(type: TypeName.ClassType): ClassInfo? {
            val path = type.binaryName.replace('.', '/') + ".class"
            return try {
                root.openClass(path).use { stream ->
                    when (val result = AsmClassReader.read(stream, path)) {
                        is ClassReadResult.Ok -> result.info
                        else -> null
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun hashMap(lookup: JrtLookup): ClassInfo =
        lookup(typeNameFromBinaryName("java.util.HashMap"))
            ?: error("the running JDK has no java.util.HashMap")

    @Test
    fun `HashMap resolves the Map API agents complete on`() {
        // The names IntelliJ offers on `map.` — declared on HashMap or inherited from
        // AbstractMap/Map. Pinned as names, not counts: counts shift with JDK releases.
        val lookup = JrtLookup()
        val resolved = MemberResolver.resolve(hashMap(lookup), lookup)

        val names = resolved.methods.map { it.member.name }.toSet()
        val expected = setOf(
            "put", "get", "remove", "containsKey", "containsValue", "putAll", "clear",
            "keySet", "entrySet", "values", "size", "isEmpty",
            "getOrDefault", "putIfAbsent", "remove", "replace", "forEach",
            "clone",
        )
        (names.containsAll(expected)) shouldBe true
    }

    @Test
    fun `HashMap resolution is deterministic and complete`() {
        val lookup = JrtLookup()
        val target = hashMap(lookup)

        val first = MemberResolver.resolve(target, lookup)
        val second = MemberResolver.resolve(target, lookup)

        first shouldBe second
        // The JDK ships every supertype: nothing dangling.
        first.missingSupertypes shouldBe emptyList()
        // Linearisation bottoms out at Object, last.
        first.linearization.last().type.binaryName shouldBe "java.lang.Object"
    }

    @Test
    fun `no private supertype member leaks through HashMap`() {
        val lookup = JrtLookup()
        val resolved = MemberResolver.resolve(hashMap(lookup), lookup)

        resolved.methods
            .filter { it.declaringType.binaryName != "java.util.HashMap" }
            .none { it.member.access.visibility == Visibility.PRIVATE } shouldBe true
    }

    @Test
    fun `a concrete HashMap subclass substitutes K and V`() {
        // class StringMap extends HashMap<String, Integer>, resolved against real JDK
        // supertypes: inherited `put` must show (String, Integer), not (K, V).
        val lookup = JrtLookup()
        val stringMap = ClassInfo(
            name = typeNameFromBinaryName("test.StringMap") as TypeName.ClassType,
            kind = TypeKind.CLASS,
            superclass = typeNameFromBinaryName("java.util.HashMap"),
            genericSignature = GenericSignature.parseClass(
                "Ljava/util/HashMap<Ljava/lang/String;Ljava/lang/Integer;>;",
            ) ?: error("test signature must parse"),
        )
        val resolving: (TypeName) -> ClassInfo? = { name ->
            if (name.binaryName == "test.StringMap") stringMap else lookup(name)
        }

        val resolved = MemberResolver.resolve(stringMap, resolving)

        val put = resolved.methods.single {
            it.member.name == "put" && it.member.descriptor.descriptor ==
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
        }
        put.declaringType.binaryName shouldBe "java.util.HashMap"
        put.substitutedSignature?.signature shouldBe
            "(Ljava/lang/String;Ljava/lang/Integer;)Ljava/lang/Integer;"
    }
}
