package com.retrovault

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import android.widget.LinearLayout
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.net.URLEncoder

private data class GameEntry(
    val id: String,
    val title: String,
    val system: String,
    val kind: String,
    val payloadPath: String,
    val posterPath: String,
    val description: String,
    val tags: List<String>,
    val landscape: Boolean,
)

class RetroVault : MainAPI() {
    override var mainUrl = "https://retrovault.local"
    override var name = "RetroVault"
    override val supportedTypes = setOf(TvType.Others)
    override var lang = "en"
    override val hasMainPage = true

    private val emulatorSystems = linkedMapOf(
        "Nintendo Entertainment System" to "nes",
        "Super Nintendo" to "snes",
        "Game Boy / Color" to "gb",
        "Game Boy Advance" to "gba",
        "Sega Genesis" to "segaMD",
        "Sega Master System" to "segaMS",
        "Sega Game Gear" to "segaGG",
        "Nintendo 64" to "n64",
        "PlayStation" to "psx",
        "Arcade" to "arcade",
    )

    private val games = listOf(
        GameEntry(
            "snek", "Snek", "NES Homebrew", "emulated",
            "roms/snek.nes", "posters/snek.png",
            "Snake for the NES by zorchenhimer. Guide the snake, eat, grow, " +
                "do not bite yourself. Open source under the MIT license.",
            listOf("NES", "Snake", "MIT License"), true,
        ),
        GameEntry(
            "pong", "Pong", "NES Homebrew", "emulated",
            "roms/pong.nes", "posters/pong.png",
            "Classic paddle duel for the NES by zorchenhimer, written from " +
                "scratch in 6502 assembly. Open source under the BSD license.",
            listOf("NES", "Arcade", "BSD License"), true,
        ),
        GameEntry(
            "flappy_paratroopa", "Flappy Paratroopa", "NES Homebrew", "emulated",
            "roms/flappy_paratroopa.nes", "posters/flappy_paratroopa.png",
            "A Flappy Bird style game for the NES: be the paratroopa, dodge " +
                "the pipes. Open source under the MIT license.",
            listOf("NES", "Arcade", "MIT License"), true,
        ),
        GameEntry(
            "waveforms", "Waveforms", "NES Homebrew", "emulated",
            "roms/waveforms.nes", "posters/waveforms.png",
            "A tiny NES audio toy by Nick Culbertson that plays and shows " +
                "different console waveforms. Open source under the MIT license.",
            listOf("NES", "Demo", "MIT License"), true,
        ),
        GameEntry(
            "ucity", "uCity", "Game Boy Homebrew", "emulated",
            "roms/ucity.gbc", "posters/ucity.png",
            "Micro city builder for the Game Boy Color by AntonioND: zone " +
                "land, manage power and traffic, watch your micro metropolis " +
                "grow. Open source under the GPLv3 license.",
            listOf("Game Boy", "Strategy", "GPL License"), false,
        ),
        GameEntry(
            "g2048", "2048", "HTML5 Arcade", "html5",
            "html5/g2048/index.html", "posters/g2048.png",
            "The famous tile merger by Gabriele Cirulli. Swipe to join the " +
                "numbers and reach 2048. Open source under the MIT license.",
            listOf("HTML5", "Puzzle", "MIT License"), false,
        ),
        GameEntry(
            "hextris", "Hextris", "HTML5 Arcade", "html5",
            "html5/hextris/index.html", "posters/hextris.png",
            "Fast paced hexagon block game inspired by Tetris. Open source " +
                "under the GPL license.",
            listOf("HTML5", "Puzzle", "GPL License"), false,
        ),
        GameEntry(
            "clumsybird", "Clumsy Bird", "HTML5 Arcade", "html5",
            "html5/clumsybird/index.html", "posters/clumsybird.png",
            "The MelonJS take on Flappy Bird by Ellison Leao. Tap to flap. " +
                "Open source under the GPL license.",
            listOf("HTML5", "Arcade", "GPL License"), false,
        ),
    )

    private val customKey = "retrovault_custom_roms"

    private fun customRoms(): List<List<String>> =
        (getKey<List<String>>(customKey) ?: emptyList()).mapNotNull { raw ->
            val parts = raw.split("|")
            if (parts.size == 3) parts else null
        }

    override val mainPage = mainPageOf(
        Pair("nes", "NES Homebrew"),
        Pair("gb", "Game Boy Homebrew"),
        Pair("html5", "HTML5 Arcade"),
        Pair("custom", "My ROM Library"),
    )

    private fun gameToSearch(game: GameEntry, posterUrl: String): MovieSearchResponse =
        newMovieSearchResponse(
            game.title,
            "retro://game/${game.id}",
            TvType.Others,
        ) {
            this.posterUrl = posterUrl
            this.year = null
        }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

