package com.vidcloud

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The topic sheet: every material the site hangs under a topic - lectures,
 * class notes, dpp sheets and the dpp quizzes - listed the way the site's
 * topic page tabs do, one row per item.
 *
 * Lectures open the site's own player page, pdfs open the site's pdf.js page
 * with a download button next to it, quizzes run in the site's quiz page,
 * all inside [WebDialogFragment].
 */
class TopicDialogFragment : DialogFragment() {

    companion object {
        private const val TAG = "VidCloudTopicSheet"
        private const val ARG_TOPIC = "arg_topic"
        private const val ARG_SUBJECT = "arg_subject"
        private const val ARG_BATCH = "arg_batch"
        private const val ARG_SUBJECT_ID = "arg_subject_id"
        private const val ARG_TOPIC_ID = "arg_topic_id"

        fun show(
            activity: AppCompatActivity,
            topicName: String,
            subjectName: String,
            batchId: String,
            subjectId: String,
            topicId: String,
        ) {
            if (activity.isFinishing || activity.isDestroyed) return
            val fm = activity.supportFragmentManager
            if (fm.isDestroyed) return
            activity.runOnUiThread {
                if (fm.findFragmentByTag(TAG) != null) return@runOnUiThread
                fm.beginTransaction()
                    .add(
                        TopicDialogFragment().apply {
                            arguments = Bundle().apply {
                                putString(ARG_TOPIC, topicName)
                                putString(ARG_SUBJECT, subjectName)
                                putString(ARG_BATCH, batchId)
                                putString(ARG_SUBJECT_ID, subjectId)
                                putString(ARG_TOPIC_ID, topicId)
                            }
                        },
                        TAG
                    )
                    .commitAllowingStateLoss()
            }
        }
    }

