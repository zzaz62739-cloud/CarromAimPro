package com.carrom.rebuild

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "com.carrom.rebuild.START"
        const val ACTION_STOP = "com.carrom.rebuild.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "carrom_capture"
        private const val NOTIFICATION_ID = 7302
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var framesSeen = 0L
    // Require repeated positive detections before reporting a board. This is a
    // notification-only guard, not proof that the detector is calibrated.
    private var consecutiveBoardDetections = 0
    private var lastPublishedBoardState: Boolean? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || projection != null) return START_NOT_STICKY

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, buildNotification("بانتظار أول إطار شاشة"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("بانتظار أول إطار شاشة"))
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi
        worker = HandlerThread("CarromFrameReader").also { it.start() }
        workerHandler = Handler(worker!!.looper)

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ source ->
            var image: Image? = null
            try {
                image = source.acquireLatestImage()
                if (image != null) {
                    framesSeen++
                    lastFrameAt = SystemClock.elapsedRealtime()
                    // Run conservative vision once per ~30 frames to bound CPU and
                    // allocation pressure. No overlay and no touch interception.
                    if (framesSeen == 1L || framesSeen % 30L == 0L) {
                        val detection = BoardVision.detect(image)
                        consecutiveBoardDetections = if (detection.found) {
                            (consecutiveBoardDetections + 1).coerceAtMost(3)
                        } else {
                            0
                        }
                        // A single frame can contain transient UI effects or
                        // compression artifacts. Publish a positive result only
                        // after three sampled frames agree that a board is present.
                        val confirmed = consecutiveBoardDetections >= 3
                        val state = if (confirmed) true else if (detection.found) null else false
                        if (state != null && state != lastPublishedBoardState) {
                            lastPublishedBoardState = state
                            val text = if (state) {
                                "تم رصد لوحة محتملة • أقراص: ${detection.discs.size}"
                            } else {
                                "التقاط الشاشة يعمل • لم يتم تأكيد لوحة كاروم"
                            }
                            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            manager.notify(NOTIFICATION_ID, buildNotification(text))
                        }
                    }
                }
            } catch (_: IllegalStateException) {
                stopSelf()
            } finally {
                image?.close()
            }
        }, workerHandler)

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, workerHandler)

        display = projection?.createVirtualDisplay(
            "CarromRebuildCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            workerHandler
        )
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "اختبار التقاط الشاشة", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(message: String): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Carrom Rebuild")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Carrom Rebuild")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        display?.release()
        display = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        worker?.quitSafely()
        worker = null
        workerHandler = null
        super.onDestroy()
    }
}
