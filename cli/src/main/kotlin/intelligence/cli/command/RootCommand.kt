package intelligence.cli.command

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import intelligence.cli.BuildInfo
import kotlin.system.exitProcess as terminateProcess

internal class RootCommand : CoreCliktCommand(
    name = BuildInfo.NAME,
) {
    override val invokeWithoutSubcommand: Boolean = true

    init {
        context {
            echoMessage = { _, message, trailingNewline, error ->
                val stream = if (error) System.err else System.out
                if (trailingNewline) stream.println(message) else stream.print(message)
            }
            exitProcess = { status -> terminateProcess(status) }
        }
        versionOption(BuildInfo.VERSION)
        subcommands(ProjectCommand())
    }

    override fun help(context: Context): String =
        "Project provider-neutral agent tooling into harness-native material."

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            echoFormattedHelp()
        }
    }
}
