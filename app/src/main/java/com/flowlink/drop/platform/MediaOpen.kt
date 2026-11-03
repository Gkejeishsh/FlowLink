package com.flowlink.drop.platform

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.flowlink.drop.ReceivedItem
import com.flowlink.drop.core.Names

/**
 * Opening something that was received.
 *
 * The list stores a name and a destination label, not a URI, because a URI can be invalidated by a
 * re-index. Resolving it at the moment of the tap keeps the row working across reboots and media
 * rescans, and needs no FileProvider (a `file://` URI would throw on Android 7+ anyway).
 */
object MediaOpen {

    fun uriFor(context: Context, item: ReceivedItem): Uri? {
        val resolver = context.contentResolver
        val collections = buildList {
            add(MediaStore.Files.getContentUri("external"))
            when {
                item.where.startsWith("Pictures") -> add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                item.where.startsWith("Movies") -> add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                item.where.startsWith("Music") -> add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            }
        }
        for (collection in collections) {
            val uri = runCatching {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(item.name),
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0))
                    else null
                }
            }.getOrNull()
            if (uri != null) return uri
        }
        return null
    }

    /** Returns false when nothing on this phone can open the file — the caller says where it is. */
    fun open(context: Context, item: ReceivedItem): Boolean {
        val uri = uriFor(context, item) ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, Names.mimeOf(item.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
