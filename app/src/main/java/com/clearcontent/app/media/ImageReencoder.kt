package com.clearcontent.app.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.os.Build
import com.clearcontent.core.media.ImageTraits
import com.clearcontent.core.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes pixels and writes a brand-new file. Android's encoders write no EXIF/XMP/C2PA,
 * so this is the fallback for HEIF/AVIF/TIFF and for resizing or format conversion.
 */
object ImageReencoder {

    class Result(val bytes: ByteArray, val format: MediaFormat, val width: Int, val height: Int, val notes: List<String>)

    fun targetFormat(source: MediaFormat, requested: MediaFormat?): MediaFormat = requested ?: when (source) {
        MediaFormat.JPEG, MediaFormat.PNG, MediaFormat.WEBP -> source
        MediaFormat.GIF -> MediaFormat.PNG
        else -> MediaFormat.JPEG
    }

    fun encode(
        bytes: ByteArray,
        source: MediaFormat,
        traits: ImageTraits,
        requested: MediaFormat?,
        quality: Int,
        maxDimension: Int,
        keepColorProfile: Boolean,
    ): Result {
        val notes = mutableListOf<String>()
        val budget = pixelBudget()
        var decodedFrom = 0 to 0
        var bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            if (!keepColorProfile) decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val w = info.size.width
            val h = info.size.height
            decodedFrom = w to h
            var scale = 1.0
            if (maxDimension > 0 && max(w, h) > maxDimension) scale = maxDimension.toDouble() / max(w, h)
            val pixels = w.toDouble() * h * scale * scale
            if (pixels > budget) scale *= kotlin.math.sqrt(budget / pixels)
            if (scale < 1.0) {
                decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
            }
        }
        if (bitmap.width < decodedFrom.first && maxDimension == 0) {
            notes += "Уменьшено до ${bitmap.width}×${bitmap.height} из-за нехватки памяти"
        } else if (bitmap.width < decodedFrom.first) {
            notes += "Размер: ${decodedFrom.first}×${decodedFrom.second} → ${bitmap.width}×${bitmap.height}"
        }
        if (traits.animated) notes += "Анимация сохранена только первым кадром"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()) {
            // An Ultra HDR gain map would be written back with XMP; drop it to keep the file metadata-free.
            bitmap.gainmap = null
            notes += "HDR-карта удалена (фото сохранено в SDR)"
        }

        val format = targetFormat(source, requested)
        if (format == MediaFormat.JPEG && bitmap.hasAlpha()) {
            bitmap = flattenOnWhite(bitmap)
            notes += "Прозрачность заменена белым фоном (JPEG)"
        }
        val compressFormat = when (format) {
            MediaFormat.PNG -> Bitmap.CompressFormat.PNG
            MediaFormat.WEBP -> webpFormat(lossless = traits.lossless && requested == null)
            else -> Bitmap.CompressFormat.JPEG
        }
        val out = ByteArrayOutputStream(bytes.size)
        val q = if (compressFormat == Bitmap.CompressFormat.PNG) 100 else quality
        check(bitmap.compress(compressFormat, q, out)) { "Не удалось закодировать изображение" }
        val result = Result(out.toByteArray(), format, bitmap.width, bitmap.height, notes)
        bitmap.recycle()
        return result
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(lossless: Boolean): Bitmap.CompressFormat = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
        else -> Bitmap.CompressFormat.WEBP
    }

    private fun flattenOnWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888, false, src.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB))
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        src.recycle()
        return out
    }

    /** Leaves room for the encoder's own buffers; a 512 MB heap allows roughly 40 MP. */
    private fun pixelBudget(): Double {
        val heap = Runtime.getRuntime().maxMemory().toDouble()
        return (heap * 0.3 / 4).coerceIn(8e6, 120e6)
    }
}
