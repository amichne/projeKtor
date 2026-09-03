package intelligence.cli.command

import intelligence.cli.BuildInfo
import intelligence.cli.io.FileSystem
import intelligence.cli.marketplace.MarketplaceFailure
import intelligence.cli.marketplace.MarketplaceProjector
import intelligence.cli.marketplace.MarketplaceProvider
import intelligence.cli.validation.ProjectionValidationOptions
import intelligence.cli.validation.ProjectionValidator
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option

internal class ProjectCommand : CoreCliktCommand(name = "project") {
    private val sourceRaw by option(
        "--source",
        help = "Provider-neutral marketplace repository to read.",
    )
    private val harnessRaw by option(
        "--harness",
        help = "Target harness: codex or github-copilot.",
    )
    private val outputRaw by option(
        "--out",
        help = "Generated harness payload directory.",
    )

    override fun help(context: Context): String =
        "Convert one provider-neutral source tree into one harness-native projection."

    override fun run() {
        val source = sourceRaw ?: reject("SOURCE_REQUIRED", "--source is required")
        val harness = ProjectionHarness.parse(
            harnessRaw ?: reject("HARNESS_REQUIRED", "--harness is required"),
        ) ?: reject("HARNESS_UNSUPPORTED", "harness must be one of: codex, github-copilot")
        val output = outputRaw ?: reject("OUTPUT_REQUIRED", "--out is required")
        val normalizedSource = parsePath(source, "--source").toAbsolutePath().normalize()
        val normalizedOutput = parsePath(output, "--out").toAbsolutePath().normalize()
        if (!Files.isDirectory(normalizedSource)) {
            reject("SOURCE_UNAVAILABLE", "source directory does not exist: $normalizedSource")
        }
        if (normalizedOutput.startsWith(normalizedSource) || normalizedSource.startsWith(normalizedOutput)) {
            reject("PATHS_OVERLAP", "source and output directories must not overlap")
        }

        val sourceValidation = mutableListOf<String>()
        val sourceExit = ProjectionValidator(output = sourceValidation::add).validate(
            ProjectionValidationOptions(repo = normalizedSource, hydrated = null),
        )
        if (sourceExit != 0) {
            reject(
                code = "SOURCE_INVALID",
                message = sourceValidation.joinToString("; ") { it.removePrefix("FAIL ") },
            )
        }

        try {
            val outputParent = normalizedOutput.parent
                ?: reject("OUTPUT_UNAVAILABLE", "output directory must have a parent")
            Files.createDirectories(outputParent)
            val stagingOutput = Files.createTempDirectory(outputParent, ".${BuildInfo.NAME}-project-")
            try {
                MarketplaceProjector(output = {}).materialize(
                    repoRoot = normalizedSource,
                    outRoot = stagingOutput,
                    provider = harness.provider,
                )

                val hydratedValidation = mutableListOf<String>()
                val hydratedExit = ProjectionValidator(output = hydratedValidation::add).validate(
                    ProjectionValidationOptions(repo = normalizedSource, hydrated = stagingOutput),
                )
                if (hydratedExit != 0) {
                    reject(
                        code = "PROJECTION_INVALID",
                        message = hydratedValidation.joinToString("; ") { it.removePrefix("FAIL ") },
                    )
                }

                FileSystem.replaceDirectory(normalizedOutput)
                FileSystem.copyTreeContents(stagingOutput, normalizedOutput)
            } finally {
                FileSystem.deleteRecursively(stagingOutput)
            }
        } catch (failure: MarketplaceFailure) {
            reject("PROJECTION_REJECTED", failure.message ?: "projection was rejected")
        } catch (failure: IOException) {
            reject("PROJECTION_IO", failure.message ?: "projection failed while writing output")
        }

        val fileCount = Files.walk(normalizedOutput).use { paths ->
            paths.filter(Files::isRegularFile).count()
        }
        echo(
            buildString {
                appendLine("status: projected")
                appendLine("harness: ${harness.cliName}")
                appendLine("files: $fileCount")
                appendLine("source: ${quoteToon(normalizedSource.toString())}")
                append("output: ${quoteToon(normalizedOutput.toString())}")
            },
            trailingNewline = false,
        )
    }

    private fun reject(code: String, message: String): Nothing {
        echo(
            buildString {
                appendLine("error:")
                appendLine("  code: $code")
                appendLine("  message: ${quoteToon(message)}")
                append("  help: ${quoteToon("${BuildInfo.NAME} project --source <directory> --harness <codex|github-copilot> --out <directory>")}")
            },
            trailingNewline = false,
        )
        throw ProgramResult(1)
    }

    private fun parsePath(raw: String, option: String): Path =
        try {
            Path.of(raw)
        } catch (_: InvalidPathException) {
            reject("PATH_INVALID", "$option must be a valid path")
        }
}

private enum class ProjectionHarness(
    val cliName: String,
    val provider: MarketplaceProvider,
) {
    Codex("codex", MarketplaceProvider.Codex),
    GitHubCopilot("github-copilot", MarketplaceProvider.GitHub),
    ;

    companion object {
        fun parse(raw: String): ProjectionHarness? =
            entries.singleOrNull { harness -> harness.cliName == raw.lowercase() }
    }
}

private fun quoteToon(value: String): String =
    buildString {
        append('"')
        value.forEach { character ->
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
