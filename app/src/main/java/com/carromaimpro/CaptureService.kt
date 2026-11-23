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

data class Disc(val x: Float, val y: Float, val radius: Float)

object DiscVision {
    private data class Candidate(val disc: Disc, val score: Int)

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
                        val candidate = Disc(x * scale, y * scale, bestRadius * scale)
                        if (candidates.none {
                                hypot(it.disc.x - candidate.x, it.disc.y - candidate.y) <
                                    min(it.disc.radius, candidate.radius) * 1.35f
                            }) {
                            candidates.add(Candidate(candidate, bestScore))
                        }
                    }
                }
                x += step
            }
            y += step
        }
        return candidates.sortedByDescending { it.score }.take(25).map { it.disc }
    }
}

object CarromPhysics {
    data class Ray(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    // Simple equal-mass 2D collision estimate with cushion reflections.
    // This is a visual estimate, not a calibrated game-engine simulation.
    fun predict(discs: List<Disc>, left: Float, top: Float, right: Float, bottom: Float): List<Ray> {
        if (discs.size < 2) return emptyList()
        val striker = discs.maxByOrNull { it.y } ?: return emptyList()
        val target = discs.filter { it !== striker }
            .minByOrNull { hypot(it.x - striker.x, it.y - striker.y) } ?: return emptyList()

        val dx = target.x - striker.x
        val dy = target.y - striker.y
        val length = hypot(dx, dy).coerceAtLeast(1f)
        val ux = dx / length
        val uy = dy / length
        val contactX = target.x - ux * (striker.radius + target.radius)
        val contactY = target.y - uy * (striker.radius + target.radius)
        val rays = ArrayList<Ray>()
        rays.add(Ray(striker.x, striker.y, contactX, contactY))

        var x = target.x
        var y = target.y
        var vx = ux
        var vy = uy
        val moving = discs.filter { it !== striker && it !== target }.toMutableList()
        val radius = target.radius
        repeat(5) {
            var eventT = Float.POSITIVE_INFINITY
            var eventType = 0 // 1=vertical wall, 2=horizontal wall, 3=disc
            var hitDisc: Disc? = null

            if (vx > 0f) {
                val t = (right - radius - x) / vx
                if (t > 1f && t < eventT) { eventT = t; eventType = 1; hitDisc = null }
            } else if (vx < 0f) {
                val t = (left + radius - x) / vx
                if (t > 1f && t < eventT) { eventT = t; eventType = 1; hitDisc = null }
            }
            if (vy > 0f) {
                val t = (bottom - radius - y) / vy
                if (t > 1f && t < eventT) { eventT = t; eventType = 2; hitDisc = null }
            } else if (vy < 0f) {
                val t = (top + radius - y) / vy
                if (t > 1f && t < eventT) { eventT = t; eventType = 2; hitDisc = null }
            }

            for (other in moving) {
                val ox = other.x - x
                val oy = other.y - y
                val projection = ox * vx + oy * vy
                if (projection <= 0f) continue
                val perp2 = ox * ox + oy * oy - projection * projection
                val sumR = radius + other.radius
                if (perp2 >= sumR * sumR) continue
                val t = projection - sqrt((sumR * sumR - perp2).coerceAtLeast(0f))
                if (t > 1f && t < eventT) { eventT = t; eventType = 3; hitDisc = other }
            }

            if (!eventT.isFinite()) return@repeat
            val nx = x + vx * eventT
            val ny = y + vy * eventT
            rays.add(Ray(x, y, nx, ny))
            x = nx; y = ny

            when (eventType) {
                1 -> vx = -vx
                2 -> vy = -vy
                3 -> {
                    val other = hitDisc ?: return@repeat
                    val cx = other.x - x
                    val cy = other.y - y
                    val cl = hypot(cx, cy).coerceAtLeast(1f)
                    val nxn = cx / cl
                    val nyn = cy / cl
                    val dot = vx * nxn + vy * nyn
                    vx -= dot * nxn
                    vy -= dot * nyn
                    moving.remove(other)
                }
            }
        }
        return rays
    }
}

class AimOverlay(context: Context) : View(context) {
    private val discPaint = Paint(3).apply { color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val strikerPaint = Paint(3).apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val pathPaint = Paint(3).apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val textPaint = Paint(3).apply { color = Color.WHITE; textSize = 28f; setShadowLayer(5f, 1f, 1f, Color.BLACK) }
    @Volatile private var discs: List<Disc> = emptyList()
    private var screenW = 1f; private var screenH = 1f
    fun update(items: List<Disc>, w: Int, h: Int) { discs = items; screenW = w.toFloat(); screenH = h.toFloat(); postInvalidate() }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val list = discs
        list.forEach { c.drawCircle(it.x, it.y, it.radius, discPaint) }
        val striker = list.maxByOrNull { it.y }
        if (striker != null) c.drawCircle(striker.x, striker.y, striker.radius + 5f, strikerPaint)
        val rays = CarromPhysics.predict(list, screenW * .06f, screenH * .12f, screenW * .94f, screenH * .88f)
        rays.forEach { c.drawLine(it.x1, it.y1, it.x2, it.y2, pathPaint) }
        c.drawText("CarromAimPro | أقراص مرصودة: ${list.size}", 20f, 38f, textPaint)
        if (list.isEmpty()) c.drawText("لم يتم تمييز الأقراص؛ تحقق من ظهور الطاولة", 20f, 76f, textPaint)
    }
}
