package dev.termux.firetvreceiver

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.*
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
  private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
  private lateinit var renderer: SurfaceViewRenderer
  private lateinit var rig: ChihiroRig
  private lateinit var status: TextView
  private lateinit var cursor: TextView
  private lateinit var egl: EglBase
  private lateinit var factory: PeerConnectionFactory
  private var peer: PeerConnection? = null
  private lateinit var audioPlayer: PcmPlayer
  private var socket: WebSocket? = null
  private var serverUrl = "ws://192.168.0.10:8080/ws"
  private val pendingIce = mutableListOf<IceCandidate>()
  private var videoAreaWidth = 0
  private var videoAreaHeight = 0
  private var videoLeft = 0
  private var videoTop = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    serverUrl = intent.getStringExtra("server_url") ?: getPreferences(MODE_PRIVATE).getString("server_url", serverUrl)!!
    initWebRtc(); audioPlayer = PcmPlayer(); buildUi(); connect()
  }

  private fun initWebRtc() {
    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())
    egl = EglBase.create()
    factory = PeerConnectionFactory.builder()
      .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
      .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
      .createPeerConnectionFactory()
  }

  private fun buildUi() {
    renderer = SurfaceViewRenderer(this).apply {
      init(egl.eglBaseContext, null)
      setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
      setMirror(false)
      setEnableHardwareScaler(false)
      setZOrderMediaOverlay(false)
    }
    rig = ChihiroRig(this)
    status = TextView(this).apply { text = "接続中…"; textSize = 18f; setTextColor(Color.WHITE); setPadding(24,12,24,12); setBackgroundColor(0x99000000.toInt()) }
    val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    // The whole portrait phone frame is aspect-fitted to the TV. The surface
    // stays in the base layer so the character/cursor can be drawn above it.
    val screenW = resources.displayMetrics.widthPixels
    val screenH = resources.displayMetrics.heightPixels
    videoAreaHeight = (screenH * .90f).toInt()
    videoAreaWidth = (videoAreaHeight * .50f).toInt()
    videoLeft = (screenW - videoAreaWidth) / 2
    videoTop = (screenH - videoAreaHeight) / 2
    root.addView(renderer, FrameLayout.LayoutParams(videoAreaWidth, videoAreaHeight).apply {
      leftMargin = videoLeft; topMargin = videoTop
    })
    val avatarSize = (resources.displayMetrics.heightPixels * .62f).toInt()
    root.addView(rig, FrameLayout.LayoutParams(avatarSize, avatarSize, Gravity.END or Gravity.BOTTOM).apply { rightMargin = 28; bottomMargin = 8 })
    cursor = TextView(this).apply {
      setTextColor(Color.WHITE); textSize = 13f; setPadding(8,2,8,2); elevation = 20f
      background = GradientDrawable().apply { setColor(0x2233CCFF); setStroke(4, 0xff67e8ff.toInt()); cornerRadius = 10f }
      visibility = View.GONE
    }
    root.addView(cursor, FrameLayout.LayoutParams(80,56))
    root.addView(status, FrameLayout.LayoutParams(-2,-2,Gravity.START or Gravity.TOP).apply { leftMargin=24; topMargin=18 })
    setContentView(root)
  }

  private fun connect() {
    val separator = if (serverUrl.contains('?')) '&' else '?'
    val request = Request.Builder().url("$serverUrl${separator}role=receiver").build()
    socket = client.newWebSocket(request, object : WebSocketListener() {
      override fun onOpen(ws: WebSocket, response: Response) = showStatus("Androidを待っています")
      override fun onMessage(ws: WebSocket, text: String) = handle(JSONObject(text))
      override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { showStatus("再接続中…"); renderer.postDelayed({ connect() }, 1500) }
      override fun onClosed(ws: WebSocket, code: Int, reason: String) { showStatus("再接続中…"); renderer.postDelayed({ connect() }, 1500) }
    })
  }

  private fun makePeer() {
    peer?.close()
    pendingIce.clear()
    val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
    peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
      override fun onIceCandidate(c: IceCandidate) = send(JSONObject().put("type","ice").put("candidate",JSONObject().put("sdpMid",c.sdpMid).put("sdpMLineIndex",c.sdpMLineIndex).put("candidate",c.sdp)))
      override fun onAddTrack(r: RtpReceiver, streams: Array<out MediaStream>) { (r.track() as? VideoTrack)?.addSink(renderer) }
      override fun onConnectionChange(s: PeerConnection.PeerConnectionState) = showStatus("WebRTC: $s")
      override fun onSignalingChange(s: PeerConnection.SignalingState)=Unit
      override fun onIceConnectionChange(s: PeerConnection.IceConnectionState)=Unit
      override fun onIceConnectionReceivingChange(v:Boolean)=Unit
      override fun onIceGatheringChange(s: PeerConnection.IceGatheringState)=Unit
      override fun onIceCandidatesRemoved(c:Array<out IceCandidate>)=Unit
      override fun onAddStream(s:MediaStream)=Unit
      override fun onRemoveStream(s:MediaStream)=Unit
      override fun onDataChannel(c:DataChannel)=Unit
      override fun onRenegotiationNeeded()=Unit
    })
  }

  private fun handle(message: JSONObject) {
    when(message.optString("type")) {
      "offer" -> {
        makePeer()
        val remote = message.getJSONObject("sdp")
        peer!!.setRemoteDescription(object: Sdp() {
          override fun onSetSuccess() { pendingIce.forEach { peer?.addIceCandidate(it) }; pendingIce.clear(); peer!!.createAnswer(object: Sdp() {
            override fun onCreateSuccess(s: SessionDescription) { peer!!.setLocalDescription(object:Sdp(){ override fun onSetSuccess(){ sendSdp("answer",s) } },s) }
          }, MediaConstraints()) }
        }, SessionDescription(SessionDescription.Type.OFFER, remote.getString("sdp")))
      }
      "ice" -> { val c=message.getJSONObject("candidate"); val candidate=IceCandidate(c.optString("sdpMid"),c.getInt("sdpMLineIndex"),c.getString("candidate")); if(peer?.remoteDescription==null) pendingIce+=candidate else peer?.addIceCandidate(candidate) }
      "audio-level" -> rig.setAudioLevel(message.optDouble("value",0.0).toFloat())
      "audio-pcm" -> { rig.setAudioLevel(message.optDouble("level",0.0).toFloat()); audioPlayer.offer(message.getString("value")) }
      "audio-status" -> showStatus(message.optString("value"))
      "focus" -> showCursor(message)
      "focus-click" -> runOnUiThread { cursor.animate().scaleX(.82f).scaleY(.82f).setDuration(70).withEndAction { cursor.animate().scaleX(1f).scaleY(1f).setDuration(100) } }
      "peer" -> showStatus(message.optString("value"))
    }
  }

  private fun showCursor(message: JSONObject) = runOnUiThread {
    val screenW = resources.displayMetrics.widthPixels
    val screenH = resources.displayMetrics.heightPixels
    val aspect = message.optDouble("screenAspect", .5).toFloat()
    val contentW = minOf(videoAreaWidth.toFloat(), videoAreaHeight * aspect)
    val contentH = contentW / aspect
    val offsetX = videoLeft + (videoAreaWidth-contentW)/2f
    val offsetY = videoTop + (videoAreaHeight-contentH)/2f
    val left = offsetX + message.optDouble("x").toFloat()*contentW
    val top = offsetY + message.optDouble("y").toFloat()*contentH
    val width = maxOf(64f, message.optDouble("w").toFloat()*contentW)
    val height = maxOf(48f, message.optDouble("h").toFloat()*contentH)
    cursor.text = message.optString("label")
    cursor.layoutParams = FrameLayout.LayoutParams(width.toInt(),height.toInt()).apply { leftMargin=left.toInt(); topMargin=top.toInt() }
    cursor.visibility = View.VISIBLE; cursor.bringToFront()
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
    val action = when(event.keyCode) {
      KeyEvent.KEYCODE_DPAD_UP -> "up"; KeyEvent.KEYCODE_DPAD_DOWN -> "down"
      KeyEvent.KEYCODE_DPAD_LEFT -> "left"; KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
      KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "select"
      KeyEvent.KEYCODE_BACK -> "back"
      KeyEvent.KEYCODE_MENU -> { showServerDialog(); return true }
      else -> return super.dispatchKeyEvent(event)
    }
    send(JSONObject().put("type","remote").put("action",action)); return true
  }

  private fun showServerDialog() {
    val input=EditText(this).apply { setText(serverUrl); setSelectAllOnFocus(true) }
    AlertDialog.Builder(this).setTitle("Termux server URL").setView(input).setPositiveButton("保存して再接続") { _,_ ->
      serverUrl=input.text.toString().trim(); getPreferences(MODE_PRIVATE).edit().putString("server_url",serverUrl).apply(); socket?.close(1000,"reconfigure"); connect()
    }.setNegativeButton("キャンセル",null).show()
  }

  private fun sendSdp(type:String,s:SessionDescription)=send(JSONObject().put("type",type).put("sdp",JSONObject().put("type",s.type.canonicalForm()).put("sdp",s.description)))
  private fun send(j:JSONObject){ socket?.send(j.toString()) }
  private fun showStatus(value:String)=runOnUiThread { status.text=value; status.visibility=View.VISIBLE; status.postDelayed({ if(status.text==value) status.visibility=View.GONE },3000) }

  override fun onDestroy() { socket?.close(1000,"activity destroyed"); peer?.close(); audioPlayer.release(); renderer.release(); factory.dispose(); egl.release(); client.dispatcher.executorService.shutdown(); super.onDestroy() }
  private open class Sdp:SdpObserver { override fun onCreateSuccess(s:SessionDescription)=Unit; override fun onSetSuccess()=Unit; override fun onCreateFailure(e:String)=Unit; override fun onSetFailure(e:String)=Unit }
}
