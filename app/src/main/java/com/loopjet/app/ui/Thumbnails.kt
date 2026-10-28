package com.loopjet.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thumbnails for the picker, loaded lazily per visible row.
 *
 * Deliberately tiny and dependency-free: a bounded LRU of 96 px bitmaps, decoded off the main thread,
 * `loadThumbnail` on API 29+ so the provider does the scaling. A row that fails (or is not media)
 * falls back to the extension badge, so a 20 000-row list never blocks on images.
 */
object ThumbnailCache {
    private val cache = LruCache<String, Bitmap>(160)

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    suspend fun load(context: Context, uri: String, mime: String): Bitmap? = withContext(Dispatchers.IO) {
        if (cache.get(uri) != null) return@withContext cache.get(uri)
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return@withContext null
        val bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(parsed, android.util.Size(96, 96), null)
            } else if (mime.startsWith("video/")) {
                videoFrame(context, parsed)
            } else {
                null
            }
        }.getOrNull()
        if (bitmap != null) cache.put(uri, bitmap)
        bitmap
    }

    private fun videoFrame(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0)
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/** 44 dp thumbnail slot with the text badge as the instant placeholder. */
@Composable
fun Thumbnail(item: PickItem, badge: String) {
    val context = LocalContext.current
    val uri = item.uri
    val bitmap = produceState<Bitmap?>(initialValue = null, item.key) {
        if (item.isFolder || uri == null) return@produceState
        val isVisual = item.mime.startsWith("image/") || item.mime.startsWith("video/")
        if (!isVisual) return@produceState
        value = ThumbnailCache.get(uri) ?: ThumbnailCache.load(context, uri, item.mime)
    }
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap.value
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(badge, style = MaterialTheme.typography.labelMedium)
        }
    }
}
