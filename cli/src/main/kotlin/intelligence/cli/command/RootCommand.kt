package intelligence.cli.command

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import intelligence.cli.BuildInfo
import intelligence.cli.io.JsonFiles
import intelligence.cli.io.sorted
import intelligence.cli.schema.SchemaCatalog
import kotlinx.serialization.json.JsonElement
import kotlin.system.exitProcess as terminateProcess

internal class RootCommand : CoreCliktCommand(
    name = BuildInfo.NAME,
) {
    override val invokeWithoutSubcommand: Boolean = true
    private val schemaRequested by option(
        "--schema",
        help = "Print the complete self-contained JSON Schema suite.",
    ).flag(default = false)

    init {
        context {
            echoMessage = { _, message, trailingNewline, error ->
                val stream = if (error) System.err else System.out
                if (trailingNewline) stream.println(message) else stream.print(message)
            }
            exitProcess = { status -> terminateProcess(status) }
        }
        versionOption(BuildInfo.VERSION)
        subcommands(ProjectCommand(), IngestCommand(), ValidateCommand())
    }

    override fun help(context: Context): String =
        "Project provider-neutral agent tooling, ingest harness-native documents, and validate supported shapes."

    override fun run() {
        if (schemaRequested) {
            echo(
                JsonFiles.json.encodeToString(
                    JsonElement.serializer(),
                    SchemaCatalog.complete.sorted(),
                ),
            )
        } else if (currentContext.invokedSubcommand == null) {
            echoFormattedHelp()
        }
    }
}
