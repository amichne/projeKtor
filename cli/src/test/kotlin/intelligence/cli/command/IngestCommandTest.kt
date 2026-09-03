package intelligence.cli.command

import intelligence.cli.io.JsonFiles
import intelligence.cli.io.arrayValue
import intelligence.cli.io.objectValue
import intelligence.cli.io.stringValue
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir

class IngestCommandTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `schema option emits the complete self contained document schema`() {
        val result = RootCommand().testIngestion("--schema")

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals("", result.stderr)
        val schema = JsonFiles.json.parseToJsonElement(result.stdout).jsonObject
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.stringValue("${'$'}schema"))
        assertEquals(6, schema.arrayValue("oneOf").size)
        assertEquals(16, schema.objectValue("${'$'}defs")?.size)
        val references = schema.references()
        assertTrue(references.isNotEmpty())
        assertTrue(references.all { reference -> reference.startsWith("#/") })

        val compiled = JsonSchema.fromJsonElement(schema)
        listOf(codexMarketplace(), codexPlugin(), githubMarketplace(), githubPlugin()).forEach { fixture ->
            val errors = mutableListOf<ValidationError>()
            assertTrue(
                compiled.validate(JsonFiles.json.parseToJsonElement(fixture), errors::add),
                errors.joinToString(),
            )
        }
    }

    @Test
    fun `ingest refines every native harness document and preserves its exact adapter`() {
        val fixtures = listOf(
            NativeFixture(
                shape = "codex-marketplace",
                adapter = "codex",
                adapterContainerType = "CODEX_MARKETPLACE_ADAPTER",
                adaptableType = "MARKETPLACE",
                json = codexMarketplace(),
                extraArguments = listOf("--owner-name", "Platform Team"),
            ),
            NativeFixture(
                shape = "codex-plugin",
                adapter = "codex",
                adapterContainerType = "CODEX_PLUGIN_ADAPTER",
                adaptableType = "PLUGIN",
                json = codexPlugin(),
            ),
            NativeFixture(
                shape = "github-copilot-marketplace",
                adapter = "github-copilot",
                adapterContainerType = "GITHUB_COPILOT_MARKETPLACE_ADAPTER",
                adaptableType = "MARKETPLACE",
                json = githubMarketplace(),
            ),
            NativeFixture(
                shape = "github-copilot-plugin",
                adapter = "github-copilot",
                adapterContainerType = "GITHUB_COPILOT_PLUGIN_ADAPTER",
                adaptableType = "PLUGIN",
                json = githubPlugin(),
            ),
        )

        fixtures.forEach { fixture ->
            val input = writeFixture("${fixture.shape}.json", fixture.json)
            val result = RootCommand().testIngestion(
                *(listOf("ingest", "--shape", fixture.shape, "--input", input.toString()) + fixture.extraArguments)
                    .toTypedArray(),
            )

            assertEquals(0, result.statusCode, "${fixture.shape}: ${result.stdout}\n${result.stderr}")
            assertEquals("", result.stderr)
            val adaptable = JsonFiles.json.parseToJsonElement(result.stdout).jsonObject
            assertEquals(fixture.adaptableType, adaptable.stringValue("type"))
            assertEquals("native-plugin", adaptable.stringValue("name"))
            assertEquals(
                fixture.adapterContainerType,
                adaptable.objectValue("adapters")?.stringValue("type"),
            )
            assertEquals(
                JsonFiles.json.parseToJsonElement(fixture.json),
                adaptable.objectValue("adapters")?.get(fixture.adapter),
            )
            val adaptableSource = adaptable.arrayValue("plugins")
                .firstOrNull()
                ?.jsonObject
                ?.objectValue("plugin")
                ?.objectValue("source")
            when (fixture.shape) {
                "codex-marketplace" -> {
                    assertEquals("LOCAL_SOURCE", adaptableSource?.stringValue("type"))
                    assertEquals("./.agents/plugins/native-plugin", adaptableSource?.stringValue("path"))
                }
                "github-copilot-marketplace" -> {
                    assertEquals("GIT_SUBDIR_SOURCE", adaptableSource?.stringValue("type"))
                    assertEquals("acme/native-plugin", adaptableSource?.stringValue("url"))
                    assertEquals("plugin", adaptableSource?.stringValue("path"))
                    assertEquals("v1.2.3", adaptableSource?.stringValue("ref"))
                    assertEquals(
                        "0123456789abcdef0123456789abcdef01234567",
                        adaptableSource?.stringValue("sha"),
                    )
                }
            }
        }
    }

    @Test
    fun `ingest adds a native adapter without weakening an existing adaptable plugin`() {
        val input = writeFixture("codex-plugin.json", codexPlugin())
        val target = writeFixture(
            "adaptable-plugin.json",
            """
            {
              "type": "PLUGIN",
              "schemaVersion": 1,
              "name": "native-plugin",
              "version": "9.0.0",
              "description": "Provider-neutral authority.",
              "skills": [],
              "agents": [],
              "instructions": [],
              "hooks": []
            }
            """.trimIndent(),
        )

        val result = RootCommand().testIngestion(
            "ingest",
            "--shape", "codex-plugin",
            "--input", input.toString(),
            "--into", target.toString(),
        )

        assertEquals(0, result.statusCode, result.stdout)
        val adaptable = JsonFiles.json.parseToJsonElement(result.stdout).jsonObject
        assertEquals("9.0.0", adaptable.stringValue("version"))
        assertEquals("Provider-neutral authority.", adaptable.stringValue("description"))
        assertEquals(
            JsonFiles.json.parseToJsonElement(codexPlugin()),
            adaptable.objectValue("adapters")?.get("codex"),
        )
    }

    @Test
    fun `ingest immutably adds a second marketplace adapter and preserves the first`() {
        val githubInput = writeFixture("github-marketplace.json", githubMarketplace())
        val codexInput = writeFixture("codex-marketplace.json", codexMarketplace())
        val githubResult = RootCommand().testIngestion(
            "ingest",
            "--shape", "github-copilot-marketplace",
            "--input", githubInput.toString(),
        )
        assertEquals(0, githubResult.statusCode, githubResult.stdout)
        val target = writeFixture("adaptable-marketplace.json", githubResult.stdout)

        val result = RootCommand().testIngestion(
            "ingest",
            "--shape", "codex-marketplace",
            "--input", codexInput.toString(),
            "--into", target.toString(),
        )

        assertEquals(0, result.statusCode, result.stdout)
        val adaptable = JsonFiles.json.parseToJsonElement(result.stdout).jsonObject
        assertEquals("Platform Team", adaptable.objectValue("owner")?.stringValue("name"))
        assertEquals(
            "MULTI_HARNESS_MARKETPLACE_ADAPTER",
            adaptable.objectValue("adapters")?.stringValue("type"),
        )
        assertEquals(
            JsonFiles.json.parseToJsonElement(githubMarketplace()),
            adaptable.objectValue("adapters")?.get("github-copilot"),
        )
        assertEquals(
            JsonFiles.json.parseToJsonElement(codexMarketplace()),
            adaptable.objectValue("adapters")?.get("codex"),
        )
    }

    @Test
    fun `ingest immutably transitions a plugin from one adapter to both`() {
        val githubInput = writeFixture("github-plugin.json", githubPlugin())
        val codexInput = writeFixture("codex-plugin.json", codexPlugin())
        val githubResult = RootCommand().testIngestion(
            "ingest",
            "--shape", "github-copilot-plugin",
            "--input", githubInput.toString(),
        )
        assertEquals(0, githubResult.statusCode, githubResult.stdout)
        val target = writeFixture("adaptable-plugin-from-github.json", githubResult.stdout)

        val result = RootCommand().testIngestion(
            "ingest",
            "--shape", "codex-plugin",
            "--input", codexInput.toString(),
            "--into", target.toString(),
        )

        assertEquals(0, result.statusCode, result.stdout)
        val adapters = JsonFiles.json.parseToJsonElement(result.stdout).jsonObject.objectValue("adapters")
        assertEquals("MULTI_HARNESS_PLUGIN_ADAPTER", adapters?.stringValue("type"))
        assertEquals(JsonFiles.json.parseToJsonElement(githubPlugin()), adapters?.get("github-copilot"))
        assertEquals(JsonFiles.json.parseToJsonElement(codexPlugin()), adapters?.get("codex"))
    }

    @Test
    fun `ingest refines every GitHub marketplace source variant without inventing a location`() {
        val fixtures = listOf(
            SourceFixture(
                source = "\"./plugins/native-plugin\"",
                expectedType = "LOCAL_SOURCE",
                expectedFields = mapOf("path" to "./plugins/native-plugin"),
            ),
            SourceFixture(
                source = """{"source":"github","repo":"acme/native-plugin","ref":"main"}""",
                expectedType = "GITHUB_SOURCE",
                expectedFields = mapOf("repo" to "acme/native-plugin", "ref" to "main"),
            ),
            SourceFixture(
                source = """{"source":"url","url":"https://example.com/native.git","ref":"main"}""",
                expectedType = "GIT_SOURCE",
                expectedFields = mapOf("url" to "https://example.com/native.git", "ref" to "main"),
            ),
            SourceFixture(
                source = """{"source":"git-subdir","url":"acme/mono","path":"plugins/native","ref":"main"}""",
                expectedType = "GIT_SUBDIR_SOURCE",
                expectedFields = mapOf(
                    "url" to "acme/mono",
                    "path" to "plugins/native",
                    "ref" to "main",
                ),
            ),
        )

        fixtures.forEachIndexed { index, fixture ->
            val input = writeFixture("github-marketplace-source-$index.json", githubMarketplace(fixture.source))
            val result = RootCommand().testIngestion(
                "ingest",
                "--shape", "github-copilot-marketplace",
                "--input", input.toString(),
            )

            assertEquals(0, result.statusCode, result.stdout)
            val source = JsonFiles.json.parseToJsonElement(result.stdout)
                .jsonObject
                .arrayValue("plugins")
                .single()
                .jsonObject
                .objectValue("plugin")
                ?.objectValue("source")
            assertEquals(fixture.expectedType, source?.stringValue("type"))
            fixture.expectedFields.forEach { (name, value) ->
                assertEquals(value, source?.stringValue(name), "$index:$name")
            }
        }
    }

    @Test
    fun `ingest rejects missing refinement data duplicate adapters and identity conflicts`() {
        val codexMarketplace = writeFixture("codex-marketplace.json", codexMarketplace())
        val codexPlugin = writeFixture("codex-plugin.json", codexPlugin())
        val incompatibleGitHubMarketplace = writeFixture(
            "github-marketplace-with-nonportable-tag.json",
            githubMarketplace().replace("\"tags\": [\"engineering\"]", "\"tags\": [\"Not Portable\"]"),
        )
        val incompatibleGitHubPlugin = writeFixture(
            "github-plugin-with-non-https-homepage.json",
            githubPlugin().replace(
                "\"homepage\": \"https://example.com/native-plugin\"",
                "\"homepage\": \"mailto:platform@example.com\"",
            ),
        )
        val conflictingTarget = writeFixture(
            "conflicting-plugin.json",
            """
            {
              "type": "PLUGIN",
              "schemaVersion": 1,
              "name": "another-plugin",
              "skills": [],
              "agents": [],
              "instructions": [],
              "hooks": []
            }
            """.trimIndent(),
        )
        val alreadyAdaptedTarget = writeFixture(
            "already-adapted-plugin.json",
            """
            {
              "type": "PLUGIN",
              "schemaVersion": 1,
              "name": "native-plugin",
              "skills": [],
              "agents": [],
              "instructions": [],
              "hooks": [],
              "adapters": {
                "type": "CODEX_PLUGIN_ADAPTER",
                "codex": ${codexPlugin()}
              }
            }
            """.trimIndent(),
        )

        val missingOwner = RootCommand().testIngestion(
            "ingest", "--shape", "codex-marketplace", "--input", codexMarketplace.toString(),
        )
        val identityConflict = RootCommand().testIngestion(
            "ingest", "--shape", "codex-plugin", "--input", codexPlugin.toString(),
            "--into", conflictingTarget.toString(),
        )
        val duplicate = RootCommand().testIngestion(
            "ingest", "--shape", "codex-plugin", "--input", codexPlugin.toString(),
            "--into", alreadyAdaptedTarget.toString(),
        )
        val incompatibleOutput = RootCommand().testIngestion(
            "ingest",
            "--shape", "github-copilot-marketplace",
            "--input", incompatibleGitHubMarketplace.toString(),
        )
        val incompatiblePluginOutput = RootCommand().testIngestion(
            "ingest",
            "--shape", "github-copilot-plugin",
            "--input", incompatibleGitHubPlugin.toString(),
        )

        assertEquals(1, missingOwner.statusCode)
        assertTrue(missingOwner.stdout.contains("code: OWNER_REQUIRED"))
        assertEquals(1, identityConflict.statusCode)
        assertTrue(identityConflict.stdout.contains("code: IDENTITY_CONFLICT"))
        assertEquals(1, duplicate.statusCode)
        assertTrue(duplicate.stdout.contains("code: ADAPTER_EXISTS"))
        assertEquals(1, incompatibleOutput.statusCode)
        assertTrue(incompatibleOutput.stdout.contains("code: OUTPUT_SCHEMA_INVALID"))
        assertEquals(1, incompatiblePluginOutput.statusCode)
        assertTrue(incompatiblePluginOutput.stdout.contains("code: OUTPUT_SCHEMA_INVALID"))
    }

    @Test
    fun `validate checks any named input shape against its authored schema`() {
        val valid = writeFixture("github-plugin.json", githubPlugin())
        val invalid = writeFixture("invalid-github-plugin.json", """{"name":"native-plugin","unknown":true}""")
        val invalidFormat = writeFixture(
            "invalid-github-plugin-uri.json",
            """{"name":"native-plugin","homepage":"not a uri"}""",
        )
        val emptyAdapterSet = writeFixture(
            "empty-adapter-set.json",
            """
            {
              "type": "PLUGIN",
              "schemaVersion": 1,
              "name": "native-plugin",
              "skills": [],
              "agents": [],
              "instructions": [],
              "hooks": [],
              "adapters": {}
            }
            """.trimIndent(),
        )

        val accepted = RootCommand().testIngestion(
            "validate", "--shape", "github-copilot-plugin", "--input", valid.toString(),
        )
        val rejected = RootCommand().testIngestion(
            "validate", "--shape", "github-copilot-plugin", "--input", invalid.toString(),
        )
        val malformedUri = RootCommand().testIngestion(
            "validate", "--shape", "github-copilot-plugin", "--input", invalidFormat.toString(),
        )
        val invalidAdapters = RootCommand().testIngestion(
            "validate", "--shape", "adaptable-plugin", "--input", emptyAdapterSet.toString(),
        )

        assertEquals(0, accepted.statusCode, accepted.stdout)
        assertTrue(accepted.stdout.contains("status: valid"))
        assertTrue(accepted.stdout.contains("shape: github-copilot-plugin"))
        assertEquals(1, rejected.statusCode)
        assertTrue(rejected.stdout.contains("code: SCHEMA_INVALID"))
        assertEquals("", rejected.stderr)
        assertEquals(1, malformedUri.statusCode)
        assertTrue(malformedUri.stdout.contains("code: SCHEMA_INVALID"))
        assertEquals("", malformedUri.stderr)
        assertEquals(1, invalidAdapters.statusCode)
        assertTrue(invalidAdapters.stdout.contains("code: SCHEMA_INVALID"))
        assertEquals("", invalidAdapters.stderr)
    }

    private fun codexMarketplace(): String =
        """
        {
          "name": "native-plugin",
          "interface": { "displayName": "Native marketplace" },
          "plugins": [
            {
              "name": "native-plugin",
              "source": { "source": "local", "path": "./.agents/plugins/native-plugin" },
              "policy": {
                "installation": "AVAILABLE",
                "authentication": "ON_INSTALL",
                "products": ["codex"]
              },
              "category": "Engineering"
            }
          ]
        }
        """.trimIndent()

    private fun codexPlugin(): String =
        """
        {
          "id": "native-codex-plugin",
          "name": "native-plugin",
          "version": "1.2.3",
          "description": "Native Codex plugin.",
          "author": {
            "name": "Platform Team",
            "email": "platform@example.com",
            "url": "https://example.com/team"
          },
          "homepage": "https://example.com/native-plugin",
          "repository": "acme/native-plugin",
          "license": "MIT",
          "keywords": ["native", "engineering"],
          "skills": "./skills/",
          "hooks": ["./hooks/pre-tool.json"],
          "apps": "./.app.json",
          "mcpServers": "./.mcp.json",
          "interface": {
            "displayName": "Native Plugin",
            "shortDescription": "Native Codex plugin.",
            "longDescription": "A native Codex plugin retained without loss.",
            "developerName": "Platform Team",
            "category": "Engineering",
            "capabilities": ["Interactive"],
            "websiteURL": "https://example.com/native-plugin",
            "privacyPolicyURL": "https://example.com/privacy",
            "termsOfServiceURL": "https://example.com/terms",
            "brandColor": "#123ABC",
            "composerIcon": "./icons/composer.svg",
            "logo": "./icons/logo.svg",
            "screenshots": ["./images/plugin.png"],
            "defaultPrompt": ["Use Native Plugin."],
            "default_prompt": ["Use the legacy prompt field."]
          }
        }
        """.trimIndent()

    private fun githubMarketplace(
        source: String =
            """
            {
              "source": "github",
              "repo": "acme/native-plugin",
              "path": "plugin",
              "ref": "v1.2.3",
              "sha": "0123456789abcdef0123456789abcdef01234567"
            }
            """.trimIndent(),
    ): String =
        """
        {
          "${'$'}schema": "https://example.com/github-marketplace.schema.json",
          "name": "native-plugin",
          "owner": {
            "name": "Platform Team",
            "email": "platform@example.com",
            "url": "https://example.com/team"
          },
          "metadata": {
            "description": "Native GitHub marketplace.",
            "version": "4.5.6",
            "pluginRoot": ".github/plugin"
          },
          "plugins": [
            {
              "name": "native-plugin",
              "source": $source,
              "description": "Native GitHub plugin.",
              "version": "1.2.3",
              "author": { "name": "Platform Team" },
              "homepage": "https://example.com/native-plugin",
              "repository": "acme/native-plugin",
              "license": "MIT",
              "keywords": ["native", "engineering"],
              "category": "Engineering",
              "tags": ["engineering"],
              "commands": ["./commands/one.md", "./commands/two.md"],
              "agents": "./agents",
              "skills": "./skills",
              "hooks": "./hooks.json",
              "mcpServers": { "native": { "command": "native-mcp" } },
              "lspServers": ["./lsp/typescript.json"],
              "strict": true
            }
          ]
        }
        """.trimIndent()

    private fun githubPlugin(): String =
        """
        {
          "${'$'}schema": "https://example.com/github-plugin.schema.json",
          "name": "native-plugin",
          "description": "Native GitHub Copilot plugin.",
          "version": "1.2.3",
          "author": {
            "name": "Platform Team",
            "email": "platform@example.com",
            "url": "https://example.com/team"
          },
          "homepage": "https://example.com/native-plugin",
          "repository": "acme/native-plugin",
          "license": "MIT",
          "keywords": ["native", "engineering"],
          "category": "Engineering",
          "tags": ["engineering"],
          "commands": "./commands",
          "agents": ["./agents/reviewer.md"],
          "skills": "./skills",
          "hooks": "./hooks.json",
          "mcpServers": { "native": { "command": "native-mcp" } },
          "lspServers": ["./lsp/typescript.json"],
          "extensions": {
            "paths": ["./extensions/native"],
            "exclusive": true
          }
        }
        """.trimIndent()

    private fun writeFixture(name: String, json: String): Path =
        temporaryDirectory.resolve(name).also { path -> path.writeText(json + "\n") }
}

private data class NativeFixture(
    val shape: String,
    val adapter: String,
    val adapterContainerType: String,
    val adaptableType: String,
    val json: String,
    val extraArguments: List<String> = emptyList(),
)

private data class SourceFixture(
    val source: String,
    val expectedType: String,
    val expectedFields: Map<String, String>,
)

private data class IngestionCommandResult(
    val stdout: String,
    val stderr: String,
    val statusCode: Int,
)

private fun RootCommand.testIngestion(vararg arguments: String): IngestionCommandResult {
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
    return IngestionCommandResult(stdout.toString(), stderr.toString(), statusCode)
}

private fun JsonElement.references(): List<String> =
    when (this) {
        is JsonObject -> buildList {
            (this@references["${'$'}ref"] as? JsonPrimitive)?.content?.let(::add)
            this@references.values.forEach { element -> addAll(element.references()) }
        }
        is JsonArray -> flatMap(JsonElement::references)
        else -> emptyList()
    }
