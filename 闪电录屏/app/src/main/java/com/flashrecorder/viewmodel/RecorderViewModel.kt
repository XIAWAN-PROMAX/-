package com.flashrecorder.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flashrecorder.service.FloatingWindowService
import com.flashrecorder.service.RecordingService
import com.flashrecorder.util.C
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 状态中枢：通过广播接收 RecordingService 的状态/产物，以 StateFlow 暴露给 UI；
 * UI 指令经此转发为广播/服务启动。
 */
class RecorderViewModel(app: Application) : AndroidViewModel(app) {

    enum class ClipType { RECORD, HIGHLIGHT }
    data class ClipItem(val path: String, val time: Long, val type: ClipType)

    data class UiState(
        val state: String = RecordingService.State.IDLE.name,
        val elapsedMs: Long = 0L,
        val clips: List<ClipItem> = emptyList(),
        val message: String? = null,
        val floatingVisible: Boolean = false,
    ) {
        val isRecording: Boolean get() = state == RecordingService.State.RECORDING.name
        val isStandby: Boolean get() = state == RecordingService.State.STANDBY.name
        val isSaving: Boolean get() = state == RecordingService.State.SAVING.name
        val isActive: Boolean get() = isRecording || isStandby || isSaving
    }

    private val _state = MutableStateFlow(UiState(state = RecordingService.currentState.name))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != C.ACTION_STATE) return
            val st = intent.getStringExtra(C.EXTRA_STATE)
            val elapsed = intent.getLongExtra(C.EXTRA_ELAPSED, 0L)
            val recPath = intent.getStringExtra(C.EXTRA_RECORD_PATH)
            val hlPath = intent.getStringExtra(C.EXTRA_HIGHLIGHT_PATH)
            val hlTime = intent.getLongExtra(C.EXTRA_HIGHLIGHT_TIME, 0L)

            _state.update { s ->
                var clips = s.clips
                if (recPath != null && File(recPath).exists()) {
                    clips = clips + ClipItem(recPath, System.currentTimeMillis(), ClipType.RECORD)
                }
                if (hlPath != null && File(hlPath).exists()) {
                    clips = clips + ClipItem(hlPath, hlTime, ClipType.HIGHLIGHT)
                }
                s.copy(
                    state = st ?: s.state,
                    elapsedMs = if (st != null) elapsed else s.elapsedMs,
                    clips = clips,
                    message = when {
                        recPath != null -> "录制已保存"
                        hlPath != null -> "高光已保存"
                        else -> s.message
                    },
                )
            }
        }
    }

    init {
        getApplication<Application>().registerReceiver(
            receiver, IntentFilter(C.ACTION_STATE), Context.RECEIVER_NOT_EXPORTED
        )
    }

    fun startMonitor(resultCode: Int, data: Intent) {
        C.sendCommand(
            getApplication(),
            C.ACTION_START_MONITOR,
            C.EXTRA_RESULT_CODE to resultCode,
            C.EXTRA_RESULT_DATA to data,
        )
        // 监控启动后展示悬浮窗
        viewModelScope.launch { showFloating() }
    }

    fun startRecording() {
        if (_state.value.isStandby) C.sendCommand(getApplication(), C.ACTION_START_RECORD)
    }

    fun stopRecording() {
        if (_state.value.isRecording) C.sendCommand(getApplication(), C.ACTION_STOP_RECORD)
    }

    fun triggerHighlight() {
        if (_state.value.isActive) C.sendCommand(getApplication(), C.ACTION_TRIGGER_HIGHLIGHT)
    }

    fun stopAll() {
        hideFloating()
        C.sendCommand(getApplication(), C.ACTION_STOP_ALL)
    }

    fun showFloating() {
        getApplication<Application>().startService(
            Intent(getApplication(), FloatingWindowService::class.java)
        )
        _state.update { it.copy(floatingVisible = true) }
    }

    fun hideFloating() {
        val intent = Intent(getApplication(), FloatingWindowService::class.java)
            .setAction(FloatingWindowService.ACTION_HIDE)
        getApplication<Application>().startService(intent)
        _state.update { it.copy(floatingVisible = false) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { getApplication<Application>().unregisterReceiver(receiver) }
    }
}
