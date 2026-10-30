package dev.alder

import android.content.Intent
import android.os.Bundle

/**
 * Compatibility entry point for older intents. Jellyfin now lives inside MainActivity's unified Alder home.
 */
class JellyfinHomeActivity : Screen() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
