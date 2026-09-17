package com.clearcontent.app

import android.content.ContentUris
import android.graphics.ImageDecoder
import android.media.MediaExtractor
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clearcontent.app.media.CleanMethod
import com.clearcontent.app.media.MediaCleaner
import com.clearcontent.app.media.OutputStore
import com.clearcontent.app.media.SourceReader
import com.clearcontent.app.settings.AppSettings
import com.clearcontent.app.settings.ImageMode
import com.clearcontent.app.settings.OutputFormat
import com.clearcontent.core.media.MediaFormat
import com.clearcontent.core.media.MediaKind
import com.clearcontent.core.text.TextCleanOptions
import com.clearcontent.core.text.TextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the full Android pipeline (ContentResolver → strip/re-encode/remux → verify → FileProvider)
 * over the shared fixtures. Cleaned files are copied to the app's external files dir
 * (Android/data/<pkg>/files/verify) so they can be pulled and checked with exiftool/c2patool.
 */
@RunWith(AndroidJUnit4::class)
class DeviceCleaningTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = instrumentation.context.assets
    private val reader = SourceReader(context)
    private val store = OutputStore(context)
    private val cleaner = MediaCleaner(context, reader, store)
    private val noGallery = AppSettings.DEFAULT.copy(autoSaveToGallery = false, neutralFileNames = false)

    private fun fixtures(): List<File> {
        val dir = File(context.cacheDir, "fixtures").apply { mkdirs() }
        return assets.list("fixtures").orEmpty()
            .filter { name -> name.substringAfterLast('.', "") !in setOf("md", "jsonl", "json", "txt", "py", "sh", "") }
            .map { name ->
                File(dir, name).also { f -> assets.open("fixtures/$name").use { input -> f.outputStream().use { input.copyTo(it) } } }
            }
    }

    private fun verifyDir(name: String) = File(context.getExternalFilesDir(null), name).apply {
        deleteRecursively()
        mkdirs()
    }

    private fun assertDecodes(file: File, format: MediaFormat) {
        if (format.kind == MediaKind.VIDEO) {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(file.path)
                assertTrue("${file.name}: no tracks", ex.trackCount > 0)
                ex.selectTrack(0)
                var samples = 0
                val buf = java.nio.ByteBuffer.allocate(8 shl 20)
                while (ex.readSampleData(buf, 0) >= 0 && samples < 5000) {
                    samples++
                    ex.advance()
                }
                assertTrue("${file.name}: no samples", samples > 0)
            } finally {
                ex.release()
            }
        } else {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
            assertTrue(bmp.width > 0 && bmp.height > 0)
            bmp.recycle()
        }
    }

    @Test
    fun cleansEveryFixtureOnDevice() {
        val out = verifyDir("verify")
        val failures = mutableListOf<String>()
        for (f in fixtures()) {
            val info = reader.describe(Uri.fromFile(f))
            val line = StringBuilder(f.name)
            try {
                val r = cleaner.clean(info, noGallery)
                assertTrue(r.after.isClean())
                assertDecodes(r.output.file, r.output.format)
                r.output.file.copyTo(File(out, r.output.file.name), overwrite = true)
                line.append(" → ${r.output.file.name} ${r.method} ai=${r.before.aiFindings.map { it.kind }.distinct()} notes=${r.notes}")
                if (f.name.startsWith("c2pa_") && f.name != "c2pa_dashinit.mp4") {
                    assertTrue("${f.name}: C2PA not detected", r.before.hasAiMarkers)
                }
                if (r.before.format.losslessStrip && r.before.format.kind == MediaKind.IMAGE) {
                    assertEquals("${f.name} should be lossless", CleanMethod.LOSSLESS, r.method)
                }
            } catch (e: Throwable) {
                // The DASH init segment has no samples at all and must be rejected; everything else must clean.
                if (f.name != "c2pa_dashinit.mp4" || e !is com.clearcontent.app.media.CleanFailedException) failures += "${f.name}: $e"
                line.append(" ✗ $e")
                Log.i(TAG, line.toString())
                continue
            }
            if (f.name == "c2pa_dashinit.mp4") failures += "${f.name}: a file without frames must not be published"
            Log.i(TAG, line.toString())
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun reencodeModeAndFormatConversion() {
        val out = verifyDir("verify-reencode")
        val settings = noGallery.copy(imageMode = ImageMode.REENCODE, outputFormat = OutputFormat.JPEG, maxDimension = 1080, jpegQuality = 90)
        for (f in fixtures().filter { it.extension.lowercase() in setOf("jpg", "png", "webp", "heic", "avif") }) {
            val r = cleaner.clean(reader.describe(Uri.fromFile(f)), settings)
            assertTrue(r.after.isClean())
            assertEquals(MediaFormat.JPEG, r.output.format)
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(r.output.file))
            assertTrue("${f.name}: ${bmp.width}x${bmp.height}", maxOf(bmp.width, bmp.height) <= 1080)
            r.output.file.copyTo(File(out, r.output.file.name), overwrite = true)
            Log.i(TAG, "reencode ${f.name} → ${bmp.width}x${bmp.height} ${r.output.sizeBytes} ${r.notes}")
        }
    }

    @Test
    fun deepVideoCleanRemuxes() {
        val out = verifyDir("verify-remux")
        val settings = noGallery.copy(deepVideoClean = true)
        for (f in fixtures().filter { it.extension.lowercase() in setOf("mp4", "mov") && it.name != "c2pa_dashinit.mp4" }) {
            val r = cleaner.clean(reader.describe(Uri.fromFile(f)), settings)
            assertEquals(CleanMethod.REMUXED, r.method)
            assertTrue(r.after.isClean())
            assertDecodes(r.output.file, r.output.format)
            r.output.file.copyTo(File(out, r.output.file.name), overwrite = true)
            Log.i(TAG, "remux ${f.name} → ${r.output.sizeBytes} ${r.notes}")
        }
    }

    @Test
    fun galleryPublishingAndSharing() {
        val f = fixtures().first { it.name == "ai_xmp_iptc.jpg" }
        val r = cleaner.clean(reader.describe(Uri.fromFile(f)), AppSettings.DEFAULT)
        val gallery = r.output.galleryUri
        try {
            assertTrue("gallery: ${r.output.galleryError}", gallery != null)
            // The public copy must be byte-identical to the verified file.
            val published = context.contentResolver.openInputStream(gallery!!)!!.use { it.readBytes() }
            assertTrue(published.contentEquals(r.output.file.readBytes()))
            assertTrue(r.output.file.name.startsWith("IMG_"))
            // FileProvider URI is readable through the resolver.
            val shared = context.contentResolver.openInputStream(r.output.shareUri)!!.use { it.readBytes() }
            assertEquals(r.output.sizeBytes, shared.size.toLong())
            assertEquals("content", r.output.shareUri.scheme)
            assertTrue(ContentUris.parseId(gallery) > 0)
        } finally {
            gallery?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    @Test
    fun replaceModeKeepsAlbumAndCaptureDate() {
        // An item this app owns stands in for a camera photo in DCIM.
        val source = fixtures().first { it.name == "c2pa_signed_ai.jpg" }
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "cc_replace_test.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/ClearContentTest")
        }
        val original = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        var copy: Uri? = null
        try {
            resolver.openOutputStream(original)!!.use { out -> source.inputStream().use { it.copyTo(out) } }
            val info = reader.describe(original).copy(dateTaken = 1_750_000_000_000L)
            assertEquals(original, info.galleryItem)
            assertEquals("DCIM/ClearContentTest/", info.relativePath)
            val settings = AppSettings.DEFAULT.copy(replaceOriginals = true)
            val r = cleaner.clean(info, settings)
            copy = r.output.galleryUri
            assertTrue(r.after.isClean())
            assertEquals("cc_replace_test.jpg", r.output.finalName)
            resolver.query(copy!!, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DATE_TAKEN), null, null, null)!!.use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("DCIM/ClearContentTest/", c.getString(0))
                assertEquals(1_750_000_000_000L, c.getLong(1))
            }
            assertTrue(r.after.findings.any { it.kind == com.clearcontent.core.media.FindingKind.CAPTURE_DATE })
            // Own items can be deleted directly; the app would ask the user via createDeleteRequest.
            assertEquals(1, resolver.delete(original, null, null))
            store.rename(copy, r.output.finalName!!)
            resolver.query(copy, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("cc_replace_test.jpg", c.getString(0))
            }
        } finally {
            runCatching { resolver.delete(original, null, null) }
            copy?.let { runCatching { resolver.delete(it, null, null) } }
        }
    }

    @Test
    fun textSanitizerOnAndroidRuntime() {
        fun cp(vararg c: Int) = String(c, 0, c.size)
        val o = TextCleanOptions(stripMarkdown = true)
        val input = "## Итоги\n**Жирный** и *курсив* — пример" + cp(0x202F) + "текста" + cp(0x200B) +
            " со ссылкой [сайт](https://a.ru/?utm_source=chatgpt.com)" + cp(0xE200) + "cite" + cp(0xE202) + "turn0search0" + cp(0xE201) +
            "\nsnake_case_name и эмодзи " + cp(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467) + " " + cp(0x2764, 0xFE0F, 0x200D, 0x1F525)
        val r = TextSanitizer.clean(input, o)
        val expected = "Итоги\nЖирный и курсив — пример текста со ссылкой сайт (https://a.ru/)\nsnake_case_name и эмодзи " +
            cp(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467) + " " + cp(0x2764, 0xFE0F, 0x200D, 0x1F525)
        assertEquals(expected, r.text)
        val tags = "hidden".map { it.code + 0xE0000 }.toIntArray()
        assertEquals("ok", TextSanitizer.clean("o" + String(tags, 0, tags.size) + "k").text)
        assertEquals("a b", TextSanitizer.clean("a" + cp(0x00A0) + "b").text)
    }

    @Test
    fun outputStoreNeutralNames() {
        assertTrue(OutputStore.looksGenerated("ChatGPT Image 1 июн. 2025 г., 12_00_00.png"))
        assertTrue(OutputStore.looksGenerated("Gemini_Generated_Image_abc123.png"))
        assertTrue(!OutputStore.looksGenerated("IMG_20250101_120000.jpg"))
        val file = store.newFile("Gemini_Generated_Image_x.png", MediaFormat.PNG, neutral = false)
        assertTrue(file.name, file.name.startsWith("IMG_"))
    }

    @Suppress("unused")
    private fun imagesCollection(): Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    companion object {
        const val TAG = "ClearContentTest"
    }
}
