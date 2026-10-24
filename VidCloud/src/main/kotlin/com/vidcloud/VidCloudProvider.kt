package com.vidcloud

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

class VidCloudProvider : MainAPI() {

    override var mainUrl = VidCloudApi.SITE
    override var name = "VidCloud"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Others)

    override val mainPage = listOf(
        MainPageData("All Batches", "all"),
        MainPageData("IIT JEE", "IIT-JEE"),
        MainPageData("NEET", "NEET"),
        MainPageData("UPSC", "UPSC"),
        MainPageData("GATE", "GATE"),
        MainPageData("SSC", "SSC"),
        MainPageData("Banking", "Banking"),
        MainPageData("School Prep", "SCHOOL_PREPARATION"),
        MainPageData("Foundation", "FOUNDATION"),
        MainPageData("Board Exams", "BOARD_EXAM"),
    )

    companion object {
        private const val PAGE_SIZE = 60

        // the site keeps every batch on one hash route, these ids ride the
        // same url shape so watch history and share links keep working
        fun batchUrl(id: String) = "${VidCloudApi.SITE}/#batch/$id"
        fun topicUrl(batchId: String, subjectId: String, topicId: String, title: String = "") =
            "${VidCloudApi.SITE}/#content/batch/$batchId/subject/$subjectId/topic/$topicId" +
                if (title.isBlank()) "" else "?title=" + java.net.URLEncoder.encode(title, "UTF-8")
    }

    // the batch id out of any form that can reach load(): the site's own hash
    // route, the bare path and the bare id - the ids live after the hash on
    // the site, so the hash route is read first
    private fun batchIdOf(input: String): String? {
        var s = input.trim()
        if (s.isBlank()) return null
        if (s.contains('#')) s = s.substringAfterLast('#')
        if (s.startsWith(VidCloudApi.SITE)) s = s.removePrefix(VidCloudApi.SITE)
        s = s.trim('/')
        s = s.removePrefix("batch/").trim('/')
        if (s.contains('/')) s = s.substringBefore('/')
        return s.takeIf { it.length == 24 && it.matches(Regex("[0-9a-f]{24}")) }
    }

    private fun toSearch(b: VidCloudApi.BatchEntry): SearchResponse? {
        if (b.id.isBlank() || b.name.isBlank()) return null
        return newMovieSearchResponse(b.name, batchUrl(b.id), TvType.TvSeries) {
            posterUrl = b.photo.takeIf { it.startsWith("http") }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val all = VidCloudApi.batches()
        val filtered = if (request.data == "all") all else all.filter { it.exam == request.data }
        val from = (page - 1) * PAGE_SIZE
        val slice = if (from >= filtered.size) emptyList()
        else filtered.subList(from, minOf(from + PAGE_SIZE, filtered.size))
        val items = slice.mapNotNull(::toSearch)
        return newHomePageResponse(request.name, items, hasNext = from + PAGE_SIZE < filtered.size)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        return VidCloudApi.batches()
            .filter { it.name.lowercase().contains(q) }
            .take(50)
            .mapNotNull(::toSearch)
    }

    override suspend fun load(url: String): LoadResponse {
        val id = batchIdOf(url)
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Batch not found: $url")
        val details = when (val r = VidCloudApi.details(id)) {
            is VidCloudApi.SiteResult.Fail ->
                throw com.lagradost.cloudstream3.ErrorLoadingException(
                    "Could not reach vidcloud.eu.org (${r.reason})"
                )
            is VidCloudApi.SiteResult.Ok -> r.json.optJSONObject("data")
        } ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Batch not found: $id")

        val title = details.optString("name").takeIf { it.isNotBlank() }
            ?: details.optString("batchName").takeIf { it.isNotBlank() }
            ?: "Batch"
        val photo = details.optJSONObject("previewImage")?.let {
            it.optString("baseUrl").takeIf { b -> b.isNotBlank() }?.let { b -> b + it.optString("key") }
        } ?: ""

        val subjects = details.optJSONArray("subjects") ?: run {
            return newTvSeriesLoadResponse(title, batchUrl(id), TvType.TvSeries, emptyList()) {
                posterUrl = photo.ifBlank { null }
                plot = details.optString("byName").takeIf { it.isNotBlank() }
            }
        }

        // one topics call per subject, throttled the way the other providers
        // here fetch seasons in parallel; a transport that answers once keeps
        // answering, so the first subject warms it for the rest
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val topicFailures = java.util.concurrent.atomic.AtomicInteger(0)
        kotlinx.coroutines.coroutineScope {
            val gate = Semaphore(6)
            val jobs = (0 until subjects.length()).map { s ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val subject = subjects.optJSONObject(s) ?: return@withPermit null
                        val subjectId = subject.optString("_id")
                        if (subjectId.isBlank()) return@withPermit null
                        val subjectTitle = subject.optString("subject")
                        val list = mutableListOf<com.lagradost.cloudstream3.Episode>()
                        when (val r = VidCloudApi.topics(id, subjectId)) {
                            is VidCloudApi.SiteResult.Fail -> {
                                topicFailures.incrementAndGet()
                                null
                            }
                            is VidCloudApi.SiteResult.Ok -> {
                                var n = 0
                                for (topic in VidCloudApi.dataArrayOf(r)) {
                                    val topicId = topic.optString("_id")
                                    val topicName = topic.optString("name")
                                    if (topicId.isBlank() || topicName.isBlank()) continue
                                    n++
                                    list.add(
                                        newEpisode(topicUrl(id, subjectId, topicId, topicName)) {
                                            this.name = topicName
                                            this.season = s + 1
                                            this.episode = n
                                            this.posterUrl = photo.ifBlank { null }
                                            this.description = "$subjectTitle - $topicName"
                                        }
                                    )
                                }
                                list
                            }
                        }
                    }
                }
            }
            jobs.forEach { job ->
                runCatching { episodes.addAll(job.await() ?: return@forEach) }
            }
        }
        if (topicFailures.get() > 0 && episodes.isEmpty() && subjects.length() > 0) {
            throw com.lagradost.cloudstream3.ErrorLoadingException(
                "Could not reach vidcloud.eu.org (topics: ${topicFailures.get()}/${subjects.length()} failed)"
            )
        }

        return newTvSeriesLoadResponse(title, batchUrl(id), TvType.TvSeries, episodes) {
            posterUrl = photo.ifBlank { null }
            plot = details.optString("byName").takeIf { it.isNotBlank() }
            year = details.optString("startDate").takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()
        }
    }

    // the episode data is a topic url; every material the topic carries -
    // lectures, notes, dpp sheets, dpp quizzes - opens from its sheet
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        val s = data.trim()
        val m = Regex("/#content/batch/([0-9a-f]{24})/subject/([0-9a-f]{24})/topic/([0-9a-f]{24})").find(s)
            ?: return false
        val (batchId, subjectId, topicId) = m.destructured
        val subjectName = when (val r = VidCloudApi.details(batchId)) {
            is VidCloudApi.SiteResult.Ok -> r.json.optJSONObject("data")?.optJSONArray("subjects")
                ?.let { subs ->
                    (0 until subs.length())
                        .mapNotNull { subs.optJSONObject(it) }
                        .firstOrNull { it.optString("_id") == subjectId }
                        ?.optString("subject")
                }
            is VidCloudApi.SiteResult.Fail -> null
        } ?: ""
        val topicName = episodesTopicName(s) ?: "Topic"

        val activity = com.lagradost.cloudstream3.CommonActivity.activity
            as? androidx.appcompat.app.AppCompatActivity ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false
        activity.runOnUiThread {
            TopicDialogFragment.show(activity, topicName, subjectName, batchId, subjectId, topicId)
        }
        return true
    }

    private fun episodesTopicName(data: String): String? =
        Regex("topic/[0-9a-f]{24}\\?title=([^&]+)").find(data)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
}
