package com.clearcontent.app.system

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.IntentCompat
import androidx.core.content.getSystemService

/** Clipboard access. Reading only works while one of our windows has focus (Android 10+). */
object Clipboard {

    sealed interface Content {
        data class Text(val text: String) : Content
        data class Media(val uris: List<Uri>) : Content
        data object Empty : Content
    }

    fun read(context: Context): Content {
        val cm = context.getSystemService<ClipboardManager>() ?: return Content.Empty
        val clip = cm.primaryClip ?: return Content.Empty
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        if (uris.isNotEmpty()) {
            val mediaUris = uris.filter { uri ->
                val type = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
                type.startsWith("image/") || type.startsWith("video/") || clip.description.hasMimeType("image/*") || clip.description.hasMimeType("video/*")
            }
            if (mediaUris.isNotEmpty()) return Content.Media(mediaUris)
        }
        val text = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).coerceToText(context)?.toString() }
            .joinToString("\n").takeIf { it.isNotEmpty() }
        return if (text != null) Content.Text(text) else Content.Empty
    }

    fun copyText(context: Context, text: String, label: String = "Clear Content") {
        val cm = context.getSystemService<ClipboardManager>() ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun copyMedia(context: Context, uri: Uri) {
        val cm = context.getSystemService<ClipboardManager>() ?: return
        cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "Clear Content", uri))
    }
}

object Share {
    const val INSTAGRAM = "com.instagram.android"

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun mimeFor(mimes: List<String>): String = when {
        mimes.all { it.startsWith("image/") } -> if (mimes.distinct().size == 1) mimes.first() else "image/*"
        mimes.all { it.startsWith("video/") } -> if (mimes.distinct().size == 1) mimes.first() else "video/*"
        else -> "*/*"
    }

    fun mediaIntent(uris: List<Uri>, mimeType: String, pkg: String? = null): Intent {
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = mimeType
        val clip = ClipData.newRawUri("", uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (pkg != null) intent.setPackage(pkg)
        return intent
    }

    fun shareMedia(context: Context, uris: List<Uri>, mimeType: String) {
        if (uris.isEmpty()) return
        val chooser = Intent.createChooser(mediaIntent(uris, mimeType), "Отправить очищенные файлы")
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    }

    /** Opens Instagram's own share UI (Feed / Stories / Reels / Direct). Returns false when not installed. */
    fun toInstagram(context: Context, uris: List<Uri>, mimeType: String): Boolean {
        if (uris.isEmpty() || !isInstalled(context, INSTAGRAM)) return false
        return try {
            context.startActivity(mediaIntent(uris, mimeType, INSTAGRAM))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(intent, "Отправить текст"))
    }

    fun view(context: Context, uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
    }
}

/** What another app handed to us. */
sealed interface Incoming {
    data class Media(val uris: List<Uri>) : Incoming
    data class Text(val text: String) : Incoming
    data object PickMedia : Incoming
    data object OpenText : Incoming
    data object None : Incoming

    companion object {
        const val ACTION_PICK_MEDIA = "com.clearcontent.app.action.PICK_MEDIA"
        const val ACTION_OPEN_TEXT = "com.clearcontent.app.action.OPEN_TEXT"

        fun parse(context: Context, intent: Intent?): Incoming {
            intent ?: return None
            val type = intent.type.orEmpty()
            return when (intent.action) {
                Intent.ACTION_SEND -> {
                    val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    when {
                        type.startsWith("text/") && !text.isNullOrEmpty() -> Text(text)
                        type.startsWith("text/") && stream != null ->
                            runCatching { Text(com.clearcontent.app.AppGraph.get(context).reader.readText(stream)) }.getOrDefault(None)
                        stream != null -> Media(listOf(stream))
                        !text.isNullOrEmpty() -> Text(text)
                        else -> None
                    }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    val list = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                    if (list.isNotEmpty()) Media(list) else None
                }
                Intent.ACTION_VIEW -> intent.data?.let { Media(listOf(it)) } ?: None
                ACTION_PICK_MEDIA -> PickMedia
                ACTION_OPEN_TEXT -> OpenText
                else -> None
            }
        }
    }
}
