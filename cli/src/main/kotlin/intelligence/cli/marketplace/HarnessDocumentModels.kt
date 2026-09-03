@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package intelligence.cli.marketplace

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal sealed interface NativeHarnessDocument

@Serializable
internal data class HarnessPerson(
    val name: String,
    val email: String? = null,
    val url: String? = null,
)

@Serializable
internal data class CodexMarketplaceDocument(
    val name: String,
    @SerialName("interface") val presentation: CodexMarketplaceInterface,
    val plugins: List<CodexMarketplacePluginEntry>,
) : NativeHarnessDocument

@Serializable
internal data class CodexMarketplaceInterface(
    val displayName: String,
)

@Serializable
internal data class CodexMarketplacePluginEntry(
    val name: String,
    val source: CodexLocalPluginSource,
    val policy: CodexPluginPolicy,
    val category: String,
)

@Serializable
internal data class CodexLocalPluginSource(
    val source: CodexLocalPluginSourceType,
    val path: String,
)

@Serializable
internal enum class CodexLocalPluginSourceType {
    @SerialName("local")
    Local,
}

@Serializable
internal data class CodexPluginPolicy(
    val installation: CodexInstallationPolicy,
    val authentication: CodexAuthenticationPolicy,
    val products: List<String>? = null,
)

@Serializable
internal enum class CodexInstallationPolicy {
    @SerialName("NOT_AVAILABLE")
    NotAvailable,

    @SerialName("AVAILABLE")
    Available,

    @SerialName("INSTALLED_BY_DEFAULT")
    InstalledByDefault,
}

@Serializable
internal enum class CodexAuthenticationPolicy {
    @SerialName("ON_INSTALL")
    OnInstall,

    @SerialName("ON_USE")
    OnUse,
}

@Serializable
internal data class CodexPluginDocument(
    val name: String,
    val version: String,
    val description: String,
    val author: HarnessPerson,
    @SerialName("interface") val presentation: CodexPluginInterface,
    val id: String? = null,
    val homepage: String? = null,
    val repository: String? = null,
    val license: String? = null,
    val keywords: List<String>? = null,
    val skills: String? = null,
    val hooks: List<String>? = null,
    val apps: String? = null,
    val mcpServers: String? = null,
) : NativeHarnessDocument

@Serializable
internal data class CodexPluginInterface(
    val displayName: String,
    val shortDescription: String,
    val longDescription: String,
    val developerName: String,
    val category: String,
    val capabilities: List<String>,
    @SerialName("websiteURL") val websiteUrl: String? = null,
    @SerialName("privacyPolicyURL") val privacyPolicyUrl: String? = null,
    @SerialName("termsOfServiceURL") val termsOfServiceUrl: String? = null,
    val brandColor: String? = null,
    val composerIcon: String? = null,
    val logo: String? = null,
    val screenshots: List<String>? = null,
    val defaultPrompt: List<String>? = null,
    @SerialName("default_prompt") val legacyDefaultPrompt: List<String>? = null,
)

@Serializable
internal data class GitHubMarketplaceDocument(
    val name: String,
    val owner: HarnessPerson,
    val plugins: List<GitHubMarketplacePluginEntry>,
    @SerialName("\$schema") val schema: String? = null,
    val metadata: GitHubMarketplaceMetadata? = null,
) : NativeHarnessDocument

@Serializable
internal data class GitHubMarketplaceMetadata(
    val description: String? = null,
    val version: String? = null,
    val pluginRoot: String? = null,
)

@Serializable
internal data class GitHubMarketplacePluginEntry(
    val name: String,
    val source: JsonElement,
    val description: String? = null,
    val version: String? = null,
    val author: HarnessPerson? = null,
    val homepage: String? = null,
    val repository: String? = null,
    val license: String? = null,
    val keywords: List<String>? = null,
    val category: String? = null,
    val tags: List<String>? = null,
    val commands: JsonElement? = null,
    val agents: JsonElement? = null,
    val skills: JsonElement? = null,
    val hooks: JsonElement? = null,
    val mcpServers: JsonElement? = null,
    val lspServers: JsonElement? = null,
    val strict: Boolean? = null,
)

