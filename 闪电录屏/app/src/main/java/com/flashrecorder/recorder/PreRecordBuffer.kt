package com.flashrecorder.recorder

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaProjection
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.flashrecorder.util.C
import java.io.File
import java.nio.ByteBuffer

/**
 * 预录环形缓冲区 + MP4 合并器。
 *
 * 设计：
 * - 单个常驻 MediaRecorder + 单个 VirtualDisplay（输入 Surface 在分片切换期间保持有效）。
 * - 通过 [setMaxFileSize] 限制每段大小（约 1 秒），达到上限后由 [setNextOutputFile] 无缝切换到下一段，
 *   避免每秒重建 VirtualDisplay 造成的丢帧。
 * - [ArrayDeque] 维护最近分片；待机状态下超 [C.MAX_SEGMENTS] / [C.MAX_BUFFER_BYTES] 从头丢弃并删文件。
 * - 正式录制时不裁剪，保留预录前 30 秒 + 录制全程；停止时 [stopRecording] 返回全部分片，
 *   由 [Mp4Merger] 用原生 MediaMuxer 合并为最终 MP4。
 * - 高光触发时 [snapshot] 拷贝最近 N 段后合并，避免与裁剪竞争。
 */
class PreRecordBuffer(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
) {
    data class Segment(val file: File, val index: Long, val sizeBytes: Long, val createdAtMs: Long)

    private val lock = Any()
    private val deque = ArrayDeque<Segment>()
    private var totalBytes = 0L
    private var seq = 0L

    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var currentFile: File? = null
    private var nextFile: File? = null

    @Volatile var recording: Boolean = false
        private set

    private val handlerThread = HandlerThread("prebuffer").apply { start() }
    private val handler = Handler(handlerThread.looper)

    /** 每段文件大小上限（字节）。约对应 1 秒（6Mbps 视频 + 128kbps 音频 ≈ 0.78MB/s）。 */
    private val maxFileBytes = 1_000_000L

    fun start() {
        synchronized(lock) {
            recording = false
            val f0 = C.tempSegmentFile(ctx, seq++)
            currentFile = f0
            val rec = buildRecorder(f0)
            rec.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    handler.post { onFileLimit() }
                }
            }
            rec.setOnErrorListener { _, _, _ -> handler.post { recover() } }
            rec.prepare()
            val surface = rec.surface
            display = projection.createVirtualDisplay(
                "FlashBuffer", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler
            )
            rec.start()
            recorder = rec
            val f1 = C.tempSegmentFile(ctx, seq++)
            nextFile = f1
            runCatching { rec.setNextOutputFile(f1) }
        }
    }

    /** 当前文件达到大小上限，录制器已自动切换到 nextFile；归档完成的分片并预置下一段。 */
    private fun onFileLimit() {
        synchronized(lock) {
            val done = currentFile ?: return
            enqueue(done)
            currentFile = nextFile
            val f = C.tempSegmentFile(ctx, seq++)
            nextFile = f
            val ok = runCatching { recorder?.setNextOutputFile(f) }.isSuccess
            if (!ok) recover()
        }
    }

    /** 录制器异常时重建（可能产生一次极短空帧，但保证流水线不中断）。 */
    private fun recover() {
        synchronized(lock) {
            runCatching { recorder?.stop() }
            currentFile?.let { enqueue(it) }
            runCatching { recorder?.release() }
            runCatching { display?.release() }
            val f0 = C.tempSegmentFile(ctx, seq++)
            currentFile = f0
            val rec = buildRecorder(f0)
            rec.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) handler.post { onFileLimit() }
            }
            rec.setOnErrorListener { _, _, _ -> handler.post { recover() } }
            rec.prepare()
            val surface = rec.surface
            display = projection.createVirtualDisplay(
                "FlashBuffer", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler
            )
            rec.start()
            recorder = rec
            val f1 = C.tempSegmentFile(ctx, seq++)
            nextFile = f1
            runCatching { rec.setNextOutputFile(f1) }
        }
    }

    /** 进入正式录制：停止裁剪，使预录前 30 秒与录制全程一并保留。 */
    fun beginRecording() {
        synchronized(lock) { recording = true }
    }

    /** 停止录制并返回待合并的全部分片（含预录前 30 秒），并清空缓冲以便重新进入待机。 */
    fun stopRecording(): List<File> {
        synchronized(lock) {
            runCatching { recorder?.stop() }
            currentFile?.let { enqueue(it) }
            runCatching { recorder?.release() }
            runCatching { display?.release() }
            recorder = null
            display = null
            currentFile = null
            nextFile = null
            recording = false
            val files = deque.map { it.file }.toList()
            deque.clear()
            totalBytes = 0
            return files
        }
    }

    /** 高光快照：拷贝最近 [seconds] 段分片，避免与裁剪竞争。 */
    fun snapshot(seconds: Int): List<File> {
        synchronized(lock) {
            val take = deque.toList().takeLast(seconds.coerceAtLeast(1))
            val copies = ArrayList<File>(take.size)
            for (seg in take) {
                val copy = File(C.tempDir(ctx), "snap_${seg.index}_${System.nanoTime()}.mp4")
                runCatching { seg.file.copyTo(copy, overwrite = true) }.onSuccess { copies.add(copy) }
            }
            return copies
        }
    }

    /** 彻底停止并清理所有临时分片。 */
    fun stopAll() {
        synchronized(lock) {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { display?.release() }
            recorder = null
            display = null
            currentFile = null
            nextFile = null
            deque.forEach { it.file.delete() }
            deque.clear()
            totalBytes = 0
            recording = false
        }
        handlerThread.quitSafely()
    }

    private fun enqueue(file: File) {
        val size = if (file.exists()) file.length() else 0L
        deque.addLast(Segment(file, seq, size, System.currentTimeMillis()))
        totalBytes += size
        if (!recording) {
            while (deque.isNotEmpty() && (deque.size > C.MAX_SEGMENTS || totalBytes > C.MAX_BUFFER_BYTES)) {
                val drop = deque.removeFirst()
                totalBytes -= drop.sizeBytes
                drop.file.delete()
            }
        }
    }

    private fun buildRecorder(file: File): MediaRecorder {
        return MediaRecorder(ctx).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(file.absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(6_000_000)
            setVideoFrameRate(30)
            setVideoSize(width, height)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setMaxFileSize(maxFileBytes)
        }
    }
}

