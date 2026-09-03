package intelligence.cli.marketplace

import intelligence.cli.io.stringValue
import intelligence.cli.schema.DocumentKind
import intelligence.cli.schema.Harness
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@JvmInline
internal value class MarketplaceOwnerName private constructor(
    val value: String,
) {
    companion object {
        fun refine(value: String): OwnerNameRefinement {
            val normalized = value.trim()
            return if (normalized.isEmpty()) {
                OwnerNameRefinement.Blank
            } else {
                OwnerNameRefinement.Refined(MarketplaceOwnerName(normalized))
            }
        }
    }
}

internal sealed interface OwnerNameRefinement {
    data class Refined(val name: MarketplaceOwnerName) : OwnerNameRefinement

    data object Blank : OwnerNameRefinement
}

internal sealed interface IngestionTarget {
    data object New : IngestionTarget

    data class Existing(
        val document: AdaptableDocumentModel,
    ) : IngestionTarget
}

internal data class HarnessIngestionRequest(
    val input: NativeHarnessDocument,
    val target: IngestionTarget,
    val ownerName: MarketplaceOwnerName?,
)

internal sealed interface HarnessIngestionResult {
    data class Ingested(
        val document: AdaptableDocumentModel,
    ) : HarnessIngestionResult

    sealed interface Rejected : HarnessIngestionResult {
        data object OwnerRequired : Rejected

        data class KindConflict(
            val expected: DocumentKind,
            val actual: DocumentKind,
        ) : Rejected

        data class IdentityConflict(
            val targetName: String,
            val inputName: String,
        ) : Rejected

        data class AdapterExists(
            val harness: Harness,
        ) : Rejected
    }
}

internal object HarnessDocumentIngestor {
    fun ingest(request: HarnessIngestionRequest): HarnessIngestionResult =
        when (val target = request.target) {
            IngestionTarget.New -> create(request.input, request.ownerName)
            is IngestionTarget.Existing -> addAdapter(request.input, target.document)
        }

    private fun create(
        input: NativeHarnessDocument,
        ownerName: MarketplaceOwnerName?,
    ): HarnessIngestionResult =
        when (input) {
            is CodexMarketplaceDocument ->
                if (ownerName == null) {
                    HarnessIngestionResult.Rejected.OwnerRequired
                } else {
                    HarnessIngestionResult.Ingested(
                        AdaptableMarketplaceDocument(
                            name = input.name,
                            owner = AdaptableOwner(name = ownerName.value),
                            plugins = input.plugins.map(::adaptablePluginEntry),
                            adapters = CodexMarketplaceAdapter(codex = input),
                        ),
                    )
                }
            is GitHubMarketplaceDocument ->
                HarnessIngestionResult.Ingested(
                    AdaptableMarketplaceDocument(
                        name = input.name,
                        owner = AdaptableOwner(
                            name = input.owner.name,
                            email = input.owner.email,
                        ),
                        plugins = input.plugins.map(::adaptablePluginEntry),
                        description = input.metadata?.description,
                        adapters = GitHubCopilotMarketplaceAdapter(githubCopilot = input),
                    ),
                )
            is CodexPluginDocument ->
                HarnessIngestionResult.Ingested(
                    AdaptablePluginDocument(
                        name = input.name,
                        version = input.version,
                        description = input.description,
                        presentation = adaptableInterface(input),
                        adapters = CodexPluginAdapter(codex = input),
                    ),
                )
            is GitHubPluginDocument ->
                HarnessIngestionResult.Ingested(
                    AdaptablePluginDocument(
                        name = input.name,
                        version = input.version,
                        description = input.description,
                        presentation = adaptableInterface(input),
                        adapters = GitHubCopilotPluginAdapter(githubCopilot = input),
                    ),
                )
        }

    private fun addAdapter(
        input: NativeHarnessDocument,
        target: AdaptableDocumentModel,
    ): HarnessIngestionResult {
        if (input.shape.kind != target.shape.kind) {
            return HarnessIngestionResult.Rejected.KindConflict(
                expected = input.shape.kind,
                actual = target.shape.kind,
            )
        }
        if (input.name != target.name) {
            return HarnessIngestionResult.Rejected.IdentityConflict(
                targetName = target.name,
                inputName = input.name,
            )
        }
        return when (target) {
            is AdaptableMarketplaceDocument -> addMarketplaceAdapter(input, target)
            is AdaptablePluginDocument -> addPluginAdapter(input, target)
        }
    }

    private fun addMarketplaceAdapter(
        input: NativeHarnessDocument,
        target: AdaptableMarketplaceDocument,
    ): HarnessIngestionResult =
        when (input) {
            is CodexMarketplaceDocument -> {
                when (val adapters = target.adapters) {
                    null -> HarnessIngestionResult.Ingested(
                        target.copy(adapters = CodexMarketplaceAdapter(codex = input)),
                    )
                    is GitHubCopilotMarketplaceAdapter -> HarnessIngestionResult.Ingested(
                        target.copy(
                            adapters = MultiHarnessMarketplaceAdapters(
                                codex = input,
                                githubCopilot = adapters.githubCopilot,
                            ),
                        ),
                    )
                    is CodexMarketplaceAdapter,
                    is MultiHarnessMarketplaceAdapters,
                    -> HarnessIngestionResult.Rejected.AdapterExists(Harness.Codex)
                }
            }
            is GitHubMarketplaceDocument -> {
                when (val adapters = target.adapters) {
                    null -> HarnessIngestionResult.Ingested(
                        target.copy(adapters = GitHubCopilotMarketplaceAdapter(githubCopilot = input)),
                    )
                    is CodexMarketplaceAdapter -> HarnessIngestionResult.Ingested(
                        target.copy(
                            adapters = MultiHarnessMarketplaceAdapters(
                                codex = adapters.codex,
                                githubCopilot = input,
                            ),
                        ),
                    )
                    is GitHubCopilotMarketplaceAdapter,
                    is MultiHarnessMarketplaceAdapters,
                    -> HarnessIngestionResult.Rejected.AdapterExists(Harness.GitHubCopilot)
                }
            }
            is CodexPluginDocument,
            is GitHubPluginDocument,
            -> HarnessIngestionResult.Rejected.KindConflict(
                expected = input.shape.kind,
                actual = target.shape.kind,
            )
        }

