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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
        }
        layout.addView(TextView(this).apply {
            text = "CarromAimPro\nمساعد كاروم"
            textSize = 27f
        })
        layout.addView(TextView(this).apply {
            text = "طريقة التشغيل:\n1) اسمح بالظهور فوق التطبيقات.\n2) اضغط تشغيل المساعد ووافق على التقاط الشاشة.\n3) افتح Carrom Pool واترك الطاولة ظاهرة.\nسيحاول المساعد تمييز الأقراص ورسم مسار تقديري فوق اللعبة."
            textSize = 16f
            setPadding(0, 24, 0, 24)
        })
        layout.addView(Button(this).apply {
            text = "السماح بالنافذة العائمة"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else Toast.makeText(this@MainActivity, "الإذن مفعّل", Toast.LENGTH_SHORT).show()
            }
        })
        layout.addView(Button(this).apply {
            text = "تشغيل المساعد"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "فعّل إذن النافذة العائمة أولاً", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), requestCapture)
            }
        })
        layout.addView(Button(this).apply {
            text = "إيقاف المساعد"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                Toast.makeText(this@MainActivity, "تم طلب إيقاف المساعد", Toast.LENGTH_SHORT).show()
            }
        })
        setContentView(layout)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCapture) {
            if (resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(this, CaptureService::class.java)
                    .putExtra("resultCode", resultCode)
                    .putExtra("data", data)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent)
                else startService(serviceIntent)
                Toast.makeText(this, "بدأ تشغيل المساعد", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "لم تتم الموافقة على التقاط الشاشة", Toast.LENGTH_LONG).show()
            }
        }
    }
}
