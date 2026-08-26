package app.whatfits.ocr

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class OfflineOcrEngine(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val lifecycleMutex = Mutex()
    private val tesseract = TesseractOcrEngine(appContext)
    private var paddle: PaddleOCR? = null
    private var paddleUnavailable = false

    suspend fun recognize(source: Bitmap): String {
        val paddleText = try {
            paddleEngine().recognize(source).results
                .asSequence()
                .filter { it.confidence >= MIN_CONFIDENCE }
                .map { normalize(it.text) }
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .take(MAX_TEXT_LENGTH)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            ""
        }

        if (MODEL_TOKEN.containsMatchIn(paddleText)) return paddleText

        val fallbackText = withContext(Dispatchers.Default) {
            tesseract.recognize(source)
        }
        return listOf(paddleText, fallbackText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .take(MAX_TEXT_LENGTH)
    }

    private suspend fun paddleEngine(): PaddleOCR {
        paddle?.let { return it }
        check(!paddleUnavailable) { "PP-OCR недоступен." }

        return lifecycleMutex.withLock {
            paddle?.let { return@withLock it }
            check(OpenCVUtils.init(appContext)) { "Не удалось инициализировать OpenCV." }
            try {
                PaddleOCR.create(
                    context = appContext,
                    config = PaddleOCRConfig(
                        recScoreThresh = MIN_CONFIDENCE,
                        recBatchSize = 1,
                    ),
                    engineConfig = EngineConfig(numThreads = 4),
                ).also { paddle = it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                paddleUnavailable = true
                throw error
            }
        }
    }

    private fun normalize(text: String): String = text
        .lineSequence()
        .joinToString(" ") { it.trim() }
        .replace(Regex("\\s+"), " ")
        .trim()

    override fun close() {
        runBlocking(Dispatchers.IO) {
            lifecycleMutex.withLock {
                paddle?.release()
                paddle = null
            }
        }
        tesseract.close()
    }

    private companion object {
        const val MIN_CONFIDENCE = 0.10f
        const val MAX_TEXT_LENGTH = 20_000
        val MODEL_TOKEN = Regex("\\b[A-ZА-Я]{1,8}\\s*\\d{3,}[A-ZА-Я0-9-]*\\b", RegexOption.IGNORE_CASE)
    }
}
