package com.clearcontent.app.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore

/**
 * Maps a picked or shared URI back to the gallery (MediaStore) item it came from, so the original
 * can be removed after the cleaned copy is saved. Returns null for content that is not in the
 * gallery (Telegram/ChatGPT caches, cloud photos) — such sources are never deleted.
 */
object GalleryOriginals {
    private const val LOCAL_PICKER = "com.android.providers.media.photopicker"

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun resolve(context: Context, uri: Uri, mimeType: String?): Uri? {
        if (uri.scheme != "content") return null
        val segments = uri.pathSegments
        return when {
            uri.authority == MediaStore.AUTHORITY && segments.any { it.startsWith("picker") } -> {
                // content://media/picker/<user>/com.android.providers.media.photopicker/media/<id>:
                // local picker items carry the MediaStore row id; cloud items are skipped.
                if (LOCAL_PICKER !in segments) return null
                val id = uri.lastPathSegment?.toLongOrNull() ?: return null
                val video = mimeType?.startsWith("video/") == true
                val base = if (video) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                }
                ContentUris.withAppendedId(base, id)
            }
            uri.authority == MediaStore.AUTHORITY ->
                uri.takeIf { segments.firstOrNull()?.startsWith("external") == true && uri.lastPathSegment?.toLongOrNull() != null }
            DocumentsContract.isDocumentUri(context, uri) ->
                runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
            else -> null
        }
    }
}
