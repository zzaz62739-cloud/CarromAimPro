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
    private val tracker = DiscTracker()

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
                val detected = DiscVision.detect(frame)
                frame.recycle()
                val tracked = tracker.update(detected, now)
                handler.post { overlay?.update(tracked.discs, dm.widthPixels, dm.heightPixels, tracked.isMoving, DiscVision.currentBoardBounds()) }
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

class DiscTracker {
    data class Snapshot(val discs: List<Disc>, val isMoving: Boolean)
    private data class Track(var disc: Disc, var lastSeen: Long, var stableFrames: Int)
    private data class Match(val trackIndex: Int, val detectionIndex: Int, val distance: Float)
    private val tracks = ArrayList<Track>()

    @Synchronized
    fun update(detections: List<Disc>, timestamp: Long): Snapshot {
        // Match globally by shortest distance so one detection cannot be claimed by
        // multiple tracks when pieces cross or briefly overlap.
        val matches = ArrayList<Match>()
        for (ti in tracks.indices) {
            val track = tracks[ti]
            for (di in detections.indices) {
                val detected = detections[di]
                if (detected.kind != track.disc.kind) continue
                val distance = hypot(detected.x - track.disc.x, detected.y - track.disc.y)
                val gate = max(22f, track.disc.radius * 2.8f)
                if (distance <= gate) matches.add(Match(ti, di, distance))
            }
        }
        matches.sortBy { it.distance }
        val usedTracks = HashSet<Int>()
        val usedDetections = HashSet<Int>()
        var moving = false

        for (match in matches) {
            if (!usedTracks.add(match.trackIndex) || !usedDetections.add(match.detectionIndex)) continue
            val track = tracks[match.trackIndex]
            val next = detections[match.detectionIndex]
            val elapsed = (timestamp - track.lastSeen).coerceAtLeast(1L) / 1000f
            val distance = hypot(next.x - track.disc.x, next.y - track.disc.y)
            val speed = distance / elapsed
            if (speed >= 7.5f) {
                track.stableFrames = 0
                moving = true
            } else {
                track.stableFrames++
            }
            val blend = if (speed >= 7.5f) 0.78f else 0.32f
            track.disc = Disc(
                track.disc.x + (next.x - track.disc.x) * blend,
                track.disc.y + (next.y - track.disc.y) * blend,
                track.disc.radius * 0.65f + next.radius * 0.35f,
                next.kind
            )
            track.lastSeen = timestamp
        }

        for (i in detections.indices) {
            if (i !in usedDetections) tracks.add(Track(detections[i], timestamp, 0))
        }
        tracks.removeAll { timestamp - it.lastSeen > 1400L }
        val visibleTracks = tracks.filter { timestamp - it.lastSeen < 900L }
        val visible = visibleTracks.map { it.disc }
        // Wait for three consecutive quiet observations before drawing a prediction.
        val unsettled = visibleTracks.any { it.stableFrames < 3 }
        return Snapshot(visible, moving || unsettled)
    }
}

object DiscVision {
    @Volatile private var detectedBoard: RectF? = null
    fun currentBoardBounds(): RectF? = detectedBoard?.let { RectF(it) }
    private data class Candidate(val disc: Disc, val score: Int, val kind: Int)

    private fun centeredBoard(w: Int, h: Int): RectF {
        val side = min(w * 0.94f, h * 0.94f)
        val cx = w / 2f; val cy = h / 2f
        return RectF(cx-side/2f, cy-side/2f, cx+side/2f, cy+side/2f)
    }

