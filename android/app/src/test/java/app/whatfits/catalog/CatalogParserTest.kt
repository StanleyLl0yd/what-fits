package app.whatfits.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogParserTest {
    @Test
    fun parsesDeviceReplacementAndEvidence() {
        val jsonl = """
            {"brand":"Pantum","canonical_name":"Pantum P2500W","model_code":"P2500W","aliases":["P2500 W"],"market":"RU","replacements":[{"type":"toner_cartridge","part_number":"PC-211P","canonical_name":"Pantum PC-211P","yield_pages":1600,"status":"VERIFIED","source":{"publisher":"Pantum Russia","title":"Картридж PC-211P","url":"https://example.test/pc-211p","checked_at":"2026-08-25"}}]}
        """.trimIndent()

        val device = CatalogParser.parse(jsonl).single()
        val replacement = device.replacements.single()

        assertEquals("P2500W", device.modelCode)
        assertEquals(listOf("P2500 W"), device.aliases)
        assertEquals("PC-211P", replacement.partNumber)
        assertEquals(1600, replacement.yieldPages)
        assertEquals("VERIFIED", replacement.status)
        assertEquals("Pantum Russia", replacement.source.publisher)
    }
}
