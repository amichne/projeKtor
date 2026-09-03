package intelligence.cli.marketplace

import intelligence.cli.io.arrayValue
import intelligence.cli.io.JsonFiles
import intelligence.cli.io.objectValue
import intelligence.cli.io.stringValue
import intelligence.cli.validation.ProjectionValidationOptions
import intelligence.cli.validation.ProjectionValidator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

class ProjectionPipelineTest {
    @Test
    fun `materialize serializes each published harness projection`() {
        val codexOutput = Files.createTempDirectory("source-projection-codex-test-")
        val githubOutput = Files.createTempDirectory("source-projection-github-test-")
        val service = MarketplaceProjector(output = {})

        service.materialize(
            repoRoot = fixtureRoot(),
            outRoot = codexOutput,
            provider = MarketplaceProvider.Codex,
        )
        service.materialize(
            repoRoot = fixtureRoot(),
            outRoot = githubOutput,
            provider = MarketplaceProvider.GitHub,
        )

        assertTrue(codexOutput.resolve(".agents/plugins/marketplace.json").exists())
        val codexMarketplace =
            JsonFiles.readObject(codexOutput.resolve(".agents/plugins/marketplace.json"))
        val codexEntry = codexMarketplace.arrayValue("plugins").single().jsonObject
        assertEquals(
            "./.agents/plugins/fixture-plugin",
            codexEntry.objectValue("source")!!.stringValue("path"),
        )
        assertTrue(codexOutput.resolve(".agents/plugins/fixture-plugin").exists())

        assertTrue(githubOutput.resolve(".github/plugin/marketplace.json").exists())
        val githubMarketplace =
            JsonFiles.readObject(githubOutput.resolve(".github/plugin/marketplace.json"))
        assertEquals(".github/plugin", githubMarketplace.objectValue("metadata")!!.stringValue("pluginRoot"))
        val githubEntry = githubMarketplace.arrayValue("plugins").single().jsonObject
        assertEquals("fixture-plugin", githubEntry.stringValue("source"))
        val githubPluginRoot = githubOutput.resolve(".github/plugin/fixture-plugin")
        val githubPlugin = JsonFiles.readObject(githubPluginRoot.resolve("plugin.json"))
        assertEquals("./hooks.json", githubPlugin.stringValue("hooks"))
        assertTrue(githubPluginRoot.resolve("hooks.json").exists())

        assertEquals(
            0,
            ProjectionValidator(output = {}).validate(
                ProjectionValidationOptions(
                    repo = fixtureRoot(),
                    hydrated = codexOutput,
                )
            ),
        )
        assertEquals(
            0,
            ProjectionValidator(output = {}).validate(
                ProjectionValidationOptions(
                    repo = fixtureRoot(),
                    hydrated = githubOutput,
                )
            ),
        )
    }

