package com.laddu100.anishows

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/** Container for data fetched during MALSync requests */
data class MalSyncData(
    val title: String?,
    val animepaheUrl: String?,
    val aniId: Int?,
    val malId: Int?,
    val episode: Int?,
    val year: Int?,
    val origin: String,
    val animepaheTitle: String?,
)

/** * Defines a provider and its execution logic for Standard, Anime, and MALSync data.
 * The `AniShowsExtractors.` receiver allows direct access to internal scraping functions.
 */
data class ProviderDef(
    val key: String,
    val displayName: String,
    val isTorrent: Boolean = false,
    val executeStandard: (suspend AniShowsExtractors.(res: AllLoadLinksData, subCb: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) -> Unit)? = null,
    val executeAnime: (suspend AniShowsExtractors.(res: AllLoadLinksData, subCb: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) -> Unit)? = null,
    val executeMalSync: (suspend AniShowsExtractors.(data: MalSyncData, subCb: (SubtitleFile) -> Unit, cb: (ExtractorLink) -> Unit) -> Unit)? = null
)

object ProviderRegistry {

    val builtInProviders = listOf(
        ProviderDef(
            key = "p_vidrock", displayName = "Vidrock",
            executeStandard = { res, _, cb -> invokeVidrock(res.tmdbId, res.season, res.episode, cb) }
        ),
        ProviderDef(
            key = "p_videasy", displayName = "Videasy",
            executeStandard = { res, subCb, cb -> invokeVideasy(res.title, res.tmdbId, res.imdbId, res.year, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "p_vidfast", displayName = "VidFast",
            executeStandard = { res, subCb, cb -> invokeVidFastPro(res.tmdbId, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "p_vidlink", displayName = "Vidlink",
            executeStandard = { res, subCb, cb -> invokeVidlink(res.tmdbId, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "p_vidzee", displayName = "Vidzee",
            executeStandard = { res, subCb, cb -> invokeVidzee(res.tmdbId, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "p_vidcore", displayName = "Vidcore",
            executeStandard = { res, subCb, cb -> invokeVidcore(res.tmdbId, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "p_vegamovies", displayName = "VegaMovies",
            executeStandard = { res, subCb, cb -> invokeVegamovies("VegaMovies", res.imdbId, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "p_rogmovies", displayName = "RogMovies",
            executeStandard = { res, subCb, cb -> invokeVegamovies("RogMovies", res.imdbId, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "p_moviesmod", displayName = "Moviesmod",
            executeStandard = { res, subCb, cb -> invokeMoviesmod(res.imdbId, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "p_topmovies", displayName = "TopMovies",
            executeStandard = { res, subCb, cb -> invokeTopMovies(res.imdbId, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "p_moviesdrive", displayName = "MoviesDrive",
            executeStandard = { res, subCb, cb -> invokeMoviesdrive(res.title, res.imdbId, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "p_4khdhub", displayName = "4KHDHub",
            executeStandard = { res, subCb, cb -> invoke4khdhub(res.title, res.year, res.season, res.episode, subCb, cb) }
        ),
        ProviderDef(
            key = "p_movies4u", displayName = "Movies4u",
            executeStandard = { res, subCb, cb -> invokeMovies4u(res.imdbId, res.title, res.year, res.season, res.episode, subCb, cb) }
        ),
    )

    // Dynamically provided to Settings.kt
    val keys get() = builtInProviders.map { it.key }
    val namesMap get() = builtInProviders.associate { it.key to it.displayName }
    val torrentKeys get() = builtInProviders.filter { it.isTorrent }.map { it.key }.toSet()
}
