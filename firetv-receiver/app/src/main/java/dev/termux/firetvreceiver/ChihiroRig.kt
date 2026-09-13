package dev.termux.firetvreceiver

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.random.Random

class ChihiroRig(context: Context) : FrameLayout(context) {
  private val eyesOpen = layer(R.drawable.chihiro_eyes_open)
  private val eyesClosed = layer(R.drawable.chihiro_eyes_closed).apply { alpha = 0f }
  private val mouthClosed = layer(R.drawable.chihiro_mouth_closed)
  private val mouthSmall = layer(R.drawable.chihiro_mouth_small).apply { alpha = 0f }
  private val mouthOpen = layer(R.drawable.chihiro_mouth_a).apply { alpha = 0f }
  private val mouthRound = layer(R.drawable.chihiro_mouth_o).apply { alpha = 0f }
  private val handler = Handler(Looper.getMainLooper())
  private var smooth = 0f

  init {
    setLayerType(LAYER_TYPE_HARDWARE, null)
    addView(layer(R.drawable.chihiro_base))
    addView(layer(R.drawable.chihiro_brows_neutral))
    addView(eyesOpen); addView(eyesClosed)
    addView(mouthClosed); addView(mouthSmall); addView(mouthOpen); addView(mouthRound)
    scheduleBlink()
  }

  private fun layer(drawable: Int) = ImageView(context).apply {
    setImageResource(drawable)
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
  }

  fun setAudioLevel(level: Float) = post {
    smooth += (level.coerceIn(0f, 1f) - smooth) * .55f
    val state = when {
      smooth < .08f -> 0
      smooth < .23f -> 1
      smooth < .58f -> 2
      else -> 3
    }
    listOf(mouthClosed, mouthSmall, mouthOpen, mouthRound).forEachIndexed { index, image -> image.alpha = if (index == state) 1f else 0f }
  }

  private fun scheduleBlink() {
    handler.postDelayed({
      eyesOpen.alpha = 0f; eyesClosed.alpha = 1f
      handler.postDelayed({ eyesClosed.alpha = 0f; eyesOpen.alpha = 1f; scheduleBlink() }, 130)
    }, Random.nextLong(2600, 5200))
  }

  override fun onDetachedFromWindow() { handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }
}
