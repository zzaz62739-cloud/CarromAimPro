package com.carrom.rebuild

import android.media.Image
import kotlin.math.max
import kotlin.math.min

data class DiscDetection(val x: Float, val y: Float, val radius: Float, val color: String)
data class BoardDetection(val found: Boolean, val discs: List<DiscDetection>, val confidence: Float)

/**
 * Conservative first-pass detector for portrait Carrom Pool frames.
 * Coordinates returned are normalized to the estimated square board.
 * It fails closed: no detections are reported unless several compact disc-like
 * color components are found in the board region.
 */
object BoardVision {
    fun detect(image: Image): BoardDetection {
        val plane = image.planes.firstOrNull() ?: return BoardDetection(false, emptyList(), 0f)
        val width = image.width
        val height = image.height
        val boardLeft = (width * 0.025f).toInt()
        val boardRight = (width * 0.975f).toInt()
        val boardTop = (height * 0.292f).toInt()
        val boardBottom = (height * 0.728f).toInt()
        if (boardRight <= boardLeft || boardBottom <= boardTop) return BoardDetection(false, emptyList(), 0f)

        val buffer = plane.buffer.duplicate()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val step = 2
        val sampleWidth = (boardRight - boardLeft + step - 1) / step
        val sampleHeight = (boardBottom - boardTop + step - 1) / step
        val size = sampleWidth * sampleHeight
        val rgb = IntArray(size)
        val base = buffer.position()
        for (sy in 0 until sampleHeight) {
            val y = boardTop + sy * step
            for (sx in 0 until sampleWidth) {
                val x = boardLeft + sx * step
                val offset = base + y * rowStride + x * pixelStride
                if (offset + 2 >= buffer.limit()) continue
                val r = buffer.get(offset).toInt() and 255
                val g = buffer.get(offset + 1).toInt() and 255
                val b = buffer.get(offset + 2).toInt() and 255
                rgb[sy * sampleWidth + sx] = (r shl 16) or (g shl 8) or b
            }
        }

        val found = ArrayList<DiscDetection>()
        found += components(rgb, sampleWidth, sampleHeight, "black") { r, g, b ->
            r < 62 && g < 62 && b < 68
        }
        found += components(rgb, sampleWidth, sampleHeight, "red") { r, g, b ->
            r > 145 && r > g * 1.45 && r > b * 1.35 && g < 115
        }
        found += components(rgb, sampleWidth, sampleHeight, "white") { r, g, b ->
            r > 205 && g > 195 && b > 175 && max(r, max(g, b)) - min(r, min(g, b)) < 75
        }

        // Remove duplicate detections caused by adjacent color masks and keep
        // the most disc-like compact blobs.
        val unique = ArrayList<DiscDetection>()
        for (candidate in found.sortedByDescending { it.radius }) {
            if (unique.none { old ->
                old.color == candidate.color &&
                    kotlin.math.hypot((old.x - candidate.x).toDouble(), (old.y - candidate.y).toDouble()) < 0.035
            }) unique += candidate
        }
        val boardDiscs = unique.filter { it.x in 0.04f..0.96f && it.y in 0.04f..0.96f }
        val confidence = (boardDiscs.size / 8f).coerceIn(0f, 1f)
        return BoardDetection(boardDiscs.size >= 5, boardDiscs, confidence)
    }

    private inline fun components(
        pixels: IntArray, width: Int, height: Int, color: String,
        crossinline match: (Int, Int, Int) -> Boolean
    ): List<DiscDetection> {
        val visited = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        val output = ArrayList<DiscDetection>()
        for (start in pixels.indices) {
            if (visited[start]) continue
            val value = pixels[start]
            val r = (value shr 16) and 255
            val g = (value shr 8) and 255
            val b = value and 255
            if (!match(r, g, b)) { visited[start] = true; continue }
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = width; var maxX = 0; var minY = height; var maxY = 0
            var sumX = 0L; var sumY = 0L; var count = 0
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                sumX += x; sumY += y; count++
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val ni = ny * width + nx
                    if (visited[ni]) continue
                    val p = pixels[ni]
                    if (match((p shr 16) and 255, (p shr 8) and 255, p and 255)) {
                        visited[ni] = true
                        queue[tail++] = ni
                    }
                }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            // At half resolution, a carrom disc is roughly 14-24 px wide.
            if (count in 22..500 && bw in 7..30 && bh in 7..30 &&
                bw.toFloat() / bh.toFloat() in 0.65f..1.5f) {
                val cx = sumX.toFloat() / count
                val cy = sumY.toFloat() / count
                val nx = cx / (width - 1).coerceAtLeast(1)
                val ny = cy / (height - 1).coerceAtLeast(1)
                val radius = (bw + bh) / 4f / max(width, height).toFloat()
                output += DiscDetection(nx, ny, radius, color)
            }
        }
        return output
    }
}