/**
 * 原生 MP4 合并器：用 MediaExtractor 读取多个分片，统一时间戳后写入单个 MediaMuxer。
 * 假设各分片由同一 MediaRecorder 配置生成，音视频轨道格式一致（SPS/PPS/AAC config 相同）。
 */
object Mp4Merger {

    fun merge(files: List<File>, output: File): Boolean {
        val valid = files.filter { it.exists() && it.length() > 0 }
        if (valid.isEmpty()) return false
        if (valid.size == 1) {
            runCatching { valid[0].copyTo(output, overwrite = true) }.onSuccess { return true }
            return false
        }
        var muxer: MediaMuxer? = null
        val openExtractors = mutableListOf<MediaExtractor>()
        try {
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val first = MediaExtractor().apply { setDataSource(valid[0].absolutePath) }
            val vFmt = first.firstFormat("video/")
            val aFmt = first.firstFormat("audio/")
            val muxerVideo = vFmt?.let { muxer.addTrack(it) } ?: -1
            val muxerAudio = aFmt?.let { muxer.addTrack(it) } ?: -1
            muxer.start()
            first.release()

            var vOffset = 0L
            var aOffset = 0L
            val buf = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            for (f in valid) {
                val ex = MediaExtractor().apply { setDataSource(f.absolutePath) }
                openExtractors.add(ex)
                val vt = ex.firstIndex("video/")
                val at = ex.firstIndex("audio/")
                if (vt >= 0 && muxerVideo >= 0) {
                    ex.selectTrack(vt)
                    val dur = ex.getTrackFormat(vt).optLong(MediaFormat.KEY_DURATION, 0L)
                    copyTrack(ex, muxer, muxerVideo, buf, vOffset)
                    ex.unselectTrack(vt)
                    vOffset += dur
                }
                if (at >= 0 && muxerAudio >= 0) {
                    ex.selectTrack(at)
                    val dur = ex.getTrackFormat(at).optLong(MediaFormat.KEY_DURATION, 0L)
                    copyTrack(ex, muxer, muxerAudio, buf, aOffset)
                    ex.unselectTrack(at)
                    aOffset += dur
                }
                ex.release()
                openExtractors.remove(ex)
            }
            muxer.stop()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            runCatching { muxer?.release() }
            openExtractors.forEach { runCatching { it.release() } }
        }
    }

    private fun copyTrack(ex: MediaExtractor, muxer: MediaMuxer, muxerTrack: Int, buf: ByteBuffer, offset: Long) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            buf.position(0)
            buf.limit(size)
            info.offset = 0
            info.size = size
            info.flags = ex.sampleFlags
            info.presentationTimeUs = ex.sampleTime + offset
            muxer.writeSampleData(muxerTrack, buf, info)
            if (!ex.advance()) break
        }
    }

    private fun MediaExtractor.firstIndex(prefix: String): Int {
        for (i in 0 until trackCount) {
            val mime = getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun MediaExtractor.firstFormat(prefix: String): MediaFormat? {
        val i = firstIndex(prefix)
        return if (i >= 0) getTrackFormat(i) else null
    }

    private fun MediaFormat.optLong(key: String, default: Long): Long =
        if (containsKey(key)) getLong(key) else default
}
