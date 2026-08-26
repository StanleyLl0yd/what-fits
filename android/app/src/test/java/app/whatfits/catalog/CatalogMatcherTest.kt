package app.whatfits.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMatcherTest {
    private val matcher = CatalogMatcher(
        listOf(
            device("P2500"),
            device("P2500W", aliases = listOf("P2500 W")),
            device("P2502W"),
        ),
    )

    @Test
    fun exactModelResolvesOffline() {
        val result = matcher.resolve("P2500W")

        assertTrue(result is FitResult.Exact)
        assertEquals("P2500W", (result as FitResult.Exact).device.modelCode)
    }

    @Test
    fun longestIdentifierWinsInsideOcrText() {
        val result = matcher.resolve("PANTUM P2500W 220-240V 50Hz")

        assertTrue(result is FitResult.Exact)
        assertEquals("P2500W", (result as FitResult.Exact).device.modelCode)
    }

    @Test
    fun twoEqualExactModelsRequireConfirmation() {
        val result = matcher.resolve("P2500W P2502W")

        assertTrue(result is FitResult.Ambiguous)
        assertEquals(
            setOf("P2500W", "P2502W"),
            (result as FitResult.Ambiguous).candidates.map(Device::modelCode).toSet(),
        )
    }

    @Test
    fun fuzzyPrefixIsNeverPromotedToExact() {
        assertTrue(matcher.resolve("P25") is FitResult.Ambiguous)
    }

    @Test
    fun unknownModelIsNotGuessed() {
        assertEquals(FitResult.NotFound, matcher.resolve("ZXQ999"))
    }

    private fun device(code: String, aliases: List<String> = emptyList()) = Device(
        brand = "Pantum",
        canonicalName = "Pantum $code",
        modelCode = code,
        aliases = aliases,
        market = "RU",
        replacements = emptyList(),
    )
}
