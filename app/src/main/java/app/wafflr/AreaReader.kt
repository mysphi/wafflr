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
