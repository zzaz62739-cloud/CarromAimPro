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
    // Downsample before circle scoring so live analysis remains lightweight on phones.
    fun detect(source: Bitmap): List<Disc> {
        val targetW = 540
        val scale = source.width.toFloat() / targetW
        val targetH = max(1, (source.height / scale).roundToInt())
        val small = Bitmap.createScaledBitmap(source, targetW, targetH, true)
        val w = small.width; val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()
        val gray = IntArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            gray[i] = ((Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100)
        }
        val minR = max(5, w / 95); val maxR = max(minR + 2, w / 30)
        val found = ArrayList<Disc>()
        val x0 = (w * 0.06f).toInt(); val x1 = (w * 0.94f).toInt()
        val y0 = (h * 0.12f).toInt(); val y1 = (h * 0.88f).toInt()
        var y = y0 + maxR
        while (y < y1 - maxR) {
            var x = x0 + maxR
            while (x < x1 - maxR) {
                val center = gray[y * w + x]
                // Ignore flat wood/background pixels; only score plausible piece interiors.
                if (center < 238) {
                    var bestR = 0; var bestScore = 0
                    var radius = minR
                    while (radius <= maxR) {
                        var score = 0
                        for (a in 0 until 12) {
                            val angle = a * (Math.PI / 6.0)
                            val ox = x + (cos(angle) * (radius - 2)).roundToInt()
                            val oy = y + (sin(angle) * (radius - 2)).roundToInt()
                            val ix = x + (cos(angle) * radius).roundToInt()
                            val iy = y + (sin(angle) * radius).roundToInt()
                            if (ix in 0 until w && iy in 0 until h && ox in 0 until w && oy in 0 until h &&
                                abs(gray[iy * w + ix] - gray[oy * w + ox]) > 26) score++
                        }
                        if (score > bestScore) { bestScore = score; bestR = radius }
                        radius += 2
                    }
                    if (bestScore >= 8 && bestR > 0) {
                        val candidate = Disc(x * scale, y * scale, bestR * scale)
                        if (found.none { hypot(it.x - candidate.x, it.y - candidate.y) < min(it.radius, candidate.radius) * 1.45f })
                            found.add(candidate)
                    }
                }
                x += max(3, minR / 2)
            }
            y += max(3, minR / 2)
        }
        return found.sortedByDescending { it.radius }.take(25)
    }
}

object CarromPhysics {
    data class Ray(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
    fun predict(discs: List<Disc>, left: Float, top: Float, right: Float, bottom: Float): List<Ray> {
        if (discs.size < 2) return emptyList()
        val striker = discs.maxByOrNull { it.y } ?: return emptyList()
        val target = discs.filter { it !== striker }.minByOrNull { hypot(it.x - striker.x, it.y - striker.y) } ?: return emptyList()
        val dx = target.x - striker.x; val dy = target.y - striker.y
        val length = hypot(dx, dy).coerceAtLeast(1f)
        val ux = dx / length; val uy = dy / length
        val contactX = target.x - ux * (striker.radius + target.radius)
        val contactY = target.y - uy * (striker.radius + target.radius)
        val rays = ArrayList<Ray>()
        rays.add(Ray(striker.x, striker.y, contactX, contactY))
        // Approximate straight post-impact target motion, stopping at the first board boundary.
        val tx = target.x; val ty = target.y
        val wallT = listOf(
            if (ux > 0f) (right - target.radius - tx) / ux else if (ux < 0f) (left + target.radius - tx) / ux else Float.POSITIVE_INFINITY,
            if (uy > 0f) (bottom - target.radius - ty) / uy else if (uy < 0f) (top + target.radius - ty) / uy else Float.POSITIVE_INFINITY
        ).filter { it > 0f }.minOrNull() ?: 0f
        if (wallT > 0f && wallT.isFinite()) rays.add(Ray(tx, ty, tx + ux * wallT, ty + uy * wallT))
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
