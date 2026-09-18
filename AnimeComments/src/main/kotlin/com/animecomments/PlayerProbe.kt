package com.animecomments

import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.result.ResultEpisode

// CloudStream has no official hook for a plugin to read the player, so walk
// the standard fragment stack and reflect the current meta out of the player
// fragment. Every access degrades to null on failure so an app update can
// never crash the plugin, it just stops reacting.
object PlayerProbe {

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

    fun currentEpisode(): ResultEpisode? = runCatching {
        val fragment = playerFragment() ?: return null
        metaMethod.invoke(fragment) as? ResultEpisode
    }.getOrNull()
}
