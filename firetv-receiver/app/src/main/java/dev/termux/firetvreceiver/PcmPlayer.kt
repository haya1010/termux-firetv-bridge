package dev.termux.firetvreceiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import java.util.concurrent.ArrayBlockingQueue

class PcmPlayer(private val sampleRate: Int = 16000) {
  private val queue = ArrayBlockingQueue<ByteArray>(12)
  @Volatile private var running = true
  private val track = AudioTrack.Builder()
    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
    .setBufferSizeInBytes(maxOf(8192, AudioTrack.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)*2))
    .setTransferMode(AudioTrack.MODE_STREAM).build()

  init {
    track.play()
    Thread({ while (running) queue.take().let { track.write(it,0,it.size,AudioTrack.WRITE_BLOCKING) } }, "TvPcmPlayer").start()
  }

  fun offer(base64: String) {
    val data = Base64.decode(base64,Base64.DEFAULT)
    if (!queue.offer(data)) { queue.poll(); queue.offer(data) }
  }

  fun release() { running=false; queue.offer(ByteArray(0)); try { track.stop() } catch (_:Throwable) {}; track.release() }
}
