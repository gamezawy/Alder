package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JellyfinEpisodesTest {
    private fun episode(id: String, season: Int?, number: Int?, played: Boolean = false, position: Long = 0L) =
        JellyfinItem(id, id, "Episode", "", null, null, null, played, position,
            seasonNumber = season, episodeNumber = number)

    @Test
    fun episodesAreOrderedBySeasonAndEpisodeWithSpecialsLast() {
        val ordered = orderJellyfinEpisodes(listOf(
            episode("special", null, 1),
            episode("s2e1", 2, 1),
            episode("s1e2", 1, 2),
            episode("s1e1", 1, 1)
        ))
        assertEquals(listOf("s1e1", "s1e2", "s2e1", "special"), ordered.map { it.id })
    }

    @Test
    fun resumeEpisodeTakesPriorityOverFirstUnplayedEpisode() {
        val selected = nextJellyfinEpisode(listOf(
            episode("first", 1, 1),
            episode("resume", 1, 2, position = 90_000L),
            episode("played", 1, 3, played = true)
        ))
        assertEquals("resume", selected?.id)
    }

    @Test
    fun completedSeriesWithoutUnplayedEpisodesFallsBackToFirstEpisode() {
        val selected = nextJellyfinEpisode(listOf(
            episode("first", 1, 1, played = true),
            episode("second", 1, 2, played = true)
        ))
        assertEquals("first", selected?.id)
    }

    @Test
    fun emptySeriesHasNoNextEpisode() {
        assertNull(nextJellyfinEpisode(emptyList()))
    }
}
