package com.bookorbit.feature.reader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.bookorbit.core.storage.LocalRef
import com.bookorbit.core.storage.openParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Wraps [android.graphics.pdf.PdfRenderer] over a local file for the native PDF reader. PdfRenderer
 * permits only one open [PdfRenderer.Page] at a time, so every page access is serialized behind a
 * [Mutex] and runs off the main thread. Rendered bitmaps are kept in a small LRU cache keyed by
 * page + width + rotation; evicted bitmaps are left for the GC (they may still be on screen).
 */
class PdfRenderCore private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) {
    private val mutex = Mutex()
    private var closed = false

    val pageCount: Int = renderer.pageCount

    private val cache = LruCache<String, Bitmap>(CACHE_ENTRIES)
    private val cropCache = LruCache<Int, RectF>(128)

    /** Intrinsic page size in PDF points (pre-rotation). Used to lay out placeholders before render. */
    data class PageSize(val width: Int, val height: Int)

    suspend fun pageSize(index: Int): PageSize = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (closed || index !in 0 until pageCount) return@withContext PageSize(1, 1)
            renderer.openPage(index).use { PageSize(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1)) }
        }
    }

    /**
     * Returns the effective page bounds in pre-rotation PDF points. When [cropMargins] is true,
     * detects and caches the non-white content bounding box so publisher print margins are trimmed.
     */
    suspend fun pageBounds(index: Int, cropMargins: Boolean): RectF {
        if (index !in 0 until pageCount) return RectF(0f, 0f, 1f, 1f)
        if (cropMargins) {
            cropCache.get(index)?.let { return it }
        }
        return mutex.withLock {
            if (cropMargins) {
                cropCache.get(index)?.let { return@withLock it }
            }
            withContext(Dispatchers.IO) {
                if (closed) return@withContext RectF(0f, 0f, 1f, 1f)
                renderer.openPage(index).use { page ->
                    val w = page.width.coerceAtLeast(1).toFloat()
                    val h = page.height.coerceAtLeast(1).toFloat()
                    if (!cropMargins) {
                        RectF(0f, 0f, w, h)
                    } else {
                        detectCropBounds(page).also { cropCache.put(index, it) }
                    }
                }
            }
        }
    }

    /**
     * Render [index] to a bitmap [targetWidthPx] wide in its displayed orientation (height follows
     * the page aspect ratio), rotated clockwise by [rotationDegrees] (0/90/180/270).
     */
    suspend fun renderPage(
        index: Int,
        targetWidthPx: Int,
        rotationDegrees: Int = 0,
        cropMargins: Boolean = false,
    ): Bitmap? {
        if (index !in 0 until pageCount || targetWidthPx <= 0) return null
        val rotate = (((rotationDegrees % 360) + 360) % 360)
        val key = "$index@$targetWidthPx@$rotate@$cropMargins"
        cache.get(key)?.let { return it }
        return mutex.withLock {
            cache.get(key)?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                if (closed) return@withContext null
                renderer.openPage(index).use { page ->
                    val cropRect = if (cropMargins) {
                        cropCache.get(index) ?: detectCropBounds(page).also { cropCache.put(index, it) }
                    } else {
                        RectF(0f, 0f, page.width.coerceAtLeast(1).toFloat(), page.height.coerceAtLeast(1).toFloat())
                    }
                    val bitmap = renderToBitmap(page, cropRect, targetWidthPx, rotate)
                    cache.put(key, bitmap)
                    bitmap
                }
            }
        }
    }

    private fun renderToBitmap(page: PdfRenderer.Page, cropRect: RectF, targetWidthPx: Int, rotate: Int): Bitmap {
        val cropW = cropRect.width().roundToInt().coerceAtLeast(1)
        val cropH = cropRect.height().roundToInt().coerceAtLeast(1)
        val (bmpW, bmpH) = PdfLayout.displayedSize(cropW, cropH, targetWidthPx, rotate)
        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        // PDFs assume white paper; the page may be transparent, so paint a white backdrop first.
        bitmap.eraseColor(Color.WHITE)
        val matrix = displayMatrix(cropRect, targetWidthPx, rotate)
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    // ---- API 35+ text layer (search / links / page text). Empty/no-op below API 35. ----

    private val hasTextLayer: Boolean get() = Build.VERSION.SDK_INT >= 35

    suspend fun searchPage(index: Int, query: String): List<PdfTextMatch> = withPage(index) { page ->
        if (!hasTextLayer || query.isBlank()) emptyList() else PdfTextLayer.search(page, query)
    } ?: emptyList()

    suspend fun pageLinks(index: Int): List<PdfLink> = withPage(index) { page ->
        if (!hasTextLayer) emptyList() else PdfTextLayer.links(page)
    } ?: emptyList()

    suspend fun pageText(index: Int): String = withPage(index) { page ->
        if (!hasTextLayer) "" else PdfTextLayer.text(page)
    } ?: ""

    private suspend fun <T> withPage(index: Int, block: (PdfRenderer.Page) -> T): T? {
        if (index !in 0 until pageCount) return null
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                if (closed) null else renderer.openPage(index).use(block)
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        cache.evictAll()
        cropCache.evictAll()
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }

    companion object {
        private const val CACHE_ENTRIES = 6

        /** Detect non-white content bounds in pre-rotation PDF points, with padding and minimum size. */
        fun detectCropBounds(page: PdfRenderer.Page): RectF {
            val pageW = page.width.coerceAtLeast(1)
            val pageH = page.height.coerceAtLeast(1)
            val full = RectF(0f, 0f, pageW.toFloat(), pageH.toFloat())
            val probeW = 240
            val probeH = (probeW.toFloat() * pageH / pageW).roundToInt().coerceAtLeast(1)
            val probe = Bitmap.createBitmap(probeW, probeH, Bitmap.Config.ARGB_8888)
            probe.eraseColor(Color.WHITE)
            page.render(probe, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val pixels = IntArray(probeW * probeH)
            probe.getPixels(pixels, 0, probeW, 0, 0, probeW, probeH)
            probe.recycle()

            var minX = probeW
            var minY = probeH
            var maxX = -1
            var maxY = -1
            for (y in 0 until probeH) {
                val rowOffset = y * probeW
                for (x in 0 until probeW) {
                    val c = pixels[rowOffset + x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (r < 245 || g < 245 || b < 245) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (maxX < minX || maxY < minY) return full

            val padX = pageW * 0.012f
            val padY = pageH * 0.010f
            var left = ((minX.toFloat() / probeW) * pageW - padX).coerceAtLeast(0f)
            var top = ((minY.toFloat() / probeH) * pageH - padY).coerceAtLeast(0f)
            var right = (((maxX + 1).toFloat() / probeW) * pageW + padX).coerceAtMost(pageW.toFloat())
            var bottom = (((maxY + 1).toFloat() / probeH) * pageH + padY).coerceAtMost(pageH.toFloat())

            // Prevent sparse/partial pages from zooming to giant font sizes:
            val minWidth = pageW * 0.65f
            if (right - left < minWidth) {
                val cx = (left + right) / 2f
                left = (cx - minWidth / 2f).coerceAtLeast(0f)
                right = (left + minWidth).coerceAtMost(pageW.toFloat())
                left = (right - minWidth).coerceAtLeast(0f)
            }
            val minHeight = pageH * 0.78f
            if (bottom - top < minHeight) {
                bottom = (top + minHeight).coerceAtMost(pageH.toFloat())
                top = (bottom - minHeight).coerceAtLeast(0f)
            }
            return RectF(left, top, right, bottom)
        }

        /** Transform from page-point space into displayed pixel space (scale → rotate → translate). */
        fun displayMatrix(pageW: Int, pageH: Int, targetWidthPx: Int, rotate: Int): Matrix =
            displayMatrix(RectF(0f, 0f, pageW.toFloat(), pageH.toFloat()), targetWidthPx, rotate)

        fun displayMatrix(cropRect: RectF, targetWidthPx: Int, rotate: Int): Matrix {
            val cropW = cropRect.width().roundToInt().coerceAtLeast(1)
            val cropH = cropRect.height().roundToInt().coerceAtLeast(1)
            val (bmpW, bmpH) = PdfLayout.displayedSize(cropW, cropH, targetWidthPx, rotate)
            val swap = rotate == 90 || rotate == 270
            val dispW = if (swap) cropRect.height() else cropRect.width()
            val scale = targetWidthPx / dispW.coerceAtLeast(1f)
            return Matrix().apply {
                postTranslate(-cropRect.left, -cropRect.top)
                postScale(scale, scale)
                postRotate(rotate.toFloat())
                when (rotate) {
                    90 -> postTranslate(bmpW.toFloat(), 0f)
                    180 -> postTranslate(bmpW.toFloat(), bmpH.toFloat())
                    270 -> postTranslate(0f, bmpH.toFloat())
                }
            }
        }

        /** Map a page-space rect (text/link/match bounds) into displayed pixel coordinates. */
        fun mapPageRect(rect: RectF, pageW: Int, pageH: Int, targetWidthPx: Int, rotate: Int): RectF =
            mapPageRect(rect, RectF(0f, 0f, pageW.toFloat(), pageH.toFloat()), targetWidthPx, rotate)

        fun mapPageRect(rect: RectF, cropRect: RectF, targetWidthPx: Int, rotate: Int): RectF {
            val out = RectF(rect)
            displayMatrix(cropRect, targetWidthPx, rotate).mapRect(out)
            return out
        }

        /** Open a PDF from a local file/SAF document. Throws if it's not a valid/openable PDF. */
        suspend fun open(context: Context, ref: LocalRef): PdfRenderCore = withContext(Dispatchers.IO) {
            val pfd = ref.openParcelFileDescriptor(context, "r")
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: Throwable) {
                runCatching { pfd.close() }
                throw e
            }
            PdfRenderCore(pfd, renderer)
        }
    }
}
