package intelligence.cli.marketplace

import intelligence.cli.BuildInfo
import intelligence.cli.io.DigestAlgorithm
import intelligence.cli.io.Digests
import intelligence.cli.io.FileSystem
import intelligence.cli.io.JsonFiles
import intelligence.cli.io.arrayValue
import intelligence.cli.io.normalizedAbsolute
import intelligence.cli.io.objectValue
import intelligence.cli.io.putIfNotNull
import intelligence.cli.io.stringList
import intelligence.cli.io.stringValue
import intelligence.cli.io.toCliPath
import intelligence.cli.schema.DocumentShape
import intelligence.cli.schema.DocumentValidation
import intelligence.cli.schema.SchemaDocumentValidator
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal class MarketplaceProjector(
    private val output: (String) -> Unit = ::println,
    resolvedAssetRoot: Path = defaultResolvedAssetRoot(),
) {
    private val resolvedAssets = ResolvedMarketplaceAssets(resolvedAssetRoot.normalizedAbsolute())

    fun materialize(
        repoRoot: Path,
        outRoot: Path,
        provider: MarketplaceProvider,
        generatedAt: String? = null,
        sourceSha: String? = null,
    ) {
        val repository = repoRoot.normalizedAbsolute()
        val outputRoot = outRoot.normalizedAbsolute()
        refuseRepositoryOutput(repository, outputRoot)

        RemoteMarketplaceResolver(repository).use { resolver ->
            when (provider) {
                MarketplaceProvider.Codex -> materializeCodexMarketplace(repository, outputRoot, generatedAt, sourceSha, resolver)
                MarketplaceProvider.GitHub -> materializeGitHubMarketplace(repository, outputRoot, resolver)
            }
        }
    }

    private fun materializeCodexMarketplace(
        repoRoot: Path,
        outRoot: Path,
        generatedAt: String?,
        sourceSha: String?,
        resolver: RemoteMarketplaceResolver,
    ) {
        FileSystem.replaceDirectory(outRoot)
        renderCodexMarketplace(repoRoot, outRoot, generatedAt, sourceSha, resolver)
        output("materialized Codex marketplace at $outRoot")
    }

    private fun materializeGitHubMarketplace(
        repoRoot: Path,
        outRoot: Path,
        resolver: RemoteMarketplaceResolver,
    ) {
        FileSystem.replaceDirectory(outRoot)
        renderGitHubMarketplace(repoRoot, outRoot, resolver)
        output("materialized GitHub marketplace at $outRoot")
    }

    private fun renderCodexMarketplace(
        repoRoot: Path,
        outRoot: Path,
        generatedAt: String?,
        sourceSha: String?,
        resolver: RemoteMarketplaceResolver,
    ) {
        val marketplace = marketplace(repoRoot)
        val adaptableMarketplace = adaptableMarketplaceWithAdapters(marketplace)
        val owner = marketplace.objectValue("owner") ?: JsonObject(emptyMap())
        val ownerName = owner.stringValue("name") ?: "Local developer"
        val pluginsRoot = outRoot.resolve(CODEX_BRANCH_PLUGINS_PATH)
        val codexPlugins = mutableListOf<CodexMarketplacePluginEntry>()
        val lockPlugins = mutableListOf<JsonObject>()

        marketplace.arrayValue("plugins").forEachObject { entry ->
            val pluginProjection = hydrateProjection(repoRoot, entry, owner, ownerName, resolver)
            val pluginOut = pluginsRoot.resolve(pluginProjection.name)
            pluginOut.createDirectories()

            val hydrated = hydratePlugin(pluginProjection.repoRoot, pluginOut, pluginProjection.manifest)
            renderAgentsMdAdapter(pluginOut, pluginProjection.name, pluginProjection.description, hydrated)
            codexPlugins.add(codexMarketplaceEntry(pluginProjection.name, pluginProjection.category))
            val codexManifest = codexPluginManifest(pluginProjection, ownerName, hydrated)
            JsonFiles.writeObject(pluginOut.resolve(CODEX_PLUGIN_DIR).resolve("plugin.json"), codexManifest)
            lockPlugins.add(lockEntry(pluginProjection, codexManifest, hydrated))
        }

        JsonFiles.writeObject(
            outRoot.resolve(CODEX_BRANCH_MARKETPLACE_PATH),
            codexMarketplace(marketplace, adaptableMarketplace, codexPlugins),
        )
        JsonFiles.writeObject(outRoot.resolve(CODEX_BRANCH_LOCK_PATH), codexLock(repoRoot, generatedAt, sourceSha, lockPlugins))
    }

    private fun renderGitHubMarketplace(
        repoRoot: Path,
        outRoot: Path,
        resolver: RemoteMarketplaceResolver,
    ) {
        val marketplace = marketplace(repoRoot)
        val adaptableMarketplace = adaptableMarketplaceWithAdapters(marketplace)
        val owner = marketplace.objectValue("owner") ?: JsonObject(emptyMap())
        val ownerName = owner.stringValue("name") ?: "Local developer"
        val pluginsRoot = outRoot.resolve(GITHUB_BRANCH_PLUGINS_PATH)
        val githubPlugins = mutableListOf<GitHubMarketplacePluginEntry>()

        marketplace.arrayValue("plugins").forEachObject { entry ->
            val pluginProjection = hydrateProjection(repoRoot, entry, owner, ownerName, resolver)
            val pluginOut = pluginsRoot.resolve(pluginProjection.name)
            pluginOut.createDirectories()

            val hydrated = hydratePlugin(pluginProjection.repoRoot, pluginOut, pluginProjection.manifest)
            removeGitHubAgentModelOverrides(pluginOut, hydrated)
            renderGitHubCopilotHooks(pluginOut, hydrated)
            renderAgentsMdAdapter(pluginOut, pluginProjection.name, pluginProjection.description, hydrated)
            JsonFiles.writeObject(
                pluginOut.resolve("plugin.json"),
                githubCopilotPluginManifest(pluginProjection, hydrated),
            )
            githubPlugins.add(githubCopilotPluginEntry(pluginProjection))
        }

        JsonFiles.writeObject(
            outRoot.resolve(GITHUB_BRANCH_MARKETPLACE_PATH),
            githubMarketplace(marketplace, adaptableMarketplace, owner, ownerName, githubPlugins),
        )
    }

    private fun hydrateProjection(
        repoRoot: Path,
        entry: JsonObject,
        owner: JsonObject,
        ownerName: String,
        resolver: RemoteMarketplaceResolver,
    ): PluginProjection {
        val sourceEntry = sourceEntryFor(repoRoot, entry, resolver)
        val pluginRef = sourceEntry.entry.requiredObject("plugin")
        val sourcePath = pluginRef.requiredObject("source").requiredString("path")
        val manifestPath = resolveSourcePath(sourceEntry.repoRoot, sourcePath).resolve("plugin.json")
        val manifest = JsonFiles.readObject(manifestPath)
        val adaptableManifest = adaptablePluginWithAdapters(manifest, manifestPath)
        val name = manifest.requiredString("name")
        val description = manifest.stringValue("description")
            ?: entry.stringValue("description")
            ?: "$name plugin."
        val tags = entry.stringList("tags")

        return PluginProjection(
            name = name,
            repoRoot = sourceEntry.repoRoot,
            sourcePath = Path.of(sourcePath),
            manifest = manifest,
            pluginRef = entry.requiredObject("plugin"),
            description = description,
            tags = tags,
            category = categoryFor(tags),
            owner = personFor(owner, ownerName),
            interfaceMetadata = NeutralPluginInterfaceMetadata.fromManifest(manifest, manifestPath),
            adaptableManifest = adaptableManifest,
        )
    }

    private fun hydratePlugin(repoRoot: Path, pluginOut: Path, pluginManifest: JsonObject): HydratedPlugin {
        val queue = ArrayDeque<JsonObject>()
        val seen = linkedSetOf<Pair<String, String>>()
        val hydrated = HydratedPluginBuilder()

        fun enqueue(primitive: JsonObject) {
            val primitiveType = primitive.stringValue("type")
            val path = primitive.stringValue("path")
            if (PrimitiveKind.fromSourceName(primitiveType) == null || path.isNullOrBlank()) {
                return
            }
            val key = primitiveType!! to path
            if (seen.add(key)) {
                queue.add(primitive)
            }
        }

        PrimitiveKind.entries.forEach { kind ->
            pluginManifest.arrayValue(kind.collectionName).forEachObject(::enqueue)
        }

        while (queue.isNotEmpty()) {
            val primitive = queue.removeFirst()
            primitive.arrayValue("dependsOn").forEachObject(::enqueue)
            copyPrimitive(repoRoot, pluginOut, primitive, hydrated)
        }

        return hydrated.build()
    }

    private fun copyPrimitive(
        repoRoot: Path,
        pluginOut: Path,
        primitive: JsonObject,
        hydrated: HydratedPluginBuilder,
    ) {
        val source = primitive.requiredObject("source")
        if (source.stringValue("type") != "LOCAL_SOURCE" || source.stringValue("path") != "./") {
            throw MarketplaceFailure.InvalidSource("unsupported non-local primitive reference: $primitive")
        }

        val primitiveType = primitive.requiredString("type")
        val kind = PrimitiveKind.fromSourceName(primitiveType)
            ?: throw MarketplaceFailure.InvalidSource("unsupported primitive type: $primitiveType")
        val sourcePathValue = primitive.requiredString("path")
        val sourcePath = resolveSourcePath(repoRoot, sourcePathValue)
        if (!sourcePath.exists()) {
            throw MarketplaceFailure.InvalidSource("missing primitive path: $sourcePathValue")
        }

        if (kind == PrimitiveKind.Hook) {
            copyHook(repoRoot, pluginOut, primitive, sourcePath, hydrated)
            return
        }

        val targetPath = targetForPrimitive(pluginOut, kind, primitive, sourcePath)
        FileSystem.copyPath(sourcePath, targetPath)
        val relativeTarget = targetPath.relativeToUnix(pluginOut)
        hydrated.addPath(kind, relativeTarget)
        hydrated.addReference(
            HydratedReference(
                type = primitiveType,
                name = primitive.stringValue("name"),
                sourcePath = sourceDisplayPath(sourcePathValue),
                targetPath = relativeTarget,
                sha256 = Digests.digestPath(targetPath, DigestAlgorithm.Sha256),
            )
        )

        if (kind == PrimitiveKind.Agent && sourcePath.name.endsWith(".md")) {
            renderAgentToml(targetPath)
        }
    }

    private fun copyHook(
        repoRoot: Path,
        pluginOut: Path,
        primitive: JsonObject,
        sourcePath: Path,
        hydrated: HydratedPluginBuilder,
    ) {
        val metadata = JsonFiles.readObject(sourcePath)
        val adapterPathValue = metadata.stringValue("path") ?: primitive.requiredString("path")
        val adapterSource = resolveSourcePath(repoRoot, adapterPathValue)
        if (!adapterSource.exists()) {
            throw MarketplaceFailure.InvalidSource("missing hook adapter path: $adapterPathValue")
        }

        val metadataTarget = pluginOut.resolve("hooks").resolve("metadata").resolve(sourcePath.name)
        val adapterTarget = pluginOut.resolve("hooks").resolve(adapterSource.name)
        JsonFiles.writeObject(
            metadataTarget,
            rewritePrimitivePathsForHydratedPackage(repoRoot, pluginOut, metadata).jsonObject,
        )
        val adapter = JsonFiles.readObject(adapterSource)
        JsonFiles.writeObject(adapterTarget, rewriteHookCommandsForPlugin(adapter).jsonObject)

        val relativeAdapter = adapterTarget.relativeToUnix(pluginOut)
        hydrated.addPath(PrimitiveKind.Hook, relativeAdapter)
        hydrated.addReference(
            HydratedReference(
                type = PrimitiveKind.Hook.sourceName,
                name = primitive.stringValue("name"),
                sourcePath = sourceDisplayPath(primitive.requiredString("path")),
                targetPath = relativeAdapter,
                sha256 = Digests.digestPath(adapterTarget, DigestAlgorithm.Sha256),
            )
        )

        hookCommandPaths(adapter).sorted().forEach { commandPath ->
            val commandSource = resolveSourcePath(repoRoot, commandPath)
            if (commandSource.exists()) {
                FileSystem.copyPath(commandSource, pluginOut.resolve(commandPath))
                FileSystem.copyHookSidecars(pluginOut, commandSource)
            }
        }
    }

    private fun rewritePrimitivePathsForHydratedPackage(
        repoRoot: Path,
        pluginOut: Path,
        element: JsonElement,
    ): JsonElement =
        when (element) {
            is JsonObject -> {
                val rewritten = element.entries.associate { (key, value) ->
                    key to rewritePrimitivePathsForHydratedPackage(repoRoot, pluginOut, value)
                }.toMutableMap()
                val kind = PrimitiveKind.fromSourceName(element.stringValue("type"))
                val source = element.objectValue("source")
                if (
                    kind != null &&
                    !element.stringValue("path").isNullOrBlank() &&
                    source?.stringValue("type") == "LOCAL_SOURCE" &&
                    source.stringValue("path") == "./"
                ) {
                    rewritten["path"] = JsonPrimitive(hydratedPackagePathForPrimitive(repoRoot, pluginOut, element, kind))
                }
                JsonObject(rewritten)
            }
            is JsonArray -> JsonArray(element.map { rewritePrimitivePathsForHydratedPackage(repoRoot, pluginOut, it) })
            else -> element
        }

    private fun hydratedPackagePathForPrimitive(
        repoRoot: Path,
        pluginOut: Path,
        primitive: JsonObject,
        kind: PrimitiveKind,
    ): String {
        val sourcePathValue = primitive.requiredString("path")
        return if (kind == PrimitiveKind.Hook) {
            val hookSource = resolveSourcePath(repoRoot, sourcePathValue)
            val adapterSource = if (hookSource.name.endsWith(".hook.json")) {
                val hookMetadata = JsonFiles.readObject(hookSource)
                resolveSourcePath(repoRoot, hookMetadata.requiredString("path"))
            } else {
                hookSource
            }
            pluginOut.resolve("hooks").resolve(adapterSource.name).relativeToUnix(pluginOut)
        } else {
            val sourcePath = resolveSourcePath(repoRoot, sourcePathValue)
            targetForPrimitive(pluginOut, kind, primitive, sourcePath).relativeToUnix(pluginOut)
        }
    }

    private fun renderAgentsMdAdapter(
        pluginOut: Path,
        pluginName: String,
        description: String,
        hydrated: HydratedPlugin,
    ) {
        if (hydrated.agents.isEmpty() && hydrated.instructions.isEmpty()) {
            return
        }

        val referencesByType = hydrated.references.groupBy { it.type }
        val lines = mutableListOf(
            "# ${titleCase(pluginName)} Plugin Instructions",
            "",
            "## Scope",
            "",
            "This generated adapter applies to the `$pluginName` plugin payload. Do not edit it directly; update the provider-neutral primitives or plugin manifest, then regenerate the marketplace output.",
            "",
            "## Runtime Boundary",
            "",
            "The source graph keeps skills, agent profiles, instructions, concepts, and hooks as independent primitives. This `AGENTS.md` adapts bundled agent and instruction primitives into a plain instruction file for runtimes that do not expose those primitive kinds directly.",
            "",
            "## Plugin Intent",
            "",
            description,
            "",
            "## Operating Rules",
            "",
            "- Treat this file as an adapter, not a new source of truth.",
            "- Use bundled skills for step-by-step workflows.",
            "- Apply bundled instructions as normative guidance when their scope matches the task.",
            "- Treat bundled agent profiles as review criteria or focused review passes.",
            "- Keep hook behavior in bundled hook files and runtime adapter configs.",
            "- When guidance conflicts with the target repository's nearest `AGENTS.md`, follow the target repository unless the user explicitly chooses this plugin's rule.",
            "",
        )

        appendReferenceSection(lines, "Instruction Primitives", referencesByType[PrimitiveKind.Instruction.sourceName].orEmpty())
        appendReferenceSection(lines, "Agent Profile Primitives", referencesByType[PrimitiveKind.Agent.sourceName].orEmpty())
        appendReferenceSection(lines, "Skill Primitives", referencesByType[PrimitiveKind.Skill.sourceName].orEmpty())
        appendReferenceSection(lines, "Hook Primitives", referencesByType[PrimitiveKind.Hook.sourceName].orEmpty())
        pluginOut.resolve("AGENTS.md").writeText(lines.joinToString("\n").trimEnd() + "\n", Charsets.UTF_8)
    }

    private fun appendReferenceSection(lines: MutableList<String>, title: String, references: List<HydratedReference>) {
        if (references.isEmpty()) {
            return
        }

        lines += "## $title"
        lines += ""
        references
            .sortedWith(compareBy(HydratedReference::type, HydratedReference::sourcePath, HydratedReference::targetPath))
            .forEach { reference ->
                lines += "- `${reference.name}`: `${reference.targetPath}` (source: `${reference.sourcePath}`)"
            }
        lines += ""
    }

    private fun renderAgentToml(markdown: Path) {
        val lines = markdown.readText(Charsets.UTF_8).lines()
        if (lines.firstOrNull() != "---") {
            return
        }
        val end = lines.drop(1).indexOf("---").takeIf { it >= 0 }?.plus(1) ?: return
        val frontmatter = lines.subList(1, end)
            .mapNotNull { line ->
                val separator = line.indexOf(":")
                if (separator < 0) {
                    null
                } else {
                    line.substring(0, separator).trim() to line.substring(separator + 1).trim().trim('\'', '"')
                }
            }
            .toMap()
        val name = frontmatter["name"]
        val description = frontmatter["description"]
        val instructions = lines.drop(end + 1).joinToString("\n").trim() + "\n"
        if (name.isNullOrBlank() || description.isNullOrBlank() || instructions.isBlank()) {
            return
        }

        markdown.resolveSibling("${markdown.name.substringBeforeLast(".")}.toml").writeText(
            buildString {
                append("name = ")
                appendLine(JsonFiles.json.encodeToString(String.serializer(), name))
                append("description = ")
                appendLine(JsonFiles.json.encodeToString(String.serializer(), description))
                append("developer_instructions = ")
                appendLine(JsonFiles.json.encodeToString(String.serializer(), instructions))
            },
            Charsets.UTF_8,
        )
    }

    private fun adaptableMarketplaceWithAdapters(source: JsonObject): AdaptableMarketplaceDocument? {
        if ("adapters" !in source) return null
        val validated =
            when (val validation = SchemaDocumentValidator.validate(DocumentShape.AdaptableMarketplace, source)) {
                is DocumentValidation.Valid -> validation.document
                is DocumentValidation.Invalid ->
                    throw MarketplaceFailure.InvalidSource(
                        "$ADAPTABLE_MARKETPLACE_PATH: ${validation.violations.joinToString("; ")}",
                    )
            }
        return when (val decoding = HarnessDocumentCodec.decodeAdaptable(validated)) {
            is HarnessDocumentDecoding.Decoded ->
                decoding.document as? AdaptableMarketplaceDocument
                    ?: throw MarketplaceFailure.InvalidSource(
                        "$ADAPTABLE_MARKETPLACE_PATH: decoded document is not an adaptable marketplace",
                    )
            is HarnessDocumentDecoding.Rejected ->
                throw MarketplaceFailure.InvalidSource(
                    "$ADAPTABLE_MARKETPLACE_PATH: ${decoding.reason}",
                )
        }
    }

    private fun adaptablePluginWithAdapters(
        source: JsonObject,
        sourcePath: Path,
    ): AdaptablePluginDocument? {
        if ("adapters" !in source) return null
        val validated =
            when (val validation = SchemaDocumentValidator.validate(DocumentShape.AdaptablePlugin, source)) {
                is DocumentValidation.Valid -> validation.document
                is DocumentValidation.Invalid ->
                    throw MarketplaceFailure.InvalidSource(
                        "$sourcePath: ${validation.violations.joinToString("; ")}",
                    )
            }
        return when (val decoding = HarnessDocumentCodec.decodeAdaptable(validated)) {
            is HarnessDocumentDecoding.Decoded ->
                decoding.document as? AdaptablePluginDocument
                    ?: throw MarketplaceFailure.InvalidSource(
                        "$sourcePath: decoded document is not an adaptable plugin",
                    )
            is HarnessDocumentDecoding.Rejected ->
                throw MarketplaceFailure.InvalidSource("$sourcePath: ${decoding.reason}")
        }
    }

    private fun requireMarketplaceAdapterIdentity(
        adaptableName: String,
        adapterName: String,
        adaptablePlugins: List<String>,
        adapterPlugins: List<String>,
        harness: String,
    ) {
        if (adaptableName != adapterName) {
            throw MarketplaceFailure.InvalidSource(
                "$harness marketplace adapter name `$adapterName` does not match `$adaptableName`",
            )
        }
        val adapterNames = adapterPlugins.toSet()
        val adaptableNames = adaptablePlugins.toSet()
        if (
            adapterNames.size != adapterPlugins.size ||
            adaptableNames.size != adaptablePlugins.size ||
            adapterNames != adaptableNames
        ) {
            throw MarketplaceFailure.InvalidSource(
                "$harness marketplace adapter plugins must match the adaptable marketplace plugins",
            )
        }
    }

    private fun requirePluginAdapterIdentity(
        plugin: PluginProjection,
        adapterName: String,
        adapterVersion: String?,
        harness: String,
    ) {
        val adaptable = plugin.adaptableManifest
            ?: throw MarketplaceFailure.InvalidSource("$harness plugin adapter has no adaptable authority")
        if (adapterName != adaptable.name) {
            throw MarketplaceFailure.InvalidSource(
                "$harness plugin adapter name `$adapterName` does not match `${adaptable.name}`",
            )
        }
        val adaptableVersion = adaptable.version
        if (adaptableVersion != null && adapterVersion != null && adapterVersion != adaptableVersion) {
            throw MarketplaceFailure.InvalidSource(
                "$harness plugin adapter version `$adapterVersion` does not match `$adaptableVersion`",
            )
        }
    }

    private fun codexMarketplace(
        marketplace: JsonObject,
        adaptable: AdaptableMarketplaceDocument?,
        plugins: List<CodexMarketplacePluginEntry>,
    ): JsonObject {
        val adapter = adaptable?.adapters?.codex
        if (adapter != null) {
            requireMarketplaceAdapterIdentity(
                adaptableName = adaptable.name,
                adapterName = adapter.name,
                adaptablePlugins = adaptable.plugins.map(AdaptablePluginEntry::name),
                adapterPlugins = adapter.plugins.map(CodexMarketplacePluginEntry::name),
                harness = "Codex",
            )
            return HarnessDocumentCodec.encode(
                adapter.copy(
                    plugins = adapter.plugins.map { entry ->
                        entry.copy(
                            source = entry.source.copy(
                                source = CodexLocalPluginSourceType.Local,
                                path = codexPluginSourcePath(entry.name),
                            ),
                        )
                    },
                ),
            )
        }
        val name = marketplace.requiredString("name")
        return HarnessDocumentCodec.encode(
            CodexMarketplaceDocument(
                name = name,
                presentation = CodexMarketplaceInterface(displayName = titleCase(name)),
                plugins = plugins,
            ),
        )
    }

    private fun githubMarketplace(
        marketplace: JsonObject,
        adaptable: AdaptableMarketplaceDocument?,
        owner: JsonObject,
        ownerName: String,
        plugins: List<GitHubMarketplacePluginEntry>,
    ): JsonObject {
        val adapter = adaptable?.adapters?.githubCopilot
        if (adapter != null) {
            requireMarketplaceAdapterIdentity(
                adaptableName = adaptable.name,
                adapterName = adapter.name,
                adaptablePlugins = adaptable.plugins.map(AdaptablePluginEntry::name),
                adapterPlugins = adapter.plugins.map(GitHubMarketplacePluginEntry::name),
                harness = "GitHub Copilot",
            )
            return HarnessDocumentCodec.encode(
                adapter.copy(
                    metadata = (adapter.metadata ?: GitHubMarketplaceMetadata()).copy(
                        pluginRoot = GITHUB_MARKETPLACE_PLUGIN_ROOT,
                    ),
                    plugins = adapter.plugins.map { entry ->
                        entry.copy(
                            source = JsonFiles.json.encodeToJsonElement(String.serializer(), entry.name),
                            strict = true,
                        )
                    },
                ),
            )
        }
        return HarnessDocumentCodec.encode(
            GitHubMarketplaceDocument(
                name = marketplace.requiredString("name"),
                owner = personFor(owner, ownerName),
                metadata = GitHubMarketplaceMetadata(
                    description = marketplace.stringValue("description") ?: "",
                    pluginRoot = GITHUB_MARKETPLACE_PLUGIN_ROOT,
                ),
                plugins = plugins,
            ),
        )
    }

    private fun codexLock(
        repoRoot: Path,
        generatedAt: String?,
        sourceSha: String?,
        plugins: List<JsonObject>,
    ): JsonObject =
        buildJsonObject {
            put("type", "HYDRATED_MARKETPLACE")
            put("schemaVersion", 1)
            put("generatedAt", generatedAt ?: currentSourceTimestamp(repoRoot))
            put("sourceSha", sourceSha ?: currentSha(repoRoot))
            put("plugins", JsonArray(plugins))
        }

    private fun codexMarketplaceEntry(pluginName: String, category: String): CodexMarketplacePluginEntry =
        CodexMarketplacePluginEntry(
            name = pluginName,
            source = CodexLocalPluginSource(
                source = CodexLocalPluginSourceType.Local,
                path = codexPluginSourcePath(pluginName),
            ),
            policy = CodexPluginPolicy(
                installation = CodexInstallationPolicy.Available,
                authentication = CodexAuthenticationPolicy.OnInstall,
            ),
            category = category,
        )

    private fun codexPluginSourcePath(pluginName: String): String =
        "./${CODEX_BRANCH_PLUGINS_PATH.resolve(pluginName).toString().replace('\\', '/')}"

    private fun codexPluginManifest(
        plugin: PluginProjection,
        ownerName: String,
        hydrated: HydratedPlugin,
    ): JsonObject {
        plugin.adaptableManifest?.adapters?.codex?.let { adapter ->
            requirePluginAdapterIdentity(plugin, adapter.name, adapter.version, "Codex")
            return HarnessDocumentCodec.encode(adapter)
        }
        return HarnessDocumentCodec.encode(
            CodexPluginDocument(
                name = plugin.name,
                version = plugin.version,
                description = plugin.description,
                author = HarnessPerson(name = ownerName),
                license = "UNLICENSED",
                keywords = (listOf(plugin.name) + plugin.tags).toSortedSet().toList(),
                skills = "./skills/".takeIf { hydrated.skills.isNotEmpty() },
                hooks = hydrated.hooks
                    .takeIf { hooks -> hooks.isNotEmpty() }
                    ?.sorted()
                    ?.map { path -> "./$path" },
                presentation = CodexPluginInterface(
                    displayName = titleCase(plugin.name),
                    shortDescription = shortDescription(plugin.description),
                    longDescription = plugin.description,
                    developerName = ownerName,
                    category = plugin.category,
                    capabilities = capabilitiesFor(hydrated),
                    websiteUrl = plugin.interfaceMetadata.websiteUrl?.value,
                    privacyPolicyUrl = plugin.interfaceMetadata.privacyPolicyUrl?.value,
                    termsOfServiceUrl = plugin.interfaceMetadata.termsOfServiceUrl?.value,
                    brandColor = brandColorFor(plugin.category),
                    defaultPrompt = defaultPrompts(plugin.name),
                ),
            ),
        )
    }

    private fun githubCopilotPluginManifest(plugin: PluginProjection, hydrated: HydratedPlugin): JsonObject {
        plugin.adaptableManifest?.adapters?.githubCopilot?.let { adapter ->
            requirePluginAdapterIdentity(plugin, adapter.name, adapter.version, "GitHub Copilot")
            return HarnessDocumentCodec.encode(
                adapter.copy(version = adapter.version ?: plugin.version),
            )
        }
        return HarnessDocumentCodec.encode(
            GitHubPluginDocument(
                name = plugin.name,
                description = plugin.description,
                version = plugin.version,
                author = plugin.owner,
                license = "UNLICENSED",
                keywords = (listOf(plugin.name) + plugin.tags).toSortedSet().toList(),
                category = plugin.category,
                tags = plugin.tags,
                agents = componentPath("./agents", hydrated.agents),
                skills = componentPath("./skills", hydrated.skills),
                hooks = componentPath("./hooks.json", hydrated.hooks),
            ),
        )
    }

    private fun githubCopilotPluginEntry(plugin: PluginProjection): GitHubMarketplacePluginEntry =
        GitHubMarketplacePluginEntry(
            name = plugin.name,
            source = JsonFiles.json.encodeToJsonElement(String.serializer(), plugin.name),
            description = plugin.description,
            version = plugin.version,
            author = plugin.owner,
            license = "UNLICENSED",
            keywords = (listOf(plugin.name) + plugin.tags).toSortedSet().toList(),
            category = plugin.category,
            tags = plugin.tags,
            strict = true,
        )

    private fun componentPath(path: String, components: Collection<String>): JsonElement? =
        path.takeIf { components.isNotEmpty() }
            ?.let { value -> JsonFiles.json.encodeToJsonElement(String.serializer(), value) }

    private fun removeGitHubAgentModelOverrides(pluginOut: Path, hydrated: HydratedPlugin) {
        hydrated.agents.forEach { relativePath ->
            val agent = pluginOut.resolve(relativePath)
            val lines = agent.readText(Charsets.UTF_8).lines()
            if (lines.firstOrNull() != "---") {
                return@forEach
            }
            val frontmatterEnd = lines.drop(1).indexOf("---").takeIf { it >= 0 }?.plus(1) ?: return@forEach
            agent.writeText(
                lines.filterIndexed { index, line ->
                    index !in 1 until frontmatterEnd || !GITHUB_AGENT_MODEL_FIELD.matches(line)
                }.joinToString("\n").trimEnd() + "\n",
                Charsets.UTF_8,
            )
        }
    }

    private fun renderGitHubCopilotHooks(pluginOut: Path, hydrated: HydratedPlugin) {
        if (hydrated.hooks.isEmpty()) {
            return
        }

        val hooksByEvent = linkedMapOf<String, MutableList<JsonElement>>()
        hydrated.hooks.sorted().forEach { relativePath ->
            val adapter = JsonFiles.readObject(pluginOut.resolve(relativePath))
            adapter.requiredObject("hooks").forEach { (event, groupsElement) ->
                val groups = groupsElement as? JsonArray
                    ?: throw MarketplaceFailure.InvalidSource("GitHub Copilot hook event `$event` must be an array")
                groups.forEach { groupElement ->
                    val group = groupElement as? JsonObject
                        ?: throw MarketplaceFailure.InvalidSource("GitHub Copilot hook event `$event` contains a non-object group")
                    val commands = group["hooks"] as? JsonArray
                        ?: throw MarketplaceFailure.InvalidSource("GitHub Copilot hook event `$event` is missing a hooks array")
                    commands.forEach { commandElement ->
                        val command = commandElement as? JsonObject
                            ?: throw MarketplaceFailure.InvalidSource("GitHub Copilot hook event `$event` contains a non-object hook")
                        if (command.requiredString("type") != "command") {
                            throw MarketplaceFailure.InvalidSource("GitHub Copilot projection only supports command hooks")
                        }
                        hooksByEvent.getOrPut(event) { mutableListOf() }.add(
                            buildJsonObject {
                                put("type", "command")
                                put("bash", command.requiredString("command"))
                                putIfNotNull("matcher", command.stringValue("matcher") ?: group.stringValue("matcher"))
                                command["timeout"]?.let { timeout -> put("timeoutSec", timeout) }
                            }
                        )
                    }
                }
            }
        }

        JsonFiles.writeObject(
            pluginOut.resolve("hooks.json"),
            buildJsonObject {
                put("version", 1)
                putJsonObject("hooks") {
                    hooksByEvent.forEach { (event, hooks) ->
                        put(event, JsonArray(hooks))
                    }
                }
            },
        )
    }

    private fun lockEntry(plugin: PluginProjection, codexManifest: JsonObject, hydrated: HydratedPlugin): JsonObject =
        buildJsonObject {
            put("name", plugin.name)
            put("version", codexManifest.requiredString("version"))
            put("sourceManifest", sourceDisplayPath(plugin.sourcePath.resolve("plugin.json")))
            put("references", hydrated.toReferenceJson())
        }

    private fun personFor(owner: JsonObject, fallbackName: String): HarnessPerson =
        HarnessPerson(
            name = owner.stringValue("name") ?: fallbackName,
            email = owner.stringValue("email"),
            url = owner.stringValue("url"),
        )

    private fun targetForPrimitive(
        pluginOut: Path,
        kind: PrimitiveKind,
        primitive: JsonObject,
        sourcePath: Path,
    ): Path =
        when (kind) {
            PrimitiveKind.Skill -> pluginOut.resolve("skills").resolve(primitive.requiredString("name"))
            PrimitiveKind.Agent -> pluginOut.resolve("agents").resolve(sourcePath.name)
            PrimitiveKind.Instruction -> {
                val suffix = sourcePath.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".md"
                pluginOut.resolve("instructions").resolve("${primitive.requiredString("name")}$suffix")
            }
            PrimitiveKind.Hook -> throw MarketplaceFailure.InvalidSource("hooks use hook-specific projection")
        }

    private fun hookCommandPaths(payload: JsonElement): Set<String> =
        when (payload) {
            is JsonObject -> payload.entries.flatMapTo(linkedSetOf()) { (key, value) ->
                if (key == "command" && value is JsonPrimitive && value.isString) {
                    HOOK_COMMAND_PATH_RE.findAll(value.content).map { it.value }.toSet()
                } else {
                    hookCommandPaths(value)
                }
            }
            is JsonArray -> payload.flatMapTo(linkedSetOf(), ::hookCommandPaths)
            else -> emptySet()
        }

    private fun rewriteHookCommandsForPlugin(payload: JsonElement): JsonElement =
        when (payload) {
            is JsonObject -> JsonObject(
                payload.mapValues { (key, value) ->
                    if (key == "command" && value is JsonPrimitive && value.isString) {
                        JsonPrimitive(
                            value.content.replace(HOOK_COMMAND_PATH_RE) { match ->
                                "\"${'$'}PLUGIN_ROOT/${match.value}\""
                            }
                        )
                    } else {
                        rewriteHookCommandsForPlugin(value)
                    }
                }
            )
            is JsonArray -> JsonArray(payload.map(::rewriteHookCommandsForPlugin))
            else -> payload
        }

    private fun marketplace(repoRoot: Path): JsonObject {
        val path = repoRoot.resolve(ADAPTABLE_MARKETPLACE_PATH)
        if (!path.exists()) {
            throw MarketplaceFailure.InvalidSource("missing provider-neutral marketplace: $ADAPTABLE_MARKETPLACE_PATH")
        }
        return JsonFiles.readObject(path)
    }

    private fun currentSha(repoRoot: Path): String =
        Digests.digestPath(repoRoot.resolve(SOURCE_ROOT), DigestAlgorithm.Sha1)

    private fun currentSourceTimestamp(repoRoot: Path): String {
        val process = ProcessBuilder("git", "show", "-s", "--format=%cI", "HEAD")
            .directory(repoRoot.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val value = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        return if (process.waitFor() == 0 && value.isNotBlank()) {
            value
        } else {
            "1970-01-01T00:00:00+00:00"
        }
    }

    private fun refuseRepositoryOutput(repoRoot: Path, outRoot: Path) {
        val derivedBuildOutput = repoRoot.resolve("build").resolve(BuildInfo.NAME).resolve("marketplace").normalizedAbsolute()
        if (outRoot == derivedBuildOutput || outRoot.startsWith(derivedBuildOutput)) {
            return
        }
        if (outRoot == repoRoot || outRoot.startsWith(repoRoot)) {
            throw MarketplaceFailure.InvalidSource("refusing to materialize inside repository root: $outRoot")
        }
    }

    private fun resolveSourcePath(repoRoot: Path, pathValue: String): Path {
        val path = Path.of(pathValue).normalize()
        if (path.isAbsolute) {
            throw MarketplaceFailure.InvalidSource("source paths must be repository-relative: $pathValue")
        }
        return if (path.nameCount > 0 && path.getName(0).toString() == SOURCE_ROOT.toString()) {
            repoRoot.resolve(path).normalize()
        } else {
            repoRoot.resolve(SOURCE_ROOT).resolve(path).normalize()
        }
    }

    private fun sourceDisplayPath(pathValue: Path): String =
        sourceDisplayPath(pathValue.toString().replace('\\', '/'))

    private fun sourceDisplayPath(pathValue: String): String {
        val trimmed = pathValue.removePrefix("./")
        if (trimmed == ".") {
            return SOURCE_ROOT.toString()
        }
        return if (trimmed.startsWith("${SOURCE_ROOT}/")) {
            trimmed
        } else {
            "$SOURCE_ROOT/$trimmed"
        }
    }

    private fun sourceEntryFor(
        repoRoot: Path,
        entry: JsonObject,
        resolver: RemoteMarketplaceResolver,
    ): SourceEntry {
        val pluginRef = entry.requiredObject("plugin")
        val source = pluginRef.requiredObject("source")
        return when (val sourceType = source.stringValue("type")) {
            "LOCAL_SOURCE" -> SourceEntry(repoRoot, entry)
            "MARKETPLACE_SOURCE" -> {
                val importRef = MarketplaceImportRef.parse(
                    "${source.requiredString("marketplace")}/${source.requiredString("plugin")}"
                )
                val version = ExactVersion.parse(source.requiredString("version"))
                resolvedAssets.lockedImportRoot(repoRoot, importRef, version)?.let { cachedSource ->
                    val cachedEntry = marketplacePluginEntry(cachedSource.root, importRef)
                    requireRemoteVersion(
                        remoteRoot = cachedSource.root,
                        remoteEntry = cachedEntry,
                        marketplace = importRef.marketplace.value,
                        plugin = importRef.plugin.value,
                        version = version.value,
                    )
                    return SourceEntry(cachedSource.root, cachedEntry)
                }

                val remote = marketplace(repoRoot).externalMarketplaces()
                    .firstOrNull { it.name == importRef.marketplace.value }
                    ?: throw MarketplaceFailure.InvalidSource(
                        "unknown external marketplace: ${importRef.marketplace.value}"
                    )
                val resolved = resolver.resolve(remote)
                val remoteEntry = marketplacePluginEntry(resolved.root, importRef)
                requireRemoteVersion(
                    remoteRoot = resolved.root,
                    remoteEntry = remoteEntry,
                    marketplace = importRef.marketplace.value,
                    plugin = importRef.plugin.value,
                    version = version.value,
                )
                val cachedSource = resolvedAssets.store(importRef, version, resolved.root)
                SourceEntry(cachedSource.root, marketplacePluginEntry(cachedSource.root, importRef))
            }
            else -> throw MarketplaceFailure.InvalidSource("unsupported plugin source type: $sourceType")
        }
    }

    private fun requireRemoteVersion(
        remoteRoot: Path,
        remoteEntry: JsonObject,
        marketplace: String,
        plugin: String,
        version: String,
    ) {
        val remoteVersion = remotePluginVersion(remoteRoot, remoteEntry)
        if (remoteVersion != version) {
            throw MarketplaceFailure.InvalidSource(
                "plugin $marketplace/$plugin is version $remoteVersion, not requested version $version"
            )
        }
    }

    private fun remotePluginVersion(remoteRoot: Path, remoteEntry: JsonObject): String {
        val pluginRef = remoteEntry.requiredObject("plugin")
        pluginRef.stringValue("version")?.takeIf { it.isNotBlank() }?.let { return it }

        val source = pluginRef.requiredObject("source")
        if (source.stringValue("type") != "LOCAL_SOURCE") {
            throw MarketplaceFailure.InvalidSource("imported plugin must resolve to a local source in its marketplace")
        }
        val manifestPath = resolveSourcePath(remoteRoot, source.requiredString("path")).resolve("plugin.json")
        val manifest = JsonFiles.readObject(manifestPath)
        return manifest.stringValue("version")
            ?.takeIf { it.isNotBlank() }
            ?: throw MarketplaceFailure.InvalidSource(
                "imported plugin ${pluginRef.requiredString("name")} is missing an exact version"
            )
    }

    private fun marketplaceAt(root: Path): JsonObject {
        val sourceMarketplace = root.resolve(ADAPTABLE_MARKETPLACE_PATH)
        val rootMarketplace = root.resolve("adaptable.marketplace.json")
        return when {
            sourceMarketplace.exists() -> JsonFiles.readObject(sourceMarketplace)
            rootMarketplace.exists() -> JsonFiles.readObject(rootMarketplace)
            else -> throw MarketplaceFailure.InvalidSource("missing marketplace source in $root")
        }
    }

    private fun marketplacePluginEntry(root: Path, importRef: MarketplaceImportRef): JsonObject =
        marketplaceAt(root).arrayValue("plugins")
            .objects()
            .firstOrNull { it.stringValue("name") == importRef.plugin.value }
            ?: throw MarketplaceFailure.InvalidSource(
                "plugin ${importRef.plugin.value} was not found in ${importRef.marketplace.value}"
            )

    private fun JsonObject.externalMarketplaces(): List<ExternalMarketplace> =
        arrayValue("externalMarketplaces")
            .objects()
            .map { remote ->
                ExternalMarketplace(
                    name = remote.requiredString("name"),
                    source = remote.requiredObject("source"),
                )
            }

    private fun JsonObject.withReplacedFields(vararg replacements: Pair<String, JsonElement>): JsonObject =
        buildJsonObject {
            val replacementKeys = replacements.map { it.first }.toSet()
            this@withReplacedFields.forEach { (key, value) ->
                if (key !in replacementKeys) {
                    put(key, value)
                }
            }
            replacements.forEach { (key, value) -> put(key, value) }
        }

    private fun JsonObject.matches(importRef: MarketplaceImportRef, version: ExactVersion): Boolean {
        val target = objectValue("target") ?: return false
        val source = target.objectValue("source") ?: return false
        return target.stringValue("name") == importRef.plugin.value &&
            source.stringValue("type") == "MARKETPLACE_SOURCE" &&
            source.stringValue("marketplace") == importRef.marketplace.value &&
            source.stringValue("plugin") == importRef.plugin.value &&
            source.stringValue("version") == version.value
    }

    private class ResolvedMarketplaceAssets(
        private val root: Path,
    ) {
        fun store(
            importRef: MarketplaceImportRef,
            version: ExactVersion,
            remoteRoot: Path,
        ): CachedMarketplaceSource {
            val sourceRoot = remoteRoot.resolve(SOURCE_ROOT)
            if (!Files.isDirectory(sourceRoot)) {
                throw MarketplaceFailure.InvalidSource("resolved marketplace source root is missing: $sourceRoot")
            }

            val integrity = Digests.digestPath(sourceRoot, DigestAlgorithm.Sha256)
            cached(importRef, version, integrity)?.let { return it }

            root.createDirectories()
            val incoming = Files.createTempDirectory(root, ".incoming-")
            try {
                FileSystem.copyPath(sourceRoot, incoming.resolve(SOURCE_ROOT))
                val assetRoot = assetRoot(importRef, version, integrity)
                assetRoot.parent.createDirectories()
                FileSystem.copyPath(incoming, assetRoot)
                return cached(importRef, version, integrity)
                    ?: throw MarketplaceFailure.InvalidSource("failed to cache marketplace assets for ${importRef.display}")
            } finally {
                FileSystem.deleteRecursively(incoming)
            }
        }

        fun lockedImportRoot(
            repoRoot: Path,
            importRef: MarketplaceImportRef,
            version: ExactVersion,
        ): CachedMarketplaceSource? {
            val lockPath = repoRoot.resolve(MARKETPLACE_LOCK_PATH)
            if (!lockPath.exists()) {
                return null
            }

            val locked = JsonFiles.readObject(lockPath)
                .arrayValue("entries")
                .objects()
                .firstOrNull { entry -> entry.matches(importRef, version) }
                ?: return null
            return cached(importRef, version, locked.requiredString("integrity"))
        }

        private fun cached(
            importRef: MarketplaceImportRef,
            version: ExactVersion,
            integrity: String,
        ): CachedMarketplaceSource? {
            val assetRoot = assetRoot(importRef, version, integrity)
            val sourceRoot = assetRoot.resolve(SOURCE_ROOT)
            if (!sourceRoot.exists()) {
                return null
            }
            val actualIntegrity = Digests.digestPath(sourceRoot, DigestAlgorithm.Sha256)
            if (actualIntegrity != integrity) {
                throw MarketplaceFailure.InvalidSource(
                    "cached marketplace asset integrity mismatch for ${importRef.display}: " +
                        "expected $integrity but found $actualIntegrity"
                )
            }
            return CachedMarketplaceSource(root = assetRoot, integrity = integrity)
        }

        private fun assetRoot(
            importRef: MarketplaceImportRef,
            version: ExactVersion,
            integrity: String,
        ): Path =
            root.resolve("plugins")
                .resolve(importRef.marketplace.value)
                .resolve(importRef.plugin.value)
                .resolve(cacheComponent(version.value))
                .resolve(integrity.removePrefix("sha256:"))

        private fun JsonObject.matches(importRef: MarketplaceImportRef, version: ExactVersion): Boolean {
            val target = objectValue("target") ?: return false
            val source = target.objectValue("source") ?: return false
            return target.stringValue("name") == importRef.plugin.value &&
                source.stringValue("type") == "MARKETPLACE_SOURCE" &&
                source.stringValue("marketplace") == importRef.marketplace.value &&
                source.stringValue("plugin") == importRef.plugin.value &&
                source.stringValue("version") == version.value
        }
    }

    private inner class RemoteMarketplaceResolver(
        private val repoRoot: Path,
    ) : AutoCloseable {
        private val tempRoot: Path = Files.createTempDirectory("${BuildInfo.NAME}-marketplace-resolve-")
        private val resolvedByName = mutableMapOf<String, ResolvedMarketplace>()

        fun resolve(remote: ExternalMarketplace): ResolvedMarketplace =
            resolvedByName.getOrPut(remote.name) {
                val source = remote.source
                when (val sourceType = source.stringValue("type")) {
                    "LOCAL_SOURCE" -> {
                        val root = resolveExternalLocalRoot(source.requiredString("path"))
                        ResolvedMarketplace(root = root, resolvedSource = source)
                    }
                    "GITHUB_SOURCE" -> {
                        val repo = source.requiredString("repo")
                        val ref = source.requiredString("ref")
                        val root = cloneRepository(
                            cloneUrl = "https://github.com/$repo.git",
                            ref = ref,
                            destination = tempRoot.resolve(remote.name),
                        )
                        ResolvedMarketplace(root = root, resolvedSource = source.withSha(root))
                    }
                    "GIT_SOURCE" -> {
                        val root = cloneRepository(
                            cloneUrl = source.requiredString("url"),
                            ref = source.requiredString("ref"),
                            destination = tempRoot.resolve(remote.name),
                        )
                        ResolvedMarketplace(root = root, resolvedSource = source.withSha(root))
                    }
                    "GIT_SUBDIR_SOURCE" -> {
                        val checkout = cloneRepository(
                            cloneUrl = source.requiredString("url"),
                            ref = source.requiredString("ref"),
                            destination = tempRoot.resolve(remote.name),
                        )
                        val root = resolveRelative(checkout, source.requiredString("path"))
                        ResolvedMarketplace(root = root, resolvedSource = source.withSha(checkout))
                    }
                    else -> throw MarketplaceFailure.InvalidSource("unsupported external marketplace source type: $sourceType")
                }
            }

        override fun close() {
            FileSystem.deleteRecursively(tempRoot)
        }

        private fun resolveExternalLocalRoot(pathValue: String): Path {
            val root = resolveRelative(repoRoot, pathValue)
            if (!root.exists()) {
                throw MarketplaceFailure.InvalidSource("external marketplace path does not exist: $pathValue")
            }
            return root
        }

        private fun cloneRepository(cloneUrl: String, ref: String, destination: Path): Path {
            destination.parent.createDirectories()
            val branchClone = listOf(
                "git",
                "clone",
                "--depth",
                "1",
                "--single-branch",
                "--branch",
                ref,
                cloneUrl,
                destination.toString(),
            )
            if (runProcess(branchClone, repoRoot) != 0) {
                val clone = listOf("git", "clone", "--depth", "1", cloneUrl, destination.toString())
                if (runProcess(clone, repoRoot) != 0 || runProcess(listOf("git", "checkout", ref), destination) != 0) {
                    throw MarketplaceFailure.InvalidSource("failed to resolve external marketplace $cloneUrl at $ref")
                }
            }
            return destination
        }

        private fun JsonObject.withSha(gitRoot: Path): JsonObject =
            withReplacedFields("sha" to JsonPrimitive(gitOutput(listOf("git", "rev-parse", "HEAD"), gitRoot)))
    }

    private fun runProcess(command: List<String>, cwd: Path): Int =
        ProcessBuilder(command)
            .directory(cwd.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor()

    private fun gitOutput(command: List<String>, cwd: Path): String {
        val process = ProcessBuilder(command)
            .directory(cwd.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        if (process.waitFor() != 0 || output.isBlank()) {
            throw MarketplaceFailure.InvalidSource("command failed: ${command.joinToString(" ")}")
        }
        return output
    }

    private fun resolveRelative(base: Path, pathValue: String): Path {
        val relative = Path.of(pathValue.removePrefix("./")).normalize()
        if (relative.isAbsolute || relative.startsWith("..")) {
            throw MarketplaceFailure.InvalidSource("path escapes its root: $pathValue")
        }
        return base.resolve(relative).normalize()
    }

    private fun categoryFor(tags: List<String>): String {
        val tagSet = tags.toSet()
        return when {
            tagSet.any { it in setOf("git", "github", "ci", "schemas", "audit", "governance") } -> "Engineering"
            tagSet.any { it in setOf("kotlin", "testing") } -> "Coding"
            else -> "Productivity"
        }
    }

    private fun capabilitiesFor(hydrated: HydratedPlugin): List<String> =
        buildList {
            add("Interactive")
            if (hydrated.skills.isNotEmpty() || hydrated.hooks.isNotEmpty()) {
                add("Write")
            }
        }

    private fun brandColorFor(category: String): String =
        when (category) {
            "Coding" -> "#2563EB"
            "Engineering" -> "#0F766E"
            "Productivity" -> "#7C3AED"
            else -> "#475569"
        }

    private fun defaultPrompts(pluginName: String): List<String> {
        val display = titleCase(pluginName)
        return listOf(
            "Use $display for this task.",
            "Review this repo with $display.",
            "Show the $display workflow.",
        )
    }

    private fun shortDescription(description: String): String =
        if (description.length <= 72) description else description.take(69).trimEnd() + "..."

    private fun titleCase(value: String): String {
        val acronyms = mapOf(
            "api" to "API",
            "ci" to "CI",
            "cli" to "CLI",
            "github" to "GitHub",
            "json" to "JSON",
            "pr" to "PR",
            "prs" to "PRs",
            "tdd" to "TDD",
            "yaml" to "YAML",
        )
        return value.replace("_", "-")
            .split("-")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> acronyms[part.lowercase()] ?: part.replaceFirstChar { it.uppercase() } }
    }

    private data class PluginProjection(
        val name: String,
        val repoRoot: Path,
        val sourcePath: Path,
        val manifest: JsonObject,
        val pluginRef: JsonObject,
        val description: String,
        val tags: List<String>,
        val category: String,
        val owner: HarnessPerson,
        val interfaceMetadata: NeutralPluginInterfaceMetadata,
        val adaptableManifest: AdaptablePluginDocument?,
    ) {
        val version: String =
            manifest.stringValue("version") ?: pluginRef.stringValue("version") ?: "0.1.0"
    }

    private data class NeutralPluginInterfaceMetadata(
        val websiteUrl: HttpsUrl?,
        val privacyPolicyUrl: HttpsUrl?,
        val termsOfServiceUrl: HttpsUrl?,
    ) {
        companion object {
            private val supportedFields = setOf(
                "websiteURL",
                "privacyPolicyURL",
                "termsOfServiceURL",
            )

            fun fromManifest(manifest: JsonObject, manifestPath: Path): NeutralPluginInterfaceMetadata {
                val payload = manifest["interface"] ?: return empty
                val source = payload as? JsonObject
                    ?: throw MarketplaceFailure.InvalidSource("${manifestPath}: interface must be an object")
                val unknownFields = source.keys - supportedFields
                if (unknownFields.isNotEmpty()) {
                    throw MarketplaceFailure.InvalidSource(
                        "${manifestPath}: unsupported interface field(s): ${unknownFields.sorted().joinToString(", ")}"
                    )
                }
                return NeutralPluginInterfaceMetadata(
                    websiteUrl = source.httpsUrl("websiteURL", manifestPath),
                    privacyPolicyUrl = source.httpsUrl("privacyPolicyURL", manifestPath),
                    termsOfServiceUrl = source.httpsUrl("termsOfServiceURL", manifestPath),
                )
            }

            private val empty = NeutralPluginInterfaceMetadata(
                websiteUrl = null,
                privacyPolicyUrl = null,
                termsOfServiceUrl = null,
            )

            private fun JsonObject.httpsUrl(field: String, manifestPath: Path): HttpsUrl? =
                stringValue(field)?.let { HttpsUrl.parse(it, manifestPath, field) }
        }
    }

    private class HttpsUrl private constructor(val value: String) {
        companion object {
            fun parse(value: String, manifestPath: Path, field: String): HttpsUrl {
                val normalized = value.trim()
                if (!normalized.startsWith("https://") || normalized.length == "https://".length) {
                    throw MarketplaceFailure.InvalidSource("${manifestPath}: interface.$field must be an https URL")
                }
                return HttpsUrl(normalized)
            }
        }
    }

    private companion object {
        const val CODEX_PLUGIN_DIR = ".codex-plugin"
        val SOURCE_ROOT: Path = Path.of("source")
        val ADAPTABLE_MARKETPLACE_PATH: Path = SOURCE_ROOT.resolve("adaptable.marketplace.json")
        val PROJECTOR_STATE_ROOT: Path = Path.of(".${BuildInfo.NAME}")
        val CODEX_MARKETPLACE_ROOT: Path = Path.of(".agents").resolve("plugins")
        val CODEX_BRANCH_MARKETPLACE_PATH: Path = CODEX_MARKETPLACE_ROOT.resolve("marketplace.json")
        val CODEX_BRANCH_PLUGINS_PATH: Path = CODEX_MARKETPLACE_ROOT
        val CODEX_BRANCH_LOCK_PATH: Path = CODEX_MARKETPLACE_ROOT.resolve("marketplace-lock.json")
        val GITHUB_COPILOT_PROVIDER_PATH: Path = Path.of(".github").resolve("plugin")
        val GITHUB_BRANCH_MARKETPLACE_PATH: Path = GITHUB_COPILOT_PROVIDER_PATH.resolve("marketplace.json")
        val GITHUB_BRANCH_PLUGINS_PATH: Path = GITHUB_COPILOT_PROVIDER_PATH
        const val GITHUB_MARKETPLACE_PLUGIN_ROOT = ".github/plugin"
        val GITHUB_AGENT_MODEL_FIELD = Regex("^model\\s*:.*$")
        val HOOK_COMMAND_PATH_RE = Regex("\\bhooks/[A-Za-z0-9_.-]+")
        val MARKETPLACE_LOCK_PATH: Path = PROJECTOR_STATE_ROOT.resolve("marketplace-lock.json")
    }
}

private class MarketplaceName private constructor(
    val value: String,
) {
    companion object {
        private val NAME = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")

        fun parse(value: String): MarketplaceName {
            if (!NAME.matches(value)) {
                throw MarketplaceFailure.InvalidSource("marketplace names must be kebab-case: $value")
            }
            return MarketplaceName(value)
        }
    }
}

private class ExactVersion private constructor(
    val value: String,
) {
    companion object {
        fun parse(value: String): ExactVersion {
            if (value.isBlank() || value.any(Char::isWhitespace) || value.any { it in setOf('^', '~', '*', '<', '>', '=') }) {
                throw MarketplaceFailure.InvalidSource("imports require an exact version")
            }
            return ExactVersion(value)
        }
    }
}

private data class MarketplaceImportRef(
    val marketplace: MarketplaceName,
    val plugin: MarketplaceName,
) {
    val display: String = "${marketplace.value}/${plugin.value}"

    companion object {
        fun parse(value: String): MarketplaceImportRef {
            val parts = value.split("/")
            if (parts.size != 2) {
                throw MarketplaceFailure.InvalidSource("import must use marketplace/plugin syntax")
            }
            return MarketplaceImportRef(
                marketplace = MarketplaceName.parse(parts[0]),
                plugin = MarketplaceName.parse(parts[1]),
            )
        }
    }
}

private data class ExternalMarketplace(
    val name: String,
    val source: JsonObject,
)

private data class ResolvedMarketplace(
    val root: Path,
    val resolvedSource: JsonObject,
)

private data class CachedMarketplaceSource(
    val root: Path,
    val integrity: String,
)

private data class SourceEntry(
    val repoRoot: Path,
    val entry: JsonObject,
)

private fun JsonArray.forEachObject(action: (JsonObject) -> Unit) {
    forEach { element ->
        action(element.jsonObject)
    }
}

private fun JsonArray.objects(): List<JsonObject> =
    mapNotNull { it as? JsonObject }

private fun JsonObject.requiredObject(key: String): JsonObject =
    objectValue(key) ?: throw MarketplaceFailure.InvalidSource("missing object field `$key` in $this")

private fun JsonObject.requiredString(key: String): String =
    stringValue(key)?.takeIf { it.isNotBlank() }
        ?: throw MarketplaceFailure.InvalidSource("missing string field `$key` in $this")

private fun Path.relativeToUnix(base: Path): String =
    base.relativize(this).toString().replace('\\', '/')

private const val RESOLVED_ASSET_ROOT_ENV = "SOURCE_PROJECTION_ASSET_ROOT"

private val CACHE_COMPONENT_RE = Regex("[^A-Za-z0-9_.-]+")

private fun defaultResolvedAssetRoot(): Path =
    System.getenv(RESOLVED_ASSET_ROOT_ENV)
        ?.takeIf { it.isNotBlank() }
        ?.toCliPath()
        ?: Path.of(System.getProperty("user.home"), ".local", "share", BuildInfo.NAME, "marketplace-assets")

private fun cacheComponent(value: String): String =
    CACHE_COMPONENT_RE.replace(value, "_").trim('_').ifBlank { "_" }