    private fun findBoardBounds(px: IntArray, w: Int, h: Int): RectF? {
        val step = max(2, w / 240)
        fun orange(c: Int): Boolean {
            val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c)
            return r>125 && g>45 && g<185 && b<105 && r>g*1.22f && r-b>65
        }
        val rows=IntArray(h)
        var y=0
        while(y<h){ var x=0; while(x<w){ if(orange(px[y*w+x])) rows[y]++; x+=step }; y+=step }
        val rowThreshold=max(3,w/(step*24))
        var top=-1; var bottom=-1; y=0
        while(y<h){ if(rows[y]>=rowThreshold){if(top<0)top=y;bottom=y}; y+=step }
        if(top<0 || bottom-top<h*0.18f)return null
        val colThreshold=max(3,(bottom-top)/(step*24))
        var left=-1; var right=-1; var x=0
        while(x<w){ var hits=0; var yy=top; while(yy<=bottom){if(orange(px[yy*w+x]))hits++;yy+=step}; if(hits>=colThreshold){if(left<0)left=x;right=x};x+=step }
        if(left<0 || right-left<w*0.20f)return null
        val bw=(right-left).toFloat(); val bh=(bottom-top).toFloat()
        if(bw/bh !in 0.72f..1.38f)return null
        val side=min(bw,bh); val cx=(left+right)/2f; val cy=(top+bottom)/2f
        val inset=side*0.018f
        return RectF(max(0f,cx-side/2f+inset),max(0f,cy-side/2f+inset),
            min(w.toFloat(),cx+side/2f-inset),min(h.toFloat(),cy+side/2f-inset))
    }

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

        val board = findBoardBounds(pixels, w, h) ?: centeredBoard(w, h)
        detectedBoard = RectF(board.left*scale, board.top*scale, board.right*scale, board.bottom*scale)
        val minR = max(6, w / 72)
        val maxR = max(minR + 2, w / 32)
        val step = max(3, w / 120)
        val x0 = max(0, board.left.toInt())
        val x1 = min(w, board.right.toInt())
        val y0 = max(0, board.top.toInt())
        val y1 = min(h, board.bottom.toInt())
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
        // Reject HUD elements outside the board detected in the captured frame.
        val selected = candidates.sortedByDescending { it.score }.filter {
            it.disc.x/scale >= board.left && it.disc.x/scale <= board.right &&
            it.disc.y/scale >= board.top && it.disc.y/scale <= board.bottom
        }.take(25)
        val distinctKinds = selected.map { it.kind }.distinct().size
        val boardHeight = board.height()
        val hasLowerPiece = selected.any { it.disc.y/scale > board.top + boardHeight*0.68f }
        val xs = selected.map { it.disc.x/scale }; val ys = selected.map { it.disc.y/scale }
        val spreadX = if(xs.isEmpty()) 0f else xs.maxOrNull()!!-xs.minOrNull()!!
        val spreadY = if(ys.isEmpty()) 0f else ys.maxOrNull()!!-ys.minOrNull()!!
        if(selected.size<6 || distinctKinds<2 || !hasLowerPiece ||
            spreadX>board.width()*0.82f || spreadY>board.height()*0.82f) return emptyList()
        // Mark the largest white piece in the lower baseline region as the striker.
        val white = selected.filter { it.kind==1 && it.disc.y/scale > board.top+boardHeight*0.64f }
        val striker = white.maxByOrNull { it.disc.radius }
        return selected.map { if(striker!=null && it===striker)
            Disc(it.disc.x,it.disc.y,it.disc.radius,4) else it.disc }

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
    @Volatile private var piecesMoving: Boolean = false
    private var screenW = 1f; private var screenH = 1f
    @Volatile private var boardBounds: RectF? = null
    fun update(items: List<Disc>, w: Int, h: Int, moving: Boolean, board: RectF?) { discs = items; screenW = w.toFloat(); screenH = h.toFloat(); boardBounds = board; piecesMoving = moving; postInvalidate() }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val list = discs
        // Fail closed: never draw circles or paths unless a plausible board position exists.
        // Keep the overlay completely invisible until a plausible board is detected.
        val board = boardBounds ?: return
        if (list.size < 6) return
        c.drawRect(board, Paint(3).apply { color = 0x88FFD700.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f })
        list.forEach {
            val color = when (it.kind) { 1, 4 -> Color.WHITE; 2 -> Color.BLACK; 3 -> Color.RED; else -> Color.CYAN }
            pieceFill.color = color
            pieceStroke.color = if (it.kind == 2) Color.WHITE else color
            c.drawCircle(it.x, it.y, it.radius, pieceFill)
            c.drawCircle(it.x, it.y, it.radius, pieceStroke)
        }
        val striker = list.maxByOrNull { it.y }
        if (striker != null) c.drawCircle(striker.x, striker.y, striker.radius + 5f, strikerPaint)
        if (piecesMoving) {
            c.drawText("تتبع حركة الأقراص...", 20f, 38f, textPaint)
            return
        }
        val simulation = CarromPhysics.simulate(list, board.left, board.top, board.right, board.bottom)
        simulation.paths.forEach { c.drawLine(it.x1, it.y1, it.x2, it.y2, pathPaint) }
        val ghostPaint = Paint(3).apply { color = Color.MAGENTA; style = Paint.Style.STROKE; strokeWidth = 2f; alpha = 150 }
        simulation.finalDiscs.forEach {
            ghostPaint.color = when (it.kind) { 1 -> Color.GREEN; 2 -> Color.LTGRAY; 3 -> Color.MAGENTA; else -> Color.MAGENTA }
            c.drawCircle(it.x, it.y, it.radius, ghostPaint)
        }
        c.drawText("مسارات تقديرية لكل الأقراص: ${list.size}", 20f, 38f, textPaint)
    }
}
