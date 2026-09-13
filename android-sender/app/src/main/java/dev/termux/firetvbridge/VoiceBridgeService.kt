package dev.termux.firetvbridge

import android.app.*
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
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
  @Volatile private var micMuted=false
  private var echoCanceler:AcousticEchoCanceler?=null
  private var noiseSuppressor:NoiseSuppressor?=null
  private var echoReference:AudioTrack?=null

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
      override fun onMessage(ws:WebSocket,text:String){
        val message=JSONObject(text)
        if(message.optString("type")=="voice-gate") micMuted=message.optBoolean("muted",false)
        if(message.optString("type")=="echo-reference") {
          val pcm=Base64.decode(message.getString("value"),Base64.DEFAULT)
          echoReference?.write(pcm,0,pcm.size,AudioTrack.WRITE_NON_BLOCKING)
        }
      }
      override fun onFailure(ws:WebSocket,t:Throwable,response:Response?){ stopMic(); android.os.Handler(mainLooper).postDelayed({ connect() },1500) }
    })
  }

  private fun startMic(){
    if(running)return
    val rate=24000
    val min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
    record=AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(min*2,4096))
    getSystemService(AudioManager::class.java).mode=AudioManager.MODE_IN_COMMUNICATION
    echoReference=AudioTrack.Builder()
      .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
      .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
      .setBufferSizeInBytes(maxOf(4096,AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)*2))
      .setTransferMode(AudioTrack.MODE_STREAM).build().apply { setVolume(0f); play() }
    echoCanceler=if(AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record!!.audioSessionId)?.apply{enabled=true}else null
    noiseSuppressor=if(NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record!!.audioSessionId)?.apply{enabled=true}else null
    running=true; record!!.startRecording()
    Thread({
      val pcm=ByteArray(960)
      while(running){
        val n=record?.read(pcm,0,pcm.size)?:-1
        if(n>0 && !micMuted) socket?.send(JSONObject().put("type","voice-audio").put("value",Base64.encodeToString(pcm.copyOf(n),Base64.NO_WRAP)).toString())
      }
    },"VoiceMic").start()
  }

  private fun stopMic(){
    running=false; echoCanceler?.release(); echoCanceler=null; noiseSuppressor?.release(); noiseSuppressor=null
    try{echoReference?.stop()}catch(_:Throwable){}; echoReference?.release(); echoReference=null
    getSystemService(AudioManager::class.java).mode=AudioManager.MODE_NORMAL
    try{record?.stop()}catch(_:Throwable){}; record?.release(); record=null
  }
  override fun onDestroy(){ stopMic(); socket?.close(1000,"stopped"); client.dispatcher.executorService.shutdown(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
}
