package dev.termux.firetvbridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
  private lateinit var server: EditText
  private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode != Activity.RESULT_OK || result.data == null) return@registerForActivityResult
    val intent = Intent(this, StreamingService::class.java).apply {
      action = StreamingService.START
      putExtra(StreamingService.EXTRA_URL, server.text.toString().trim())
      putExtra(StreamingService.EXTRA_RESULT_CODE, result.resultCode)
      putExtra(StreamingService.EXTRA_RESULT_DATA, result.data)
    }
    ContextCompat.startForegroundService(this, intent)
    Toast.makeText(this, "配信を開始しました。ChatGPTへ戻ってください", Toast.LENGTH_LONG).show()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    server = EditText(this).apply { setText("ws://192.168.0.10:8080/ws") }
    val start = Button(this).apply { text = "START"; setOnClickListener { requestCapture() } }
    val stop = Button(this).apply { text = "STOP"; setOnClickListener { startService(Intent(this@MainActivity, StreamingService::class.java).setAction(StreamingService.STOP)) } }
    val accessibility = Button(this).apply { text = "アクセシビリティ設定"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
    val wakeStart = Button(this).apply {
      text = "「ちひろ」待受を開始"
      setOnClickListener { ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, WakeWordService::class.java).setAction(WakeWordService.START)) }
    }
    val wakeStop = Button(this).apply {
      text = "ウェイク待受を停止"
      setOnClickListener { startService(Intent(this@MainActivity, WakeWordService::class.java).setAction(WakeWordService.STOP)) }
    }
    val note = TextView(this).apply { text = "1) アクセシビリティを有効化\n2) START（初回だけ画面共有を許可）\n3) ウェイク待受を開始\n4) ChatGPTへ戻る"; textSize = 18f }
    setContentView(LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL; setPadding(32,32,32,32)
      addView(note); addView(server); addView(accessibility); addView(start); addView(stop); addView(wakeStart); addView(wakeStop)
    })
    if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO), 10)
    else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
  }

  private fun requestCapture() {
    val manager = getSystemService(MediaProjectionManager::class.java)
    projection.launch(manager.createScreenCaptureIntent())
  }
}
