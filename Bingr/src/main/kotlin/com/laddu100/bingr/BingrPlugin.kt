package com.laddu100.bingr

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class BingrPlugin : Plugin() {
    override fun load(context: Context) {
        // Register both providers: Bingr for movies/TV, Bingr Anime for anime.
        // They share the same API client (BingrApi) but use different ID systems
        // (TMDB for movies/TV, AniList for anime).
        registerMainAPI(BingrProvider())
        registerMainAPI(BingrAnimeProvider())
    }
}
