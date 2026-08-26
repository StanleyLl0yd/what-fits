package app.whatfits.catalog

import java.util.Locale

class CatalogMatcher(private val devices: List<Device>) {
    fun resolve(query: String, market: String = "RU"): FitResult {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.length < 2) return FitResult.NotFound

        val ranked = devices.asSequence()
            .filter { it.market == market }
            .mapNotNull { device ->
                val bestRank = identifiers(device)
                    .map(::normalize)
                    .filter { identifier ->
                        identifier == normalizedQuery ||
                            (identifier.length >= 4 && normalizedQuery.contains(identifier))
                    }
                    .maxOfOrNull { identifier ->
                        if (identifier == normalizedQuery) EXACT_RANK else identifier.length
                    }
                bestRank?.let { device to it }
            }
            .sortedWith(compareByDescending<Pair<Device, Int>> { it.second }.thenBy { it.first.canonicalName })
            .toList()

        if (ranked.isNotEmpty()) {
            val bestRank = ranked.first().second
            val exact = ranked.takeWhile { it.second == bestRank }.map { it.first }
            return if (exact.size == 1) {
                FitResult.Exact(exact.first())
            } else {
                FitResult.Ambiguous(exact)
            }
        }

        val candidates = devices.asSequence()
            .filter { it.market == market }
            .filter { device ->
                identifiers(device)
                    .map(::normalize)
                    .any { identifier ->
                        normalizedQuery.length >= 2 &&
                            (identifier.contains(normalizedQuery) || normalizedQuery.contains(identifier))
                    }
            }
            .sortedBy(Device::canonicalName)
            .take(5)
            .toList()

        return if (candidates.isEmpty()) {
            FitResult.NotFound
        } else {
            FitResult.Ambiguous(candidates)
        }
    }

    companion object {
        private const val EXACT_RANK = 100_000

        fun normalize(value: String): String = value
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .filter(Char::isLetterOrDigit)

        private fun identifiers(device: Device): List<String> = buildList {
            add(device.modelCode)
            add(device.canonicalName)
            addAll(device.aliases)
        }.distinct()
    }
}
