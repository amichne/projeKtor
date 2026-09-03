package intelligence.cli.command

import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import intelligence.cli.schema.DocumentValidation
import intelligence.cli.schema.SchemaDocumentValidator

internal class ValidateCommand : CoreCliktCommand(name = "validate") {
    private val shapeRaw by option(
        "--shape",
        help = "Document shape to validate.",
    )
    private val inputRaw by option(
        "--input",
        help = "JSON document path, or - for stdin.",
    )

    override fun help(context: Context): String =
        "Validate an adaptable or harness-native marketplace or plugin document."

    override fun run() {
        val help = documentCommandHelp("validate")
        val shape = parseDocumentShapeOrReject(
            command = this,
            raw = shapeRaw ?: rejectDocument("SHAPE_REQUIRED", "--shape is required", help),
            help = help,
        )
        val input = readDocumentOrReject(
            raw = inputRaw ?: rejectDocument("INPUT_REQUIRED", "--input is required", help),
            help = help,
        )
        when (val validation = SchemaDocumentValidator.validate(shape, input.value)) {
            is DocumentValidation.Valid ->
                echo(
                    buildString {
                        appendLine("status: valid")
                        appendLine("shape: ${shape.cliName}")
                        append("input: \"${input.displaySource.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
                    },
                    trailingNewline = false,
                )
            is DocumentValidation.Invalid ->
                rejectDocument(
                    code = "SCHEMA_INVALID",
                    message = validation.violations.boundedDescription(),
                    help = help,
                )
        }
    }
}
