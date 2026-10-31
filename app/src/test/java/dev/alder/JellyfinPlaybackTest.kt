package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Test

class JellyfinPlaybackTest {
    @Test fun distinguishesDirectPlayFromDirectStreamAndTranscode() {
        assertEquals("DirectPlay", jellyfinPlayMethod(directPlay = true, directStream = false))
        assertEquals("DirectStream", jellyfinPlayMethod(directPlay = false, directStream = true))
        assertEquals("Transcode", jellyfinPlayMethod(directPlay = false, directStream = false))
    }

    @Test fun directPlayTakesPrecedenceWhenBothFlagsAreSet() {
        assertEquals("DirectPlay", jellyfinPlayMethod(directPlay = true, directStream = true))
    }
}
