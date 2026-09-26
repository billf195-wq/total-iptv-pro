package com.totaliptv.pro.desktop.artwork

import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Image as SkiaImage

/** Output size and Skia sampler. Never enlarges a small decode. */
object ArtworkDecode {
    fun outputSize(srcWidth: Int, srcHeight: Int, targetLongEdge: Int): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0) return 1 to 1
        val longEdge = maxOf(srcWidth, srcHeight)
        if (targetLongEdge <= 0 || longEdge <= targetLongEdge) return srcWidth to srcHeight
        val scale = targetLongEdge.toFloat() / longEdge
        val dstW = (srcWidth * scale).toInt().coerceAtLeast(1)
        val dstH = (srcHeight * scale).toInt().coerceAtLeast(1)
        return dstW to dstH
    }

    /**
     * Large reductions use mipmaps. Moderate reductions use Catmull-Rom (cubic).
     */
    fun samplingFor(srcLongEdge: Int, dstLongEdge: Int): SamplingMode {
        if (srcLongEdge <= 0 || dstLongEdge <= 0 || dstLongEdge >= srcLongEdge) {
            return SamplingMode.CATMULL_ROM
        }
        val scale = dstLongEdge.toFloat() / srcLongEdge
        return if (scale < 0.5f) {
            FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)
        } else {
            SamplingMode.CATMULL_ROM
        }
    }

    fun scale(image: SkiaImage, targetLongEdge: Int, highQuality: Boolean): SkiaImage {
        val (dstW, dstH) = if (highQuality) {
            outputSize(image.width, image.height, targetLongEdge)
        } else {
            val maxDim = 600
            if (image.width <= maxDim && image.height <= maxDim) return image
            val scale = maxDim.toFloat() / maxOf(image.width, image.height)
            (image.width * scale).toInt().coerceAtLeast(1) to (image.height * scale).toInt().coerceAtLeast(1)
        }
        if (dstW == image.width && dstH == image.height) return image
        val surface = Surface.makeRasterN32Premul(dstW, dstH)
        val canvas = surface.canvas
        val paint = Paint().apply { isAntiAlias = true }
        val src = Rect.makeWH(image.width.toFloat(), image.height.toFloat())
        val dst = Rect.makeWH(dstW.toFloat(), dstH.toFloat())
        if (highQuality) {
            val sampling = samplingFor(maxOf(image.width, image.height), maxOf(dstW, dstH))
            canvas.drawImageRect(image, src, dst, sampling, paint, true)
        } else {
            canvas.drawImageRect(image, src, dst, paint)
        }
        return surface.makeImageSnapshot()
    }
}
