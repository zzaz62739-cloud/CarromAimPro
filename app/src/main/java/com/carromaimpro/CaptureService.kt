package com.carromaimpro

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.display.*
import android.media.*
import android.media.projection.*
import android.os.*
import android.view.*
import android.widget.*

class CaptureService : Service() {
    private var projection:MediaProjection?=null
    private var reader:ImageReader?=null
    private var display:VirtualDisplay?=null
    private var window:WindowManager?=null
    private var overlay: AimOverlay?=null
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val channel="carrom_aim"
        if(Build.VERSION.SDK_INT>=26){ val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager; nm.createNotificationChannel(NotificationChannel(channel,"CarromAimPro",NotificationManager.IMPORTANCE_LOW)) }
        val n=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,channel).setContentTitle("CarromAimPro يعمل").setSmallIcon(android.R.drawable.ic_menu_compass).build() else Notification.Builder(this).setContentTitle("CarromAimPro يعمل").setSmallIcon(android.R.drawable.ic_menu_compass).build()
        startForeground(7,n)
        val code=intent?.getIntExtra("resultCode",0) ?: 0
        val data=intent?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val mgr=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection=mgr.getMediaProjection(code,data)
        val dm=resources.displayMetrics
        reader=ImageReader.newInstance(dm.widthPixels,dm.heightPixels,PixelFormat.RGBA_8888,2)
        display=projection?.createVirtualDisplay("CarromAimProCapture",dm.widthPixels,dm.heightPixels,dm.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader?.surface,null,null)
        window=getSystemService(WINDOW_SERVICE) as WindowManager
        overlay=AimOverlay(this)
        val type=if(Build.VERSION.SDK_INT>=26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp=WindowManager.LayoutParams(360,180,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply { gravity=android.view.Gravity.TOP or android.view.Gravity.START; x=24; y=140 }
        window?.addView(overlay,lp)
        return START_NOT_STICKY
    }
    override fun onDestroy(){ display?.release(); reader?.close(); projection?.stop(); try{window?.removeView(overlay)}catch(_:Exception){}; super.onDestroy() }
}
class AimOverlay(context:Context):View(context) {
    private val p=Paint(3).apply{color=Color.YELLOW;strokeWidth=5f;style=Paint.Style.STROKE}
    private val dot=Paint(3).apply{color=Color.RED;style=Paint.Style.FILL}
    private var sx=0f; private var sy=0f; private var ex=0f; private var ey=0f; private var active=false
    override fun onDraw(c:Canvas){super.onDraw(c); if(active){c.drawLine(sx,sy,ex,ey,p);c.drawCircle(ex,ey,12f,dot)}; val info=Paint(3).apply{color=Color.WHITE;textSize=30f;setShadowLayer(4f,1f,1f,Color.BLACK)}; c.drawText("CarromAimPro • اسحب لرسم مسار التصويب",24f,48f,info)}
    override fun onTouchEvent(e:android.view.MotionEvent):Boolean { when(e.action){MotionEvent.ACTION_DOWN->{sx=e.x;sy=e.y;ex=e.x;ey=e.y;active=true;invalidate()};MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP->{ex=e.x;ey=e.y;invalidate()}}; return true }
}
