package com.horis.cncverse

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CNCVersePlugin : Plugin() {
    override fun load(context: Context) {
        // Initialise the shared cookie/token cache before any provider runs so
        // the first request can reuse a cached CF-bypass cookie and NewTv token.
        NetflixMirrorStorage.init(context.applicationContext)
        DisneyStudioProvider.setContext(context)
        NetflixMirrorProvider.setContext(context)
        PrimeVideoMirrorProvider.setContext(context)
        HotStarMirrorProvider.setContext(context)

        registerMainAPI(NetflixMirrorProvider())
        registerMainAPI(PrimeVideoMirrorProvider())
        registerMainAPI(HotStarMirrorProvider())

        // All four Disney-owned studios are registered unconditionally — the
        // server-side `studio` cookie (Disney/Marvel/StarWars/Pixar) is what
        // scopes each provider's home page and search results, so a single
        // class implementation handles the four distinct catalogue slices.
        listOf(
            StudioOption("studio_disney", "Disney", "disney"),
            StudioOption("studio_marvel", "Marvel", "marvel"),
            StudioOption("studio_starwars", "Star Wars", "starwars"),
            StudioOption("studio_pixar", "Pixar", "pixar")
        ).forEach { option ->
            registerMainAPI(DisneyStudioProvider(option.cookieValue, option.label))
        }
    }
}
