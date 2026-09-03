package intelligence.cli.command

import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import intelligence.cli.io.JsonFiles
import intelligence.cli.io.sorted
import intelligence.cli.marketplace.HarnessDocumentIngestor
import intelligence.cli.marketplace.HarnessDocumentCodec
import intelligence.cli.marketplace.HarnessDocumentDecoding
import intelligence.cli.marketplace.HarnessIngestionRequest
import intelligence.cli.marketplace.HarnessIngestionResult
import intelligence.cli.marketplace.IngestionTarget
import intelligence.cli.marketplace.MarketplaceOwnerName
import intelligence.cli.marketplace.OwnerNameRefinement
import intelligence.cli.marketplace.shape
import intelligence.cli.schema.DocumentValidation
import intelligence.cli.schema.NativeDocumentShape
import intelligence.cli.schema.SchemaDocumentValidator

internal class IngestCommand : CoreCliktCommand(name = "ingest") {
    private val shapeRaw by option(
        "--shape",
        help = "Native input shape: codex-marketplace, codex-plugin, github-copilot-marketplace, or github-copilot-plugin.",
    )
    private val inputRaw by option(
        "--input",
        help = "Harness-native JSON document path, or - for stdin.",
    )
    private val intoRaw by option(
        "--into",
        help = "Existing adaptable marketplace or plugin JSON to extend.",
    )
    private val ownerNameRaw by option(
        "--owner-name",
        help = "Provider-neutral owner required when creating from a Codex marketplace, whose native shape has no owner.",
    )

    override fun help(context: Context): String =
        "Refine one harness-native marketplace or plugin document into its adaptable shape."

    override fun run() {
        val help = documentCommandHelp("ingest")
        val parsedShape = parseDocumentShapeOrReject(
            command = this,
            raw = shapeRaw ?: rejectDocument("SHAPE_REQUIRED", "--shape is required", help),
            help = help,
        )
        val shape = parsedShape as? NativeDocumentShape
            ?: rejectDocument(
                code = "SHAPE_NOT_NATIVE",
                message = "ingest accepts only Codex or GitHub Copilot input shapes",
                help = help,
            )
        val input = readDocumentOrReject(
            raw = inputRaw ?: rejectDocument("INPUT_REQUIRED", "--input is required", help),
            help = help,
        )
        val validatedInput =
            when (val validation = SchemaDocumentValidator.validate(shape, input.value)) {
                is DocumentValidation.Valid -> validation.document
                is DocumentValidation.Invalid ->
                    rejectDocument(
                        code = "SCHEMA_INVALID",
                        message = validation.violations.boundedDescription(),
                        help = help,
                    )
            }
        val nativeInput =
            when (val decoding = HarnessDocumentCodec.decodeNative(validatedInput)) {
                is HarnessDocumentDecoding.Decoded -> decoding.document
                is HarnessDocumentDecoding.Rejected ->
                    rejectDocument(
                        code = "INPUT_MODEL_INVALID",
                        message = decoding.reason,
                        help = help,
                    )
            }
        val target = intoRaw?.let { raw ->
            val targetInput = readDocumentOrReject(
                raw = raw,
                help = help,
                role = "TARGET",
                allowStandardInput = false,
            )
            when (val validation = SchemaDocumentValidator.validate(shape.adaptable, targetInput.value)) {
                is DocumentValidation.Valid ->
                    when (val decoding = HarnessDocumentCodec.decodeAdaptable(validation.document)) {
                        is HarnessDocumentDecoding.Decoded -> IngestionTarget.Existing(decoding.document)
                        is HarnessDocumentDecoding.Rejected ->
                            rejectDocument(
                                code = "TARGET_MODEL_INVALID",
                                message = decoding.reason,
                                help = help,
                            )
                    }
                is DocumentValidation.Invalid ->
                    rejectDocument(
                        code = "TARGET_SCHEMA_INVALID",
                        message = validation.violations.boundedDescription(),
                        help = help,
                    )
            }
        } ?: IngestionTarget.New
        val ownerName = ownerNameRaw?.let { raw ->
            when (val refinement = MarketplaceOwnerName.refine(raw)) {
                is OwnerNameRefinement.Refined -> refinement.name
                OwnerNameRefinement.Blank ->
                    rejectDocument("OWNER_INVALID", "--owner-name must not be blank", help)
            }
        }

        val ingested = HarnessDocumentIngestor.ingest(
            HarnessIngestionRequest(
                input = nativeInput,
                target = target,
                ownerName = ownerName,
            ),
        )
        val result =
            when (ingested) {
                is HarnessIngestionResult.Ingested -> ingested
                HarnessIngestionResult.Rejected.OwnerRequired ->
                    rejectDocument(
                        code = "OWNER_REQUIRED",
                        message = "--owner-name is required when creating an adaptable marketplace from Codex",
                        help = help,
                    )
                is HarnessIngestionResult.Rejected.KindConflict ->
                    rejectDocument(
                        code = "KIND_CONFLICT",
                        message = "input kind ${ingested.expected.name.lowercase()} cannot extend ${ingested.actual.name.lowercase()}",
                        help = help,
                    )
                is HarnessIngestionResult.Rejected.IdentityConflict ->
                    rejectDocument(
                        code = "IDENTITY_CONFLICT",
                        message = "input `${ingested.inputName}` cannot extend target `${ingested.targetName}`",
                        help = help,
                    )
                is HarnessIngestionResult.Rejected.AdapterExists ->
                    rejectDocument(
                        code = "ADAPTER_EXISTS",
                        message = "target already has a ${ingested.harness.adapterKey} adapter",
                        help = help,
                    )
            }
        val encoded = HarnessDocumentCodec.encode(result.document)
        when (val validation = SchemaDocumentValidator.validate(result.document.shape, encoded)) {
            is DocumentValidation.Valid ->
                echo(
                    JsonFiles.json.encodeToString(
                        kotlinx.serialization.json.JsonElement.serializer(),
                        validation.document.value.sorted(),
                    ),
                )
            is DocumentValidation.Invalid ->
                rejectDocument(
                    code = "OUTPUT_SCHEMA_INVALID",
                    message = validation.violations.boundedDescription(),
                    help = help,
                )
        }
    }
}
