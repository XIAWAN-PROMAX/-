package com.flashrecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import com.flashrecorder.recorder.HighlightDetector
import com.flashrecorder.recorder.Mp4Merger
import com.flashrecorder.recorder.PreRecordBuffer
import com.flashrecorder.util.C
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 录屏主服务：持有 MediaProjection，串联 PreRecordBuffer 与 HighlightDetector。
 *
 * 状态机：IDLE → STANDBY（预录待机 + 高光检测） → RECORDING → SAVING → STANDBY。
 *
 * Android 13+ 适配：先 startForeground(type=MEDIA_PROJECTION)，再 getMediaProjection / createVirtualDisplay。
 */
class RecordingService : Service() {

    enum class State { IDLE, STANDBY, RECORDING, SAVING, ERROR }

    @Volatile private var state: State = State.IDLE
    @Volatile private var recordStartMs: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timerJob: Job? = null

    private var projection: MediaProjection? = null
    private var buffer: PreRecordBuffer? = null
    private var detector: HighlightDetector? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var screenW = 1080
    private var screenH = 1920
    private var dpi = 320

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            C.ACTION_START_MONITOR -> handleStartMonitor(intent)
            C.ACTION_START_RECORD -> startRecording()
            C.ACTION_STOP_RECORD -> stopRecording()
            C.ACTION_TRIGGER_HIGHLIGHT -> triggerHighlight()
            C.ACTION_STOP_ALL -> stopAll()
        }
        return START_NOT_STICKY
    }

    // ---------------- 流程控制 ----------------

    private fun handleStartMonitor(intent: Intent) {
        val code = intent.getIntExtra(C.EXTRA_RESULT_CODE, 0)
        val data: Intent? = intent.getParcelableExtra(C.EXTRA_RESULT_DATA, Intent::class.java)
        if (data == null) { stopSelf(); return }
        // 关键：先成为前台服务，再使用 MediaProjection
        startForegroundCompat(buildNotification("准备就绪"))
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mgr.getMediaProjection(code, data) ?: run {
                setState(State.ERROR); stopSelf(); return
            }
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopAll() }
            }, null)
            computeScreenMetrics()
            startMonitor()
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            setState(State.ERROR)
            stopSelf()
        }
    }

    private fun startMonitor() {
        val proj = projection ?: return
        val buf = PreRecordBuffer(this, proj, screenW, screenH, dpi)
        buffer = buf
        buf.start()
        val det = HighlightDetector(proj, screenW, screenH) { onHighlightDetected() }
        detector = det
        det.start()
        setState(State.STANDBY)
        notifyState("预录缓冲中")
    }

    private fun startRecording() {
        if (state != State.STANDBY) return
        buffer?.beginRecording()
        recordStartMs = System.currentTimeMillis()
        setState(State.RECORDING)
        notifyState("正在录制")
        startTimer()
    }

    private fun stopRecording() {
        if (state != State.RECORDING) return
        timerJob?.cancel(); timerJob = null
        setState(State.SAVING)
        notifyState("正在合并保存")
        val buf = buffer ?: return
        val files = buf.stopRecording()
        scope.launch {
            val out = C.newOutputFile(this@RecordingService, "录制")
            val ok = Mp4Merger.merge(files, out)
            files.forEach { runCatching { it.delete() } }
            if (ok) {
                broadcastRecord(out.absolutePath)
            }
            // 恢复待机
            buffer?.start()
            setState(State.STANDBY)
            notifyState("预录缓冲中")
        }
    }

    private fun triggerHighlight() {
        onHighlightDetected()
    }

    /** 高光触发：从缓冲区提取前 30 秒，合并保存。 */
    private fun onHighlightDetected() {
        val buf = buffer ?: return
        if (state == State.IDLE || state == State.SAVING) return
        scope.launch {
            val snaps = buf.snapshot(30)
            if (snaps.isEmpty()) return@launch
            val out = C.newOutputFile(this@RecordingService, "高光")
            val ok = Mp4Merger.merge(snaps, out)
            snaps.forEach { runCatching { it.delete() } }
            if (ok) {
                broadcastHighlight(out.absolutePath)
            }
        }
    }

    private fun stopAll() {
        timerJob?.cancel(); timerJob = null
        runCatching { detector?.stop() }
        runCatching { buffer?.stopAll() }
        runCatching { projection?.stop() }
        detector = null; buffer = null; projection = null
        setState(State.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------- 计时 / 状态广播 ----------------

    private fun startTimer() {
        timerJob = scope.launch {
            while (isActive && state == State.RECORDING) {
                delay(500)
                broadcastState()
            }
        }
    }

    private val elapsedMs: Long
        get() = if (state == State.RECORDING && recordStartMs > 0)
            System.currentTimeMillis() - recordStartMs else 0L

    private fun setState(s: State) {
        state = s
        currentState = s
        broadcastState()
    }

    private fun broadcastState() {
        val intent = Intent(C.ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(C.EXTRA_STATE, state.name)
            putExtra(C.EXTRA_ELAPSED, elapsedMs)
        }
        sendBroadcast(intent)
    }

    private fun broadcastRecord(path: String) {
        sendBroadcast(Intent(C.ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(C.EXTRA_RECORD_PATH, path)
        })
    }

    private fun broadcastHighlight(path: String) {
        sendBroadcast(Intent(C.ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(C.EXTRA_HIGHLIGHT_PATH, path)
            putExtra(C.EXTRA_HIGHLIGHT_TIME, System.currentTimeMillis())
        })
    }

    // ---------------- 通知 / 前台 ----------------

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "录屏服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "屏幕录制与高光识别"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("闪电录屏")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        // minSdk 33：可直接显式声明前台服务类型（API 29+ 的三参重载）
        startForeground(
            NOTIF_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    private fun notifyState(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun computeScreenMetrics() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val bounds: Rect = wm.currentWindowMetrics.bounds
        // H.264 编码器要求宽高为偶数；向下对齐避免 setVideoSize 失败
        screenW = (bounds.width() and 0x7FFFFFFE).coerceAtLeast(320)
        screenH = (bounds.height() and 0x7FFFFFFE).coerceAtLeast(480)
        dpi = resources.displayMetrics.densityDpi
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlashRecorder:capture").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L) // 上限 10 小时，防止录屏中休眠
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { detector?.stop() }
        runCatching { buffer?.stopAll() }
        runCatching { projection?.stop() }
        runCatching { wakeLock?.release() }
        scope.cancel()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "flash_recorder"
        private const val NOTIF_ID = 1001

        /** 供 ViewModel 同步初始状态（同进程）。 */
        @Volatile var currentState: State = State.IDLE
            private set
    }
}