@Serializable
internal data class GitHubPluginDocument(
    val name: String,
    @SerialName("\$schema") val schema: String? = null,
    val description: String? = null,
    val version: String? = null,
    val author: HarnessPerson? = null,
    val homepage: String? = null,
    val repository: String? = null,
    val license: String? = null,
    val keywords: List<String>? = null,
    val category: String? = null,
    val tags: List<String>? = null,
    val commands: JsonElement? = null,
    val agents: JsonElement? = null,
    val skills: JsonElement? = null,
    val hooks: JsonElement? = null,
    val mcpServers: JsonElement? = null,
    val lspServers: JsonElement? = null,
    val extensions: JsonElement? = null,
) : NativeHarnessDocument

internal sealed interface AdaptableDocumentModel

@Serializable
internal data class AdaptableMarketplaceDocument(
    @EncodeDefault val type: AdaptableMarketplaceType = AdaptableMarketplaceType.Marketplace,
    @EncodeDefault val schemaVersion: Int = 1,
    val name: String,
    val owner: AdaptableOwner,
    val plugins: List<AdaptablePluginEntry>,
    val description: String? = null,
    val management: AdaptableManagementPolicy? = null,
    val externalMarketplaces: List<AdaptableExternalMarketplace> = emptyList(),
    @EncodeDefault val skills: List<JsonObject> = emptyList(),
    @EncodeDefault val agents: List<JsonObject> = emptyList(),
    @EncodeDefault val instructions: List<JsonObject> = emptyList(),
    @EncodeDefault val hooks: List<JsonObject> = emptyList(),
    val adapters: AdaptableMarketplaceAdapters? = null,
    val metadata: Map<String, String> = emptyMap(),
) : AdaptableDocumentModel

@Serializable
internal enum class AdaptableMarketplaceType {
    @SerialName("MARKETPLACE")
    Marketplace,
}

@Serializable
internal data class AdaptableOwner(
    val name: String,
    val email: String? = null,
)

@Serializable
internal data class AdaptableManagementPolicy(
    @EncodeDefault val type: AdaptableManagementPolicyType = AdaptableManagementPolicyType.Policy,
    val mode: AdaptableManagementMode,
    val allowExternalMarketplaces: List<String> = emptyList(),
)

@Serializable
internal enum class AdaptableManagementPolicyType {
    @SerialName("MANAGEMENT_POLICY")
    Policy,
}

@Serializable
internal enum class AdaptableManagementMode {
    @SerialName("OPEN")
    Open,

    @SerialName("CURATED")
    Curated,

    @SerialName("MANAGED")
    Managed,
}

@Serializable
internal data class AdaptableExternalMarketplace(
    @EncodeDefault val type: AdaptableExternalMarketplaceType = AdaptableExternalMarketplaceType.Marketplace,
    val name: String,
    val source: AdaptableSource,
)

@Serializable
internal enum class AdaptableExternalMarketplaceType {
    @SerialName("EXTERNAL_MARKETPLACE")
    Marketplace,
}

