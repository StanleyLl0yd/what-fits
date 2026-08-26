package app.whatfits.catalog

import android.content.Context

object CatalogRepository {
    private const val CATALOG_ASSET = "seed_ru_printers_v0.1.jsonl"

    fun load(context: Context): List<Device> = context.assets
        .open(CATALOG_ASSET)
        .bufferedReader(Charsets.UTF_8)
        .use { reader -> CatalogParser.parse(reader.readText()) }
}
