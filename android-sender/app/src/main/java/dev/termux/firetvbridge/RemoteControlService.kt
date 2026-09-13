package dev.termux.firetvbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs
import kotlin.math.hypot

object RemoteFocusBus {
  @Volatile var listener: ((Rect, Int, Int, String) -> Unit)? = null
}

class RemoteControlService : AccessibilityService() {
  companion object { @Volatile var instance: RemoteControlService? = null }
  override fun onServiceConnected() { instance = this }
  override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
  override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
  override fun onInterrupt() = Unit

  fun handle(action: String) {
    Log.i("FireTvRemote", "remote action=$action")
    when (action) {
      "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
      "select" -> if (!clickFocused()) gesture(.5f, .5f, .5f, .5f, 80)
      "up" -> if (!moveFocus(0, -1)) gesture(.5f, .38f, .5f, .70f, 260)
      "down" -> if (!moveFocus(0, 1)) gesture(.5f, .70f, .5f, .38f, 260)
      "left" -> if (!moveFocus(-1, 0)) gesture(.35f, .5f, .70f, .5f, 260)
      "right" -> if (!moveFocus(1, 0)) gesture(.70f, .5f, .35f, .5f, 260)
    }
  }

  private fun moveFocus(dx: Int, dy: Int): Boolean {
    val root = rootInActiveWindow ?: return false
    val candidates = mutableListOf<AccessibilityNodeInfo>()
    collectActionable(root, candidates)
    if (candidates.isEmpty()) return false
    val current = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
    val display = resources.displayMetrics
    val origin = Rect().apply {
      if (current != null) current.getBoundsInScreen(this)
      else set(display.widthPixels/2, display.heightPixels/2, display.widthPixels/2, display.heightPixels/2)
    }
    val ox = origin.centerX().toFloat(); val oy = origin.centerY().toFloat()
    val best = candidates.asSequence().filter { it != current }.mapNotNull { node ->
      val r = Rect(); node.getBoundsInScreen(r)
      if (r.isEmpty) return@mapNotNull null
      val vx = r.centerX()-ox; val vy = r.centerY()-oy
      val forward = if (dx != 0) vx*dx else vy*dy
      if (forward <= 8f) return@mapNotNull null
      val cross = if (dx != 0) abs(vy) else abs(vx)
      node to (forward + cross*2.4f + hypot(vx,vy)*.15f)
    }.minByOrNull { it.second }?.first ?: return false
    val focused = best.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
    if (focused) {
      val bounds = Rect(); best.getBoundsInScreen(bounds)
      val label = (best.contentDescription ?: best.text ?: "").toString().take(80)
      RemoteFocusBus.listener?.invoke(bounds, display.widthPixels, display.heightPixels, label)
    }
    return focused
  }

  private fun collectActionable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
    if (!node.isVisibleToUser) return
    if ((node.isClickable || node.isFocusable || node.isEditable) && node.className?.toString() != "android.view.ViewGroup") out += node
    for (i in 0 until node.childCount) node.getChild(i)?.let { collectActionable(it, out) }
  }

  private fun clickFocused(): Boolean {
    val root = rootInActiveWindow ?: return false
    var node = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: return false
    repeat(5) {
      if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        RemoteFocusBus.listener?.invoke(Rect(), 1, 1, "__click__")
        return true
      }
      node = node.parent ?: return false
    }
    return false
  }

  private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
    val dm = resources.displayMetrics
    val path = Path().apply { moveTo(dm.widthPixels*x1, dm.heightPixels*y1); lineTo(dm.widthPixels*x2, dm.heightPixels*y2) }
    dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)
  }
}
