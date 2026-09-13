package dev.termux.firetvbridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioPlaybackCaptureConfiguration
import android.media.audiofx.Visualizer
import android.media.projection.MediaProjection
import android.os.*
import android.util.DisplayMetrics
import android.util.Base64
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class StreamingService : Service() {
  companion object {
    const val START = "bridge.START"
    const val STOP = "bridge.STOP"
    const val EXTRA_URL = "url"
    const val EXTRA_RESULT_CODE = "resultCode"
    const val EXTRA_RESULT_DATA = "resultData"
    private const val CHANNEL = "bridge_stream"
  }

  private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
  private var socket: WebSocket? = null
  private var factory: PeerConnectionFactory? = null
  private var peer: PeerConnection? = null
  private var capturer: ScreenCapturerAndroid? = null
  private var helper: SurfaceTextureHelper? = null
  private var videoTrack: VideoTrack? = null
  private var visualizer: Visualizer? = null
  private var playbackRecord: AudioRecord? = null
  @Volatile private var audioRunning = false
  private var reconnect: Runnable? = null
  private val handler = Handler(Looper.getMainLooper())
  private var wsUrl = ""
  private val pendingIce = mutableListOf<IceCandidate>()

  override fun onBind(intent: Intent?) = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
    if (intent?.action != START) return START_NOT_STICKY
    // MediaProjection grants are single-use. A quick double tap used to create
    // two capturers, and the first projection's onStop then killed the second.
    if (capturer != null) return START_NOT_STICKY
    createChannel()
    startForeground(42, NotificationCompat.Builder(this, CHANNEL)
      .setSmallIcon(android.R.drawable.presence_video_online)
      .setContentTitle("Fire TV bridge is running")
      .setContentText("Tap STOP in the app to end screen sharing")
      .setOngoing(true).build())
    wsUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
    @Suppress("DEPRECATION") val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
    if (resultData == null || resultCode != Activity.RESULT_OK) { stopSelf(); return START_NOT_STICKY }
    initWebRtc(resultData)
    connect()
    if (!startPlaybackAudio()) startAudioMeter()
    return START_NOT_STICKY
  }

  private fun initWebRtc(permissionData: Intent) {
    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())
    val egl = EglBase.create()
    factory = PeerConnectionFactory.builder()
      .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
      .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
      .createPeerConnectionFactory()
    helper = SurfaceTextureHelper.create("ScreenCapture", egl.eglBaseContext)
    capturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
      override fun onStop() { stopSelf() }
    })
    val source = factory!!.createVideoSource(true)
    capturer!!.initialize(helper, this, source.capturerObserver)
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION") getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
    val scale = minOf(1f, 1280f / maxOf(metrics.widthPixels, metrics.heightPixels))
    val captureWidth = ((metrics.widthPixels * scale).roundToInt() / 2) * 2
    val captureHeight = ((metrics.heightPixels * scale).roundToInt() / 2) * 2
    capturer!!.startCapture(captureWidth, captureHeight, 30)
    videoTrack = factory!!.createVideoTrack("screen", source)
    RemoteFocusBus.listener = { rect, width, height, label ->
      val message = JSONObject().put("type", if (label == "__click__") "focus-click" else "focus")
      if (label != "__click__") message
        .put("x", rect.left.toDouble()/width).put("y", rect.top.toDouble()/height)
        .put("w", rect.width().toDouble()/width).put("h", rect.height().toDouble()/height)
        .put("screenAspect", width.toDouble()/height).put("label", label)
      send(message)
    }
  }

  private fun createPeer() {
    peer?.close()
    pendingIce.clear()
    val config = PeerConnection.RTCConfiguration(emptyList()).apply {
      sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
      continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }
    peer = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
      override fun onIceCandidate(c: IceCandidate) = send(JSONObject().put("type","ice").put("candidate", JSONObject().put("sdpMid",c.sdpMid).put("sdpMLineIndex",c.sdpMLineIndex).put("candidate",c.sdp)))
      override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = Unit
      override fun onSignalingChange(s: PeerConnection.SignalingState) = Unit
      override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) = Unit
      override fun onIceConnectionReceivingChange(v: Boolean) = Unit
      override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) = Unit
      override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) = Unit
      override fun onAddStream(s: MediaStream) = Unit
      override fun onRemoveStream(s: MediaStream) = Unit
      override fun onDataChannel(c: DataChannel) = Unit
      override fun onRenegotiationNeeded() = Unit
      override fun onAddTrack(r: RtpReceiver, streams: Array<out MediaStream>) = Unit
    })
    peer!!.addTrack(requireNotNull(videoTrack), listOf("screen-stream"))
  }

  private fun offer() {
    createPeer()
    peer!!.createOffer(object : SimpleSdpObserver() {
      override fun onCreateSuccess(sdp: SessionDescription) {
        peer?.setLocalDescription(object : SimpleSdpObserver() {
          override fun onSetSuccess() { sendSdp("offer", sdp) }
        }, sdp)
      }
    }, MediaConstraints())
  }

  private fun connect() {
    val separator = if (wsUrl.contains('?')) '&' else '?'
    val request = Request.Builder().url("$wsUrl${separator}role=sender").build()
    socket = client.newWebSocket(request, object : WebSocketListener() {
      override fun onOpen(ws: WebSocket, response: Response) = Unit
      override fun onMessage(ws: WebSocket, text: String) { handleMessage(JSONObject(text)) }
      override fun onClosed(ws: WebSocket, code: Int, reason: String) = scheduleReconnect()
      override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = scheduleReconnect()
    })
  }

  private fun handleMessage(message: JSONObject) {
    when (message.optString("type")) {
      "receiver-ready" -> offer()
      "peer" -> if (message.optString("value") == "receiver-ready") offer()
      "remote" -> RemoteControlService.instance?.handle(message.optString("action"))
      "answer" -> {
        val sdp = message.getJSONObject("sdp")
        peer?.setRemoteDescription(object : SimpleSdpObserver() {
          override fun onSetSuccess() { pendingIce.forEach { peer?.addIceCandidate(it) }; pendingIce.clear() }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp.getString("sdp")))
      }
      "ice" -> {
        val c = message.getJSONObject("candidate")
        val candidate = IceCandidate(c.optString("sdpMid"), c.getInt("sdpMLineIndex"), c.getString("candidate"))
        if (peer?.remoteDescription == null) pendingIce += candidate else peer?.addIceCandidate(candidate)
      }
    }
  }

  private fun startAudioMeter() {
    try {
      visualizer = Visualizer(0).apply {
        captureSize = Visualizer.getCaptureSizeRange()[0]
        setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
          override fun onWaveFormDataCapture(v: Visualizer, data: ByteArray, rate: Int) {
            var sum = 0.0
            data.forEach { val x = (it.toInt() and 0xff) - 128; sum += x*x }
            val rms = kotlin.math.sqrt(sum / data.size) / 48.0
            send(JSONObject().put("type","audio-level").put("value", rms.coerceIn(0.0, 1.0)))
          }
          override fun onFftDataCapture(v: Visualizer, data: ByteArray, rate: Int) = Unit
        }, Visualizer.getMaxCaptureRate() / 4, true, false)
        enabled = true
      }
    } catch (_: Throwable) { /* Some vendors block output-mix capture; video/control still work. */ }
  }

  private fun startPlaybackAudio(): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    return try {
      val projection = capturer?.mediaProjection ?: return false
      val sampleRate = 16000
      val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
      val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
        .addMatchingUsage(AudioAttributes.USAGE_GAME)
        .build()
      val bufferSize = maxOf(4096, AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)*2)
      playbackRecord = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(bufferSize)
        .setAudioPlaybackCaptureConfig(capture).build()
      audioRunning = true
      Thread({
        try {
          playbackRecord?.startRecording()
          send(JSONObject().put("type","audio-status").put("value","tv-audio-active"))
          val pcm = ByteArray(640) // 20 ms, 16 kHz, mono PCM16
          while (audioRunning) {
            val count = playbackRecord?.read(pcm,0,pcm.size) ?: break
            if (count <= 0) continue
            var sum = 0.0; var i = 0
            while (i+1 < count) { val sample = ((pcm[i+1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt(); sum += sample.toDouble()*sample; i += 2 }
            val level = (kotlin.math.sqrt(sum/maxOf(1,count/2))/6500.0).coerceIn(0.0,1.0)
            send(JSONObject().put("type","audio-pcm").put("sampleRate",sampleRate).put("value",Base64.encodeToString(pcm.copyOf(count),Base64.NO_WRAP)).put("level",level))
          }
        } catch (_: Throwable) {
          send(JSONObject().put("type","audio-status").put("value","phone-audio-fallback"))
          handler.post { startAudioMeter() }
        }
      }, "PlaybackCapture").start()
      true
    } catch (_: Throwable) { false }
  }

  private fun sendSdp(type: String, sdp: SessionDescription) = send(JSONObject().put("type",type).put("sdp",JSONObject().put("type",sdp.type.canonicalForm()).put("sdp",sdp.description)))
  private fun send(json: JSONObject) { socket?.send(json.toString()) }
  private fun scheduleReconnect() { reconnect?.let(handler::removeCallbacks); reconnect = Runnable { connect() }; handler.postDelayed(reconnect!!, 1500) }

  override fun onDestroy() {
    reconnect?.let(handler::removeCallbacks)
    audioRunning = false
    try { playbackRecord?.stop() } catch (_: Throwable) {}
    playbackRecord?.release(); visualizer?.release(); capturer?.stopCapture(); capturer?.dispose(); helper?.dispose()
    peer?.close(); factory?.dispose(); socket?.close(1000, "stopped"); client.dispatcher.executorService.shutdown()
    RemoteFocusBus.listener = null
    stopForeground(STOP_FOREGROUND_REMOVE)
    super.onDestroy()
  }

  private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(s: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(e: String) = Unit
    override fun onSetFailure(e: String) = Unit
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
      .createNotificationChannel(NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW))
  }
}
