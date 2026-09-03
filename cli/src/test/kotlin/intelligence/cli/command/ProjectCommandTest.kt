package intelligence.cli.command

import intelligence.cli.BuildInfo
import intelligence.cli.io.JsonFiles
import intelligence.cli.io.arrayValue
import intelligence.cli.io.stringValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.io.TempDir
import kotlinx.serialization.json.jsonObject

class ProjectCommandTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `root help exposes projection ingestion and validation`() {
        val result = RootCommand().test("--help")

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.contains("Project provider-neutral agent tooling"))
        assertTrue(result.stdout.lineSequence().any { line -> line.trimStart().startsWith("project ") })
        assertTrue(result.stdout.lineSequence().any { line -> line.trimStart().startsWith("ingest ") })
        assertTrue(result.stdout.lineSequence().any { line -> line.trimStart().startsWith("validate ") })
        listOf("doctor", "setup", "marketplace", "rpc", "install", "publish").forEach { command ->
            assertFalse(result.stdout.lineSequence().any { line -> line.trimStart().startsWith("$command ") })
        }
    }

    @Test
    fun `project reports argument failures as structured stdout`() {
        val missing = RootCommand().test("project")
        val unsupported = RootCommand().test(
            "project", "--source", "/tmp/source", "--harness", "cursor", "--out", "/tmp/output",
        )

        assertEquals(1, missing.statusCode)
        assertTrue(missing.stdout.contains("code: SOURCE_REQUIRED"))
        assertEquals("", missing.stderr)
        assertEquals(1, unsupported.statusCode)
        assertTrue(unsupported.stdout.contains("code: HARNESS_UNSUPPORTED"))
        assertEquals("", unsupported.stderr)
    }

    @Test
    fun `project converts one source marketplace to codex`() {
        val source = minimalMarketplaceSource()
        val output = temporaryDirectory.resolve("codex")

        val result = RootCommand().test(
            "project", "--source", source.toString(), "--harness", "codex", "--out", output.toString(),
        )

        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stdout.contains("status: projected"))
        assertTrue(result.stdout.contains("harness: codex"))
        assertTrue(output.resolve(".agents/plugins/marketplace.json").exists())
        val pluginRoot = output.resolve(".agents/plugins/core-plugin")
        val pluginManifest = pluginRoot.resolve(".codex-plugin/plugin.json")
        assertTrue(pluginManifest.exists())
        assertTrue(pluginManifest.toFile().readText().contains("\"./hooks/core-hook.hooks.json\""))
        assertTrue(
            pluginRoot.resolve("hooks/core-hook.hooks.json").toFile().readText()
                .contains("${'$'}PLUGIN_ROOT/hooks/core-hook.py"),
        )
        assertTrue(pluginRoot.resolve("hooks/core-hook.py").exists())
        assertFalse(output.resolve(".agents/plugins/unexposed-plugin").exists())
    }

    @Test
    fun `project converts one source marketplace to github copilot`() {
        val source = minimalMarketplaceSource()
        val output = temporaryDirectory.resolve("github-copilot")

        val result = RootCommand().test(
            "project", "--source", source.toString(), "--harness", "github-copilot", "--out", output.toString(),
        )

        assertEquals(0, result.statusCode, result.stderr)
        assertTrue(result.stdout.contains("status: projected"))
        assertTrue(result.stdout.contains("harness: github-copilot"))
        val providerRoot = output.resolve(".github/plugin")
        val marketplace = JsonFiles.readObject(providerRoot.resolve("marketplace.json"))
        val pluginEntry = marketplace.arrayValue("plugins").single().jsonObject
        val pluginRoot = providerRoot.resolve("core-plugin")
        val pluginManifest = JsonFiles.readObject(pluginRoot.resolve("plugin.json"))

        assertEquals("core-plugin", pluginEntry.stringValue("source"))
        assertEquals("core-plugin", pluginManifest.stringValue("name"))
        assertEquals("./agents", pluginManifest.stringValue("agents"))
        assertEquals("./skills", pluginManifest.stringValue("skills"))
        assertEquals("./hooks.json", pluginManifest.stringValue("hooks"))
        assertTrue(pluginRoot.resolve("skills/core-skill/SKILL.md").exists())
        assertFalse(pluginRoot.resolve("agents/core-agent.agent.md").toFile().readText().contains("\nmodel:"))
        assertTrue(pluginRoot.resolve("hooks.json").toFile().readText().contains("\"version\": 1"))
        assertTrue(
            pluginRoot.resolve("hooks.json").toFile().readText()
                .contains("\"bash\": \"python3 \\\"${'$'}PLUGIN_ROOT/hooks/core-hook.py\\\" --repo .\""),
        )
    }

    @Test
    fun `project rejects overlapping source and output without touching source`() {
        val source = minimalMarketplaceSource()
        val sentinel = source.resolve("keep.txt").toFile().also { it.writeText("keep") }

        val result = RootCommand().test(
            "project", "--source", source.toString(), "--harness", "codex", "--out", source.toString(),
        )

        assertEquals(1, result.statusCode)
        assertTrue(result.stdout.contains("code: PATHS_OVERLAP"))
        assertEquals("keep", sentinel.readText())
    }

    @Test
    fun `project rejects invalid source without replacing existing output`() {
        val source = temporaryDirectory.resolve("invalid-source").also { it.createDirectories() }
        val output = temporaryDirectory.resolve("existing-output").also { it.createDirectories() }
        val sentinel = output.resolve("keep.txt").toFile().also { it.writeText("keep") }

        val result = RootCommand().test(
            "project", "--source", source.toString(), "--harness", "codex", "--out", output.toString(),
        )

        assertEquals(1, result.statusCode)
        assertTrue(result.stdout.contains("code: SOURCE_INVALID"))
        assertEquals("keep", sentinel.readText())
    }

    @Test
    fun `version option prints packaged version`() {
        val result = RootCommand().test("--version")

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.trim().startsWith("${BuildInfo.NAME} version "))
    }

    private fun minimalMarketplaceSource(): Path =
        temporaryDirectory.resolve("source-${System.nanoTime()}").also { repository ->
            writeJson(
                repository.resolve("source/adaptable.marketplace.json"),
                """
                {
                  "type": "MARKETPLACE",
                  "schemaVersion": 1,
                  "name": "fixture-marketplace",
                  "owner": { "name": "Fixture Owner" },
                  "plugins": [
                    {
                      "type": "PLUGIN_ENTRY",
                      "name": "core-plugin",
                      "plugin": {
                        "type": "PLUGIN_REFERENCE",
                        "name": "core-plugin",
                        "source": { "type": "LOCAL_SOURCE", "path": "./plugins/core-plugin" },
                        "version": "0.1.0"
                      },
                      "tags": ["engineering"]
                    }
                  ],
                  "skills": [],
                  "agents": [],
                  "hooks": [],
                  "instructions": []
                }
                """.trimIndent(),
            )
            writeJson(
                repository.resolve("source/plugins/core-plugin/plugin.json"),
                """
                {
                  "type": "PLUGIN",
                  "schemaVersion": 1,
                  "name": "core-plugin",
                  "version": "0.1.0",
                  "description": "Core plugin.",
                  "skills": [
                    {
                      "type": "SKILL",
                      "source": { "type": "LOCAL_SOURCE", "path": "./" },
                      "path": "skills/core-skill",
                      "name": "core-skill"
                    }
                  ],
                  "agents": [
                    {
                      "type": "AGENT",
                      "source": { "type": "LOCAL_SOURCE", "path": "./" },
                      "path": "agents/core-agent.agent.md",
                      "name": "core-agent"
                    }
                  ],
                  "instructions": [],
                  "hooks": [
                    {
                      "type": "HOOK",
                      "source": { "type": "LOCAL_SOURCE", "path": "./" },
                      "path": "hooks/core-hook.hook.json",
                      "name": "core-hook"
                    }
                  ]
                }
                """.trimIndent(),
            )
            writeJson(
                repository.resolve("source/plugins/unexposed-plugin/plugin.json"),
                """
                {
                  "type": "PLUGIN",
                  "schemaVersion": 1,
                  "name": "unexposed-plugin",
                  "version": "0.1.0",
                  "description": "Authored material outside this projection."
                }
                """.trimIndent(),
            )
            repository.resolve("source/skills/core-skill").createDirectories()
            repository.resolve("source/skills/core-skill/SKILL.md").toFile().writeText(
                """
                ---
                name: core-skill
                description: Core fixture skill.
                ---

                # Core skill
                """.trimIndent() + "\n",
            )
            repository.resolve("source/agents/core-agent.agent.md").toFile().apply {
                parentFile.mkdirs()
                writeText(
                    """
                    ---
                    name: core-agent
                    description: Core fixture agent.
                    model: sonnet
                    ---

                    Review the fixture.
                    """.trimIndent() + "\n",
                )
            }
            writeJson(
                repository.resolve("source/hooks/core-hook.hook.json"),
                """
                {
                  "type": "HOOK",
                  "source": { "type": "LOCAL_SOURCE", "path": "./" },
                  "path": "hooks/codex/core-hook.hooks.json",
                  "name": "core-hook"
                }
                """.trimIndent(),
            )
            writeJson(
                repository.resolve("source/hooks/codex/core-hook.hooks.json"),
                """
                {
                  "hooks": {
                    "Stop": [
                      {
                        "hooks": [
                          {
                            "type": "command",
                            "command": "python3 hooks/core-hook.py --repo .",
                            "timeout": 10
                          }
                        ]
                      }
                    ]
                  }
                }
                """.trimIndent(),
            )
            repository.resolve("source/hooks/core-hook.py").toFile().apply {
                parentFile.mkdirs()
                writeText("#!/usr/bin/env python3\n")
            }
        }

    private fun writeJson(path: Path, content: String) {
        path.parent.createDirectories()
        JsonFiles.writeObject(path, JsonFiles.json.parseToJsonElement(content).let { element -> element as kotlinx.serialization.json.JsonObject })
    }
}

private data class CommandResult(
    val stdout: String,
    val stderr: String,
    val statusCode: Int,
)

private fun RootCommand.test(vararg arguments: String): CommandResult {
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    var statusCode = 0
    configureContext {
        echoMessage = { _, message, trailingNewline, error ->
            val destination = if (error) stderr else stdout
            destination.append(message)
            if (trailingNewline) destination.append('\n')
        }
        exitProcess = { statusCode = it }
    }
    try {
        parse(arguments.toList())
    } catch (failure: CliktError) {
        echoFormattedHelp(failure)
        statusCode = failure.statusCode
    }
    return CommandResult(stdout.toString(), stderr.toString(), statusCode)
}
