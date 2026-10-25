package com.vidcloud

import com.lagradost.cloudstream3.Prerelease
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.CLEARKEY_DRM_UUID
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
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

    // one topic of one subject: the season all the topic's lectures and its
    // material row live under, named the way the site names the topic
    private class TopicRow(
        val subjectId: String,
        val subjectName: String,
        val topicId: String,
        val topicName: String,
        val expectsVideos: Boolean,
        val order: Int,
    )

    private class TaggedLecture(val lecture: VidCloudApi.Lecture, val dpp: Boolean)

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
        val subjectsJson = details.optJSONArray("subjects")

        if (subjectsJson == null || subjectsJson.length() == 0) {
            return newTvSeriesLoadResponse(title, batchUrl(id), TvType.TvSeries, emptyList()) {
                posterUrl = photo.ifBlank { null }
                plot = details.optString("byName").takeIf { it.isNotBlank() }
            }
        }

        val subjects = (0 until subjectsJson.length())
            .mapNotNull { subjectsJson.optJSONObject(it) }
            .filter { it.optString("_id").isNotBlank() }
            .sortedBy { it.optInt("displayOrder", 0) }

        // the site's own order: subjects first, then each subject's topics
        // in their display order, topics with nothing under them never get
        // a season
        val rows = mutableListOf<TopicRow>()
        val topicsFailures = java.util.concurrent.atomic.AtomicInteger(0)
        coroutineScope {
            val gate = Semaphore(6)
            val jobs = subjects.map { subject ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val subjectId = subject.optString("_id")
                        val subjectName = subject.optString("subject")
                        when (val r = VidCloudApi.topics(id, subjectId)) {
                            is VidCloudApi.SiteResult.Fail -> {
                                topicsFailures.incrementAndGet()
                                emptyList()
                            }
                            is VidCloudApi.SiteResult.Ok -> {
                                val listed = VidCloudApi.dataArrayOf(r).mapNotNull { t ->
                                    val topicId = t.optString("_id")
                                    val topicName = t.optString("name")
                                    if (topicId.isBlank() || topicName.isBlank()) return@mapNotNull null
                                    val videos = t.optInt("videos", 0) + t.optInt("lectureVideos", 0)
                                    val papers = t.optInt("notes", 0) + t.optInt("exercises", 0)
                                    if (videos <= 0 && papers <= 0) return@mapNotNull null
                                    TopicRow(
                                        subjectId, subjectName, topicId, topicName,
                                        videos > 0, t.optInt("displayOrder", 0),
                                    )
                                }
                                listed.sortedBy { it.order }
                            }
                        }
                    }
                }
            }
            jobs.forEach { job ->
                runCatching { rows.addAll(job.await()) }
            }
        }
        if (rows.isEmpty()) {
            if (topicsFailures.get() > 0 && subjects.isNotEmpty()) {
                throw com.lagradost.cloudstream3.ErrorLoadingException(
                    "Could not reach vidcloud.eu.org (topics: ${topicsFailures.get()}/${subjects.size} failed)"
                )
            }
            return newTvSeriesLoadResponse(title, batchUrl(id), TvType.TvSeries, emptyList()) {
                posterUrl = photo.ifBlank { null }
                plot = details.optString("byName").takeIf { it.isNotBlank() }
            }
        }

        // every lecture the batch carries, grouped under its own topic tag.
        // the whole subject walk answers the same items the per topic calls
        // do, so it is tried first and only the topics it leaves empty are
        // asked one by one afterwards
        val lecturesByTopic = java.util.concurrent.ConcurrentHashMap<String, MutableList<TaggedLecture>>()
        coroutineScope {
            val gate = Semaphore(4)
            val jobs = subjects.map { subject ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val subjectId = subject.optString("_id")
                        val known = rows.filter { it.subjectId == subjectId }
                            .map { it.topicId }.toSet()
                        if (known.isEmpty()) return@withPermit
                        for ((type, dpp) in listOf("videos" to false, "DppVideos" to true)) {
                            var page = 1
                            while (page <= 40) {
                                val items = when (val r = VidCloudApi.subjectContents(subjectId, type, page)) {
                                    is VidCloudApi.SiteResult.Fail -> break
                                    is VidCloudApi.SiteResult.Ok -> VidCloudApi.dataArrayOf(r)
                                }
                                if (items.isEmpty()) break
                                for (item in items) {
                                    val topicId = VidCloudApi.tagOf(item)?.first ?: continue
                                    if (topicId !in known) continue
                                    val lecture = VidCloudApi.toLecture(item) ?: continue
                                    lecturesByTopic.getOrPut(topicId) { mutableListOf() }
                                        .add(TaggedLecture(lecture, dpp))
                                }
                                if (items.size < 20) break
                                page++
                            }
                        }
                    }
                }
            }
            jobs.forEach { job -> runCatching { job.await() } }
        }

        // topics the subject walk never answered are asked one by one, the
        // exact call the site's own topic page makes, under one budget so a
        // slow batch still opens with whatever already arrived
        val missing = rows.filter { it.expectsVideos && lecturesByTopic[it.topicId].isNullOrEmpty() }
        if (missing.isNotEmpty()) {
            withTimeoutOrNull(25_000L) {
                coroutineScope {
                    val gate = Semaphore(10)
                    val jobs = missing.map { row ->
                        async(Dispatchers.IO) {
                            gate.withPermit {
                                runCatching {
                                    when (val r = VidCloudApi.contents(row.subjectId, row.topicId, "videos")) {
                                        is VidCloudApi.SiteResult.Ok ->
                                            VidCloudApi.dataArrayOf(r).forEach { item ->
                                                VidCloudApi.toLecture(item)?.let {
                                                    lecturesByTopic.getOrPut(row.topicId) { mutableListOf() }
                                                        .add(TaggedLecture(it, false))
                                                }
                                            }
                                        else -> Unit
                                    }
                                }
                            }
                        }
                    }
                    jobs.forEach { job -> runCatching { job.await() } }
                }
            }
        }

        // seasons are the topics themselves, named the way the site names
        // them, in the site's own subject order; the lectures under each are
        // the episodes and the material row sits right after the last one
        val seasonList = mutableListOf<SeasonData>()
        val episodes = mutableListOf<Episode>()
        var season = 0
        for (row in rows) {
            val lectures = (lecturesByTopic[row.topicId] ?: emptyList())
                .sortedWith(compareBy({ it.dpp }, { it.lecture.startedAt }))
            season++
            seasonList.add(SeasonData(season, row.topicName, season))
            var n = 0
            for (tagged in lectures) {
                n++
                val lecture = tagged.lecture
                episodes.add(
                    newEpisode(
                        JSONObject()
                            .put("k", "v")
                            .put("b", id)
                            .put("s", row.subjectId)
                            .put("t", row.topicId)
                            .put("sn", row.subjectName)
                            .put("tn", row.topicName)
                            .put("vid", lecture.videoId)
                            .put("tid", lecture.typeId)
                            .put("vu", lecture.url)
                            .put("n", lecture.name)
                            .put("img", lecture.image)
                            .toString()
                    ) {
                        this.name = if (tagged.dpp) "DPP - ${lecture.name}" else lecture.name
                        this.season = season
                        this.episode = n
                        this.posterUrl = lecture.image.ifBlank { photo }.ifBlank { null }
                        this.description =
                            listOf(row.subjectName, row.topicName).filter { it.isNotBlank() }
                                .joinToString(" - ")
                        this.runTime = VidCloudApi.minutesOf(lecture.duration)
                        if (lecture.startedAt > 0L) this.date = lecture.startedAt
                    }
                )
            }
            episodes.add(
                newEpisode(
                    JSONObject()
                        .put("k", "m")
                        .put("b", id)
                        .put("s", row.subjectId)
                        .put("t", row.topicId)
                        .put("sn", row.subjectName)
                        .put("tn", row.topicName)
                        .toString()
                ) {
                    this.name = "Notes & DPP Quiz"
                    this.season = season
                    this.episode = n + 1
                    this.posterUrl = photo.ifBlank { null }
                    this.description = "Class notes, DPP sheets and the DPP quiz for ${row.topicName}"
                }
            )
        }

        return newTvSeriesLoadResponse(title, batchUrl(id), TvType.TvSeries, episodes) {
            posterUrl = photo.ifBlank { null }
            plot = details.optString("byName").takeIf { it.isNotBlank() }
            year = details.optString("startDate").takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()
            seasonNames = seasonList
        }
    }

    // a lecture row hands its stream to the cloudstream player itself: the
    // player page is walked back to the playlist url and the drm fields it
    // ships, and the playlist rides the native player - a clearkey lecture
    // rides the player's own drm link with its kid and key. the material row
    // opens the sheet that carries the topic's notes, sheets and quiz
    @OptIn(Prerelease::class)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        val j = try {
            JSONObject(data.trim())
        } catch (_: Exception) {
            return false
        }
        val batchId = j.optString("b")
        val subjectId = j.optString("s")
        val topicId = j.optString("t")
        if (batchId.isBlank() || subjectId.isBlank() || topicId.isBlank()) return false
        val subjectName = j.optString("sn")
        val topicName = j.optString("tn").ifBlank { "Topic" }

        when (j.optString("k")) {
            "m" -> {
                val activity = com.lagradost.cloudstream3.CommonActivity.activity
                    as? androidx.appcompat.app.AppCompatActivity ?: return false
                if (activity.isFinishing || activity.isDestroyed) return false
                activity.runOnUiThread {
                    TopicDialogFragment.show(activity, topicName, subjectName, batchId, subjectId, topicId)
                }
                return true
            }

            "v" -> {
                val lecture = VidCloudApi.Lecture(
                    videoId = j.optString("vid"),
                    typeId = j.optString("tid"),
                    url = j.optString("vu"),
                    name = j.optString("n").ifBlank { "Lecture" },
                    image = j.optString("img"),
                    duration = "",
                    startedAt = 0L,
                )
                val playUrl = VidCloudApi.playUrl(batchId, subjectId, topicId, lecture)
                val payload = VidCloudApi.resolvePlay(playUrl)
                    ?: throw com.lagradost.cloudstream3.ErrorLoadingException(
                        "Could not open the player page for ${lecture.name} - " +
                            "${VidCloudApi.lastPlayNotes}. Try again in a minute"
                    )
                if (payload.clearkey) {
                    callback(
                        newDrmExtractorLink(
                            name,
                            lecture.name,
                            payload.url,
                            ExtractorLinkType.M3U8,
                            CLEARKEY_DRM_UUID,
                        ) {
                            this.kid = payload.keyId
                            this.key = payload.keyValue
                            this.referer = "${VidCloudApi.SITE}/"
                            this.headers = VidCloudApi.playerHeaders()
                        }
                    )
                } else {
                    callback(
                        newExtractorLink(
                            name,
                            lecture.name,
                            payload.url,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.referer = "${VidCloudApi.SITE}/"
                            this.headers = VidCloudApi.playerHeaders()
                        }
                    )
                }
                return true
            }

            else -> return false
        }
    }
}
