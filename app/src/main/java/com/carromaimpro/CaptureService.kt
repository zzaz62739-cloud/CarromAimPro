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
        val n = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, channel).setContentTitle("CarromAimPro يحلل الطاولة").setSmallIcon(android.R.drawable.ic_menu_compass).build()
        else Notification.Builder(this).setContentTitle("CarromAimPro يحلل الطاولة").setSmallIcon(android.R.drawable.ic_menu_compass).build()
        startForeground(7, n)
        val code = intent?.getIntExtra("resultCode", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        val dm = resources.displayMetrics
        reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 3)
        reader?.setOnImageAvailableListener({ r ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysis < 450) { r.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            lastAnalysis = now
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width
                val full = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(buffer)
                val frame = Bitmap.createBitmap(full, 0, 0, image.width, image.height)
                full.recycle()
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
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
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
    // Finds high-contrast round game pieces using radial edge transitions.
    fun detect(bitmap: Bitmap): List<Disc> {
        val w = bitmap.width; val h = bitmap.height
        val left = (w * 0.06f).toInt(); val right = (w * 0.94f).toInt()
        val top = (h * 0.10f).toInt(); val bottom = (h * 0.90f).toInt()
        val step = 3
        val gray = IntArray(w * h)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            gray[i] = (((c shr 16 and 255) * 30 + (c shr 8 and 255) * 59 + (c and 255) * 11) / 100)
        }
        val found = ArrayList<Disc>()
        val minR = max(7, w / 105); val maxR = max(minR + 2, w / 28)
        var y = top + maxR
        while (y < bottom - maxR) {
            var x = left + maxR
            while (x < right - maxR) {
                val center = gray[y * w + x]
                if (center in 15..245) {
                    for (r in minR..maxR step 2) {
                        var edges = 0; var valid = 0
                        for (a in 0 until 16) {
                            val ang = a * (Math.PI / 8.0)
                            val xx = x + (cos(ang) * r).roundToInt()
                            val yy = y + (sin(ang) * r).roundToInt()
                            val ox = x + (cos(ang) * (r - 3)).roundToInt()
                            val oy = y + (sin(ang) * (r - 3)).roundToInt()
                            val v1 = gray[yy * w + xx]; val v0 = gray[oy * w + ox]
                            valid++
                            if (abs(v1 - v0) > 24) edges++
                        }
                        if (valid > 0 && edges >= 10) {
                            val candidate = Disc(x.toFloat(), y.toFloat(), r.toFloat())
                            if (found.none { hypot(it.x - candidate.x, it.y - candidate.y) < min(it.radius, candidate.radius) * 1.35f })
                                found.add(candidate)
                            break
                        }
                    }
                }
                x += step
            }
            y += step
        }
        return found.take(45)
    }
}

class AimOverlay(context: Context) : View(context) {
    private val discPaint = Paint(3).apply { color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val strikerPaint = Paint(3).apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val pathPaint = Paint(3).apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val textPaint = Paint(3).apply { color = Color.WHITE; textSize = 30f; setShadowLayer(5f, 1f, 1f, Color.BLACK) }
    @Volatile private var discs: List<Disc> = emptyList()
    private var screenW = 1; private var screenH = 1
    fun update(items: List<Disc>, w: Int, h: Int) { discs = items; screenW = w; screenH = h; invalidate() }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val list = discs
        list.forEach { c.drawCircle(it.x, it.y, it.radius, discPaint) }
        // The striker is estimated as the lowest detected round piece; nearest piece is the first target.
        val striker = list.maxByOrNull { it.y }
        if (striker != null) {
            c.drawCircle(striker.x, striker.y, striker.radius + 5f, strikerPaint)
            val target = list.filter { it !== striker && hypot(it.x - striker.x, it.y - striker.y) > striker.radius + it.radius }
                .minByOrNull { hypot(it.x - striker.x, it.y - striker.y) }
            if (target != null) {
                val dx = target.x - striker.x; val dy = target.y - striker.y
                val len = hypot(dx, dy).coerceAtLeast(1f)
                val ux = dx / len; val uy = dy / len
                val startX = striker.x + ux * striker.radius
                val startY = striker.y + uy * striker.radius
                val hitX = target.x - ux * target.radius
                val hitY = target.y - uy * target.radius
                c.drawLine(startX, startY, hitX, hitY, pathPaint)
                c.drawCircle(hitX, hitY, 7f, pathPaint)
            }
        }
        c.drawText("CarromAimPro | أقراص مرصودة: ${list.size}", 24f, 42f, textPaint)
        if (list.isEmpty()) c.drawText("لم يتم تمييز الأقراص بوضوح", 24f, 80f, textPaint)
    }
}
