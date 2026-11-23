package com.carromaimpro

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.*
import android.media.*
import android.media.projection.*
import android.os.*
import android.view.*
import kotlin.math.*

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var window: WindowManager? = null
    private var overlay: AimOverlay? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastAnalysis = 0L

    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = "carrom_aim"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(channel, "CarromAimPro", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, channel).setContentTitle("CarromAimPro يعمل").setSmallIcon(android.R.drawable.ic_menu_compass).build()
        else Notification.Builder(this).setContentTitle("CarromAimPro يعمل").setSmallIcon(android.R.drawable.ic_menu_compass).build()
        startForeground(7, notification)
        val code = intent?.getIntExtra("resultCode", 0) ?: return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        val dm = resources.displayMetrics
        reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ r ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysis < 700) { r.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            lastAnalysis = now
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val p = image.planes[0]
                val paddedWidth = image.width + (p.rowStride - p.pixelStride * image.width) / p.pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(p.buffer)
                val frame = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                padded.recycle()
                val circles = DiscVision.detect(frame)
                frame.recycle()
                handler.post { overlay?.update(circles, dm.widthPixels, dm.heightPixels) }
            } catch (_: Exception) {
            } finally { image.close() }
        }, handler)
        display = projection?.createVirtualDisplay("CarromAimProCapture", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler)
        window = getSystemService(WINDOW_SERVICE) as WindowManager
        overlay = AimOverlay(this)
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(dm.widthPixels, dm.heightPixels, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        window?.addView(overlay, lp)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        display?.release(); reader?.close(); projection?.stop()
        try { window?.removeView(overlay) } catch (_: Exception) {}
        super.onDestroy()
    }
}

data class Disc(val x: Float, val y: Float, val radius: Float, val kind: Int = 0)

object DiscVision {
    private data class Candidate(val disc: Disc, val score: Int, val kind: Int)

    // Color-aware, coarse scan: recognizes common white, black and red carrom pieces.
    // Coordinates are mapped back to the original screen for the overlay.
    fun detect(source: Bitmap): List<Disc> {
        val targetW = 480
        val scale = source.width.toFloat() / targetW
        val targetH = max(1, (source.height / scale).roundToInt())
        val small = Bitmap.createScaledBitmap(source, targetW, targetH, true)
        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        small.recycle()

        val minR = max(6, w / 72)
        val maxR = max(minR + 2, w / 32)
        val step = max(3, w / 120)
        val x0 = (w * 0.07f).toInt()
        val x1 = (w * 0.93f).toInt()
        val y0 = (h * 0.12f).toInt()
        val y1 = (h * 0.88f).toInt()
        val candidates = ArrayList<Candidate>()

        fun pieceColor(c: Int): Int {
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            val maxC = max(r, max(g, b)); val minC = min(r, min(g, b))
            return when {
                maxC > 185 && maxC - minC < 38 -> 1 // white
                maxC < 78 -> 2 // black
                r > 105 && r > g * 1.35 && r > b * 1.25 -> 3 // red
                else -> 0
            }
        }

        var y = y0 + maxR
        while (y < y1 - maxR) {
            var x = x0 + maxR
            while (x < x1 - maxR) {
                val kind = pieceColor(pixels[y * w + x])
                if (kind != 0) {
                    var bestRadius = 0
                    var bestScore = 0
                    var radius = minR
                    while (radius <= maxR) {
                        var same = 0
                        var contrast = 0
                        for (a in 0 until 16) {
                            val angle = a * (Math.PI / 8.0)
                            val ix = x + (cos(angle) * radius).roundToInt()
                            val iy = y + (sin(angle) * radius).roundToInt()
                            val ox = x + (cos(angle) * (radius * 0.55f)).roundToInt()
                            val oy = y + (sin(angle) * (radius * 0.55f)).roundToInt()
                            if (ix in 0 until w && iy in 0 until h && ox in 0 until w && oy in 0 until h) {
                                if (pieceColor(pixels[oy * w + ox]) == kind) same++
                                if (pieceColor(pixels[iy * w + ix]) != kind) contrast++
                            }
                        }
                        val score = same + contrast
                        if (same >= 10 && contrast >= 8 && score > bestScore) {
                            bestScore = score
                            bestRadius = radius
                        }
                        radius += 2
                    }
                    if (bestRadius > 0) {
                        val candidate = Disc(x * scale, y * scale, bestRadius * scale, kind)
                        if (candidates.none {
                                hypot(it.disc.x - candidate.x, it.disc.y - candidate.y) <
                                    min(it.disc.radius, candidate.radius) * 1.35f
                            }) {
                            candidates.add(Candidate(candidate, bestScore, kind))
                        }
                    }
                }
                x += step
            }
            y += step
        }
        // Accept pieces only inside the centered, approximately square play area.
        // This rejects most unrelated colored UI elements elsewhere on the phone screen.
        val boardSize = min(w * 0.94f, h * 0.94f)
        val boardLeft = (w - boardSize) / 2f
        val boardTop = (h - boardSize) / 2f
        val boardRight = boardLeft + boardSize
        val boardBottom = boardTop + boardSize
        val boardCandidates = candidates
            .sortedByDescending { it.score }
            .map { it.disc }
            .filter {
                it.x / scale >= boardLeft && it.x / scale <= boardRight &&
                it.y / scale >= boardTop && it.y / scale <= boardBottom
            }
            .take(25)

        // Fail closed: a phone screen without a carrom board must not produce marks.
        // Require multiple piece colors and a striker-like piece in the lower board area.
        val selected = candidates
            .sortedByDescending { it.score }
            .filter {
                it.disc.x / scale >= boardLeft && it.disc.x / scale <= boardRight &&
                it.disc.y / scale >= boardTop && it.disc.y / scale <= boardBottom
            }
            .take(25)
        val distinctKinds = selected.map { it.kind }.distinct().size
        val hasLowerPiece = selected.any {
            it.disc.y / scale > boardTop + boardSize * 0.68f
        }
        val xs = selected.map { it.disc.x / scale }
        val ys = selected.map { it.disc.y / scale }
        val spreadX = if (xs.isEmpty()) 0f else xs.maxOrNull()!! - xs.minOrNull()!!
        val spreadY = if (ys.isEmpty()) 0f else ys.maxOrNull()!! - ys.minOrNull()!!
        if (selected.size < 6 || distinctKinds < 2 || !hasLowerPiece ||
            spreadX > boardSize * 0.82f || spreadY > boardSize * 0.82f) return emptyList()
        return selected.map { it.disc }
    }
}

