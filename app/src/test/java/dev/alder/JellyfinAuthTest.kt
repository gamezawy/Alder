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

    @Test fun queryParameterMatchesServerGeneration() {
        assertEquals("ApiKey", JellyfinAuth.apiKeyParameter(true))
        assertEquals("api_key", JellyfinAuth.apiKeyParameter(false))
    }
}
