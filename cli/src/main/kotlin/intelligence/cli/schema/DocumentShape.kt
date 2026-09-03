package intelligence.cli.schema

internal enum class DocumentKind {
    Marketplace,
    Plugin,
}

internal enum class Harness(
    val adapterKey: String,
) {
    Codex("codex"),
    GitHubCopilot("github-copilot"),
}

internal sealed interface DocumentShape {
    val cliName: String
    val schemaPath: String
    val kind: DocumentKind

    data object AdaptableMarketplace : AdaptableDocumentShape {
        override val cliName: String = "adaptable-marketplace"
        override val schemaPath: String = "marketplace/adaptable.schema.json"
        override val kind: DocumentKind = DocumentKind.Marketplace
    }

    data object AdaptablePlugin : AdaptableDocumentShape {
        override val cliName: String = "adaptable-plugin"
        override val schemaPath: String = "core/plugin.schema.json"
        override val kind: DocumentKind = DocumentKind.Plugin
    }

    data object CodexMarketplace : NativeDocumentShape {
        override val cliName: String = "codex-marketplace"
        override val schemaPath: String = "marketplace/codex.schema.json"
        override val kind: DocumentKind = DocumentKind.Marketplace
        override val harness: Harness = Harness.Codex
        override val adaptable: AdaptableDocumentShape = AdaptableMarketplace
    }

    data object CodexPlugin : NativeDocumentShape {
        override val cliName: String = "codex-plugin"
        override val schemaPath: String = "adapters/codex/plugin.schema.json"
        override val kind: DocumentKind = DocumentKind.Plugin
        override val harness: Harness = Harness.Codex
        override val adaptable: AdaptableDocumentShape = AdaptablePlugin
    }

    data object GitHubCopilotMarketplace : NativeDocumentShape {
        override val cliName: String = "github-copilot-marketplace"
        override val schemaPath: String = "marketplace/github.schema.json"
        override val kind: DocumentKind = DocumentKind.Marketplace
        override val harness: Harness = Harness.GitHubCopilot
        override val adaptable: AdaptableDocumentShape = AdaptableMarketplace
    }

    data object GitHubCopilotPlugin : NativeDocumentShape {
        override val cliName: String = "github-copilot-plugin"
        override val schemaPath: String = "adapters/github/plugin.schema.json"
        override val kind: DocumentKind = DocumentKind.Plugin
        override val harness: Harness = Harness.GitHubCopilot
        override val adaptable: AdaptableDocumentShape = AdaptablePlugin
    }

    sealed interface ParseResult {
        data class Parsed(val shape: DocumentShape) : ParseResult

        data class Unsupported(val value: String) : ParseResult
    }

    companion object {
        val entries: List<DocumentShape> = listOf(
            AdaptableMarketplace,
            AdaptablePlugin,
            CodexMarketplace,
            CodexPlugin,
            GitHubCopilotMarketplace,
            GitHubCopilotPlugin,
        )

        val nativeEntries: List<NativeDocumentShape> = entries.filterIsInstance<NativeDocumentShape>()

        fun parse(value: String): ParseResult =
            entries.singleOrNull { shape -> shape.cliName == value.lowercase() }
                ?.let(ParseResult::Parsed)
                ?: ParseResult.Unsupported(value)
    }
}

internal sealed interface AdaptableDocumentShape : DocumentShape

internal sealed interface NativeDocumentShape : DocumentShape {
    val harness: Harness
    val adaptable: AdaptableDocumentShape
}
