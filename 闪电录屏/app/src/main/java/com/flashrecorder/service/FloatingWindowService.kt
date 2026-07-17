package com.flashrecorder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateRegistryOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.viewmodel.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.flashrecorder.util.C
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/**
 * 悬浮窗服务：ComposeView + OnTouchListener 拖拽 + 广播通信。
 * 顶部 grip 区域消费触摸用于拖动，下方按钮区域交给 Compose 处理点击。
 */
class FloatingWindowService : Service() {

    data class UiState(val stateName: String = "IDLE", val elapsedMs: Long = 0L)

    private val uiState = MutableStateFlow(UiState(stateName = RecordingService.currentState.name))

    private var windowView: ComposeView? = null
    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private val lifecycleOwner = FloatingLifecycleOwner()

    private var initialX = 0
    private var initialY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var dragging = false
    private var consume = false
    private val slop = 12
    private val gripHeightPx by lazy {
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics).toInt()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != C.ACTION_STATE) return
            val stateName = intent.getStringExtra(C.EXTRA_STATE) ?: return
            val elapsed = intent.getLongExtra(C.EXTRA_ELAPSED, 0L)
            uiState.value = UiState(stateName, elapsed)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> { removeWindow(); stopSelf() }
            else -> showWindow()
        }
        return START_NOT_STICKY
    }

    @Suppress("ClickableViewAccessibility")
    private fun showWindow() {
        if (windowView != null) return
        lifecycleOwner.create(); lifecycleOwner.start(); lifecycleOwner.resume()

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent { FloatingContent(uiState, ::onRecord, ::onHighlight, ::onClose) }
        }
        view.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = p.x; initialY = p.y
                    touchX = e.rawX; touchY = e.rawY
                    dragging = false
                    // 仅在顶部 grip 区域消费触摸用于拖动；其余区域交给 Compose 按钮
                    consume = e.y <= gripHeightPx
                    consume
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!consume) return@setOnTouchListener false
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) {
                        p.x = initialX + dx.toInt()
                        p.y = initialY + dy.toInt()
                        runCatching { wm?.updateViewLayout(view, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val was = consume; consume = false; dragging = false; was
                }
                else -> false
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 200
        }
        params = lp
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { wm?.addView(view, lp) }
        windowView = view

        registerReceiver(receiver, IntentFilter(C.ACTION_STATE), RECEIVER_NOT_EXPORTED)
    }

    private fun removeWindow() {
        runCatching { unregisterReceiver(receiver) }
        windowView?.let { v -> runCatching { wm?.removeView(v) } }
        windowView = null
        params = null
        lifecycleOwner.pause(); lifecycleOwner.stop(); lifecycleOwner.destroy()
    }

    private fun onRecord() {
        val s = uiState.value.stateName
        if (s == "STANDBY") C.sendCommand(this, C.ACTION_START_RECORD)
        else if (s == "RECORDING") C.sendCommand(this, C.ACTION_STOP_RECORD)
    }

    private fun onHighlight() {
        C.sendCommand(this, C.ACTION_TRIGGER_HIGHLIGHT)
    }

    private fun onClose() {
        removeWindow(); stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeWindow()
    }

    // ---------------- 悬浮窗 UI ----------------

    @Composable
    private fun FloatingContent(
        state: MutableStateFlow<UiState>,
        onRecord: () -> Unit,
        onHighlight: () -> Unit,
        onClose: () -> Unit,
    ) {
        val ui by state.collectAsState()
        val recording = ui.stateName == "RECORDING"
        val standby = ui.stateName == "STANDBY"
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFFF2F2F7))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // grip 拖拽条 + 状态点 + 计时
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(28.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFFC7C7CC))
                )
                Spacer(Modifier.width(8.dp))
                StatusDot(recording = recording, standby = standby)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatTime(ui.elapsedMs),
                    fontSize = 13.sp,
                    color = Color(0xFF1D1D1F),
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircleButton(
                    symbol = if (recording) "■" else "▶",
                    label = if (recording) "停止" else "录制",
                    tint = if (recording) Color(0xFFFF3B30) else Color(0xFF1D1D1F),
                    enabled = standby || recording,
                    onClick = onRecord,
                )
                CircleButton("✦", "高光", Color(0xFFFF9500), enabled = true, onClick = onHighlight)
                CircleButton("✕", "关闭", Color(0xFF1D1D1F), enabled = true, onClick = onClose)
            }
        }
    }

    @Composable
    private fun StatusDot(recording: Boolean, standby: Boolean) {
        val color = when {
            recording -> Color(0xFFFF3B30)
            standby -> Color(0xFFFF9500)
            else -> Color(0xFF86868B)
        }
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
    }

    @Composable
    private fun CircleButton(
        symbol: String,
        label: String,
        tint: Color,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (enabled) tint else Color(0xFFE5E5EA))
                    .clickable(enabled = enabled) { onClick() }
            ) {
                Text(
                    text = symbol,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(label, fontSize = 10.sp, color = Color(0xFF86868B))
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    companion object {
        const val ACTION_HIDE = "com.flashrecorder.HIDE_FLOATING"
    }
}

/** 服务内承载 ComposeView 所需的 Lifecycle / SavedStateRegistry / ViewModelStore。 */
private class FloatingLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun create() {
        controller.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun start() { registry.handleLifecycleEvent(Lifecycle.Event.ON_START) }
    fun resume() { registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
    fun pause() { registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
    fun stop() { registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
    fun destroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
