package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinAuthTest {
    @Test fun detectsJellyfin12AndNewer() {
        assertTrue(JellyfinAuth.usesModernAuthorization("12.4.0"))
        assertTrue(JellyfinAuth.usesModernAuthorization("13.0.1"))
    }

    @Test fun keepsLegacyModeForJellyfin10And11() {
        assertFalse(JellyfinAuth.usesModernAuthorization("10.11.11"))
        assertFalse(JellyfinAuth.usesModernAuthorization("11.0.0"))
    }

    @Test fun unknownVersionUsesSupportedModernScheme() {
        assertTrue(JellyfinAuth.usesModernAuthorization(null))
        assertTrue(JellyfinAuth.usesModernAuthorization("unknown"))
    }

    @Test fun modernAndLegacyHeadersRemainSeparate() {
        assertTrue(JellyfinAuth.loginHeaders("test", true).containsKey("Authorization"))
        assertFalse(JellyfinAuth.loginHeaders("test", true).containsKey("X-Emby-Authorization"))
        assertTrue(JellyfinAuth.loginHeaders("test", false).containsKey("X-Emby-Authorization"))
        assertEquals("MediaBrowser Token=\"secret\"", JellyfinAuth.tokenHeaders("secret", true)["Authorization"])
        assertEquals("secret", JellyfinAuth.tokenHeaders("secret", false)["X-Emby-Token"])
    }

    private fun episode(id: String, season: Int?, number: Int?, played: Boolean = false, position: Long = 0L) =
        JellyfinItem(id, id, "Episode", null, null, null, played, position,
            seasonNumber = season, episodeNumber = number)

    @Test fun episodesAreOrderedBySeasonAndEpisodeWithoutServerSortFields() {
        val unordered = listOf(episode("s2e1", 2, 1), episode("special", null, 2),
            episode("s1e2", 1, 2), episode("s1e1", 1, 1))
        assertEquals(listOf("special", "s1e1", "s1e2", "s2e1"), orderJellyfinEpisodes(unordered).map { it.id })
    }

    @Test fun nextEpisodePrefersUnfinishedResumeThenFirstUnwatched() {
        val episodes = listOf(episode("watched", 1, 1, played = true),
            episode("next", 1, 2), episode("resume", 1, 3, position = 45_000L))
        assertEquals("resume", nextJellyfinEpisode(episodes)?.id)
        assertEquals("next", nextJellyfinEpisode(episodes.dropLast(1))?.id)
    }

    @Test fun nextEpisodeFallsBackToFirstWhenEverythingWasWatched() {
        val episodes = listOf(episode("first", 1, 1, played = true), episode("second", 1, 2, played = true))
        assertEquals("first", nextJellyfinEpisode(episodes)?.id)
    }

    @Test fun queryParameterMatchesServerGeneration() {
        assertEquals("ApiKey", JellyfinAuth.apiKeyParameter(true))
        assertEquals("api_key", JellyfinAuth.apiKeyParameter(false))
    }
}
