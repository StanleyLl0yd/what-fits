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
        val prepared = prepare(source)
        try {
            tess.setImage(prepared)
            tess.getUTF8Text().orEmpty()
                .lineSequence()
                .joinToString(" ") { it.trim() }
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_TEXT_LENGTH)
        } finally {
            tess.clear()
            prepared.recycle()
        }
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
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789- ",
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

    private fun prepare(source: Bitmap): Bitmap {
        val scale = minOf(1f, MAX_DIMENSION.toFloat() / maxOf(source.width, source.height))
        val width = maxOf(1, (source.width * scale).roundToInt())
        val height = maxOf(1, (source.height * scale).roundToInt())
        val scaled = source.scale(width, height)
        val grayscale = createBitmap(width, height)
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        Canvas(grayscale).drawBitmap(
            scaled,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            },
        )
        if (scaled !== source) scaled.recycle()
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
        const val MAX_DIMENSION = 2400
        const val MAX_TEXT_LENGTH = 20_000
        const val MODEL_SHA256 = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
    }
}
