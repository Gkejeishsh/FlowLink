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
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
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

/**
 * 44 dp thumbnail slot. While a real thumbnail is still decoding the slot breathes instead of
 * showing a dead grey square: a 1.4 s alpha pulse, only when rich motion is on. The badge stays as
 * the instant label so a slow provider never leaves an empty row.
 */
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
    val motion = LocalMotion.current
    val isVisual = item.mime.startsWith("image/") || item.mime.startsWith("video/")
    val waiting = bitmap.value == null && isVisual && !item.isFolder
    val pulse = if (waiting && motion.rich) {
        val transition = rememberInfiniteTransition(label = "thumb")
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
            label = "thumbPulse"
        )
        value
    } else 1f
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (waiting) pulse else 1f)),
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

/**
 * App icons, loaded one visible row at a time.
 *
 * The installer list used to arrive with a Drawable for every app already decoded and retained,
 * which cost tens of megabytes before anything was drawn and made the Apps tab slow to open on a
 * phone with a couple of hundred packages. This is the same idea as [ThumbnailCache], sized smaller
 * because there are fewer visible rows.
 */
object AppIconCache {
    private val cache = android.util.LruCache<String, Bitmap>(64)

    suspend fun load(context: Context, packageName: String): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(packageName)?.let { return@withContext it }
        val drawable = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
            ?: return@withContext null
        val bitmap = runCatching { drawableToBitmap(drawable) }.getOrNull() ?: return@withContext null
        cache.put(packageName, bitmap)
        bitmap
    }
}
