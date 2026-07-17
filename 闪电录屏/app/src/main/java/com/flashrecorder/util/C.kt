package com.flashrecorder.util

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局常量：广播 Action、Intent Extra 键、路径与格式化工具。
 * 服务与 UI / ViewModel 之间通过广播 + Intent 通信，统一在此声明避免散落。
 */
object C {
    // ---- 广播：UI/VM -> 服务（指令） ----
    const val ACTION_START_MONITOR = "com.flashrecorder.START_MONITOR"     // 进入预录待机
    const val ACTION_START_RECORD = "com.flashrecorder.START_RECORD"       // 开始正式录制
    const val ACTION_STOP_RECORD = "com.flashrecorder.STOP_RECORD"         // 停止录制（合并输出）
    const val ACTION_STOP_ALL = "com.flashrecorder.STOP_ALL"               // 停止监控并退出
    const val ACTION_TRIGGER_HIGHLIGHT = "com.flashrecorder.TRIGGER_HIGHLIGHT" // 手动高光
    const val ACTION_TOGGLE_FLOATING = "com.flashrecorder.TOGGLE_FLOATING" // 显示/隐藏悬浮窗

    // ---- 广播：服务 -> UI/VM（状态） ----
    const val ACTION_STATE = "com.flashrecorder.STATE"
    const val EXTRA_STATE = "state"            // RecorderState 名称
    const val EXTRA_ELAPSED = "elapsed"        // 已录制毫秒
    const val EXTRA_HIGHLIGHT_PATH = "hl_path" // 新高光片段路径
    const val EXTRA_HIGHLIGHT_TIME = "hl_time" // 高光触发时间戳
    const val EXTRA_RECORD_PATH = "rec_path"   // 录制完成输出路径
    const val EXTRA_MESSAGE = "message"        // 文本提示

    // ---- Intent Extra ----
    const val EXTRA_RESULT_CODE = "result_code"
    const val EXTRA_RESULT_DATA = "result_data"

    // ---- 预录缓冲参数 ----
    const val SEGMENT_MS = 1_000L          // 每段 1 秒
    const val MAX_SEGMENTS = 30            // 最多保留 30 段（≈30 秒）
    const val MAX_BUFFER_BYTES = 80L * 1024 * 1024  // 80MB 上限

    // ---- 高光检测参数 ----
    const val HIGHLIGHT_COOLDOWN_MS = 5_000L
    const val AUDIO_RATIO = 3.0            // RMS 超 5s 均值 3 倍
    const val FRAME_DIFF_RATIO = 0.15      // 画面差分超 15%
    const val AUDIO_WINDOW_MS = 5_000L

    /** 录制输出目录 */
    fun outputDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "FlashRecorder").apply { mkdirs() }

    /** 预录临时分片目录 */
    fun tempDir(ctx: Context): File =
        File(ctx.cacheDir, "prebuffer").apply { mkdirs() }

    fun tempSegmentFile(ctx: Context, index: Long): File =
        File(tempDir(ctx), "seg_%06d.mp4".format(index))

    fun newOutputFile(ctx: Context, prefix: String): File {
        val name = "%s_%s.mp4".format(prefix, SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()))
        return File(outputDir(ctx), name)
    }

    /** 发送指令广播给 RecordingService */
    fun sendCommand(ctx: Context, action: String, vararg extras: Pair<String, Any?>) {
        val intent = Intent(action).apply {
            setPackage(ctx.packageName)
            extras.forEach { (k, v) ->
                when (v) {
                    is Int -> putExtra(k, v)
                    is Long -> putExtra(k, v)
                    is String -> putExtra(k, v)
                    is Boolean -> putExtra(k, v)
                    is android.os.Parcelable -> putExtra(k, v)
                }
            }
        }
        // Android 14+ 要求显式指定包名，已通过 setPackage 处理；监控入口需拉起前台服务
        if (action == ACTION_START_MONITOR) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    fun isAtLeastU(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}
