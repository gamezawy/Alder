package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvTest {
    @Test
    fun parsesM3uChannelMetadataAndGroups() {
        val playlist = Iptv.parseM3u(
            """#EXTM3U
#EXTINF:-1 tvg-id="news.one" tvg-name="News One" tvg-logo="https://cdn.example/logo.png" group-title="News",News One HD
https://stream.example/news.m3u8
#EXTINF:-1 group-title="Sports, Live" tvg-id="sport.two",Sports 2
https://stream.example/sport.ts
"""
        )
        assertEquals(2, playlist.channels.size)
        assertEquals("News One HD", playlist.channels[0].name)
        assertEquals("news.one", playlist.channels[0].tvgId)
        assertEquals("https://cdn.example/logo.png", playlist.channels[0].logoUrl)
        assertEquals("News", playlist.channels[0].group)
        assertEquals("Sports, Live", playlist.channels[1].group)
        assertEquals(listOf("News", "Sports, Live"), playlist.groups())
    }

    @Test
    fun parsesPerChannelRequestHeaders() {
        val playlist = Iptv.parseM3u(
            """#EXTM3U
#EXTINF:-1 group-title="Movies",Protected Channel
#EXTVLCOPT:http-user-agent=TestPlayer/1.0
#EXTVLCOPT:http-referrer=https://portal.example/
https://stream.example/protected
"""
        )
        val channel = playlist.channels.single()
        assertEquals("TestPlayer/1.0", channel.userAgent)
        assertEquals("https://portal.example/", channel.referrer)
    }

    @Test
    fun channelIdsAreStableAndDistinctForDifferentStreams() {
        val first = IptvChannel("News", "https://stream.example/news")
        val same = IptvChannel("Renamed News", "https://stream.example/news")
        val other = IptvChannel("News", "https://stream.example/other")
        assertEquals(Iptv.stableId(first), Iptv.stableId(same))
        assertTrue(Iptv.stableId(first) != Iptv.stableId(other))
    }

    @Test
    fun skipsInvalidAndDuplicateStreamUrls() {
        val playlist = Iptv.parseM3u(
            """#EXTM3U
#EXTINF:-1,Invalid
file:///tmp/movie.ts
#EXTINF:-1,Valid
https://stream.example/live
#EXTINF:-1,Duplicate
https://stream.example/live
"""
        )
        assertEquals(1, playlist.channels.size)
        assertTrue(playlist.channels.single().streamUrl.startsWith("https://"))
    }
}
