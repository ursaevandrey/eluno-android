package ru.eluno.feature.commissioning

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.DecoderFactory
import com.journeyapps.barcodescanner.MixedDecoder
import kotlin.math.*

internal fun matterQrDecoderFactory() = DecoderFactory { baseHints ->
    val hints = baseHints.toMutableMap().apply {
        put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
        put(DecodeHintType.TRY_HARDER, true)
    }
    MatterQrDecoder(MultiFormatReader().apply { setHints(hints) })
}

/** Handles both polarities and QR codes rendered without a light quiet zone on a dark screen. */
private class MatterQrDecoder(reader: MultiFormatReader) : MixedDecoder(reader) {
    private var frames = 0

    override fun decode(source: LuminanceSource): Result? {
        super.decode(source)?.let { return it }
        // Keep extra image processing off most camera frames; ordinary scans use the fast path.
        if (frames++ % 3 != 0) return null
        val bright = try { HybridBinarizer(source.invert()).blackMatrix } catch (_: Exception) { return null }
        val seen = mutableSetOf<QrBounds>()
        val seeds = listOf(.5 to .5, .5 to .35, .5 to .65, .35 to .5, .65 to .5)
        for ((x, y) in seeds) {
            for (divisor in listOf(12, 24)) {
                val bounds = bright.screenQrBounds(
                    (source.width * x).toInt(), (source.height * y).toInt(),
                    (minOf(source.width, source.height) / divisor).coerceAtLeast(8),
                ) ?: continue
                if (!seen.add(bounds)) continue
                val padded = source.withQuietZone(bounds)
                // Padding restores the missing light surround, without changing the encoded modules.
                decode(BinaryBitmap(HybridBinarizer(padded)))?.let { return it }
                source.withOrientedQuietZone(bounds, bright)?.let { aligned ->
                    decode(BinaryBitmap(HybridBinarizer(aligned)))?.let { return it }
                }
            }
        }
        return null
    }
}

private data class QrBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left + 1
    val height get() = bottom - top + 1
}

/** Grow a seed window to the dark surround, then trim to the bright pixels of the symbol. */
private fun BitMatrix.screenQrBounds(x: Int, y: Int, radius: Int): QrBounds? {
    var left = (x - radius).coerceAtLeast(0)
    var right = (x + radius).coerceAtMost(width - 1)
    var top = (y - radius).coerceAtLeast(0)
    var bottom = (y + radius).coerceAtMost(height - 1)
    fun rowHasLight(row: Int) = (left..right).any { get(it, row) }
    fun columnHasLight(column: Int) = (top..bottom).any { get(column, it) }
    do {
        var expanded = false
        if (left > 0 && columnHasLight(left)) { left--; expanded = true }
        if (right < width - 1 && columnHasLight(right)) { right++; expanded = true }
        if (top > 0 && rowHasLight(top)) { top--; expanded = true }
        if (bottom < height - 1 && rowHasLight(bottom)) { bottom++; expanded = true }
    } while (expanded)
    // A clipped symbol cannot be repaired; keep scanning until the whole code is in view.
    if (left == 0 || top == 0 || right == width - 1 || bottom == height - 1) return null
    var minX = right
    var maxX = left
    var minY = bottom
    var maxY = top
    for (row in top..bottom) for (column in left..right) {
        if (get(column, row)) {
            minX = minOf(minX, column); maxX = maxOf(maxX, column)
            minY = minOf(minY, row); maxY = maxOf(maxY, row)
        }
    }
    val bounds = QrBounds(minX, minY, maxX, maxY)
    if (bounds.width < 42 || bounds.height < 42) return null
    if (bounds.width.toDouble() / bounds.height !in .75..1.33) return null
    return bounds
}

private fun LuminanceSource.withQuietZone(bounds: QrBounds): LuminanceSource {
    val margin = (maxOf(bounds.width, bounds.height) / 5).coerceAtLeast(8)
    val paddedWidth = bounds.width + 2 * margin
    val paddedHeight = bounds.height + 2 * margin
    val pixels = ByteArray(paddedWidth * paddedHeight) { 0xff.toByte() }
    val input = matrix
    for (row in 0 until bounds.height) {
        val start = (bounds.top + row) * width + bounds.left
        input.copyInto(pixels, (row + margin) * paddedWidth + margin, start, start + bounds.width)
    }
    return PlanarYUVLuminanceSource(pixels, paddedWidth, paddedHeight, 0, 0, paddedWidth, paddedHeight, false)
}

private data class QrPoint(val x: Double, val y: Double)
private data class QrRectangle(val angle: Double, val minU: Double, val maxU: Double, val minV: Double, val maxV: Double) {
    val area get() = (maxU - minU) * (maxV - minV)
}

/** A tilted screen needs a quiet zone along the symbol edges, not its axis-aligned bounding box. */
private fun LuminanceSource.withOrientedQuietZone(bounds: QrBounds, bright: BitMatrix): LuminanceSource? {
    val outline = buildList {
        for (y in bounds.top..bounds.bottom) {
            val first = (bounds.left..bounds.right).firstOrNull { bright[it, y] } ?: continue
            val last = (bounds.right downTo bounds.left).first { bright[it, y] }
            add(QrPoint(first.toDouble(), y.toDouble()))
            if (first != last) add(QrPoint(last.toDouble(), y.toDouble()))
        }
    }.distinct().sortedWith(compareBy<QrPoint> { it.x }.thenBy { it.y })
    if (outline.size < 4) return null
    fun half(points: List<QrPoint>): List<QrPoint> {
        val hull = mutableListOf<QrPoint>()
        for (p in points) {
            while (hull.size >= 2) {
                val a = hull[hull.lastIndex - 1]
                val b = hull.last()
                val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
                if (cross > 0) break
                hull.removeAt(hull.lastIndex)
            }
            hull.add(p)
        }
        return hull.dropLast(1)
    }
    val hull = half(outline) + half(outline.asReversed())
    val rectangle = hull.indices.map { i ->
        val a = hull[i]
        val b = hull[(i + 1) % hull.size]
        val angle = atan2(b.y - a.y, b.x - a.x)
        val c = cos(angle)
        val s = sin(angle)
        val u = hull.map { it.x * c + it.y * s }
        val v = hull.map { -it.x * s + it.y * c }
        QrRectangle(angle, u.min(), u.max(), v.min(), v.max())
    }.minByOrNull { it.area } ?: return null
    val qrWidth = ceil(rectangle.maxU - rectangle.minU).toInt() + 1
    val qrHeight = ceil(rectangle.maxV - rectangle.minV).toInt() + 1
    if (qrWidth < 42 || qrHeight < 42 || qrWidth.toDouble() / qrHeight !in .75..1.33) return null
    val margin = maxOf(qrWidth, qrHeight) / 5
    val outputWidth = qrWidth + 2 * margin
    val outputHeight = qrHeight + 2 * margin
    val output = ByteArray(outputWidth * outputHeight) { 0xff.toByte() }
    val input = matrix
    val c = cos(rectangle.angle)
    val s = sin(rectangle.angle)
    for (row in 0 until qrHeight) for (column in 0 until qrWidth) {
        val u = rectangle.minU + column
        val v = rectangle.minV + row
        val x = (u * c - v * s).roundToInt()
        val y = (u * s + v * c).roundToInt()
        if (x in 0 until width && y in 0 until height) {
            output[(row + margin) * outputWidth + column + margin] = input[y * width + x]
        }
    }
    return PlanarYUVLuminanceSource(output, outputWidth, outputHeight, 0, 0, outputWidth, outputHeight, false)
}
