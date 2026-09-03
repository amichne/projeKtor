package intelligence.cli.marketplace

internal sealed class MarketplaceFailure(
    message: String,
) : RuntimeException(message) {
    class InvalidSource(message: String) : MarketplaceFailure(message)
}
