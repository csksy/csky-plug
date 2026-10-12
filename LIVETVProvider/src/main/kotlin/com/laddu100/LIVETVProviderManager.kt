package com.laddu100

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ProviderData(
    val id: Int,
    val title: String,
    val image: String,
    val catLink: String?,
    val type: String? = null
)

data class ChannelWrapper(
    val channel: String = ""
)

data class ChannelData(
    val name: String? = null,
    val logo: String? = null,
    val visible: Boolean? = null,
    val links: String? = null,
    val link_names: List<String>? = null,
    val is_playlist: Boolean? = null
)

data class LiveEventData(
    val id: Int,
    val title: String,
    val image: String? = null,
    val slug: String,
    val cat: String? = null,
    val eventInfo: LiveEventInfo? = null,
    val publish: Int = 1,
    val formats: List<LiveEventFormat>? = null
)

data class LiveEventInfo(
    val teamA: String? = null,
    val teamB: String? = null,
    val teamAFlag: String? = null,
    val teamBFlag: String? = null,
    val eventCat: String? = null,
    val eventName: String? = null,
    val eventLogo: String? = null,
    val isHot: String? = null,
    val eventType: String? = null,
    val startTime: String? = null,
    val endTime: String? = null
)

data class LiveEventFormat(
    val title: String? = null,
    val webLink: String? = null
)

class ExpiringRemoteValue(
    private val ttlMs: Long = 30 * 60 * 1000L,
    private val fallbackTtlMs: Long = 2 * 60 * 1000L,
    private val minRetryMs: Long = 60 * 1000L
) {
    private val mutex = Mutex()

    @Volatile
    private var value: String? = null

    @Volatile
    private var expiresAt: Long = 0L

    @Volatile
    private var lastAttempt: Long = 0L

    val peek: String? get() = value

    suspend fun get(
        fetch: suspend () -> String?,
        fallback: suspend () -> String?
    ): String? = mutex.withLock {
        val now = System.currentTimeMillis()
        val current = value
        if (current != null && now < expiresAt) return current
        // a failed refresh moments ago should serve stale data until the retry window opens
        if (current != null && now - lastAttempt < minRetryMs) return current

        lastAttempt = now
        val fetched = fetch()
        if (!fetched.isNullOrBlank()) {
            value = fetched
            expiresAt = now + ttlMs
            return fetched
        }
        val backup = fallback()
        if (!backup.isNullOrBlank()) {
            value = backup
            expiresAt = now + fallbackTtlMs
            return backup
        }
        current
    }

    fun invalidate() {
        expiresAt = 0L
    }

    fun reset() {
        expiresAt = 0L
        lastAttempt = 0L
    }
}

// an api host answering with errors gets its cache dropped so the next call re-reads firebase
class StaleDomainInterceptor(private val domain: ExpiringRemoteValue) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val host = chain.request().url.host
        val cached = domain.peek
        val ours = cached?.toHttpUrlOrNull()?.host.equals(host, ignoreCase = true)
        return try {
            val response = chain.proceed(chain.request())
            if (ours && response.code >= 400) {
                domain.invalidate()
            }
            response
        } catch (e: IOException) {
            if (ours) {
                domain.invalidate()
            }
            throw e
        }
    }
}

object LIVETVProviderManager {

