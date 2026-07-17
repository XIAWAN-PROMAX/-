package com.flashrecorder.recorder

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaProjection
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import com.flashrecorder.util.C
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 高光识别：音频 RMS 振幅检测 + ImageReader 画面变化率，双维度任一触发，5 秒冷却。
 * 两条检测跑在独立协程 / 独立 HandlerThread 上，互不阻塞。
 *
 * - 音频：PCM 16bit 每帧计算 RMS，与最近 5 秒均值比较；超过 3 倍且高于底噪即触发。
 * - 画面：低分辨率 VirtualDisplay → ImageReader，下采样到 64x64 灰度，相邻帧差分超 15% 即触发。
 */
class HighlightDetector(
    private val projection: MediaProjection,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onHighlight: () -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var lastTriggerMs = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var audioJob: Job? = null
    private var frameJob: Job? = null

    private var audioRecord: android.media.AudioRecord? = null
    private var imageReader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val frameThread = HandlerThread("hl-frame").apply { start() }
    private val frameHandler = Handler(frameThread.looper)

    // 音频参数
    private val sampleRate = 16_000
    private val chunkSamples = sampleRate / 10 // 100ms
    private val windowChunks = (C.AUDIO_WINDOW_MS / 100).toInt() // 5 秒 = 50 帧
    private val audioFloor = 600.0 // 底噪门槛（16bit PCM RMS）

    fun start() {
        if (running) return
        running = true
        lastTriggerMs = 0
        audioJob = scope.launch { audioLoop() }
        frameJob = scope.launch { frameLoop() }
    }

    fun stop() {
        running = false
        audioJob?.cancel(); frameJob?.cancel()
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        runCatching { display?.release() }
        runCatching { imageReader?.close() }
        audioRecord = null; display = null; imageReader = null
        scope.cancel()
        frameThread.quitSafely()
    }

    private fun trigger() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < C.HIGHLIGHT_COOLDOWN_MS) return
        if (!running) return
        lastTriggerMs = now
        onHighlight()
    }

    // ---------------- 音频 RMS ----------------
    @SuppressLint("MissingPermission")
    private fun audioLoop() {
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return
        val bufSize = maxOf(minBuf, chunkSamples)
        val ar = android.media.AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2
        )
        if (ar.state != android.media.AudioRecord.STATE_INITIALIZED) {
            runCatching { ar.release() }; return
        }
        audioRecord = ar
        ar.startRecording()
        val buf = ShortArray(chunkSamples)
        val window = ArrayDeque<Double>()
        try {
            while (scope.isActive && running) {
                val read = ar.read(buf, 0, chunkSamples)
                if (read <= 0) continue
                var sum = 0L
                for (i in 0 until read) sum += buf[i].toLong() * buf[i].toLong()
                val rms = sqrt(sum.toDouble() / read)
                window.addLast(rms)
                while (window.size > windowChunks) window.removeFirst()
                if (window.size >= 10) {
                    val avg = window.average()
                    if (rms > audioFloor && avg > 0 && rms > avg * C.AUDIO_RATIO) {
                        trigger()
                    }
                }
            }
        } finally {
            runCatching { ar.stop() }
            runCatching { ar.release() }
            audioRecord = null
        }
    }

    // ---------------- 画面差分 ----------------
    private fun frameLoop() {
        val smallW = 160
        val smallH = (160.0 * screenHeight / screenWidth).toInt().coerceAtLeast(90)
        val reader = ImageReader.newInstance(smallW, smallH, android.graphics.PixelFormat.RGBA_8888, 3)
        imageReader = reader
        display = projection.createVirtualDisplay(
            "FlashHL", smallW, smallH, 1,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, frameHandler
        )
        var prev: IntArray? = null
        val gray = IntArray(64 * 64)
        while (scope.isActive && running) {
            delay(120)
            val image: Image? = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image == null) continue
            try {
                val plane = image.planes[0]
                val buf = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val w = image.width
                val h = image.height
                // 下采样到 64x64 灰度（最近邻）
                for (gy in 0 until 64) {
                    val sy = gy * h / 64
                    val rowOff = sy * rowStride
                    for (gx in 0 until 64) {
                        val sx = gx * w / 64
                        val p = rowOff + sx * pixelStride
                        if (p + 2 < buf.limit()) {
                            val r = buf.get(p).toInt() and 0xFF
                            val g = buf.get(p + 1).toInt() and 0xFF
                            val b = buf.get(p + 2).toInt() and 0xFF
                            gray[gy * 64 + gx] = (r + g + b) / 3
                        } else {
                            gray[gy * 64 + gx] = 0
                        }
                    }
                }
                val p = prev
                if (p != null) {
                    var changed = 0
                    for (i in 0 until gray.size) {
                        if (abs(gray[i] - p[i]) > 25) changed++
                    }
                    val ratio = changed.toDouble() / gray.size
                    if (ratio > C.FRAME_DIFF_RATIO) trigger()
                }
                prev = gray.copyOf()
            } finally {
                image.close()
            }
        }
    }
}
