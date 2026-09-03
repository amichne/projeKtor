@file:OptIn(io.github.optimumcode.json.schema.ExperimentalApi::class)

package intelligence.cli.schema

import io.github.optimumcode.json.schema.FormatBehavior
import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.JsonSchemaLoader
import io.github.optimumcode.json.schema.SchemaOption
import io.github.optimumcode.json.schema.ValidationError
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal class ValidatedDocument<out S : DocumentShape> private constructor(
    val shape: S,
    val value: JsonObject,
) {
    companion object {
        fun <S : DocumentShape> create(shape: S, value: JsonObject): ValidatedDocument<S> =
            ValidatedDocument(shape, value)
    }
}

internal sealed interface DocumentValidation<out S : DocumentShape> {
    data class Valid<S : DocumentShape>(
        val document: ValidatedDocument<S>,
    ) : DocumentValidation<S>

    data class Invalid(
        val violations: List<SchemaViolation>,
    ) : DocumentValidation<Nothing> {
        init {
            require(violations.isNotEmpty())
        }
    }
}

internal data class SchemaViolation(
    val instancePath: String,
    val message: String,
) {
    override fun toString(): String =
        if (instancePath.isBlank()) message else "$instancePath: $message"
}

internal object SchemaDocumentValidator {
    private val compiledSchemas = mutableMapOf<DocumentShape, JsonSchema>()
    private val schemaLoader = JsonSchemaLoader.create()
        .withSchemaOption(
            SchemaOption.FORMAT_BEHAVIOR_OPTION,
            FormatBehavior.ANNOTATION_AND_ASSERTION,
        )

    @Synchronized
    fun <S : DocumentShape> validate(shape: S, value: JsonElement): DocumentValidation<S> {
        val document = value as? JsonObject
            ?: return DocumentValidation.Invalid(
                listOf(SchemaViolation(instancePath = "${'$'}", message = "document must be a JSON object")),
            )
        val errors = mutableListOf<ValidationError>()
        val schema = compiledSchemas.getOrPut(shape) {
            schemaLoader.fromJsonElement(SchemaCatalog.forShape(shape))
        }
        return if (schema.validate(document, errors::add)) {
            DocumentValidation.Valid(ValidatedDocument.create(shape, document))
        } else {
            DocumentValidation.Invalid(
                errors.map { error ->
                    SchemaViolation(
                        instancePath = error.objectPath.toString(),
                        message = error.message,
                    )
                },
            )
        }
    }
}
