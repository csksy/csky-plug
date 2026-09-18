package com.animeparty

import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.IPlayer
import com.lagradost.cloudstream3.ui.result.ResultEpisode

// CloudStream exposes no official player hook for plugins, so walk the
// fragment stack and poll the public player surface. Everything degrades to
// null instead of throwing, an app update only makes the overlay idle.
object PartyProbe {

    private val metaMethod by lazy {
        GeneratorPlayer::class.java.getDeclaredMethod("getCurrentMeta").apply {
            isAccessible = true
        }
    }

    fun playerFragment(): GeneratorPlayer? = runCatching {
        val activity = CommonActivity.activity as? MainActivity ?: return null
        val navHost = activity.supportFragmentManager
            .findFragmentById(com.lagradost.cloudstream3.R.id.nav_host_fragment) as? NavHostFragment
            ?: return null
        val top: Fragment? = navHost.childFragmentManager.fragments.lastOrNull()
        top as? GeneratorPlayer
    }.getOrNull()

    fun isPlayerActive(): Boolean = playerFragment() != null

    fun currentPlayer(): IPlayer? = runCatching {
        playerFragment()?.player
    }.getOrNull()

    fun currentEpisode(): ResultEpisode? = runCatching {
        val fragment = playerFragment() ?: return null
        metaMethod.invoke(fragment) as? ResultEpisode
    }.getOrNull()

    fun currentLabel(): String? {
        val meta = currentEpisode() ?: return null
        val show = meta.headerName?.takeIf { it.isNotBlank() } ?: meta.name ?: return null
        val season = meta.season
        val episode = if (meta.episode > 0) meta.episode else null
        return when {
            season != null && episode != null -> "$show S${season}E$episode"
            episode != null -> "$show EP $episode"
            else -> show
        }
    }
}
