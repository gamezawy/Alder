package dev.alder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderSearchTest {
    private fun jellyfin(name: String, year: Int, type: String = "Movie") =
        JellyfinItem("j-" + name + year, name, type, "", null, year, null, false, 0L)

    private fun stremio(name: String, year: String, type: String = "movie") =
        Meta(JSONObject().put("id", "s-" + name + year).put("name", name).put("releaseInfo", year).put("type", type))

    @Test
    fun prefersJellyfinForEquivalentResultsByDefault() {
        val hits = mergeProviderSearchHits(
            listOf(jellyfin("Dune", 2021)),
            listOf(stremio("Dune", "2021")),
            "jellyfin"
        )
        assertEquals(1, hits.size)
        assertEquals("jellyfin", hits.single().provider)
    }

    @Test
    fun honorsStremioPriorityWhenConfigured() {
        val hits = mergeProviderSearchHits(
            listOf(jellyfin("Dune", 2021)),
            listOf(stremio("Dune", "2021")),
            "stremio"
        )
        assertEquals(1, hits.size)
        assertEquals("stremio", hits.single().provider)
    }

    @Test
    fun keepsDifferentYearsAndMediaTypesSeparate() {
        val hits = mergeProviderSearchHits(
            listOf(jellyfin("The Office", 2005, "Series")),
            listOf(stremio("The Office", "1995", "movie"), stremio("The Office", "2005", "series")),
            "jellyfin"
        )
        assertEquals(2, hits.size)
    }
}
