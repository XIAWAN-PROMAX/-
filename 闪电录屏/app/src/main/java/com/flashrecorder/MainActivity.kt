package com.flashrecorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import com.flashrecorder.ui.FlashTheme
import com.flashrecorder.ui.MainScreen
import com.flashrecorder.viewmodel.RecorderViewModel
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: RecorderViewModel by viewModels()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioOk = results[Manifest.permission.RECORD_AUDIO]
            ?: (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
        if (!audioOk) {
            toast("需要录音权限用于音频录制与高光识别")
            return@registerForActivityResult
        }
        proceedToOverlayThenProjection()
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // 传递授权结果给服务：先 startForeground 再 getMediaProjection
            vm.startMonitor(result.resultCode, result.data!!)
        } else {
            toast("未授予屏幕录制权限")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlashTheme {
                val state by vm.state.collectAsState()
                MainScreen(
                    state = state,
                    onRequestStart = { startFlow() },
                    onStartRecord = { vm.startRecording() },
                    onStopRecord = { vm.stopRecording() },
                    onTriggerHighlight = { vm.triggerHighlight() },
                    onStopMonitor = { vm.stopAll() },
                    onToggleFloating = {
                        if (state.floatingVisible) vm.hideFloating() else vm.showFloating()
                    },
                    onOpenClip = { path -> openClip(path) },
                )
            }
        }
    }

    /** 开始监控：依次引导录音/通知权限 → 悬浮窗权限 → 屏幕录制授权。 */
    private fun startFlow() {
        val needAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val needNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needAudio || needNotif) {
            val perms = buildList {
                if (needAudio) add(Manifest.permission.RECORD_AUDIO)
                if (needNotif) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
            permLauncher.launch(perms)
            return
        }
        proceedToOverlayThenProjection()
    }

    private fun proceedToOverlayThenProjection() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授权“显示在其他应用上层”后再次点击开始")
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun openClip(path: String) {
        val file = File(path)
        if (!file.exists()) { toast("文件不存在"); return }
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }.onFailure { toast("没有可播放该视频的应用") }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
