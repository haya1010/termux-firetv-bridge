package dev.termux.firetvbridge

import android.app.*
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class VoiceBridgeService : Service() {
  companion object { const val START="voice.start"; const val STOP="voice.stop"; private const val CHANNEL="voice_bridge" }
  private val client=OkHttpClient.Builder().pingInterval(15,TimeUnit.SECONDS).build()
  private var socket:WebSocket?=null
  private var record:AudioRecord?=null
  @Volatile private var running=false

  override fun onBind(intent:Intent?):IBinder?=null
  override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
    if(intent?.action==STOP){ stopSelf(); return START_NOT_STICKY }
    if(running) return START_STICKY
    getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"ちひろ音声会話",NotificationManager.IMPORTANCE_LOW))
    startForeground(103,NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentTitle("ちひろと会話中").setContentText("スマホのマイク → Fire TV").setOngoing(true).build())
    connect(); return START_STICKY
  }

  private fun connect(){
    socket=client.newWebSocket(Request.Builder().url("ws://192.168.0.10:8080/ws?role=voice").build(),object:WebSocketListener(){
      override fun onOpen(ws:WebSocket,response:Response){ startMic() }
      override fun onFailure(ws:WebSocket,t:Throwable,response:Response?){ stopMic(); android.os.Handler(mainLooper).postDelayed({ connect() },1500) }
    })
  }

  private fun startMic(){
    if(running)return
    val rate=24000
    val min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
    record=AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(min*2,4096))
    running=true; record!!.startRecording()
    Thread({
      val pcm=ByteArray(960)
      while(running){
        val n=record?.read(pcm,0,pcm.size)?:-1
        if(n>0) socket?.send(JSONObject().put("type","voice-audio").put("value",Base64.encodeToString(pcm.copyOf(n),Base64.NO_WRAP)).toString())
      }
    },"VoiceMic").start()
  }

  private fun stopMic(){ running=false; try{record?.stop()}catch(_:Throwable){}; record?.release(); record=null }
  override fun onDestroy(){ stopMic(); socket?.close(1000,"stopped"); client.dispatcher.executorService.shutdown(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
}
