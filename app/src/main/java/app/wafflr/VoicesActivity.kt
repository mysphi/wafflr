package app.wafflr

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast

/** Voices: the ones on the phone (use, hear, remove) and more to download. */
class VoicesActivity : WafflrScreen(), ReaderListener {
    private lateinit var installedCard: LinearLayout
    private lateinit var moreGrid: GridLayout
    private lateinit var moreLabel: View
    private val reader get() = app.reader


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = page()
        root.addView(backHeader("Voices"))
        root.addView(note("Every voice works offline once it's on your phone. Tap one to hear it."),
            margins(4).apply { bottomMargin = 0 })

        root.addView(sectionLabel("On your phone"))
        installedCard = card(padding = 0)
        root.addView(installedCard, margins())

        moreLabel = sectionLabel("More voices")
        root.addView(moreLabel)
        moreGrid = GridLayout(this).apply { columnCount = 2; useDefaultMargins = false }
        root.addView(moreGrid, margins())

        root.addView(text("Each one is about 65 MB, so Wi-Fi is best. Your last voice can't be removed, so Wafflr always has one to read with.",
            12f, Paper.SOFT, lineSpacing = 1.3f), margins(14))

        setContentView(scroll(root))
    }

    override fun onResume() {
        super.onResume()
        reader.addListener(this)
        onDownloadChange = { refresh() }
        refresh()
    }

    override fun onPause() {
        reader.removeListener(this)
        onDownloadChange = null
        super.onPause()
    }

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun refresh() {
        val installed = app.voices.installedPacks()
        if (installed.isNotEmpty() && prefs.voice.pack !in installed) {
            prefs.voice = VoiceCatalog.voices.first { it.pack in installed }
        }
        refreshInstalled(installed)
        refreshMore()
    }

    private fun refreshInstalled(installed: List<VoicePack>) {
        installedCard.removeAllViews()
        val voices = VoiceCatalog.voices.filter { it.pack in installed }
        if (voices.isEmpty()) {
            installedCard.addView(note("None yet. The built-in voice is being set up.").apply { setPadding(16.px, 14.px, 16.px, 14.px) })
            return
        }
        for ((i, v) in voices.withIndex()) {
            if (i > 0) installedCard.addView(divider(0))
            val inUse = v == prefs.voice
            installedCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16.px, 10.px, 8.px, 10.px)
                minimumHeight = 64.px
                background = pressable(android.graphics.drawable.ColorDrawable(if (inUse) Paper.BLUE_ROW else Paper.CARD))
                contentDescription = "${Paper.name(v)}, ${Paper.accent(v)}${if (inUse) ", in use" else ""}. Tap to hear it."
                setOnClickListener { hear(v) }
                addView(avatar(v, 40, filled = inUse))
                addView(twoLines(Paper.name(v), Paper.accent(v)).apply { setPadding(12.px, 0, 8.px, 0) })
                if (inUse) {
                    addView(text("In use", 12f, Paper.BLUE_DARK, 600).apply {
                        background = shape(Paper.BLUE_PILL, 999f)
                        setPadding(10.px, 4.px, 10.px, 4.px)
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 8.px })
                } else {
                    addView(pillButton("Use", Look.CREAM, size = 13f) {
                        prefs.voice = v
                        refresh()
                        hear(v)
                    }.apply { contentDescription = "Use ${Paper.name(v)}" })
                }
                if (installed.size > 1) {
                    addView(iconButton(R.drawable.ic_delete, Paper.SOFT, null, "Remove ${Paper.name(v)}", iconSize = 18) { confirmRemove(v.pack) })
                }
            })
        }
    }

    private fun refreshMore() {
        moreGrid.removeAllViews()
        val available = VoiceCatalog.packs.filter { !app.voices.isInstalled(it) }
        moreLabel.visibility = if (available.isEmpty()) View.GONE else View.VISIBLE
        val cellW = (resources.displayMetrics.widthPixels - 40.px - 10.px) / 2
        for ((i, pack) in available.withIndex()) {
            val v = VoiceCatalog.voices.first { it.pack == pack }
            val busy = downloading == pack
            val cell = card(padding = 14, radius = 18f).apply {
                addView(text(Paper.name(v), 15f, weight = 600))
                addView(text(Paper.accent(v), 12f, Paper.MUTED).apply { setPadding(0, 2.px, 0, 10.px) })
                if (busy) {
                    val pct = percent(downloadStatus)
                    addView(ProgressBar(this@VoicesActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        isIndeterminate = pct == null
                        max = 100
                        progress = pct ?: 0
                        progressTintList = ColorStateList.valueOf(Paper.BLUE)
                        indeterminateTintList = ColorStateList.valueOf(Paper.BLUE)
                        progressBackgroundTintList = ColorStateList.valueOf(Paper.LINE)
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 12.px))
                    addView(text(if (pct != null) "Downloading · $pct%" else downloadStatus.ifEmpty { "Starting…" }, 12f, Paper.MUTED).apply {
                        minHeight = 28.px
                        gravity = Gravity.CENTER_VERTICAL
                    })
                } else {
                    addView(pillButton("Get · 65 MB", Look.DARK, radius = 12f, size = 13f) { download(pack) }.apply {
                        isEnabled = downloading == null
                        alpha = if (downloading == null) 1f else 0.45f
                        contentDescription = "Download ${Paper.name(v)}, about 65 megabytes"
                    })
                }
            }
            moreGrid.addView(cell, GridLayout.LayoutParams().apply {
                width = cellW
                setMargins(if (i % 2 == 1) 10.px else 0, if (i >= 2) 10.px else 0, 0, 0)
            })
        }
    }

    /** "Downloading… 40 of 65 MB" → 61. */
    private fun percent(status: String): Int? {
        val m = Regex("""(\d+) of (\d+) MB""").find(status) ?: return null
        val done = m.groupValues[1].toFloat()
        val total = m.groupValues[2].toFloat()
        return if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else null
    }

    private fun hear(v: Voice) {
        reader.stop()
        reader.read("Hi there, I'm ${Paper.name(v)}! Lovely to meet you.", v, prefs.speed, prefs.threads, "Sample", prefs.tuning)
    }

    private fun confirmRemove(pack: VoicePack) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Remove ${Paper.name(VoiceCatalog.voices.first { it.pack == pack })}?")
            .setMessage("This frees up the space it uses. You can download it again later.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ -> removePack(pack) }
            .show()
    }

    private fun removePack(pack: VoicePack) {
        val others = app.voices.installedPacks().filter { it != pack }
        if (others.isEmpty()) {
            Toast.makeText(this, "Wafflr needs at least one voice, so the last one can't be removed.", Toast.LENGTH_LONG).show()
            return
        }
        if (reader.status.state != ReaderState.IDLE) reader.stop()
        app.models.release(pack)
        app.voices.delete(pack)
        if (prefs.voice.pack == pack) {
            val next = VoiceCatalog.voices.first { it.pack == others.first() }
            prefs.voice = next
            Toast.makeText(this, "Switched to ${Paper.name(next)}.", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun download(pack: VoicePack) {
        if (downloading != null) return
        downloading = pack
        downloadStatus = ""
        refreshMore()
        val appContext = applicationContext
        val main = android.os.Handler(mainLooper)
        Thread({
            val error = try {
                app.voices.install(pack) { msg -> main.post { downloadStatus = msg; onDownloadChange?.invoke() } }
                null
            } catch (t: Throwable) {
                t.message ?: t.toString()
            }
            main.post {
                downloading = null
                onDownloadChange?.invoke()
                val name = Paper.name(VoiceCatalog.voices.first { it.pack == pack })
                Toast.makeText(appContext, if (error == null) "$name is ready." else "Download failed: $error", Toast.LENGTH_LONG).show()
            }
        }, "voice-download").start()
    }

    companion object {
        /** The voice being downloaded now (one at a time) and its latest progress line. Kept here so leaving the page doesn't lose it. */
        private var downloading: VoicePack? = null
        private var downloadStatus = ""
        private var onDownloadChange: (() -> Unit)? = null
    }
}