object CarromPhysics {
    data class Ray(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val discIndex: Int = 0)
    data class Result(val paths: List<Ray>, val finalDiscs: List<Disc>)

    // Multi-body 2D simulation: equal-mass impacts, cushion restitution and rolling friction.
    // The initial shot is inferred from the detected striker toward the nearest object coin.
    fun simulate(discs: List<Disc>, left: Float, top: Float, right: Float, bottom: Float): Result {
        if (discs.size < 2) return Result(emptyList(), discs)
        val strikerIndex = discs.indices.maxByOrNull { discs[it].y } ?: return Result(emptyList(), discs)
        val targetIndex = discs.indices.filter { it != strikerIndex }
            .minByOrNull { hypot(discs[it].x - discs[strikerIndex].x, discs[it].y - discs[strikerIndex].y) }
            ?: return Result(emptyList(), discs)

        val n = discs.size
        val x = FloatArray(n) { discs[it].x }
        val y = FloatArray(n) { discs[it].y }
        val vx = FloatArray(n)
        val vy = FloatArray(n)
        val radius = FloatArray(n) { discs[it].radius }
        val paths = ArrayList<Ray>()
        val lastX = x.copyOf()
        val lastY = y.copyOf()

        val dx = x[targetIndex] - x[strikerIndex]
        val dy = y[targetIndex] - y[strikerIndex]
        val len = hypot(dx, dy).coerceAtLeast(1f)
        // Screen-space speed is scaled to board size, not device resolution.
        val speed = (right - left) * 1.15f
        vx[strikerIndex] = dx / len * speed
        vy[strikerIndex] = dy / len * speed

        val dt = 1f / 60f
        val friction = 0.992f
        val restitution = 0.86f
        val maxSteps = 900
        for (step in 0 until maxSteps) {
            var energy = 0f
            for (i in 0 until n) {
                if (abs(vx[i]) + abs(vy[i]) < 2f) { vx[i] = 0f; vy[i] = 0f; continue }
                x[i] += vx[i] * dt
                y[i] += vy[i] * dt
                if (x[i] < left + radius[i]) { x[i] = left + radius[i]; vx[i] = abs(vx[i]) * restitution }
                if (x[i] > right - radius[i]) { x[i] = right - radius[i]; vx[i] = -abs(vx[i]) * restitution }
                if (y[i] < top + radius[i]) { y[i] = top + radius[i]; vy[i] = abs(vy[i]) * restitution }
                if (y[i] > bottom - radius[i]) { y[i] = bottom - radius[i]; vy[i] = -abs(vy[i]) * restitution }
            }

            // Resolve every overlapping pair; several passes stabilize dense clusters.
            repeat(3) {
                for (i in 0 until n) for (j in i + 1 until n) {
                    var ox = x[j] - x[i]
                    var oy = y[j] - y[i]
                    val minDist = radius[i] + radius[j]
                    val d2 = ox * ox + oy * oy
                    if (d2 >= minDist * minDist) continue
                    val d = sqrt(d2).coerceAtLeast(0.01f)
                    val nx = ox / d; val ny = oy / d
                    val overlap = minDist - d
                    x[i] -= nx * overlap * 0.5f; y[i] -= ny * overlap * 0.5f
                    x[j] += nx * overlap * 0.5f; y[j] += ny * overlap * 0.5f
                    val rel = (vx[i] - vx[j]) * nx + (vy[i] - vy[j]) * ny
                    if (rel > 0f) {
                        val impulse = rel * restitution
                        vx[i] -= impulse * nx; vy[i] -= impulse * ny
                        vx[j] += impulse * nx; vy[j] += impulse * ny
                    }
                }
            }

            for (i in 0 until n) {
                vx[i] *= friction; vy[i] *= friction
                energy += abs(vx[i]) + abs(vy[i])
                if (step % 8 == 0 && (abs(x[i] - lastX[i]) + abs(y[i] - lastY[i]) > 2f)) {
                    paths.add(Ray(lastX[i], lastY[i], x[i], y[i], i))
                    lastX[i] = x[i]; lastY[i] = y[i]
                }
            }
            if (energy < 8f) break
        }
        for (i in 0 until n) {
            if (abs(x[i] - lastX[i]) + abs(y[i] - lastY[i]) > 1f)
                paths.add(Ray(lastX[i], lastY[i], x[i], y[i], i))
        }
        return Result(paths, (0 until n).map { Disc(x[it], y[it], radius[it], discs[it].kind) })
    }