    private const val DEFAULT_BASE_URL = "https://cfynew1.top"
    private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    private val baseUrlValue = ExpiringRemoteValue()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(StaleDomainInterceptor(baseUrlValue))
        .build()

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val fallbackProviders: List<Map<String, Any>> = listOf(
        mapOf(
            "id" to 13, "title" to "TATA PLAY",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQz_qYe3Y4S5bXXVlPtXQnqtAkLw1-no57QHhPyMgWE0SQmxujzHxZKiDs&s=10",
            "catLink" to "https://hotstarlive.delta-cloud.workers.dev/?token=240bb9-374e2e-3c13f0-4a7xz5"
        ),
        mapOf(
            "id" to 14, "title" to "HOTSTAR",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRWwYjMvB58DMLsL9Ii2fhvw6NBYvD1iVCjOMU8TXBLJt0eibLGOjoRkLJP&s=10",
            "catLink" to "https://hotstar-live-event.alpha-circuit.workers.dev/?token=a13d9c-4b782a-6c90fd-9a1b84"
        ),
        mapOf(
            "id" to 15, "title" to "TATAPLAY BD",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQz_qYe3Y4S5bXXVlPtXQnqtAkLw1-no57QHhPyMgWE0SQmxujzHxZKiDs&s=10",
            "catLink" to "https://ranapk.short.gy/BDIX/tata.php"
        ),
        mapOf(
            "id" to 17, "title" to "T SPORTS",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRJ0QvfKyjAqcCOumIXjcuYg505GnaBeVk2lQ&usqp=CAU",
            "catLink" to "https://fifabangladesh2-xyz-ekkj.spidy.online/AYN/tsports.m3u"
        ),
        mapOf(
            "id" to 18, "title" to "FANCODE IND",
            "image" to "https://play-lh.googleusercontent.com/lp1Tdhp75MQyrHqrsyRBV74HxoL3Ko8KRAjOUI1wUHREAxuuVwKR6vnamgvMEn4C4Q",
            "catLink" to "https://raw.githubusercontent.com/Jitendra-unatti/fancode/refs/heads/main/data/fancode.m3u"
        ),
        mapOf(
            "id" to 19, "title" to "SONYLIV",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTzscCrHEfnHNeZdMO3haF1XSVgjskN4TNv0g&usqp=CAU",
            "catLink" to "https://raw.githubusercontent.com/doctor-8trange/zyphora/refs/heads/main/data/sony.m3u"
        ),
        mapOf(
            "id" to 22, "title" to "JIO IND",
            "image" to "https://uxwing.com/wp-content/themes/uxwing/download/brands-and-social-media/jio-logo-icon.png",
            "catLink" to "https://jiotv.byte-vault.workers.dev/?token=42e4f5-2d873b-3c37d8-7f3f50"
        ),
        mapOf(
            "id" to 29, "title" to "SONY IN",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRxsCm4WKugE7ubLr2J3AP7s-hqHl0dh69ImA&usqp=CAU",
            "catLink" to "https://sonyliv.logic-lane.workers.dev?token=a14d9c-4b782a-6c90fd-9a1b84"
        ),
        mapOf(
            "id" to 31, "title" to "SONY IN 2",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRxsCm4WKugE7ubLr2J3AP7s-hqHl0dh69ImA&usqp=CAU",
            "catLink" to "https://raw.githubusercontent.com/ramnarayan01/data/refs/heads/main/s0nyind.m3u.html"
        ),
        mapOf(
            "id" to 48, "title" to "SUN DIRECT",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSwc4OuqPmOP-Fi9dhfiDw_q-s3rOmgCPla_IaE76VD2KRQ7c4KHeI2zJY&s=10",
            "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/suntv.m3u"
        ),
        mapOf(
            "id" to 70, "title" to "VOOT BD",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQfS6QZFts2FoedMGZE28H7Kh158PsrNIiabFBVJMy_jXa8Tvvb9WAlut8&s=10",
            "catLink" to "https://ranapk.short.gy/VOOTBD.m3u"
        ),
        mapOf(
            "id" to 71, "title" to "VOOT IND",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQfS6QZFts2FoedMGZE28H7Kh158PsrNIiabFBVJMy_jXa8Tvvb9WAlut8&s=10",
            "catLink" to "https://jiocinema-live.cloud-hatchh.workers.dev/?token=42e4f5-2d414b-3c37d8-5f3f45"
        ),
        mapOf(
            "id" to 85, "title" to "SUN NXT",
            "image" to "https://upload.wikimedia.org/wikipedia/en/d/d5/Sun_NXT_logo_small.png",
            "catLink" to "https://raw.githubusercontent.com/alexandermail371/cricfytv/refs/heads/main/sunxt.m3u"
        ),
        mapOf(
            "id" to 90, "title" to "AIRTEL IND",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQf7pkggfHJKj2R8O6ttuHxgv-vQVL03xUeAg&usqp=CAU",
            "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/artl.m3u"
        ),
        mapOf(
            "id" to 92, "title" to "DISTRO TV",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRYQjBTT5SL_kuJF7CbQtoSEA7PzyiH9RYIuDO9F1sx87CtiULDyiDf7ybt&s=10",
            "catLink" to "https://playlist-storage.pages.dev/PLAYLIST/DistroTV.m3u"
        ),
        mapOf(
            "id" to 104, "title" to "ZEE5",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQS0OT2NFe9Jb4ofg_DrXx42EKLgyGnSGwoLg&usqp=CAU",
            "catLink" to "https://zee5.cloud-hatchh.workers.dev/?token=42e4f5-2d413b-3c37d8-7f3f35"
        ),
        mapOf(
            "id" to 106, "title" to "JIOTV+",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRh5KeAyYdOyxaCWDPbiUsJW7Oy4v_7uFqf06rIwGxaWc6nQuNVqZ2Q_Qej&s=10",
            "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/jtv.m3u"
        ),
        mapOf(
            "id" to 110, "title" to "JIOLIVE IND",
            "image" to "https://lens-storage.storage.googleapis.com/png/bb364a303da24e5db23f01bac26949cf",
            "catLink" to "https://raw.githubusercontent.com/alex8875/jc_live/refs/heads/main/jevents_live.m3u"
        ),
        mapOf(
            "id" to 111, "title" to "ISLAMIC TV",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTcTOpdBeIBA52NTSANHC6Ow0v-k6hAr76vWg&usqp=CAU",
            "catLink" to "null"
        ),
        mapOf(
            "id" to 114, "title" to "TAPMAD PK",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcT4X-7suwtvYWwoa6m0ngFTKZt5Hg5Z2kQF1g&usqp=CAU",
            "catLink" to "https://tv.noobon.top/playlist/tapmad.php"
        ),
        mapOf(
            "id" to 117, "title" to "ZEE5 IN",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQS0OT2NFe9Jb4ofg_DrXx42EKLgyGnSGwoLg&usqp=CAU",
            "catLink" to "https://ranapk.short.gy/Z5in.php"
        ),
        mapOf(
            "id" to 126, "title" to "WORLD TV",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSg8OiEwOT5UL5UttBZ5Tnhgsod8i2EQlfB97FFchdBOo8e_PfxvR8RJ68&s=10",
            "catLink" to "https://ranapk.short.gy/WorldTV11.m3u"
        ),
        mapOf(
            "id" to 129, "title" to "AYNA",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQvAant65yQ_au1e51MFs-uiE6juswXv4ZJoNCpzBBSg4q7DzJ1NeliS80c&s=10",
            "catLink" to "https://playlist-cricfy.noobon.top/aynaott.php"
        ),
        mapOf(
            "id" to 130, "title" to "JIO CINEMA IND",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQc3qZ1WgzPyFRX4cWIBJF0MSjWW3gZcLFycg&usqp=CAU",
            "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/jcinema.m3u"
        ),
        mapOf(
            "id" to 131, "title" to "DISH TV",
            "image" to "https://m.media-amazon.com/images/S/stores-image-uploads-eu-prod/1/AmazonStores/A21TJRUUN4KGV/d5086253b614724be106c06be13f7d54.w600.h600._RO299,1,0,0,0,0,0,0,0,0,15_FMpng_.jpg",
            "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/dishtv.m3u"
        ),
        mapOf(
            "id" to 132, "title" to "SHOOQ PK",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSvSWLnpgyvbzV9rHkREzbsX1Rzh2IbEZBL8yPpSv8aCPmy1nVcv7BhIWQ&s=10",
            "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/shoq.m3u"
        ),
        mapOf(
            "id" to 133, "title" to "SAMSUNG TV",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQI9T5vcm8wU-dLuaK5vBfoHpz8KL9Ru0aU1eoVaKNcqauxGtRTfvI1rGTA&s=10",
            "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/samsungtv.m3u"
        ),
        mapOf(
            "id" to 134, "title" to "JAGOBD",
            "image" to "https://www.jagobd.com/wp-content/uploads/2015/10/web_hi_res_512.png",
            "catLink" to "https://tv.noobon.top/playlist/jagobd.php"
        ),
        mapOf(
            "id" to 135, "title" to "JADOO",
            "image" to "https://bdix.net/wp-content/uploads/2019/07/Jadoo-Digital-Logo-PNG-1002x1024.png",
            "catLink" to "https://fifabangladesh.site/PLAYLIST/jadoo.php"
        ),
        mapOf(
            "id" to 136, "title" to "PISHOW",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcS1hOo397X8uamgdXoknED8klICRLPCqwuEUtB394H2cc7YIyYiD78s-B8&s=10",
            "catLink" to "http://playlist-storage.pages.dev/PLAYLIST/playboxtv.m3u"
        ),
        mapOf(
            "id" to 146, "title" to "CRICHD",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQ08h1gOe7MPxwehZBrbYKAUtjv22B6rAJ1kMkN-cea64Ka49KUyGU2lpTz&s=10",
            "catLink" to "https://github.com/abusaeeidx/CricHd-playlists-Auto-Update-permanent/raw/main/ALL.m3u"
        ),
        mapOf(
            "id" to 150, "title" to "ZAP SPORTS",
            "image" to "https://i.ibb.co/dJfysm3V/zap-Sports.png",
            "catLink" to "https://tv.noobon.top/zapx/api.php?action=getIPTVPlaylist"
        ),
        mapOf(
            "id" to 151, "title" to "Pirates TV",
            "image" to "https://raw.githubusercontent.com/FunctionError/Logos/main/Pirates-Tv.png",
            "catLink" to "https://raw.githubusercontent.com/FunctionError/PiratesTv/refs/heads/main/combined_playlist.m3u"
        ),
        mapOf(
            "id" to 152, "title" to "YUPPTV",
            "image" to "https://d229kpbsb5jevy.cloudfront.net/bott/v2/networks/circularimages/yupptv.png",
            "catLink" to "https://tv.noobon.top/playlist/yapp.php"
        ),
        mapOf(
            "id" to 153, "title" to "DANGAL TV",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTMnDuHwjd3WEFHmObqo53bAjkIB6E7JvIIPjGGGxqZzbNsKT85D_sRYNc&s=10",
            "catLink" to "https://playlist-storage.pages.dev/PLAYLIST/DangalPlay.m3u"
        ),
        mapOf(
            "id" to 157, "title" to "Movies & Series",
            "image" to "https://i.postimg.cc/QCVVj6D1/Movies.png",
            "catLink" to "http://tv.noobon.top/playlist/movies.php"
        ),
        mapOf(
            "id" to 158, "title" to "DEKHO 24 X 7",
            "image" to "https://tstatic.videoready.tv/cms-ui/images/custom-content/1739684250358.png",
            "catLink" to "https://dehkho24h.alpha-circuit.workers.dev/?token=1b8j9b-796c8a-36e17f-8f83a5"
        ),
        mapOf(
            "id" to 159, "title" to "JIOTV+ S2",
            "image" to "https://i.ibb.co/VY9ND7rY/image.png",
            "catLink" to "https://jiotvplus.byte-vault.workers.dev/?token=42e4f5-2d863b-3c38d8-7f3f51"
        ),
        mapOf(
            "id" to 163, "title" to "JIOHOTSTAR",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSPuz9ekmjh3vEpEc3lYL4nh6Gj7y2CQTswVG-ZCHnIS1foScuwPzuyxic&s=10",
            "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/jstar.m3u"
        ),
        mapOf(
            "id" to 164, "title" to "JIOTV+ S3",
            "image" to "https://i.ibb.co/VY9ND7rY/image.png",
            "catLink" to "https://jiotv.edge-nexus.workers.dev/?token=42e4f5-2d863b-3c37d8-8f3f51"
        ),
        mapOf(
            "id" to 165, "title" to "ICC TV",
            "image" to "https://m.media-amazon.com/images/I/31F7ropt9OL.png",
            "catLink" to "https://icc.alpha-circuit.workers.dev/?token=42e4f5-2d863b-3c37d8-7f3f69"
        ),
        mapOf(
            "id" to 166, "title" to "Pluto Tv",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRI_jYjppElN7Tb6Ok3bL_J0K7QQPzfQbzPeAWzVilH9y7CYKzAy-XJbi4&s=10",
            "catLink" to "https://tv.noobon.top/playlist/plutotv.php"
        ),
        mapOf(
            "id" to 167, "title" to "Movies",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSZsNCiIfBGnyhPuE6n-t37ma_baSTkX_trJ45qtXvwLhxsdVWzNW0dt8u7&s=10",
            "catLink" to "ok"
        ),
        mapOf(
            "id" to 168, "title" to "FANCODE BD",
            "image" to "https://play-lh.googleusercontent.com/lp1Tdhp75MQyrHqrsyRBV74HxoL3Ko8KRAjOUI1wUHREAxuuVwKR6vnamgvMEn4C4Q",
            "catLink" to "https://ranapk.short.gy/FCBD/playlist.php"
        ),
        mapOf(
            "id" to 169, "title" to "EPL BD IP",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQye0cNsXab_lPM3Zv2pklCk2ZT92X3micxy7jF2n5F46-kaSgnIBjHq3KY&s=10",
            "catLink" to "https://bdix.short.gy/EPLxBDIX/playlist.php"
        ),
        mapOf(
            "id" to 170, "title" to "JIO BD",
            "image" to "https://uxwing.com/wp-content/themes/uxwing/download/brands-and-social-media/jio-logo-icon.png",
            "catLink" to "https://ranapk.short.gy/JIOBD.m3u"
        ),
        mapOf(
            "id" to 173, "title" to "World Sports",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcT7iSlqAmYv4wa-7P9aRqiLVniqbUQUtVNmgsf4BxJJqpRKNJVhlVHvFKI&s=10",
            "catLink" to "https://tv.xmasterbd.sbs/dhd/playlist.php"
        ),
        mapOf(
            "id" to 174, "title" to "Prime Channel",
            "image" to "https://static.vecteezy.com/system/resources/previews/046/437/251/non_2x/amazon-prime-logo-free-png.png",
            "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/amzusa.m3u"
        ),
        mapOf(
            "id" to 175, "title" to "RUN TV",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQ7zqXTonSH_Xo--YxMlOacinf7mhLwuwSFFF1KJa8lGw&s=10",
            "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/runn.m3u"
        ),
        mapOf(
            "id" to 176, "title" to "WAVES OTT",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSNyx_lxD3xXIB8jpFGnMnHZIziUo1vKW9sSS-7zP-h0vhZT4cPB6wly6o&s=10",
            "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/waves.m3u"
        ),
        mapOf(
            "id" to 177, "title" to "JIO IND2",
            "image" to "https://uxwing.com/wp-content/themes/uxwing/download/brands-and-social-media/jio-logo-icon.png",
            "catLink" to "https://playlist-cricfy.noobon.top/noob/jiotv.php"
        ),
        mapOf(
            "id" to 178, "title" to "JIOTV+ S4",
            "image" to "https://i.ibb.co/VY9ND7rY/image.png",
            "catLink" to "https://jiotvplus.iron-shield.workers.dev/?token=42e4f5-2d963b-3c37d8-7f3f52"
        ),
        mapOf(
            "id" to 179, "title" to "LGTV IND",
            "image" to "https://raw.githubusercontent.com/alex8875/img/refs/heads/main/LG_tv.png",
            "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/lgtv.m3u"
        ),
        mapOf(
            "id" to 180, "title" to "TOFFEE BD",
            "image" to "https://yt3.googleusercontent.com/q0CJuxOL2f7Duy3hiM3uKArC_Zvji24XVGNfcf2TMqXMxTW6RO3R3tqwH2XAxbcz5TG-d2p6=s900-c-k-c0x00ffffff-no-rj",
            "catLink" to "https://playlist-cricfy.noobon.top/toffee.php"
        ),
        mapOf(
            "id" to 181, "title" to "ZEE5 IN 2",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQS0OT2NFe9Jb4ofg_DrXx42EKLgyGnSGwoLg&usqp=CAU",
            "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/z5.m3u"
        ),
        mapOf(
            "id" to 182, "title" to "SONY BD",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRxsCm4WKugE7ubLr2J3AP7s-hqHl0dh69ImA&usqp=CAU",
            "catLink" to "https://ranapk.short.gy/sonybd/playlist.php"
        ),
        mapOf(
            "id" to 183, "title" to "AYNA 2",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQvAant65yQ_au1e51MFs-uiE6juswXv4ZJoNCpzBBSg4q7DzJ1NeliS80c&s=10",
            "catLink" to "https://bdix2.short.gy/AYNA/playlist.php"
        ),
        mapOf(
            "id" to 184, "title" to "DARK TV",
            "image" to "https://i.ibb.co/CsQCkNnb/Screenshot-2025-11-04-06-03-45-53.jpg",
            "catLink" to "https://ranapk.short.gy/Darktv/playlist.php"
        ),
        mapOf(
            "id" to 185, "title" to "AKASH",
            "image" to "https://image.winudf.com/v2/image1/Y29tLmFrYXNoLmdvX2ljb25fMTcyMjU3ODg2N18wNTA/icon.png?w=184&fakeurl=1",
            "catLink" to "https://playlist-cricfy.noobon.top/akashgo.php"
        ),
        mapOf(
            "id" to 186, "title" to "FREE TV",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTS4HoWswvKYjnMyenamwz-xBJq0PLSyZYpo0kp3oN6gw&s=10",
            "catLink" to "https://playlist-storage.pages.dev/PLAYLIST/freetv.m3u"
        ),
        mapOf(
            "id" to 187, "title" to "FANCODE BD 2",
            "image" to "https://play-lh.googleusercontent.com/lp1Tdhp75MQyrHqrsyRBV74HxoL3Ko8KRAjOUI1wUHREAxuuVwKR6vnamgvMEn4C4Q",
            "catLink" to "https://ranapk.short.gy/FcOnlyBD/playlist.php"
        ),
        mapOf(
            "id" to 188, "title" to "DISH HOME BD",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQfc5DoDgZ5r5s1_umhMp0UUBENdkOUdWWFcSAzv-EUxA&s",
            "catLink" to "https://dish.data-vortex.workers.dev?token=42e4f5-2d863b-3c37d8-7f3f51"
        ),
        mapOf(
            "id" to 189, "title" to "ZEE5 LIVE",
            "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQS0OT2NFe9Jb4ofg_DrXx42EKLgyGnSGwoLg&usqp=CAU",
            "catLink" to "https://raw.githubusercontent.com/doctor-8trange/quarnex/refs/heads/main/data/zee5.m3u"
        ),
        mapOf(
            "id" to 190, "title" to "FREE SPORTS",
            "image" to "https://media.unreel.me/prod/freelivesports/general/6496be67-a318-46c6-a25d-93c161f86845",
            "catLink" to "https://playlist-storage.pages.dev/PLAYLIST/freelivesports.m3u"
        )
    )

