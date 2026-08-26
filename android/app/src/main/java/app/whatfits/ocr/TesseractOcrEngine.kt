package app.whatfits.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

class TesseractOcrEngine(private val context: Context) : Closeable {
    private val lock = Any()
    private var api: TessBaseAPI? = null

    fun recognize(source: Bitmap): String = synchronized(lock) {
        val tess = api ?: initialize().also { api = it }
        val recognized = linkedSetOf<String>()

        for (crop in OCR_CROPS) {
            val prepared = prepare(source, crop)
            val text = try {
                tess.setImage(prepared)
                tess.getUTF8Text().orEmpty()
                    .lineSequence()
                    .joinToString(" ") { it.trim() }
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(MAX_PASS_TEXT_LENGTH)
            } finally {
                tess.clear()
                prepared.recycle()
            }

            if (text.isNotBlank()) recognized += text
            if (MODEL_TOKEN.containsMatchIn(text)) break
        }

        recognized.joinToString("\n").take(MAX_TEXT_LENGTH)
    }

    private fun initialize(): TessBaseAPI {
        val dataRoot = File(context.filesDir, "tesseract")
        val model = File(dataRoot, "tessdata/eng.traineddata")
        ensureModel(model)

        return TessBaseAPI().also { tess ->
            check(tess.init(dataRoot.absolutePath, "eng", TessBaseAPI.OEM_LSTM_ONLY)) {
                "Не удалось инициализировать локальный OCR."
            }
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            tess.setVariable(
                TessBaseAPI.VAR_CHAR_WHITELIST,
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789- ",
            )
        }
    }

    private fun ensureModel(target: File) {
        if (target.isFile && sha256(target) == MODEL_SHA256) return

        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        context.assets.open("tessdata/eng.traineddata").use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        check(sha256(temporary) == MODEL_SHA256) { "Встроенная OCR-модель повреждена." }
        temporary.copyTo(target, overwrite = true)
        temporary.delete()
    }

    private fun prepare(source: Bitmap, crop: Crop): Bitmap {
        val left = (source.width * crop.left).roundToInt().coerceIn(0, source.width - 1)
        val top = (source.height * crop.top).roundToInt().coerceIn(0, source.height - 1)
        val right = (source.width * crop.right).roundToInt().coerceIn(left + 1, source.width)
        val bottom = (source.height * crop.bottom).roundToInt().coerceIn(top + 1, source.height)
        val cropped = if (left == 0 && top == 0 && right == source.width && bottom == source.height) {
            source
        } else {
            Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        }

        val longestSide = maxOf(cropped.width, cropped.height)
        val scale = minOf(MAX_UPSCALE, crop.targetDimension.toFloat() / longestSide)
        val width = maxOf(1, (cropped.width * scale).roundToInt())
        val height = maxOf(1, (cropped.height * scale).roundToInt())
        val scaled = cropped.scale(width, height)
        val grayscale = createBitmap(width, height)
        val contrast = 1.65f
        val offset = (1f - contrast) * 128f
        val matrix = ColorMatrix(
            floatArrayOf(
                0.299f * contrast, 0.587f * contrast, 0.114f * contrast, 0f, offset,
                0.299f * contrast, 0.587f * contrast, 0.114f * contrast, 0f, offset,
                0.299f * contrast, 0.587f * contrast, 0.114f * contrast, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        Canvas(grayscale).drawBitmap(
            scaled,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            },
        )
        if (scaled !== cropped) scaled.recycle()
        if (cropped !== source) cropped.recycle()
        return grayscale
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    override fun close() = synchronized(lock) {
        api?.recycle()
        api = null
    }

    private companion object {
        const val MAX_UPSCALE = 4f
        const val MAX_TEXT_LENGTH = 20_000
        const val MAX_PASS_TEXT_LENGTH = 4_000
        const val MODEL_SHA256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
        val MODEL_TOKEN = Regex("\\b[A-Z]{1,8}\\s*\\d{3,}[A-Z0-9-]*\\b")
        val OCR_CROPS = listOf(
            Crop(0f, 0f, 1f, 1f, 2400),
            Crop(0f, 0f, 0.58f, 0.58f, 2000),
            Crop(0.42f, 0f, 1f, 0.58f, 2000),
            Crop(0f, 0.42f, 0.58f, 1f, 2000),
            Crop(0.42f, 0.42f, 1f, 1f, 2000),
        )
    }

    private data class Crop(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val targetDimension: Int,
    )
}
