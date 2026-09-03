@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package intelligence.cli.schema

import intelligence.cli.io.JsonFiles
import java.nio.file.Path
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal object SchemaCatalog {
    private val schemaPaths: List<String> = listOf(
        "adapters/codex/hooks.schema.json",
        "adapters/codex/marketplace-lock.schema.json",
        "adapters/codex/plugin.schema.json",
        "adapters/github/hooks.schema.json",
        "adapters/github/plugin.schema.json",
        "core/agent.schema.json",
        "core/hook.schema.json",
        "core/instruction.schema.json",
        "core/lock.schema.json",
        "core/marketplace.schema.json",
        "core/plugin.schema.json",
        "core/reference-definitions.schema.json",
        "core/skill.schema.json",
        "marketplace/adaptable.schema.json",
        "marketplace/codex.schema.json",
        "marketplace/github.schema.json",
    )

    private val documents: Map<String, JsonObject> by lazy {
        schemaPaths.associateWith(::load)
    }

    val complete: JsonObject by lazy {
        schemaRoot(
            title = "projeKtor document schema suite",
            description = "Self-contained schemas for every adaptable, Codex, and GitHub Copilot marketplace or plugin document supported by projeKtor.",
            oneOf = DocumentShape.entries.map { shape ->
                SchemaBranch(
                    title = shape.cliName,
                    reference = referenceTo(shape.schemaPath),
                )
            },
        )
    }

    fun forShape(shape: DocumentShape): JsonObject =
        schemaRoot(
            title = "projeKtor ${shape.cliName} schema",
            description = "Self-contained validation contract for `${shape.cliName}` documents.",
            reference = referenceTo(shape.schemaPath),
        )

    private fun schemaRoot(
        title: String,
        description: String,
        reference: String? = null,
        oneOf: List<SchemaBranch> = emptyList(),
    ): JsonObject =
        JsonFiles.json.encodeToJsonElement(
            SchemaSuite(
                title = title,
                description = description,
                reference = reference,
                oneOf = oneOf,
                definitions = documents.mapValues { (path, document) ->
                    rewriteReferences(document, path, embeddedRoot = true)
                },
            ),
        ).jsonObject

    private fun load(path: String): JsonObject {
        val resource = "/schemas/$path"
        val text = SchemaCatalog::class.java.getResourceAsStream(resource)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { reader -> reader.readText() }
            ?: error("packaged schema resource is missing: $resource")
        return JsonFiles.json.parseToJsonElement(text).jsonObject
    }

    private fun rewriteReferences(
        element: JsonElement,
        ownerPath: String,
        embeddedRoot: Boolean = false,
    ): JsonElement =
        when (element) {
            is JsonObject ->
                buildJsonObject {
                    element.forEach { (key, value) ->
                        when {
                            embeddedRoot && key in setOf("${'$'}schema", "${'$'}id") -> Unit
                            key == "${'$'}ref" && value is JsonPrimitive && value.isString ->
                                put(key, rewrittenReference(ownerPath, value.content))
                            else -> put(key, rewriteReferences(value, ownerPath))
                        }
                    }
                }
            is JsonArray -> JsonArray(element.map { value -> rewriteReferences(value, ownerPath) })
            else -> element
        }

    private fun rewrittenReference(ownerPath: String, reference: String): String {
        val targetValue = reference.substringBefore('#')
        val fragment = reference.substringAfter('#', missingDelimiterValue = "")
        val targetPath =
            if (targetValue.isBlank()) {
                ownerPath
            } else {
                val parent = Path.of(ownerPath).parent ?: Path.of("")
                parent.resolve(targetValue).normalize().toString().replace('\\', '/')
            }
        check(targetPath in documents) {
            "schema reference escapes the packaged suite: $ownerPath -> $reference"
        }
        return referenceTo(targetPath) + fragment
    }

    private fun referenceTo(path: String): String =
        "#/${'$'}defs/${path.toJsonPointerSegment()}"

    private fun String.toJsonPointerSegment(): String =
        replace("~", "~0").replace("/", "~1")
}

@Serializable
private data class SchemaSuite(
    @EncodeDefault @SerialName("${'$'}schema") val schema: String = "https://json-schema.org/draft/2020-12/schema",
    @EncodeDefault @SerialName("${'$'}id") val id: String = "urn:projektor:schema:1",
    val title: String,
    val description: String,
    @SerialName("${'$'}ref") val reference: String? = null,
    val oneOf: List<SchemaBranch> = emptyList(),
    @SerialName("${'$'}defs") val definitions: Map<String, JsonElement>,
)

@Serializable
private data class SchemaBranch(
    val title: String,
    @SerialName("${'$'}ref") val reference: String,
)
