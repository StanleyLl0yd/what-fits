package app.whatfits.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

object ImageLoader {
    private const val MAX_PIXELS = 25_000_000L
    private const val TARGET_PIXELS = 4_000_000L
    private const val MAX_BYTES = 10L * 1024 * 1024

    fun decode(context: Context, uri: Uri): Bitmap {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.length > MAX_BYTES) error("Размер фотографии превышает 10 МБ.")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: error("Не удалось открыть фотографию.")

        val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Файл не является изображением." }
        check(pixels <= MAX_PIXELS) { "Изображение имеет слишком большое разрешение." }

        var sampleSize = 1
        while (pixels / (sampleSize.toLong() * sampleSize) > TARGET_PIXELS) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: error("Не удалось прочитать фотографию.")
    }
}
