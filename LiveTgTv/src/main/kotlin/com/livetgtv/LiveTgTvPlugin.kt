package com.livetgtv

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

@CloudstreamPlugin
class LiveTgTvPlugin : Plugin() {

    override fun load(context: Context) {
        // the livetgtv feed ships as an always on extension, a firebase value
        // under livetgtv_playlist can repoint it without shipping an update
        registerMainAPI(LiveTgTvProvider("livetgtv", DEFAULT_PLAYLIST_URL, PLAYLIST_FIREBASE_KEY))
        for (entry in savedPlaylists(context)) {
            if (entry.name.isBlank() || entry.url.isBlank()) continue
            registerMainAPI(LiveTgTvProvider(entry.name, entry.url))
        }

        openSettings = { ctx ->
            (ctx as? AppCompatActivity)?.let { activity ->
                LiveTgTvSettingsFragment().show(activity.supportFragmentManager, "LiveTgTvSettings")
            }
            kotlin.Unit
        }
    }

    private fun savedPlaylists(context: Context): List<PlaylistEntry> {
        return try {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_PLAYLISTS, "[]") ?: "[]"
            parseJson<List<PlaylistEntry>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        const val DEFAULT_PLAYLIST_URL = "https://livetgtv.lovable.app/api/public/playlist.m3u"
        const val PLAYLIST_FIREBASE_KEY = "livetgtv_playlist"
        const val PREFS_NAME = "livetgtv_prefs"
        const val PREF_PLAYLISTS = "playlists"
    }
}
