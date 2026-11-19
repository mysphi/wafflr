package app.wafflr

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Reads the text inside a drawn area. It takes a screenshot, keeps only the area you drew, and
 * runs Google's on-device text recognition (ML Kit, bundled in the app, works offline).
 *
 * Recognising the pixels, rather than asking the app for its text, means it reads exactly what is
 * inside the shape and also works in apps whose text is an image, such as PDFs and photos.
 */
class AreaReader(private val service: AccessibilityService) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun read(area: SelectedArea, onResult: (Result<String>) -> Unit) {
        capture({ onResult(Result.failure(it)) }) { bitmap ->
            recognise(cropToArea(bitmap, area), 0, 0) { r -> onResult(r.map { blocks -> blocks.joinToString("\n") { it.first } }) }
        }
    }

    /**
     * Reads what a drawn box or shape picked out, forgiving loose drawing: the whole screen is
     * recognised, any word the shape touches (even partly) counts as picked, and every paragraph
     * holding a picked word is read in full. Paragraphs come back in reading order, each with
     * its place on screen.
     */
    fun readSelection(area: SelectedArea, onResult: (Result<List<Pair<String, Rect>>>) -> Unit) {
        val region = android.graphics.Region().apply {
            setPath(area.path, android.graphics.Region(area.bounds.left - 1, area.bounds.top - 1, area.bounds.right + 1, area.bounds.bottom + 1))
        }
        capture({ onResult(Result.failure(it)) }) { bitmap ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { visionText ->
                    bitmap.recycle()
                    val blocks = visionText.textBlocks.sortedWith(
                        compareBy({ (it.boundingBox?.top ?: 0) / ROW_BAND }, { it.boundingBox?.left ?: 0 })
                    )
                    val out = mutableListOf<Pair<String, Rect>>()
                    for (block in blocks) {
                        for (para in paragraphs(block.lines.map { l -> l.text to (l.boundingBox ?: Rect()) to l.elements.mapNotNull { it.boundingBox } })) {
                            val picked = para.any { (_, words) -> words.any { touched(region, it) } }
                            if (!picked) continue
                            val text = joinLines(para.map { it.first.first }).trim()
                            if (text.isEmpty()) continue
                            val box = Rect(para.first().first.second)
                            para.forEach { box.union(it.first.second) }
                            out += text to box
                        }
                    }
                    onResult(Result.success(out))
                }
                .addOnFailureListener { e ->
                    bitmap.recycle()
                    onResult(Result.failure(e))
                }
        }
    }

    /** A word counts as picked if a fair part of it (about a fifth or more) is inside the shape. */
    private fun touched(region: android.graphics.Region, r: Rect): Boolean {
        var inside = 0
        for (i in 1..3) for (j in 1..3) {
            if (region.contains(r.left + r.width() * i / 4, r.top + r.height() * j / 4)) inside++
        }
        return inside >= 2
    }

    /**
     * Splits a recognised block's lines into paragraphs, wherever the gap between two lines is
     * clearly bigger than the usual line spacing.
     */
    private fun <T> paragraphs(lines: List<Pair<Pair<String, Rect>, T>>): List<List<Pair<Pair<String, Rect>, T>>> {
        if (lines.isEmpty()) return emptyList()
        val heights = lines.map { it.first.second.height() }.sorted()
        val lineH = heights[heights.size / 2].coerceAtLeast(1)
        val out = mutableListOf(mutableListOf(lines.first()))
        for (k in 1 until lines.size) {
            val gap = lines[k].first.second.top - lines[k - 1].first.second.bottom
            if (gap > lineH * 0.8) out += mutableListOf<Pair<Pair<String, Rect>, T>>()
            out.last() += lines[k]
        }
        return out
    }

    /**
     * Recognises the text in a picture of the whole screen, as blocks with their positions.
     * Used when an app doesn't share its text with accessibility services.
     */
    fun readScreenBlocks(onResult: (Result<List<Pair<String, Rect>>>) -> Unit) {
        capture({ onResult(Result.failure(it)) }) { bitmap -> recognise(bitmap, 0, 0, onResult) }
    }

    /**
     * The words inside [rect] on screen, in reading order, each with its box in screen pixels.
     * Used to underline words in apps that don't say where their words are. Null if the screen
     * couldn't be captured or read.
     */
    fun readWords(rect: Rect, onResult: (List<Pair<String, Rect>>?) -> Unit) {
        capture({ onResult(null) }) { full ->
            val left = rect.left.coerceIn(0, full.width - 1)
            val top = rect.top.coerceIn(0, full.height - 1)
            val right = rect.right.coerceIn(left + 1, full.width)
            val bottom = rect.bottom.coerceIn(top + 1, full.height)
            val crop = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
            if (crop !== full) full.recycle()
            recognizer.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener { visionText ->
                    val blocks = visionText.textBlocks.sortedWith(
                        compareBy({ (it.boundingBox?.top ?: 0) / ROW_BAND }, { it.boundingBox?.left ?: 0 })
                    )
                    val words = blocks.flatMap { b -> b.lines.flatMap { l -> l.elements } }.mapNotNull { e ->
                        val box = e.boundingBox ?: return@mapNotNull null
                        e.text to Rect(box).apply { offset(left, top) }
                    }
                    crop.recycle()
                    onResult(words)
                }
                .addOnFailureListener {
                    crop.recycle()
                    onResult(null)
                }
        }
    }

    private fun capture(onError: (Throwable) -> Unit, onBitmap: (Bitmap) -> Unit) {
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                    val bitmap = try {
                        val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        shot.hardwareBuffer.close()
                        soft
                    } catch (t: Throwable) {
                        null
                    }
                    if (bitmap == null) {
                        onError(IllegalStateException("Couldn't capture the screen."))
                        return
                    }
                    onBitmap(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    val why = when (errorCode) {
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                            "This app blocks screenshots (common in banking apps), so Wafflr can't read a drawn area here."
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                            "Too quick; please try again."
                        else -> "Couldn't capture the screen (error $errorCode)."
                    }
                    onError(IllegalStateException(why))
                }
            },
        )
    }

    /** Crops to the area's bounds and whites out anything outside a freehand shape. */
    private fun cropToArea(full: Bitmap, area: SelectedArea): Bitmap {
        val b = area.bounds
        val left = b.left.coerceIn(0, full.width - 1)
        val top = b.top.coerceIn(0, full.height - 1)
        val right = b.right.coerceIn(left + 1, full.width)
        val bottom = b.bottom.coerceIn(top + 1, full.height)
        val out = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val clip = Path(area.path).apply { offset(-left.toFloat(), -top.toFloat()) }
        canvas.clipPath(clip)
        canvas.drawBitmap(full, -left.toFloat(), -top.toFloat(), null)
        full.recycle()
        return out
    }

    /** Text blocks in reading order, each with its box in screen pixels (offset by [dx], [dy]). */
    private fun recognise(bitmap: Bitmap, dx: Int, dy: Int, onResult: (Result<List<Pair<String, Rect>>>) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { visionText ->
                // Reading order: top to bottom, then left to right.
                val blocks = visionText.textBlocks.sortedWith(
                    compareBy({ (it.boundingBox?.top ?: 0) / ROW_BAND }, { it.boundingBox?.left ?: 0 })
                )
                val out = blocks.mapNotNull { block ->
                    val text = joinLines(block.lines.map { it.text }).trim()
                    if (text.isEmpty()) null
                    else text to (block.boundingBox?.let { Rect(it).apply { offset(dx, dy) } } ?: Rect())
                }
                bitmap.recycle()
                onResult(Result.success(out))
            }
            .addOnFailureListener { e ->
                bitmap.recycle()
                onResult(Result.failure(e))
            }
    }

    fun close() = recognizer.close()

    companion object {
        /** Blocks whose tops are within this many pixels count as the same row. */
        private const val ROW_BAND = 24

        /** Joins OCR lines into one paragraph, re-joining words split with a hyphen at line end. */
        fun joinLines(lines: List<String>): String {
            val sb = StringBuilder()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (sb.isEmpty()) { sb.append(line); continue }
                val last = sb.last()
                if (last == '-' && sb.length >= 2 && sb[sb.length - 2].isLetter() && line.first().isLowerCase()) {
                    sb.setLength(sb.length - 1)
                    sb.append(line)
                } else {
                    sb.append(' ').append(line)
                }
            }
            return sb.toString()
        }
    }
}
