package intelligence.cli

import intelligence.cli.command.RootCommand
import com.github.ajalt.clikt.core.main

fun main(args: Array<String>) {
    RootCommand().main(args)
}