    @Test
    fun `github hook metadata rewrites primitive dependencies to hydrated package paths`() {
        val repository = minimalMarketplaceRepository()
        writeJson(
            repository.resolve("source")
                .resolve("plugins")
                .resolve("core-plugin")
                .resolve("plugin.json"),
            """
            {
              "type": "PLUGIN",
              "schemaVersion": 1,
              "name": "core-plugin",
              "version": "0.1.0",
              "description": "Core plugin.",
              "instructions": [
                {
                  "type": "INSTRUCTION",
                  "source": {
                    "type": "LOCAL_SOURCE",
                    "path": "./"
                  },
                  "path": "concepts/type-safety/core.md",
                  "name": "type-safety"
                }
              ],
              "hooks": [
                {
                  "type": "HOOK",
                  "source": {
                    "type": "LOCAL_SOURCE",
                    "path": "./"
                  },
                  "path": "hooks/layout-check.hook.json",
                  "name": "layout-check"
                }
              ]
            }
            """.trimIndent(),
        )
        writeJson(
            repository.resolve("source").resolve("adaptable.marketplace.json"),
            """
            {
              "type": "MARKETPLACE",
              "schemaVersion": 1,
              "name": "fixture-marketplace",
              "owner": {
                "name": "Fixture Owner"
              },
              "plugins": [
                {
                  "type": "PLUGIN_ENTRY",
                  "name": "core-plugin",
                  "plugin": {
                    "type": "PLUGIN_REFERENCE",
                    "name": "core-plugin",
                    "source": {
                      "type": "LOCAL_SOURCE",
                      "path": "./plugins/core-plugin"
                    },
                    "version": "0.1.0"
                  },
                  "tags": [
                    "kotlin"
                  ]
                }
              ],
              "instructions": [
                {
                  "type": "INSTRUCTION",
                  "source": {
                    "type": "LOCAL_SOURCE",
                    "path": "./"
                  },
                  "path": "concepts/type-safety/core.md",
                  "name": "type-safety"
                }
              ],
              "hooks": [
                {
                  "type": "HOOK",
                  "source": {
                    "type": "LOCAL_SOURCE",
                    "path": "./"
                  },
                  "path": "hooks/layout-check.hook.json",
                  "name": "layout-check"
                }
              ]
            }
            """.trimIndent(),
        )
        writeJson(
            repository.resolve("source")
                .resolve("concepts")
                .resolve("type-safety")
                .resolve("core.md"),
            """
            # Type Safety
            """.trimIndent(),
        )
        writeJson(
            repository.resolve("source")
                .resolve("hooks")
                .resolve("layout-check.hook.json"),
            """
            {
              "type": "HOOK",
              "source": {
                "type": "LOCAL_SOURCE",
                "path": "./"
              },
              "path": "hooks/codex/layout-check.hooks.json",
              "name": "layout-check",
              "dependsOn": [
                {
                  "type": "INSTRUCTION",
                  "source": {
                    "type": "LOCAL_SOURCE",
                    "path": "./"
                  },
                  "path": "concepts/type-safety/core.md",
                  "name": "type-safety"
                }
              ]
            }
            """.trimIndent(),
        )
        writeJson(
            repository.resolve("source")
                .resolve("hooks")
                .resolve("codex")
                .resolve("layout-check.hooks.json"),
            """
            {
              "hooks": {
                "Stop": [
                  {
                    "hooks": [
                      {
                        "type": "command",
                        "command": "bash hooks/layout-check.sh"
                      }
                    ]
                  }
                ]
              }
            }
            """.trimIndent(),
        )
        repository.resolve("source")
            .resolve("hooks")
            .resolve("layout-check.sh")
            .also {
                it.parent.createDirectories()
                it.writeText("#!/usr/bin/env bash\n", Charsets.UTF_8)
            }
        val output = Files.createTempDirectory("source-projection-github-hook-metadata-")

        MarketplaceProjector(output = {}).materialize(
            repoRoot = repository,
            outRoot = output,
            provider = MarketplaceProvider.GitHub,
        )

        val hookMetadata = JsonFiles.readObject(
            output.resolve(".github")
                .resolve("plugin")
                .resolve("core-plugin")
                .resolve("hooks")
                .resolve("metadata")
                .resolve("layout-check.hook.json")
        )
        val dependency = hookMetadata.arrayValue("dependsOn").single().jsonObject

        assertEquals("hooks/layout-check.hooks.json", hookMetadata.stringValue("path"))
        assertEquals("instructions/type-safety.md", dependency.stringValue("path"))
    }

    @Test
    fun `source validation rejects non https plugin interface URLs`() {
        val repository = Files.createTempDirectory("source-projection-interface-validation-")
        writeJson(
            repository.resolve("source").resolve("adaptable.marketplace.json"),
            """
            {
              "type": "MARKETPLACE",
              "schemaVersion": 1,
              "name": "fixture-marketplace",
              "owner": {
                "name": "Fixture Owner"
              },
              "plugins": [
                {
                  "name": "core-plugin",
                  "plugin": {
                    "source": {
                      "type": "LOCAL_SOURCE",
                      "path": "plugins/core-plugin"
                    },
                    "version": "0.1.0"
                  }
                }
              ]
            }
            """.trimIndent(),
        )
        writeJson(
            repository.resolve("source")
                .resolve("plugins")
                .resolve("core-plugin")
                .resolve("plugin.json"),
            """
            {
              "type": "PLUGIN",
              "schemaVersion": 1,
              "name": "core-plugin",
              "version": "0.1.0",
              "description": "Core plugin.",
              "interface": {
                "websiteURL": "http://example.invalid"
              }
            }
            """.trimIndent(),
        )

        val result = ProjectionValidator(output = {}).validate(
            ProjectionValidationOptions(
                repo = repository,
                hydrated = null,
            ),
        )

        assertTrue(result != 0)
    }

