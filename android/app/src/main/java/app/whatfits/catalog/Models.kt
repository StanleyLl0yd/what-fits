package app.whatfits.catalog

data class Source(
    val publisher: String,
    val title: String,
    val url: String,
    val checkedAt: String,
)

data class Replacement(
    val type: String,
    val partNumber: String,
    val canonicalName: String,
    val yieldPages: Int?,
    val status: String,
    val source: Source,
)

data class Device(
    val brand: String,
    val canonicalName: String,
    val modelCode: String,
    val aliases: List<String>,
    val market: String,
    val replacements: List<Replacement>,
)

sealed interface FitResult {
    data class Exact(val device: Device) : FitResult
    data class Ambiguous(val candidates: List<Device>) : FitResult
    data object NotFound : FitResult
}