    private lateinit var list: LinearLayout
    private var loaded = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.6f)
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismissAllowingStateLoss()
                return@setOnKeyListener true
            }
            false
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        val metrics = resources.displayMetrics
        dialog?.window?.apply {
            setLayout(
                minOf((metrics.widthPixels * 0.94f).toInt(), (440 * metrics.density).toInt()),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val a = arguments ?: Bundle()
        val topic = a.getString(ARG_TOPIC) ?: "Topic"
        val subject = a.getString(ARG_SUBJECT) ?: ""
        val batchId = a.getString(ARG_BATCH) ?: ""
        val subjectId = a.getString(ARG_SUBJECT_ID) ?: ""
        val topicId = a.getString(ARG_TOPIC_ID) ?: ""

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#151319"))
                cornerRadius = 24f * resources.displayMetrics.density
            }
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        }

        root.addView(TextView(ctx).apply {
            text = topic
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(
                dp(20), dp(20), dp(20), if (subject.isBlank()) dp(8) else dp(2)
            )
        })
        if (subject.isNotBlank()) {
            root.addView(TextView(ctx).apply {
                text = subject
                textSize = 13f
                setTextColor(Color.parseColor("#A1A1AA"))
                setPadding(dp(20), 0, dp(20), dp(10))
            })
        }

        list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        val scroller = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(list, ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(scroller, LinearLayout.LayoutParams(-1, dp(360)))

        showLoading()

        if (!loaded) {
            loaded = true
            lifecycleScope.launch(Dispatchers.IO) {
                val lectures = mutableListOf<VidCloudApi.Lecture>()
                val pdfs = mutableListOf<VidCloudApi.PdfRef>()
                val tests = mutableListOf<JSONObject>()

                for (type in listOf("videos", "DppVideos")) {
                    VidCloudApi.contents(subjectId, topicId, type).forEach { item ->
                        VidCloudApi.toLecture(item)?.let { lectures.add(it) }
                    }
                }
                for (type in listOf("notes", "DppNotes")) {
                    pdfs.addAll(VidCloudApi.toPdfRefs(VidCloudApi.contents(subjectId, topicId, type)))
                }
                VidCloudApi.dppTests(batchId, subjectId, topicId).forEach { entry ->
                    VidCloudApi.testOf(entry)?.let { tests.add(it) }
                }

                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    buildRows(topic, subject, batchId, subjectId, topicId, lectures, pdfs, tests)
                }
            }
        }
        return root
    }

    private fun showLoading() {
        list.removeAllViews()
        list.addView(TextView(requireContext()).apply {
            text = "Loading materials..."
            textSize = 14f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })
    }

    private fun buildRows(
        topic: String,
        subject: String,
        batchId: String,
        subjectId: String,
        topicId: String,
        lectures: List<VidCloudApi.Lecture>,
        pdfs: List<VidCloudApi.PdfRef>,
        tests: List<JSONObject>,
    ) {
        list.removeAllViews()
        val activity = activity as? AppCompatActivity ?: return

        if (lectures.isEmpty() && pdfs.isEmpty() && tests.isEmpty()) {
            list.addView(TextView(requireContext()).apply {
                text = "No material under this topic yet."
                textSize = 14f
                setTextColor(Color.parseColor("#8E8E93"))
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
            return
        }

        if (lectures.isNotEmpty()) {
            section("Lectures (${lectures.size})")
            lectures.forEach { lecture ->
                row(
                    title = lecture.name,
                    sub = listOf(lecture.duration, "Lecture").filter { it.isNotBlank() }.joinToString("  ·  "),
                ) {
                    WebDialogFragment.show(
                        activity,
                        lecture.name,
                        VidCloudApi.playUrl(batchId, subjectId, topicId, lecture),
                        bootstrap = true,
                        download = null,
                    )
                }
            }
        }
        if (pdfs.isNotEmpty()) {
            section(if (pdfs.size == 1) "Notes & Sheets" else "Notes & Sheets (${pdfs.size})")
            pdfs.forEach { pdf ->
                row(title = pdf.title, sub = pdf.tag) {
                    WebDialogFragment.show(
                        activity,
                        pdf.title,
                        pdf.viewerUrl,
                        bootstrap = false,
                        download = pdf,
                    )
                }
            }
        }
        if (tests.isNotEmpty()) {
            section("DPP Quiz (${tests.size})")
            tests.forEach { test ->
                val name = test.optString("name").takeIf { it.isNotBlank() } ?: "DPP Quiz"
                val questions = test.optInt("totalQuestions", 0)
                val minutes = test.optInt("maxDuration", 0)
                row(
                    title = name,
                    sub = listOf(
                        if (questions > 0) "$questions questions" else null,
                        if (minutes > 0) "$minutes min" else null,
                    ).filterNotNull().joinToString("  ·  "),
                ) {
                    WebDialogFragment.show(
                        activity,
                        name,
                        VidCloudApi.quizUrl(
                            test.optString("_id"),
                            name,
                            batchId,
                            subjectId,
                            subject,
                            topicId,
                            topic,
                        ),
                        bootstrap = false,
                        download = null,
                    )
                }
            }
        }
    }

    private fun section(label: String) {
        list.addView(TextView(requireContext()).apply {
            text = label.uppercase()
            textSize = 11f
            setTextColor(Color.parseColor("#F87171"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(14), dp(16), dp(4))
        })
    }

    private fun row(title: String, sub: String, onClick: () -> Unit) {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1D1B22"))
                cornerRadius = 12f * resources.displayMetrics.density
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
            }
        }
        card.addView(TextView(ctx).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
        })
        if (sub.isNotBlank()) {
            card.addView(TextView(ctx).apply {
                text = sub
                textSize = 12f
                setTextColor(Color.parseColor("#A1A1AA"))
                setPadding(0, dp(2), 0, 0)
            })
        }
        list.addView(card)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

/**
 * One fullscreen page dialog for every site page the plugin opens: the
 * player, the pdf viewer and the quiz all run exactly as the site ships
 * them. The player bootstraps through the site root first so the php
 * session exists before the player page asks for it, pdfs get a download
 * button in the toolbar.
 */
class WebDialogFragment : DialogFragment() {

    companion object {
        private const val TAG = "VidCloudWebDialog"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_URL = "arg_url"
        private const val ARG_BOOTSTRAP = "arg_bootstrap"
        private const val ARG_DL_URL = "arg_dl_url"
        private const val ARG_DL_NAME = "arg_dl_name"

        fun show(
            activity: AppCompatActivity,
            title: String,
            url: String,
            bootstrap: Boolean,
            download: VidCloudApi.PdfRef?,
        ) {
            if (activity.isFinishing || activity.isDestroyed) return
            val fm = activity.supportFragmentManager
            if (fm.isDestroyed) return
            activity.runOnUiThread {
                if (fm.findFragmentByTag(TAG) != null) return@runOnUiThread
                fm.beginTransaction()
                    .add(
                        WebDialogFragment().apply {
                            arguments = Bundle().apply {
                                putString(ARG_TITLE, title)
                                putString(ARG_URL, url)
                                putBoolean(ARG_BOOTSTRAP, bootstrap)
                                putString(ARG_DL_URL, download?.directUrl ?: "")
                                putString(ARG_DL_NAME, download?.fileName ?: "")
                            }
                        },
                        TAG
                    )
                    .commitAllowingStateLoss()
            }
        }
    }

    private var webView: WebView? = null
    private var progress: ProgressBar? = null
    private var bootstrapping = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            setDimAmount(1f)
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_UP) {
                return@setOnKeyListener false
            }
            val wv = webView
            if (wv?.canGoBack() == true && wv.url != requireArguments().getString(ARG_URL)) {
                wv.goBack()
            } else {
                dismissAllowingStateLoss()
            }
            true
        }
        return dialog
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val a = arguments ?: return View(ctx)
        val title = a.getString(ARG_TITLE) ?: ""
        val url = a.getString(ARG_URL) ?: return View(ctx)
        val bootstrap = a.getBoolean(ARG_BOOTSTRAP)
        val dlUrl = a.getString(ARG_DL_URL) ?: ""
        val dlName = a.getString(ARG_DL_NAME) ?: ""

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#151319"))
        }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        bar.addView(TextView(ctx).apply {
            text = "‹"
            textSize = 26f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, dp(10), 0)
            isClickable = true
            setOnClickListener { dismissAllowingStateLoss() }
        })
        bar.addView(TextView(ctx).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, -2).apply { weight = 1f }
        })
        if (dlUrl.isNotBlank()) {
            bar.addView(TextView(ctx).apply {
                text = "Download"
                textSize = 13f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#EF4444"))
                    cornerRadius = 10f * resources.displayMetrics.density
                }
                isClickable = true
                setOnClickListener { downloadPdf(dlUrl, dlName) }
            })
        }
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        progress = ProgressBar(ctx).apply {
            isIndeterminate = true
        }
        val progressWrap = FrameLayout(ctx).apply {
            setBackgroundColor(Color.parseColor("#151319"))
            addView(
                progress,
                FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
            )
        }
        root.addView(progressWrap, LinearLayout.LayoutParams(-1, dp(2)))

        webView = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = VidCloudApi.UA
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val u = request.url.toString()
                    val host = request.url.host ?: ""
                    val inApp = host.endsWith("vidcloud.eu.org") ||
                        host.endsWith("pw.live") ||
                        host.endsWith("herokuapp.com") ||
                        host.endsWith("cdnjs.cloudflare.com") ||
                        host.endsWith("jsdelivr.net") ||
                        host.endsWith("cloudfront.net") ||
                        host.endsWith("testwave.cc")
                    if (inApp) return false
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                    } catch (_: Exception) {
                    }
                    return true
                }

                override fun onPageFinished(view: WebView, finishedUrl: String?) {
                    progressWrap.visibility = View.GONE
                    if (bootstrapping && finishedUrl != null && finishedUrl == "${VidCloudApi.SITE}/") {
                        bootstrapping = false
                        view.loadUrl(url)
                    }
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(-1, -2))

        // the player page reads the php session, so the site root is loaded
        // first and the real page only after the session cookie exists
        if (bootstrap) {
            bootstrapping = true
            webView?.loadUrl("${VidCloudApi.SITE}/")
        } else {
            webView?.loadUrl(url)
        }
        return root
    }

    private fun downloadPdf(url: String, name: String) {
        val ctx = requireContext()
        try {
            val file = if (name.endsWith(".pdf", true)) name else "$name.pdf"
            val request = android.app.DownloadManager.Request(Uri.parse(url))
                .setTitle(file)
                .setDescription("VidCloud")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file)
                .addRequestHeader("User-Agent", VidCloudApi.UA)
            val dm = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            dm.enqueue(request)
            Toast.makeText(ctx, "Downloading $file", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        webView?.destroy()
        webView = null
        super.onDestroyView()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