    fun predict(discs: List<Disc>, left: Float, top: Float, right: Float, bottom: Float): List<Ray> =
        simulate(discs, left, top, right, bottom).paths
}

class AimOverlay(context: Context) : View(context) {
    private val discPaint = Paint(3).apply { color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val pieceFill = Paint(3).apply { style = Paint.Style.FILL; alpha = 105 }
    private val pieceStroke = Paint(3).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val strikerPaint = Paint(3).apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val pathPaint = Paint(3).apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val textPaint = Paint(3).apply { color = Color.WHITE; textSize = 28f; setShadowLayer(5f, 1f, 1f, Color.BLACK) }
    @Volatile private var discs: List<Disc> = emptyList()
    private var screenW = 1f; private var screenH = 1f
    fun update(items: List<Disc>, w: Int, h: Int) { discs = items; screenW = w.toFloat(); screenH = h.toFloat(); postInvalidate() }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val list = discs
        // Fail closed: never draw circles or paths unless a plausible board position exists.
        // Keep the overlay completely invisible until a plausible board is detected.
        if (list.size < 6) return
        list.forEach {
            val color = when (it.kind) { 1 -> Color.WHITE; 2 -> Color.BLACK; 3 -> Color.RED; else -> Color.CYAN }
            pieceFill.color = color
            pieceStroke.color = if (it.kind == 2) Color.WHITE else color
            c.drawCircle(it.x, it.y, it.radius, pieceFill)
            c.drawCircle(it.x, it.y, it.radius, pieceStroke)
        }
        val striker = list.maxByOrNull { it.y }
        if (striker != null) c.drawCircle(striker.x, striker.y, striker.radius + 5f, strikerPaint)
        val simulation = CarromPhysics.simulate(list, screenW * .03f, screenH * .08f, screenW * .97f, screenH * .92f)
        simulation.paths.forEach { c.drawLine(it.x1, it.y1, it.x2, it.y2, pathPaint) }
        val ghostPaint = Paint(3).apply { color = Color.MAGENTA; style = Paint.Style.STROKE; strokeWidth = 2f; alpha = 150 }
        simulation.finalDiscs.forEach {
            ghostPaint.color = when (it.kind) { 1 -> Color.GREEN; 2 -> Color.LTGRAY; 3 -> Color.MAGENTA; else -> Color.MAGENTA }
            c.drawCircle(it.x, it.y, it.radius, ghostPaint)
        }
        c.drawText("مسارات تقديرية لكل الأقراص: ${list.size}", 20f, 38f, textPaint)
    }
}
