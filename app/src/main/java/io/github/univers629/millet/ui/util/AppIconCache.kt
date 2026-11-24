package io.github.univers629.millet.ui.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import io.github.univers629.millet.ui.model.AppIconSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 异步应用图标内存缓存池（对齐 KernelSU AppIconCache，支持高刷列表流畅滚动）。
 */
object AppIconCache {

    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSizeKb = (maxMemoryKb / 8).coerceAtLeast(4096)
    private val loadSemaphore = Semaphore(4)

    private val lruCache = object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    private fun buildKey(pkg: String, uid: Int, sizePx: Int): String = "$pkg:$uid:$sizePx"

    fun getCached(source: AppIconSource, sizePx: Int): Bitmap? {
        val key = buildKey(source.packageName, source.uid, sizePx)
        return lruCache.get(key)?.takeIf { !it.isRecycled }
    }

    suspend fun loadIcon(context: Context, source: AppIconSource, sizePx: Int): Bitmap? {
        val appInfo = source.applicationInfo ?: return null
        return loadIcon(context, appInfo, sizePx)
    }

    fun getCached(appInfo: ApplicationInfo, sizePx: Int): Bitmap? {
        val key = buildKey(appInfo.packageName, appInfo.uid, sizePx)
        return lruCache.get(key)?.takeIf { !it.isRecycled }
    }

    suspend fun loadIcon(context: Context, appInfo: ApplicationInfo, sizePx: Int): Bitmap? {
        val key = buildKey(appInfo.packageName, appInfo.uid, sizePx)
        lruCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }

        return loadSemaphore.withPermit {
            lruCache.get(key)?.takeIf { !it.isRecycled }?.let { return@withPermit it }
            withContext(Dispatchers.IO) {
                try {
                    val pm = context.packageManager
                    val drawable = appInfo.loadIcon(pm) ?: pm.defaultActivityIcon
                    val bmp = drawableToBitmap(drawable, sizePx)
                    bmp.prepareToDraw()
                    lruCache.put(key, bmp)
                    bmp
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        val targetSize = sizePx.coerceAtLeast(1)
        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
            val src = drawable.bitmap
            if (src.width == targetSize && src.height == targetSize) {
                return src
            }
        }
        val bitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, targetSize, targetSize)
        drawable.draw(canvas)
        return bitmap
    }
}