    @Test
    fun `hydrated codex validation rejects nested plugin source paths`() {
        val repository = Files.createTempDirectory("source-projection-stale-codex-path-")
        writeJson(
            repository.resolve(".agents").resolve("plugins").resolve("marketplace.json"),
            """
            {
              "name": "fixture-marketplace",
              "plugins": [
                {
                  "name": "core-plugin",
                  "source": {
                    "source": "local",
                    "path": "./.agents/plugins/plugins/core-plugin"
                  },
                  "policy": {
                    "installation": "AVAILABLE",
                    "authentication": "ON_INSTALL"
                  },
                  "category": "Engineering"
                }
              ]
            }
            """.trimIndent(),
        )
        writeJson(
            repository.resolve(".agents")
                .resolve("plugins")
                .resolve("plugins")
                .resolve("core-plugin")
                .resolve(".codex-plugin")
                .resolve("plugin.json"),
            """
            {
              "name": "core-plugin",
              "version": "0.1.0",
              "description": "Nested generated Codex plugin."
            }
            """.trimIndent(),
        )

        val result = ProjectionValidator(output = {}).validate(
            ProjectionValidationOptions(
                repo = repository,
                hydrated = repository,
            ),
        )

        assertTrue(result != 0)
    }

    @Test
    fun `hydrated github validation rejects nested plugin root`() {
        val repository = Files.createTempDirectory("source-projection-stale-github-path-")
        writeJson(
            repository.resolve(".github").resolve("plugin").resolve("marketplace.json"),
            """
            {
              "name": "fixture-marketplace",
              "owner": {
                "name": "Fixture Owner"
              },
              "metadata": {
                "pluginRoot": ".github/plugin/plugins"
              },
              "plugins": [
                {
                  "name": "core-plugin",
                  "source": "core-plugin"
                }
              ]
            }
            """.trimIndent(),
        )
        writeJson(
            repository.resolve(".github")
                .resolve("plugin")
                .resolve("plugins")
                .resolve("core-plugin")
                .resolve("AGENTS.md"),
            "# Core Plugin\n",
        )

        val result = ProjectionValidator(output = {}).validate(
            ProjectionValidationOptions(
                repo = repository,
                hydrated = repository,
            ),
        )

        assertTrue(result != 0)
    }

    @Test
    fun `hydrated github validation rejects a missing plugin manifest`() {
        val output = Files.createTempDirectory("source-projection-missing-github-plugin-manifest-")
        MarketplaceProjector(output = {}).materialize(
            repoRoot = fixtureRoot(),
            outRoot = output,
            provider = MarketplaceProvider.GitHub,
        )
        Files.delete(output.resolve(".github/plugin/fixture-plugin/plugin.json"))
        val validation = mutableListOf<String>()

        val result = ProjectionValidator(output = validation::add).validate(
            ProjectionValidationOptions(
                repo = fixtureRoot(),
                hydrated = output,
            ),
        )

        assertTrue(result != 0)
        assertTrue(validation.any { it.contains("missing plugin.json") })
    }

    @Test
    fun `hydrated github validation rejects a malformed hook event`() {
        val output = Files.createTempDirectory("source-projection-malformed-github-hooks-")
        MarketplaceProjector(output = {}).materialize(
            repoRoot = fixtureRoot(),
            outRoot = output,
            provider = MarketplaceProvider.GitHub,
        )
        writeJson(
            output.resolve(".github/plugin/fixture-plugin/hooks.json"),
            """
            {
              "version": 1,
              "hooks": {
                "Stop": "not-an-array"
              }
            }
            """,
        )
        val validation = mutableListOf<String>()

        val result = ProjectionValidator(output = validation::add).validate(
            ProjectionValidationOptions(
                repo = fixtureRoot(),
                hydrated = output,
            ),
        )

        assertTrue(result != 0)
        assertTrue(validation.any { it.contains("hook event `Stop` must be an array") })
    }

    private fun fixtureRoot(): Path =
        generateSequence(Path.of(".").toAbsolutePath().normalize()) { it.parent }
            .map { it.resolve(".github/fixtures/source-projection") }
            .first { it.resolve("source/adaptable.marketplace.json").toFile().isFile }

    private fun writeJson(path: Path, content: String) {
        path.parent.createDirectories()
        path.writeText(content.trimIndent() + "\n")
    }

    private fun minimalMarketplaceRepository(): Path {
        val repository = Files.createTempDirectory("source-projection-source-")
        writeJson(
            repository.resolve("source").resolve("adaptable.marketplace.json"),
            """
            {
              "type": "MARKETPLACE",
              "schemaVersion": 1,
              "name": "fixture-marketplace",
              "owner": {
                "name": "Fixture Owner"
              },
              "plugins": []
            }
            """.trimIndent(),
        )
        return repository
    }
}
