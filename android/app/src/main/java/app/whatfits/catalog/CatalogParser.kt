package app.whatfits.catalog

import org.json.JSONObject

object CatalogParser {
    fun parse(jsonl: String): List<Device> = jsonl.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(::parseDevice)
        .toList()

    private fun parseDevice(line: String): Device {
        val json = JSONObject(line)
        val aliasesJson = json.getJSONArray("aliases")
        val replacementsJson = json.getJSONArray("replacements")

        return Device(
            brand = json.getString("brand"),
            canonicalName = json.getString("canonical_name"),
            modelCode = json.getString("model_code"),
            aliases = List(aliasesJson.length()) { aliasesJson.getString(it) },
            market = json.getString("market"),
            replacements = List(replacementsJson.length()) { index ->
                val replacement = replacementsJson.getJSONObject(index)
                val source = replacement.getJSONObject("source")
                Replacement(
                    type = replacement.getString("type"),
                    partNumber = replacement.getString("part_number"),
                    canonicalName = replacement.getString("canonical_name"),
                    yieldPages = if (replacement.isNull("yield_pages")) {
                        null
                    } else {
                        replacement.getInt("yield_pages")
                    },
                    status = replacement.getString("status"),
                    source = Source(
                        publisher = source.getString("publisher"),
                        title = source.getString("title"),
                        url = source.getString("url"),
                        checkedAt = source.getString("checked_at"),
                    ),
                )
            },
        )
    }
}