    fun refreshFromFirebase() {
        baseUrlValue.reset()
    }

    // playlist content goes to the m3u parser, the rest expects the encrypted channel list
    fun isPlaylistLink(catLink: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(catLink)
                .header("User-Agent", BROWSER_UA)
                .build()
            val response = probeClient.newCall(request).execute()
            if (!response.isSuccessful) return false
            val body = response.body.string().trim()
            body.startsWith("#EXTM3U") || body.startsWith("#EXTINF")
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getBaseUrl(): String? {
        return baseUrlValue.get(
            fetch = { LIVETVFirebaseFetcher.getProviderApiUrl()?.trimEnd('/') },
            fallback = { DEFAULT_BASE_URL }
        )
    }

    private suspend fun fetchDecrypted(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body.string()
                if (body.isNotBlank()) LIVETVCryptoUtils.decryptLIVETV(body.trim()) else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchProviders(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getBaseUrl() ?: return@withContext fallbackProviders
            val decrypted = fetchDecrypted("$baseUrl/cats.txt")
            if (!decrypted.isNullOrBlank()) {
                val providers = parseJson<List<ProviderData>>(decrypted)
                return@withContext providers
                    .filter { !it.catLink.isNullOrBlank() }
                    .map { provider ->
                        mapOf(
                            "id" to provider.id,
                            "title" to provider.title,
                            "image" to provider.image,
                            "catLink" to (provider.catLink ?: ""),
                            "type" to (provider.type ?: "custom")
                        )
                    }
            }
        } catch (_: Exception) {}
        fallbackProviders
    }

    suspend fun fetchLiveEvents(): List<LiveEventData> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getBaseUrl() ?: return@withContext emptyList()
            val decrypted = fetchDecrypted("$baseUrl/categories/live-events.txt")
            if (!decrypted.isNullOrBlank()) {
                val events = parseJson<List<LiveEventData>>(decrypted)
                return@withContext events.filter { it.publish == 1 }
            }
        } catch (_: Exception) {}
        emptyList()
    }

    suspend fun fetchCustomEvents(catLink: String): List<LiveEventData> {
        return withContext(Dispatchers.IO) {
            try {
                val decrypted = fetchDecrypted(catLink)
                if (!decrypted.isNullOrBlank()) {
                    val wrappers = parseJson<List<ChannelWrapper>>(decrypted)
                    val events = wrappers.mapIndexedNotNull { index, wrapper ->
                        try {
                            val channelData = parseJson<ChannelData>(wrapper.channel)
                            if (channelData.visible == false) return@mapIndexedNotNull null
                            val links = channelData.links
                            if (links.isNullOrBlank()) return@mapIndexedNotNull null
                            LiveEventData(
                                id = index + 1,
                                title = channelData.name ?: "Unknown Channel",
                                image = channelData.logo,
                                slug = links.substringBeforeLast("."),
                                cat = "Custom",
                                eventInfo = LiveEventInfo(
                                    eventName = channelData.name,
                                    eventLogo = channelData.logo,
                                    isHot = "0"
                                ),
                                publish = 1,
                                formats = links.split(", ").mapIndexed { i, link ->
                                    LiveEventFormat(
                                        title = channelData.link_names?.getOrNull(i) ?: "Link ${i + 1}",
                                        webLink = link
                                    )
                                }
                            )
                        } catch (_: Exception) {
                            null
                        }
                    }
                    return@withContext events.filter { it.publish == 1 }
                }
            } catch (_: Exception) {}
            emptyList()
        }
    }
}
