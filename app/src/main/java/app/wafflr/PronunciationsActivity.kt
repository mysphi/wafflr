package app.wafflr

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import java.util.Locale

/**
 * Pronunciations: words the voice says wrongly, and how they should sound. Kept per language
 * (the chosen voice's), since a fix for English would be wrong in another language.
 */
class PronunciationsActivity : WafflrScreen() {
    private val lang get() = prefs.voice.language
    private lateinit var wordInput: EditText
    private lateinit var sayInput: EditText
    private lateinit var listCard: LinearLayout
    private lateinit var listLabel: android.widget.TextView
    private val reader get() = app.reader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val language = Locale(lang).getDisplayLanguage(Locale.UK).ifEmpty { lang }
        val root = page()
        root.addView(backHeader("Pronunciations"))
        root.addView(note(
            "If the voice says a word wrongly, add it here with how it should sound. Write it the way it sounds, " +
                "for example Leicester as Lester, or split it up: Nguyen as Win. These fixes are for $language voices.",
        ), margins(4))

        // --- Add a word ---------------------------------------------------------------------------
        root.addView(sectionLabel("Add a word"))
        wordInput = input("Word, as written")
        sayInput = input("Say it like").apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { add(); true } else false }
        }
        root.addView(card().apply {
            addView(wordInput)
            addView(sayInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.px })
            addView(LinearLayout(this@PronunciationsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12.px, 0, 0)
                val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
                addView(pillButton("Hear it", Look.CREAM, radius = 12f) { hear(sayInput.text.toString().ifBlank { wordInput.text.toString() }, raw = true) }, lp())
                addView(pillButton("Save", Look.BLUE, radius = 12f) { add() }, lp().apply { marginStart = 8.px })
            })
        }, margins())

        // --- Yours ----------------------------------------------------------------------------------
        listLabel = sectionLabel("Your words")
        root.addView(listLabel)
        listCard = card(padding = 0)
        root.addView(listCard, margins())

        // --- Built in -------------------------------------------------------------------------------
        val builtIn = Pronounce.builtIn[lang].orEmpty()
        if (builtIn.isNotEmpty()) {
            root.addView(sectionLabel("Built in"))
            root.addView(card(padding = 0).apply {
                for ((i, e) in builtIn.withIndex()) {
                    if (i > 0) addView(divider())
                    addView(entryRow(e, removable = false))
                }
            }, margins())
            root.addView(text("To change one of these, add the same word above.", 12f, Paper.SOFT), margins(8))
        }

        root.addView(pillButton("Copy my list", Look.OUTLINE, radius = 12f) { copyList() }, margins(18))
        setContentView(scroll(root))
        refresh()
    }

    private fun input(hint: String) = EditText(this).apply {
        this.hint = hint
        textSize = 16f
        typeface = Paper.sans(this@PronunciationsActivity)
        setTextColor(Paper.INK)
        setHintTextColor(Paper.SOFT)
        background = shape(Paper.BG, 12f, 1.px, Paper.CHIP)
        setPadding(14.px, 12.px, 14.px, 12.px)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    private fun entryRow(e: Pronounce.Entry, removable: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(16.px, 6.px, 4.px, 6.px)
        minimumHeight = 56.px
        background = pressable(shape(0x00000000, 0f))
        contentDescription = "${e.word}, said as ${e.sayAs}. Tap to change it."
        setOnClickListener { wordInput.setText(e.word); sayInput.setText(e.sayAs); sayInput.requestFocus() }
        addView(twoLines(e.word, "Says: ${e.sayAs}", titleWeight = 500))
        addView(iconButton(R.drawable.ic_play, Paper.BLUE, null, "Hear ${e.word}", iconSize = 20) { hear(e.word, raw = false) })
        if (removable) addView(iconButton(R.drawable.ic_delete, Paper.SOFT, null, "Remove ${e.word}", iconSize = 18) { remove(e) })
    }

    private fun refresh() {
        listCard.removeAllViews()
        val mine = prefs.pronunciations(lang)
        if (mine.isEmpty()) {
            listCard.addView(note("None yet. Words you add appear here.").apply { setPadding(16.px, 14.px, 16.px, 14.px) })
            return
        }
        for ((i, e) in mine.sortedBy { it.word.lowercase() }.withIndex()) {
            if (i > 0) listCard.addView(divider())
            listCard.addView(entryRow(e, removable = true))
        }
    }

    private fun add() {
        val word = wordInput.text.toString().trim()
        val say = sayInput.text.toString().trim()
        if (word.isEmpty() || say.isEmpty()) {
            Toast.makeText(this, "Type the word and how it should sound.", Toast.LENGTH_SHORT).show()
            return
        }
        val others = prefs.pronunciations(lang).filter { !it.word.equals(word, ignoreCase = true) }
        prefs.setPronunciations(lang, others + Pronounce.Entry(word, say))
        wordInput.setText("")
        sayInput.setText("")
        wordInput.requestFocus()
        refresh()
        hear(word, raw = false)
    }

    private fun remove(e: Pronounce.Entry) {
        prefs.setPronunciations(lang, prefs.pronunciations(lang).filter { it != e })
        refresh()
    }

    /** Reads [text] in the chosen voice. Saved fixes apply; with [raw] the text is just what was typed. */
    private fun hear(text: String, raw: Boolean) {
        if (text.isBlank()) return
        reader.stop()
        reader.read(text, prefs.voice, prefs.speed, prefs.threads, if (raw) "Pronunciation test" else "Pronunciation", prefs.tuning)
    }

    private fun copyList() {
        val mine = prefs.pronunciations(lang)
        val textOut = "Wafflr pronunciations ($lang)\n" + mine.joinToString("\n") { "${it.word} → ${it.sayAs}" }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Wafflr pronunciations", textOut))
        Toast.makeText(this, "Copied ${mine.size} words.", Toast.LENGTH_SHORT).show()
    }
}
