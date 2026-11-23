package com.carromaimpro

import android.app.*
import android.content.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*

class MainActivity : Activity() {
    private val requestCapture = 41
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28,36,28,28) }
        layout.addView(TextView(this).apply { text = "CarromAimPro 2.0\nمساعد التصويب العائم"; textSize = 24f })
        layout.addView(TextView(this).apply { text = "اسمح بالظهور فوق التطبيقات، ثم ابدأ التقاط الشاشة. اسحب المؤشر العائم لتحديد اتجاه التصويب."; textSize=16f; setPadding(0,24,0,24) })
        layout.addView(Button(this).apply { text="السماح بالنافذة العائمة"; setOnClickListener { if (!Settings.canDrawOverlays(this@MainActivity)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } })
        layout.addView(Button(this).apply { text="تشغيل المساعد"; setOnClickListener { if (!Settings.canDrawOverlays(this@MainActivity)) { Toast.makeText(this@MainActivity,"فعّل إذن النافذة العائمة أولاً",Toast.LENGTH_LONG).show(); return@setOnClickListener }; val mgr=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager; startActivityForResult(mgr.createScreenCaptureIntent(),requestCapture) } })
        layout.addView(Button(this).apply { text="إيقاف المساعد"; setOnClickListener { stopService(Intent(this@MainActivity,CaptureService::class.java)) } })
        setContentView(layout)
    }
    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) { super.onActivityResult(requestCode,resultCode,data); if(requestCode==requestCapture && resultCode==RESULT_OK && data!=null) { val i=Intent(this,CaptureService::class.java).putExtra("resultCode",resultCode).putExtra("data",data); if(Build.VERSION.SDK_INT>=26) startForegroundService(i) else startService(i); finish() } }
}
