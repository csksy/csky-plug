package com.laddu100.raghavanime

import android.app.ActivityManager
import android.content.Context
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Performance infrastructure shared by the RaghavAnime aggregators.
 *
 * The old build started every source at the same time (runAllAsync), which on
 * low end devices meant a dozen concurrent scrapers, several live WebViews,
 * m3u8 expansions and JS/WASM unpackers all fighting for 4 weak cores - the
 * whole app UI stuttered while links loaded. StreamPlay style bounded
 * concurrency keeps every source loading (nothing is ever skipped) while the
 * device stays responsive.
 */
object RaghavPerf {

    private const val TAG = "RaghavPerf"

    enum class DeviceProfile {
        LOW_END, MID_RANGE, HIGH_END
    }

    @Volatile
    private var cachedProfile: DeviceProfile? = null

    /** RAM + core count based device class, detected once per app session. */
    fun profile(): DeviceProfile {
        cachedProfile?.let { return it }
        val cores = Runtime.getRuntime().availableProcessors()
        var ramMb = 0L
        try {
            val ctx = CommonActivity.activity
            val am = ctx?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                ramMb = info.totalMem / (1024L * 1024L)
            }
        } catch (e: Exception) {
            Log.d(TAG, "profile detection fell back to cores only: ${e.message}")
        }
        val p = when {
            (ramMb > 0 && ramMb < 2048) || cores < 4 -> DeviceProfile.LOW_END
            (ramMb > 0 && ramMb < 4096) || cores < 6 -> DeviceProfile.MID_RANGE
            else -> DeviceProfile.HIGH_END
        }
        cachedProfile = p
        Log.d(TAG, "device profile: $p (cores=$cores, ramMb=$ramMb)")
        return p
    }

    /**
     * How many sources may resolve at the same time while loading links.
     * RaghavAnime sources are far heavier than plain HTTP scrapers
     * (WebViews, packed JS, WASM), so the caps stay well below the number
     * of sources: everything still runs, just in queued waves.
     */
    fun sourceConcurrency(): Int = when (profile()) {
        DeviceProfile.LOW_END -> 5
        DeviceProfile.MID_RANGE -> 8
        DeviceProfile.HIGH_END -> 12
    }

    /** Background prefetch is user invisible, so it gets an even smaller slice. */
    fun prefetchConcurrency(): Int = when (profile()) {
        DeviceProfile.LOW_END -> 2
        DeviceProfile.MID_RANGE -> 3
        DeviceProfile.HIGH_END -> 4
    }

    /**
     * Run every task, at most [concurrency] at a time. A failing task never
     * cancels its siblings. This is the drop in replacement for the old
     * unbounded runAllAsync fan out.
     */
    suspend fun runLimitedAsync(concurrency: Int, tasks: List<suspend () -> Unit>) {
        if (tasks.isEmpty()) return
        if (tasks.size == 1) {
            tasks.first().invoke()
            return
        }
        val gate = Semaphore(concurrency.coerceAtLeast(1))
        coroutineScope {
            tasks.map { task ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        try {
                            task()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // keep one bad source from cancelling the wave
                            Log.e(TAG, "task failed: ${e.message}")
                        }
                    }
                }
            }.awaitAll()
        }
    }

    // WebViews are the single heaviest resource this plugin touches (renderer
    // process + JS engine + ~100MB each). Gate every WebView session behind a
    // global semaphore so at most two are alive at once, even when many
    // sources resolve in parallel.
    private val webViewGate = Semaphore(2)

    suspend fun <T> withWebView(block: suspend () -> T): T {
        webViewGate.acquire()
        try {
            return block()
        } finally {
            webViewGate.release()
        }
    }
}

/**
 * Rolling per-source statistics used only for ordering: fast and reliable
 * sources start first so usable links appear quickly, while flaky ones are
 * merely queued later - never skipped.
 */
object RaghavSourceStats {

    private const val NEUTRAL = 0.5
    private const val MAX_RUNS = 20

    private class Stat {
        var success = 0
        var fail = 0
        var totalMs = 0L
    }

    private val stats = ConcurrentHashMap<String, Stat>()
    private val lock = Any()

    fun record(source: String, success: Boolean, durationMs: Long) {
        synchronized(lock) {
            val s = stats.getOrPut(source) { Stat() }
            // halve history once it grows so recent behaviour dominates
            if (s.success + s.fail >= MAX_RUNS) {
                s.success = (s.success + 1) / 2
                s.fail = (s.fail + 1) / 2
                s.totalMs /= 2
            }
            if (success) s.success++ else s.fail++
            s.totalMs += durationMs
        }
    }

    /** Higher score = start earlier. Unknown sources keep a neutral score. */
    fun priority(source: String): Double {
        val s = stats[source] ?: return NEUTRAL
        val runs = s.success + s.fail
        if (runs <= 0) return NEUTRAL
        val successRate = s.success.toDouble() / runs
        val avgMs = s.totalMs / runs
        val speed = when {
            avgMs <= 3000 -> 1.0
            avgMs <= 8000 -> 0.75
            avgMs <= 15000 -> 0.5
            else -> 0.25
        }
        return successRate * 0.75 + speed * 0.25
    }
}
