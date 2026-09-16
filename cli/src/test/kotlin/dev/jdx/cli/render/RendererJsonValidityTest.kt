package dev.jdx.cli.render

import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.render.MemberListingOptions
import dev.jdx.core.render.buildMemberListing
import dev.jdx.core.resolve.MemberResolver
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Outside check on core's hand-rolled JSON (T-010, D-028): parses renderer
 * output with kotlinx.serialization and asserts the envelope structurally.
 * Hostile strings (quotes, backslashes, newlines, unicode) must survive the
 * round trip — a hand-rolled escaper is exactly where such bugs hide.
 */
class RendererJsonValidityTest {

    private fun classType(binary: String): TypeName.ClassType =
        typeNameFromBinaryName(binary) as TypeName.ClassType

    private val target = ClassInfo(
        name = classType("com.example.Holder"),
        kind = TypeKind.CLASS,
        superclass = classType("java.lang.Object"),
        fields = listOf(
            FieldInfo(
                name = "label",
                type = classType("java.lang.String"),
                access = Access.of(AccessFlag.PUBLIC),
            ),
        ),
        methods = listOf(
            MethodInfo(
                name = "get",
                descriptor = JvmDescriptor.parse("()Ljava/lang/Object;") as JvmDescriptor.Method,
                access = Access.of(AccessFlag.PUBLIC),
                parameterNames = listOf(),
            ),
        ),
    )
    private val objectStub = ClassInfo(
        name = classType("java.lang.Object"),
        kind = TypeKind.CLASS,
        superclass = null,
    )

    @Test
    fun `listing json parses and carries the envelope fields`() {
        val listing = buildMemberListing(
            target = target,
            resolved = MemberResolver.resolve(target, { name ->
                when (name.binaryName) {
                    "com.example.Holder" -> target
                    "java.lang.Object" -> objectStub
                    else -> null
                }
            }),
            provenance = listOf(
                Provenance(
                    artifact = "weird\\\"name.jar",
                    origin = Origin.BYTECODE,
                ),
            ),
            options = MemberListingOptions(),
        )
        val parsed = Json.parseToJsonElement(listing.toJson(command = "members")).jsonObject
        parsed.getValue("jdx").jsonPrimitive.int shouldBe 1
        parsed.getValue("ok").jsonPrimitive.content shouldBe "true"
        parsed.getValue("command").jsonPrimitive.content shouldBe "members"
        parsed.getValue("query").jsonPrimitive.content shouldBe "com.example.Holder"
        val result = parsed.getValue("result").jsonObject
        val rows = result.getValue("groups").jsonArray.flatMap {
            it.jsonObject.getValue("rows").jsonArray
        }
        rows.size shouldBe 2
        rows.map { it.jsonObject.getValue("ref").jsonPrimitive.content }.sorted() shouldBe listOf(
            "com.example.Holder#get()",
            "com.example.Holder#label",
        )
        val provenance = parsed.getValue("provenance").jsonArray
        provenance.size shouldBe 1
        provenance[0].jsonObject.getValue("artifact").jsonPrimitive.content shouldBe "weird\\\"name.jar"
    }

    @Test
    fun `hostile warning text round-trips through the envelope`() {
        val warning = Warning(
            code = WarningCode.DUPLICATE_FQN,
            message = "same \"FQN\" in two jars\nsecond line\tλ",
            subject = "com.example.Holder",
        )
        val listing = buildMemberListing(
            target = target,
            resolved = MemberResolver.resolve(target, { null }),
            provenance = emptyList(),
        ).copy(warnings = listOf(warning))
        val parsed = Json.parseToJsonElement(listing.toJson(command = "members")).jsonObject
        val warnings = parsed.getValue("warnings").jsonArray
        warnings.size shouldBe 1
        warnings[0].jsonObject.getValue("message").jsonPrimitive.content shouldBe
            "same \"FQN\" in two jars\nsecond line\tλ"
    }

    @Test
    fun `error json parses with code and candidates`() {
        val json = dev.jdx.core.render.ErrorResult.ambiguous(
            query = "Holder#get",
            candidates = listOf("com.example.Holder#get()", "com.example.Holder#get(int)"),
        ).toJson(command = "signature")
        val parsed = Json.parseToJsonElement(json).jsonObject
        parsed.getValue("ok").jsonPrimitive.content shouldBe "false"
        parsed.getValue("error").jsonObject.getValue("code").jsonPrimitive.int shouldBe 2
        parsed.getValue("candidates").jsonArray.size shouldBe 2
    }
}