        when (request.data) {
            "custom" -> {
                val dir = runCatching { RetroVaultLauncher.unpack(CommonActivity.activity!!) }.getOrNull()
                val items = mutableListOf<MovieSearchResponse>()
                customRoms().forEachIndexed { index, entry ->
                    val poster = dir?.let { RetroVaultLauncher.urlFor(it, "posters/romvault.png") }
                    items.add(
                        newMovieSearchResponse(entry[2], "retro://custom/$index", TvType.Others) {
                            this.posterUrl = poster
                        }
                    )
                }
                val addPoster = dir?.let { RetroVaultLauncher.urlFor(it, "posters/romvault.png") }
                items.add(
                    newMovieSearchResponse("Add a ROM by URL", "retro://add", TvType.Others) {
                        this.posterUrl = addPoster
                    }
                )
                return newHomePageResponse(request.name, items, false)
            }

            "html5" -> {
                val dir = runCatching { RetroVaultLauncher.unpack(CommonActivity.activity!!) }.getOrNull()
                    ?: return newHomePageResponse(request.name, emptyList(), false)
                val items = games.filter { it.kind == "html5" }.map { game ->
                    gameToSearch(game, RetroVaultLauncher.urlFor(dir, game.posterPath))
                }
                return newHomePageResponse(request.name, items, false)
            }

            else -> {
                val dir = runCatching { RetroVaultLauncher.unpack(CommonActivity.activity!!) }.getOrNull()
                    ?: return newHomePageResponse(request.name, emptyList(), false)
                val items = games.filter { it.system == request.name }.map { game ->
                    gameToSearch(game, RetroVaultLauncher.urlFor(dir, game.posterPath))
                }
                return newHomePageResponse(request.name, items, false)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (!trimmed.startsWith("http")) return emptyList()
        val lower = trimmed.lowercase()
        val core = when (lower.substringAfterLast(".")) {
            "nes" -> "nes"
            "gb", "gbc" -> "gb"
            "gba" -> "gba"
            "smc", "sfc" -> "snes"
            "md", "smd", "gen" -> "segaMD"
            "sms" -> "segaMS"
            "gg" -> "segaGG"
            "n64", "v64", "z64" -> "n64"
            "bin", "cue", "pbp" -> "psx"
            else -> null
        } ?: return emptyList()

        val name = trimmed.substringAfterLast("/").substringBeforeLast(".")
        return listOf(
            newMovieSearchResponse("Play $name", "retro://url/$core/${encodeUrl(trimmed)}", TvType.Others)
        )
    }

    private fun encodeUrl(url: String): String =
        URLEncoder.encode(url, "UTF-8")

    private fun emulatorUrl(dir: File, core: String, romUrl: String, name: String): String {
        val index = RetroVaultLauncher.urlFor(dir, "index.html")
        return "$index?core=$core&rom=${encodeUrl(romUrl)}&name=${encodeUrl(name)}"
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url == "retro://add") {
            promptAddRom()
            return newMovieLoadResponse(
                "Add a ROM by URL",
                url,
                TvType.CustomMedia,
                url,
            ) {
                this.plot = "Paste a direct ROM link in the dialog, pick the system and it " +
                    "will show up in My ROM Library on this device. You can also paste a " +
                    "ROM link straight into search."
            }
        }

        val dir = runCatching { RetroVaultLauncher.unpack(CommonActivity.activity!!) }.getOrNull()
            ?: return null

        val game = games.firstOrNull { "retro://game/${it.id}" == url }
        if (game != null) {
            return newMovieLoadResponse(
                game.title,
                url,
                TvType.CustomMedia,
                url,
            ) {
                this.posterUrl = RetroVaultLauncher.urlFor(dir, game.posterPath)
                this.plot = game.description
                this.tags = game.tags
            }
        }

        if (url.startsWith("retro://custom/")) {
            val index = url.removePrefix("retro://custom/").toIntOrNull() ?: return null
            val entry = customRoms().getOrNull(index) ?: return null
            return newMovieLoadResponse(
                entry[2],
                url,
                TvType.CustomMedia,
                url,
            ) {
                this.posterUrl = RetroVaultLauncher.urlFor(dir, "posters/romvault.png")
                this.plot = "Custom game from your library. System: ${entry[1]}."
            }
        }

        if (url.startsWith("retro://url/")) {
            val rest = url.removePrefix("retro://url/")
            val core = rest.substringBefore("/")
            val romUrl = runCatching {
                java.net.URLDecoder.decode(rest.substringAfter("/"), "UTF-8")
            }.getOrNull() ?: return null
            val name = romUrl.substringAfterLast("/").substringBeforeLast(".").ifBlank { "Game" }
            return newMovieLoadResponse(
                name,
                url,
                TvType.CustomMedia,
                url,
            ) {
                this.plot = "Play $name from an external link on the $core system."
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val activity: Activity = CommonActivity.activity ?: return false

        if (data == "retro://add") return false

        val dir = runCatching { RetroVaultLauncher.unpack(activity) }.getOrNull() ?: return false

        val target: String
        val landscape: Boolean
        var romProvider: (() -> String?)? = null

        val game = games.firstOrNull { "retro://game/${it.id}" == data }
        if (game != null) {
            landscape = game.landscape
            if (game.kind == "html5") {
                target = RetroVaultLauncher.urlFor(dir, game.payloadPath)
            } else {
                val core = when (game.system) {
                    "NES Homebrew" -> "nes"
                    else -> "gb"
                }
                val romFile = File(dir, game.payloadPath)
                target = emulatorUrl(dir, core, "bridge", game.title)
                romProvider = {
                    android.util.Base64.encodeToString(romFile.readBytes(), android.util.Base64.NO_WRAP)
                }
            }
        } else if (data.startsWith("retro://custom/")) {
            val index = data.removePrefix("retro://custom/").toIntOrNull() ?: return false
            val entry = customRoms().getOrNull(index) ?: return false
            val core = emulatorSystems[entry[1]] ?: return false
            landscape = true
            target = emulatorUrl(dir, core, "bridge", entry[2])
            if (!ensureRomCached(entry[0])) {
                CommonActivity.showToast("Could not download the ROM", 0)
                return false
            }
            romProvider = { cachedRomBase64(entry[0]) }
        } else if (data.startsWith("retro://url/")) {
            val rest = data.removePrefix("retro://url/")
            val core = rest.substringBefore("/")
            val romUrl = runCatching {
                java.net.URLDecoder.decode(rest.substringAfter("/"), "UTF-8")
            }.getOrNull() ?: return false
            val name = romUrl.substringAfterLast("/").substringBeforeLast(".").ifBlank { "Game" }
            landscape = true
            target = emulatorUrl(dir, core, "bridge", name)
            if (!ensureRomCached(romUrl)) {
                CommonActivity.showToast("Could not download the ROM", 0)
                return false
            }
            romProvider = { cachedRomBase64(romUrl) }
        } else {
            return false
        }

        val done = CompletableDeferred<Boolean>()
        activity.runOnUiThread {
            try {
                RetroVaultLauncher.launch(activity, target, landscape, {
                    if (!done.isCompleted) done.complete(true)
                }, romProvider)
            } catch (t: Throwable) {
                if (!done.isCompleted) done.complete(false)
            }
        }
        return done.await()
    }

    // remote roms are downloaded through the app http client before the game
    // view opens, so arbitrary hosts work even without CORS headers and the
    // bridge only ever reads a local cached file
    private fun romCacheFile(url: String): File {
        val cacheDir = File(CommonActivity.activity?.cacheDir, "retrovault_roms")
        return File(cacheDir, url.hashCode().toString())
    }

    private suspend fun ensureRomCached(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val cacheFile = romCacheFile(url)
        if (cacheFile.exists() && cacheFile.length() > 0) return true
        val downloaded = runCatching { app.get(url).body.bytes() }.getOrNull() ?: return false
        if (downloaded.isEmpty()) return false
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeBytes(downloaded)
        }
        return true
    }

    private fun cachedRomBase64(url: String): String? {
        val cacheFile = romCacheFile(url)
        if (!cacheFile.exists()) return null
        return runCatching {
            android.util.Base64.encodeToString(cacheFile.readBytes(), android.util.Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun promptAddRom() {
        val activity = CommonActivity.activity ?: return
        activity.runOnUiThread {
            try {
                showAddDialog(activity)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    private fun showAddDialog(activity: Activity) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val romInput = EditText(activity).apply {
            hint = "Direct ROM link (https://.../game.nes)"
            isSingleLine = true
        }
        val nameInput = EditText(activity).apply {
            hint = "Display name (optional)"
            isSingleLine = true
        }
        container.addView(romInput)
        container.addView(nameInput)

        val systems = emulatorSystems.keys.toList()
        var chosenSystem = systems[0]

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Add a ROM by URL")
            .setSingleChoiceItems(systems.toTypedArray(), 0) { _, which -> chosenSystem = systems[which] }
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val romUrl = romInput.text.toString().trim()
                if (!romUrl.startsWith("http")) return@setPositiveButton
                val title = nameInput.text.toString().trim().ifBlank {
                    romUrl.substringAfterLast("/").substringBeforeLast(".").ifBlank { "Custom Game" }
                }
                val existing = (getKey<List<String>>(customKey) ?: emptyList()).toMutableList()
                existing.add("$romUrl|$chosenSystem|$title")
                setKey(customKey, existing)
                CommonActivity.showToast("Added to My ROM Library", 0)
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }
}
