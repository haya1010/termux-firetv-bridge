package dev.termux.firetvbridge

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale

class WakeWordService : Service(), RecognitionListener {
  companion object {
    const val START = "wake.start"
    const val STOP = "wake.stop"
    private const val CHANNEL = "wake_word"
    private const val NOTIFICATION = 102
    private const val REARM_DELAY_MS = 5_000L
    // VoiceBridgeService ends its bounded session after two minutes.
    private const val VOICE_SESSION_REARM_DELAY_MS = 125_000L
  }

  private var recognizer: SpeechRecognizer? = null
  private var cpuLock: PowerManager.WakeLock? = null
  private val client = OkHttpClient()
  private val handler = Handler()
  private var triggered = false
  private var running = false

  override fun onCreate() {
    super.onCreate()
    running = true
    createChannel()
    startForeground(NOTIFICATION, notification("「ちひろ」を待っています"))
    cpuLock = getSystemService(PowerManager::class.java)
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TermuxFireTv:WakeWord").apply { acquire() }
    startListening()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == STOP) stopSelf() else if (recognizer == null) startListening()
    return START_STICKY
  }

  private fun startListening() {
    if (!running || triggered || !SpeechRecognizer.isRecognitionAvailable(this)) return
    recognizer?.destroy()
    recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
      it.setRecognitionListener(this)
      it.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
      })
    }
  }

  private fun inspect(bundle: Bundle?) {
    val words = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    if (words.any { it.lowercase(Locale.JAPAN).let { s -> "ちひろ" in s || "チヒロ" in s || "chihiro" in s } }) {
      triggered = true
      recognizer?.cancel()
      notifyText("呼びかけを検出。Fire TVを起動中…")
      val request = Request.Builder().url("http://127.0.0.1:8080/wake")
        .post(ByteArray(0).toRequestBody(null)).build()
      client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { rearm() }
        override fun onResponse(call: Call, response: Response) {
          response.close()
          // Fire TV is now foreground; hand the microphone to the bounded
          // Realtime session. Do not run SpeechRecognizer concurrently.
          startForegroundService(Intent(this@WakeWordService, VoiceBridgeService::class.java)
            .setAction(VoiceBridgeService.START))
          rearm(VOICE_SESSION_REARM_DELAY_MS)
        }
      })
    }
  }

  private fun rearm(delayMs: Long = REARM_DELAY_MS) = handler.postDelayed({
    if (!running) return@postDelayed
    recognizer?.destroy()
    recognizer = null
    triggered = false
    notifyText("「ちひろ」を待っています")
    startListening()
  }, delayMs)

  override fun onPartialResults(partialResults: Bundle?) = inspect(partialResults)
  override fun onResults(results: Bundle?) { inspect(results); if (!triggered) startListening() }
  override fun onError(error: Int) { if (!triggered) android.os.Handler(mainLooper).postDelayed({ startListening() }, 700) }
  override fun onReadyForSpeech(params: Bundle?) = Unit
  override fun onBeginningOfSpeech() = Unit
  override fun onRmsChanged(rmsdB: Float) = Unit
  override fun onBufferReceived(buffer: ByteArray?) = Unit
  override fun onEndOfSpeech() = Unit
  override fun onEvent(eventType: Int, params: Bundle?) = Unit

  override fun onDestroy() {
    running = false
    handler.removeCallbacksAndMessages(null)
    recognizer?.destroy(); recognizer = null
    cpuLock?.takeIf { it.isHeld }?.release(); cpuLock = null
    super.onDestroy()
  }
  override fun onBind(intent: Intent?): IBinder? = null

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
      .createNotificationChannel(NotificationChannel(CHANNEL, "ちひろ ウェイク待受", NotificationManager.IMPORTANCE_LOW))
  }

  private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL)
    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
    .setContentTitle("Termux Fire TV Bridge")
    .setContentText(text)
    .setOngoing(true)
    .build()

  private fun notifyText(text: String) = getSystemService(NotificationManager::class.java)
    .notify(NOTIFICATION, notification(text))
}
