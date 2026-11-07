package com.bookorbit.feature.reader.pdf

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt

/**
 * Renders a single PDF page [index] at the width chosen by [settings], drawing search highlights and
 * forwarding taps on links / long-press to copy. Bitmaps are produced off the main thread by
 * [PdfRenderCore]; a white aspect-ratio placeholder shows until the bitmap arrives.
 */
@Composable
fun PdfPageView(
    core: PdfRenderCore,
    index: Int,
    baseWidthPx: Int,
    containerHeightPx: Int,
    settings: PdfReaderSettings,
    highlights: List<RectF>,
    activeHighlight: RectF?,
    onLink: (PdfLink) -> Unit,
    onLongPress: (Int) -> Unit,
    onToggleZoom: () -> Unit,
    onTapPage: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val cropMargins = settings.cropMargins

    val cropRect by produceState(RectF(0f, 0f, 1f, 1f), core, index, cropMargins) {
        value = core.pageBounds(index, cropMargins)
    }
    val effWidth = cropRect.width().roundToInt().coerceAtLeast(1)
    val effHeight = cropRect.height().roundToInt().coerceAtLeast(1)
    val renderWidth = PdfLayout.renderWidthPx(effWidth, effHeight, baseWidthPx, containerHeightPx, settings)
    val rotation = settings.rotation

    val bitmap by produceState<Bitmap?>(null, core, index, renderWidth, rotation, cropMargins) {
        value = null
        value = core.renderPage(index, renderWidth, rotation, cropMargins)
    }

    val links by produceState<List<PdfLink>>(emptyList(), core, index, settings.hasTextLayer()) {
        value = if (settings.hasTextLayer()) core.pageLinks(index) else emptyList()
    }

    val (dispWpx, dispHpx) = PdfLayout.displayedSize(effWidth, effHeight, renderWidth, rotation)
    val widthDp = with(density) { dispWpx.toDp() }
    val heightDp = with(density) { dispHpx.toDp() }

    Box(
        modifier = modifier
            .requiredWidth(widthDp)
            .height(heightDp)
            .background(Color.White)
            .pointerInput(links, dispWpx, dispHpx, baseWidthPx, cropRect) {
                detectTapGestures(
                    onLongPress = { onLongPress(index) },
                    onDoubleTap = { onToggleZoom() },
                    onTap = { offset ->
                        val link = hitLink(links, offset, cropRect, renderWidth, rotation)
                        if (link != null) {
                            onLink(link)
                        } else {
                            val visibleLeft = ((dispWpx - baseWidthPx).coerceAtLeast(0)) / 2f
                            val visibleWidth = minOf(dispWpx, baseWidthPx).toFloat().coerceAtLeast(1f)
                            val xFraction = ((offset.x - visibleLeft) / visibleWidth).coerceIn(0f, 1f)
                            onTapPage(xFraction)
                        }
                    },
                )
            },
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        if (highlights.isNotEmpty() || activeHighlight != null) {
            // The bitmap fills the box width 1:1, so bitmap px == this DrawScope's px (device px).
            Canvas(modifier = Modifier.size(widthDp, heightDp)) {
                highlights.forEach { r ->
                    drawHighlight(r, cropRect, renderWidth, rotation, Color(0x55FFEB3B))
                }
                activeHighlight?.let { r ->
                    drawHighlight(r, cropRect, renderWidth, rotation, Color(0x88FF9800))
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHighlight(
    pageRect: RectF,
    cropRect: RectF,
    renderWidth: Int,
    rotation: Int,
    color: Color,
) {
    val px = PdfRenderCore.mapPageRect(pageRect, cropRect, renderWidth, rotation)
    drawRect(
        color = color,
        topLeft = Offset(px.left, px.top),
        size = Size(px.width(), px.height()),
    )
}

private fun hitLink(
    links: List<PdfLink>,
    tap: Offset,
    cropRect: RectF,
    renderWidth: Int,
    rotation: Int,
): PdfLink? {
    // tap is in composable px (device px); link page-rects map into the same 1:1 px space.
    return links.firstOrNull { link ->
        link.rects.any { r ->
            val px = PdfRenderCore.mapPageRect(r, cropRect, renderWidth, rotation)
            tap.x >= px.left && tap.x <= px.right && tap.y >= px.top && tap.y <= px.bottom
        }
    }
}

private fun PdfReaderSettings.hasTextLayer(): Boolean = android.os.Build.VERSION.SDK_INT >= 35
