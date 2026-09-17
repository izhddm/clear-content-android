package com.clearcontent.app.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Copies only the audio and video samples into a fresh container. Used for fragmented MP4,
 * Matroska/WebM, broken files and the "deep clean" option.
 */
object VideoRemuxer {

    class Result(val webm: Boolean, val notes: List<String>)

    private val WEBM_CODECS = setOf(
        MediaFormat.MIMETYPE_VIDEO_VP8, MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaFormat.MIMETYPE_AUDIO_OPUS, MediaFormat.MIMETYPE_AUDIO_VORBIS,
    )

    fun remux(context: Context, uri: Uri, out: File): Result {
        val extractor = MediaExtractor()
        val notes = mutableListOf<String>()
        try {
            extractor.setDataSource(context, uri, null)
            val tracks = (0 until extractor.trackCount).map { it to extractor.getTrackFormat(it) }
            val media = tracks.filter { (_, f) ->
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                mime.startsWith("video/") || mime.startsWith("audio/")
            }
            if (media.isEmpty()) throw IOException("В файле нет аудио или видео")
            val skipped = tracks.size - media.size
            if (skipped > 0) notes += "Удалено служебных дорожек: $skipped"

            val webm = media.all { (_, f) -> f.getString(MediaFormat.KEY_MIME) in WEBM_CODECS }
            val muxer = MediaMuxer(
                out.path,
                if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            val mapping = HashMap<Int, Int>()
            var rotation = 0
            var maxSample = 1 shl 20
            for ((index, format) in media) {
                val mime = format.getString(MediaFormat.KEY_MIME)
                try {
                    mapping[index] = muxer.addTrack(format)
                    extractor.selectTrack(index)
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxSample = maxOf(maxSample, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                } catch (e: IllegalArgumentException) {
                    notes += "Дорожка $mime не поддерживается и пропущена"
                }
            }
            if (mapping.isEmpty()) {
                muxer.release()
                throw IOException("Кодеки этого видео не поддерживаются для перемуксирования")
            }
            if (!webm) muxer.setOrientationHint(rotation)
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(maxOf(maxSample, 4 shl 20))
            val info = MediaCodec.BufferInfo()
            var written = 0L
            try {
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val target = mapping[extractor.sampleTrackIndex]
                    if (target != null) {
                        info.set(
                            0,
                            size,
                            extractor.sampleTime,
                            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                        )
                        muxer.writeSampleData(target, buffer, info)
                        written++
                    }
                    extractor.advance()
                }
                // An init-only segment (DASH/HLS) has track headers but no frames: nothing to publish.
                if (written == 0L) throw IOException("В файле нет кадров (это фрагмент потокового видео)")
                muxer.stop()
            } finally {
                runCatching { muxer.release() }
            }
            notes += "Видео перемуксировано: дорожки скопированы без перекодирования"
            return Result(webm, notes)
        } finally {
            extractor.release()
        }
    }
}