@Serializable
internal data class AdaptablePluginEntry(
    @EncodeDefault val type: AdaptablePluginEntryType = AdaptablePluginEntryType.Entry,
    val name: String,
    val plugin: AdaptablePluginReference,
    val description: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
internal enum class AdaptablePluginEntryType {
    @SerialName("PLUGIN_ENTRY")
    Entry,
}

@Serializable
internal data class AdaptablePluginReference(
    @EncodeDefault val type: AdaptablePluginReferenceType = AdaptablePluginReferenceType.Reference,
    val name: String,
    val source: AdaptableSource,
    val version: String? = null,
    val integrity: String? = null,
)

@Serializable
internal enum class AdaptablePluginReferenceType {
    @SerialName("PLUGIN_REFERENCE")
    Reference,
}

@Serializable
internal sealed interface AdaptableSource

@Serializable
@SerialName("LOCAL_SOURCE")
internal data class AdaptableLocalSource(
    val path: String,
) : AdaptableSource

@Serializable
@SerialName("GITHUB_SOURCE")
internal data class AdaptableGitHubSource(
    val repo: String,
    val ref: String? = null,
    val sha: String? = null,
) : AdaptableSource

@Serializable
@SerialName("GIT_SOURCE")
internal data class AdaptableGitSource(
    val url: String,
    val ref: String? = null,
    val sha: String? = null,
) : AdaptableSource

@Serializable
@SerialName("GIT_SUBDIR_SOURCE")
internal data class AdaptableGitSubdirSource(
    val url: String,
    val path: String,
    val ref: String? = null,
    val sha: String? = null,
) : AdaptableSource

@Serializable
@SerialName("MARKETPLACE_SOURCE")
internal data class AdaptableMarketplaceSource(
    val marketplace: String,
    val plugin: String,
    val version: String? = null,
) : AdaptableSource

@Serializable
internal sealed interface AdaptableMarketplaceAdapters

@Serializable
@SerialName("CODEX_MARKETPLACE_ADAPTER")
internal data class CodexMarketplaceAdapter(
    val codex: CodexMarketplaceDocument,
) : AdaptableMarketplaceAdapters

@Serializable
@SerialName("GITHUB_COPILOT_MARKETPLACE_ADAPTER")
internal data class GitHubCopilotMarketplaceAdapter(
    @SerialName("github-copilot") val githubCopilot: GitHubMarketplaceDocument,
) : AdaptableMarketplaceAdapters

@Serializable
@SerialName("MULTI_HARNESS_MARKETPLACE_ADAPTER")
internal data class MultiHarnessMarketplaceAdapters(
    val codex: CodexMarketplaceDocument,
    @SerialName("github-copilot") val githubCopilot: GitHubMarketplaceDocument,
) : AdaptableMarketplaceAdapters

internal val AdaptableMarketplaceAdapters.codex: CodexMarketplaceDocument?
    get() =
        when (this) {
            is CodexMarketplaceAdapter -> codex
            is GitHubCopilotMarketplaceAdapter -> null
            is MultiHarnessMarketplaceAdapters -> codex
        }

internal val AdaptableMarketplaceAdapters.githubCopilot: GitHubMarketplaceDocument?
    get() =
        when (this) {
            is CodexMarketplaceAdapter -> null
            is GitHubCopilotMarketplaceAdapter -> githubCopilot
            is MultiHarnessMarketplaceAdapters -> githubCopilot
        }

@Serializable
internal data class AdaptablePluginDocument(
    @EncodeDefault val type: AdaptablePluginType = AdaptablePluginType.Plugin,
    @EncodeDefault val schemaVersion: Int = 1,
    val name: String,
    val version: String? = null,
    val description: String? = null,
    @SerialName("interface") val presentation: AdaptablePluginInterface? = null,
    val extends: List<AdaptablePluginReference> = emptyList(),
    @EncodeDefault val skills: List<JsonObject> = emptyList(),
    @EncodeDefault val agents: List<JsonObject> = emptyList(),
    @EncodeDefault val instructions: List<JsonObject> = emptyList(),
    @EncodeDefault val hooks: List<JsonObject> = emptyList(),
    val adapters: AdaptablePluginAdapters? = null,
    val metadata: Map<String, String> = emptyMap(),
) : AdaptableDocumentModel

@Serializable
internal enum class AdaptablePluginType {
    @SerialName("PLUGIN")
    Plugin,
}

@Serializable
internal data class AdaptablePluginInterface(
    @SerialName("websiteURL") val websiteUrl: String? = null,
    @SerialName("privacyPolicyURL") val privacyPolicyUrl: String? = null,
    @SerialName("termsOfServiceURL") val termsOfServiceUrl: String? = null,
)

@Serializable
internal sealed interface AdaptablePluginAdapters

@Serializable
@SerialName("CODEX_PLUGIN_ADAPTER")
internal data class CodexPluginAdapter(
    val codex: CodexPluginDocument,
) : AdaptablePluginAdapters

@Serializable
@SerialName("GITHUB_COPILOT_PLUGIN_ADAPTER")
internal data class GitHubCopilotPluginAdapter(
    @SerialName("github-copilot") val githubCopilot: GitHubPluginDocument,
) : AdaptablePluginAdapters

@Serializable
@SerialName("MULTI_HARNESS_PLUGIN_ADAPTER")
internal data class MultiHarnessPluginAdapters(
    val codex: CodexPluginDocument,
    @SerialName("github-copilot") val githubCopilot: GitHubPluginDocument,
) : AdaptablePluginAdapters

internal val AdaptablePluginAdapters.codex: CodexPluginDocument?
    get() =
        when (this) {
            is CodexPluginAdapter -> codex
            is GitHubCopilotPluginAdapter -> null
            is MultiHarnessPluginAdapters -> codex
        }

internal val AdaptablePluginAdapters.githubCopilot: GitHubPluginDocument?
    get() =
        when (this) {
            is CodexPluginAdapter -> null
            is GitHubCopilotPluginAdapter -> githubCopilot
            is MultiHarnessPluginAdapters -> githubCopilot
        }