    private fun addPluginAdapter(
        input: NativeHarnessDocument,
        target: AdaptablePluginDocument,
    ): HarnessIngestionResult =
        when (input) {
            is CodexPluginDocument -> {
                when (val adapters = target.adapters) {
                    null -> HarnessIngestionResult.Ingested(
                        target.copy(adapters = CodexPluginAdapter(codex = input)),
                    )
                    is GitHubCopilotPluginAdapter -> HarnessIngestionResult.Ingested(
                        target.copy(
                            adapters = MultiHarnessPluginAdapters(
                                codex = input,
                                githubCopilot = adapters.githubCopilot,
                            ),
                        ),
                    )
                    is CodexPluginAdapter,
                    is MultiHarnessPluginAdapters,
                    -> HarnessIngestionResult.Rejected.AdapterExists(Harness.Codex)
                }
            }
            is GitHubPluginDocument -> {
                when (val adapters = target.adapters) {
                    null -> HarnessIngestionResult.Ingested(
                        target.copy(adapters = GitHubCopilotPluginAdapter(githubCopilot = input)),
                    )
                    is CodexPluginAdapter -> HarnessIngestionResult.Ingested(
                        target.copy(
                            adapters = MultiHarnessPluginAdapters(
                                codex = adapters.codex,
                                githubCopilot = input,
                            ),
                        ),
                    )
                    is GitHubCopilotPluginAdapter,
                    is MultiHarnessPluginAdapters,
                    -> HarnessIngestionResult.Rejected.AdapterExists(Harness.GitHubCopilot)
                }
            }
            is CodexMarketplaceDocument,
            is GitHubMarketplaceDocument,
            -> HarnessIngestionResult.Rejected.KindConflict(
                expected = input.shape.kind,
                actual = target.shape.kind,
            )
        }

    private fun adaptablePluginEntry(entry: CodexMarketplacePluginEntry): AdaptablePluginEntry =
        AdaptablePluginEntry(
            name = entry.name,
            plugin = AdaptablePluginReference(
                name = entry.name,
                source = AdaptableLocalSource(path = entry.source.path),
            ),
        )

    private fun adaptablePluginEntry(entry: GitHubMarketplacePluginEntry): AdaptablePluginEntry =
        AdaptablePluginEntry(
            name = entry.name,
            plugin = AdaptablePluginReference(
                name = entry.name,
                source = adaptableSource(entry.source),
                version = entry.version,
            ),
            description = entry.description,
            tags = entry.tags.orEmpty(),
        )

    private fun adaptableSource(source: JsonElement): AdaptableSource =
        when (source) {
            is JsonPrimitive -> AdaptableLocalSource(path = source.content)
            is JsonObject ->
                when (val sourceType = source.provenString("source")) {
                    "github" -> {
                        val path = source.stringValue("path")
                        if (path == null) {
                            AdaptableGitHubSource(
                                repo = source.provenString("repo"),
                                ref = source.stringValue("ref"),
                                sha = source.stringValue("sha"),
                            )
                        } else {
                            AdaptableGitSubdirSource(
                                url = source.provenString("repo"),
                                path = path,
                                ref = source.stringValue("ref"),
                                sha = source.stringValue("sha"),
                            )
                        }
                    }
                    "url" -> AdaptableGitSource(
                        url = source.provenString("url"),
                        ref = source.stringValue("ref"),
                        sha = source.stringValue("sha"),
                    )
                    "git-subdir" -> AdaptableGitSubdirSource(
                        url = source.provenString("url"),
                        path = source.provenString("path"),
                        ref = source.stringValue("ref"),
                        sha = source.stringValue("sha"),
                    )
                    else -> error("schema-validated GitHub plugin source has unsupported type: $sourceType")
                }
            else -> error("schema-validated GitHub plugin source has unsupported JSON kind")
        }

    private fun JsonObject.provenString(name: String): String =
        checkNotNull(stringValue(name)) {
            "schema-validated GitHub plugin source is missing `$name`"
        }

    private fun adaptableInterface(plugin: CodexPluginDocument): AdaptablePluginInterface? =
        AdaptablePluginInterface(
            websiteUrl = plugin.presentation.websiteUrl,
            privacyPolicyUrl = plugin.presentation.privacyPolicyUrl,
            termsOfServiceUrl = plugin.presentation.termsOfServiceUrl,
        ).takeUnless { presentation -> presentation.isEmptyModel() }

    private fun adaptableInterface(plugin: GitHubPluginDocument): AdaptablePluginInterface? =
        AdaptablePluginInterface(
            websiteUrl = plugin.homepage,
        ).takeUnless { presentation -> presentation.isEmptyModel() }

    private fun AdaptablePluginInterface.isEmptyModel(): Boolean =
        websiteUrl == null && privacyPolicyUrl == null && termsOfServiceUrl == null
}
