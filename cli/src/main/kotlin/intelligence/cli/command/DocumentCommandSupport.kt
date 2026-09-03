package intelligence.cli.command

import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import intelligence.cli.BuildInfo
import intelligence.cli.io.JsonFiles
import intelligence.cli.schema.DocumentShape
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.json.JsonElement

internal sealed interface JsonDocumentRead {
    data class Read(
        val value: JsonElement,
        val displaySource: String,
    ) : JsonDocumentRead

    sealed interface Failed : JsonDocumentRead {
        val message: String

        data class InvalidPath(override val message: String) : Failed

        data class Unavailable(override val message: String) : Failed

        data class InvalidJson(override val message: String) : Failed

        data class Io(override val message: String) : Failed
    }
}

internal object JsonDocumentReader {
    fun read(raw: String, allowStandardInput: Boolean = true): JsonDocumentRead {
        if (raw == "-" && allowStandardInput) {
            return parse(
                displaySource = "<stdin>",
                read = { System.`in`.bufferedReader(Charsets.UTF_8).readText() },
            )
        }
        val path =
            try {
                Path.of(raw).toAbsolutePath().normalize()
            } catch (_: InvalidPathException) {
                return JsonDocumentRead.Failed.InvalidPath("input must be a valid path")
            }
        if (!Files.isRegularFile(path)) {
            return JsonDocumentRead.Failed.Unavailable("JSON input does not exist: $path")
        }
        return parse(
            displaySource = path.toString(),
            read = { Files.readString(path, Charsets.UTF_8) },
        )
    }

    private fun parse(displaySource: String, read: () -> String): JsonDocumentRead =
        try {
            JsonDocumentRead.Read(
                value = JsonFiles.json.parseToJsonElement(read()),
                displaySource = displaySource,
            )
        } catch (failure: IOException) {
            JsonDocumentRead.Failed.Io(failure.message ?: "failed to read JSON input")
        } catch (failure: IllegalArgumentException) {
            JsonDocumentRead.Failed.InvalidJson(failure.message ?: "input is not valid JSON")
        }
}

internal fun CoreCliktCommand.rejectDocument(
    code: String,
    message: String,
    help: String,
): Nothing {
    echo(
        buildString {
            appendLine("error:")
            appendLine("  code: $code")
            appendLine("  message: ${message.quoteForCli()}")
            append("  help: ${help.quoteForCli()}")
        },
        trailingNewline = false,
    )
    throw ProgramResult(1)
}

internal fun CoreCliktCommand.readDocumentOrReject(
    raw: String,
    help: String,
    role: String = "INPUT",
    allowStandardInput: Boolean = true,
): JsonDocumentRead.Read =
    when (val result = JsonDocumentReader.read(raw, allowStandardInput)) {
        is JsonDocumentRead.Read -> result
        is JsonDocumentRead.Failed.InvalidPath -> rejectDocument("${role}_PATH_INVALID", result.message, help)
        is JsonDocumentRead.Failed.Unavailable -> rejectDocument("${role}_UNAVAILABLE", result.message, help)
        is JsonDocumentRead.Failed.InvalidJson -> rejectDocument("${role}_JSON_INVALID", result.message, help)
        is JsonDocumentRead.Failed.Io -> rejectDocument("${role}_IO", result.message, help)
    }

internal fun parseDocumentShapeOrReject(
    command: CoreCliktCommand,
    raw: String,
    help: String,
): DocumentShape =
    when (val result = DocumentShape.parse(raw)) {
        is DocumentShape.ParseResult.Parsed -> result.shape
        is DocumentShape.ParseResult.Unsupported ->
            command.rejectDocument(
                code = "SHAPE_UNSUPPORTED",
                message = "shape must be one of: ${DocumentShape.entries.joinToString { it.cliName }}",
                help = help,
            )
    }

internal fun List<*>.boundedDescription(limit: Int = 12): String {
    val shown = take(limit).joinToString("; ")
    return if (size <= limit) shown else "$shown; and ${size - limit} more"
}

private fun String.quoteForCli(): String =
    buildString {
        append('"')
        this@quoteForCli.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

internal fun documentCommandHelp(command: String): String =
    "${BuildInfo.NAME} $command --shape <shape> --input <file|->"
