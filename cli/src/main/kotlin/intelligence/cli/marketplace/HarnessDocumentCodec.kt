package intelligence.cli.marketplace

import intelligence.cli.io.JsonFiles
import intelligence.cli.schema.AdaptableDocumentShape
import intelligence.cli.schema.DocumentShape
import intelligence.cli.schema.NativeDocumentShape
import intelligence.cli.schema.ValidatedDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal sealed interface HarnessDocumentDecoding<out D> {
    data class Decoded<D>(val document: D) : HarnessDocumentDecoding<D>

    data class Rejected(val reason: String) : HarnessDocumentDecoding<Nothing>
}

internal object HarnessDocumentCodec {
    fun decodeNative(
        validated: ValidatedDocument<NativeDocumentShape>,
    ): HarnessDocumentDecoding<NativeHarnessDocument> =
        decode {
            when (validated.shape) {
                DocumentShape.CodexMarketplace ->
                    JsonFiles.json.decodeFromJsonElement<CodexMarketplaceDocument>(validated.value)
                DocumentShape.CodexPlugin ->
                    JsonFiles.json.decodeFromJsonElement<CodexPluginDocument>(validated.value)
                DocumentShape.GitHubCopilotMarketplace ->
                    JsonFiles.json.decodeFromJsonElement<GitHubMarketplaceDocument>(validated.value)
                DocumentShape.GitHubCopilotPlugin ->
                    JsonFiles.json.decodeFromJsonElement<GitHubPluginDocument>(validated.value)
            }
        }

    fun decodeAdaptable(
        validated: ValidatedDocument<AdaptableDocumentShape>,
    ): HarnessDocumentDecoding<AdaptableDocumentModel> =
        decode {
            when (validated.shape) {
                DocumentShape.AdaptableMarketplace ->
                    JsonFiles.json.decodeFromJsonElement<AdaptableMarketplaceDocument>(validated.value)
                DocumentShape.AdaptablePlugin ->
                    JsonFiles.json.decodeFromJsonElement<AdaptablePluginDocument>(validated.value)
            }
        }

    fun encode(document: AdaptableDocumentModel): JsonObject =
        when (document) {
            is AdaptableMarketplaceDocument -> JsonFiles.json.encodeToJsonElement(document).jsonObject
            is AdaptablePluginDocument -> JsonFiles.json.encodeToJsonElement(document).jsonObject
        }

    fun encode(document: CodexMarketplaceDocument): JsonObject =
        JsonFiles.json.encodeToJsonElement(document).jsonObject

    fun encode(document: CodexPluginDocument): JsonObject =
        JsonFiles.json.encodeToJsonElement(document).jsonObject

    fun encode(document: GitHubMarketplaceDocument): JsonObject =
        JsonFiles.json.encodeToJsonElement(document).jsonObject

    fun encode(document: GitHubPluginDocument): JsonObject =
        JsonFiles.json.encodeToJsonElement(document).jsonObject

    private inline fun <D> decode(block: () -> D): HarnessDocumentDecoding<D> =
        try {
            HarnessDocumentDecoding.Decoded(block())
        } catch (failure: SerializationException) {
            HarnessDocumentDecoding.Rejected(failure.message ?: "document does not match its generated model")
        } catch (failure: IllegalArgumentException) {
            HarnessDocumentDecoding.Rejected(failure.message ?: "document does not match its generated model")
        }
}

internal val NativeHarnessDocument.shape: NativeDocumentShape
    get() =
        when (this) {
            is CodexMarketplaceDocument -> DocumentShape.CodexMarketplace
            is CodexPluginDocument -> DocumentShape.CodexPlugin
            is GitHubMarketplaceDocument -> DocumentShape.GitHubCopilotMarketplace
            is GitHubPluginDocument -> DocumentShape.GitHubCopilotPlugin
        }

internal val NativeHarnessDocument.name: String
    get() =
        when (this) {
            is CodexMarketplaceDocument -> name
            is CodexPluginDocument -> name
            is GitHubMarketplaceDocument -> name
            is GitHubPluginDocument -> name
        }

internal val AdaptableDocumentModel.shape: AdaptableDocumentShape
    get() =
        when (this) {
            is AdaptableMarketplaceDocument -> DocumentShape.AdaptableMarketplace
            is AdaptablePluginDocument -> DocumentShape.AdaptablePlugin
        }

internal val AdaptableDocumentModel.name: String
    get() =
        when (this) {
            is AdaptableMarketplaceDocument -> name
            is AdaptablePluginDocument -> name
        }
