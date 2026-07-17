package com.flashrecorder.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flashrecorder.viewmodel.RecorderViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- 设计令牌（白色简约高级风） ----------------
object FlashColors {
    val White = Color(0xFFFFFFFF)
    val Card = Color(0xFFF5F5F7)
    val Divider = Color(0xFFE5E5EA)
    val TextPrimary = Color(0xFF1D1D1F)
    val TextSecondary = Color(0xFF86868B)
    val Accent = Color(0xFF007AFF)
    val RecordRed = Color(0xFFFF3B30)
    val HighlightOrange = Color(0xFFFF9500)
    val Black = Color(0xFF000000)
}

private val CardShape = RoundedCornerShape(16.dp)
private val ButtonShape = RoundedCornerShape(12.dp)

@Composable
fun FlashTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = FlashColors.Accent,
            background = FlashColors.White,
            surface = FlashColors.White,
            onPrimary = Color.White,
            onBackground = FlashColors.TextPrimary,
            onSurface = FlashColors.TextPrimary,
        ),
        content = content,
    )
}

// ---------------- 主屏 ----------------

@Composable
fun MainScreen(
    state: RecorderViewModel.UiState,
    onRequestStart: () -> Unit,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onTriggerHighlight: () -> Unit,
    onStopMonitor: () -> Unit,
    onToggleFloating: () -> Unit,
    onOpenClip: (String) -> Unit,
) {
    Surface(color = FlashColors.White, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Header()
            Spacer(Modifier.height(24.dp))
            HeroCard(
                state = state,
                onRequestStart = onRequestStart,
                onStartRecord = onStartRecord,
                onStopRecord = onStopRecord,
                onStopMonitor = onStopMonitor,
                onToggleFloating = onToggleFloating,
            )
            Spacer(Modifier.height(24.dp))
            ClipsSection(state = state, onOpenClip = onOpenClip)
            Spacer(Modifier.height(24.dp))
            InfoSection()
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text("闪电录屏", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = FlashColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "预录 30 秒 · 自动高光 · 低内存环形缓冲",
            fontSize = 14.sp, color = FlashColors.TextSecondary,
        )
    }
}

@Composable
private fun HeroCard(
    state: RecorderViewModel.UiState,
    onRequestStart: () -> Unit,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onStopMonitor: () -> Unit,
    onToggleFloating: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, CardShape)
            .clip(CardShape)
            .background(FlashColors.White)
            .border(1.dp, FlashColors.Divider, CardShape)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.isRecording) PulsingDot() else StatusBullet(state)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stateLabel(state), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = FlashColors.TextPrimary)
                Text(
                    if (state.isRecording) "正在录制，高光检测运行中" else stateHint(state),
                    fontSize = 13.sp, color = FlashColors.TextSecondary,
                )
            }
            Text(formatTime(state.elapsedMs), fontSize = 22.sp, fontWeight = FontWeight.Medium, color = FlashColors.TextPrimary)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.isRecording -> {
                    PrimaryButton("停止录制", color = FlashColors.RecordRed, modifier = Modifier.weight(1f), onClick = onStopRecord)
                    SecondaryButton("高光", modifier = Modifier.weight(1f)) { onTriggerHighlight() }
                }
                state.isStandby -> {
                    PrimaryButton("开始录制", color = FlashColors.Black, modifier = Modifier.weight(1f), onClick = onStartRecord)
                    SecondaryButton("结束监控", modifier = Modifier.weight(1f)) { onStopMonitor() }
                }
                state.isSaving -> {
                    PrimaryButton("保存中…", color = FlashColors.TextSecondary, modifier = Modifier.weight(1f), enabled = false, onClick = {})
                }
                else -> {
                    PrimaryButton("开始监控", color = FlashColors.Black, modifier = Modifier.weight(1f), onClick = onRequestStart)
                }
            }
        }
        if (state.isActive) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (state.floatingVisible) "隐藏悬浮窗" else "显示悬浮窗",
                fontSize = 13.sp, color = FlashColors.Accent, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { onToggleFloating() },
            )
        }
    }
}

@Composable
private fun StatusBullet(state: RecorderViewModel.UiState) {
    val color = when {
        state.isStandby -> FlashColors.HighlightOrange
        state.isSaving -> FlashColors.Accent
        else -> FlashColors.TextSecondary
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        Modifier.size(10.dp).clip(CircleShape).background(FlashColors.RecordRed.copy(alpha = alpha))
    )
}

@Composable
private fun PrimaryButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(ButtonShape)
            .background(if (enabled) color else FlashColors.Divider)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(ButtonShape)
            .border(1.dp, FlashColors.Divider, ButtonShape)
            .background(FlashColors.White)
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = FlashColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------- 录制 / 高光 列表（iOS 分组风） ----------------

@Composable
private fun ClipsSection(state: RecorderViewModel.UiState, onOpenClip: (String) -> Unit) {
    SectionTitle("录制与高光")
    Spacer(Modifier.height(10.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(2.dp, CardShape)
            .clip(CardShape)
            .background(FlashColors.White)
            .border(1.dp, FlashColors.Divider, CardShape),
    ) {
        if (state.clips.isEmpty()) {
            Text(
                "暂无录制，开始监控后将自动预录最近 30 秒",
                fontSize = 14.sp, color = FlashColors.TextSecondary,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            state.clips.forEachIndexed { idx, clip ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 },
                    exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { it / 4 },
                ) {
                    ClipRow(clip = clip, onOpen = { onOpenClip(clip.path) })
                }
                if (idx < state.clips.lastIndex) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .padding(start = 16.dp)
                            .background(FlashColors.Divider)
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipRow(clip: RecorderViewModel.ClipItem, onOpen: () -> Unit) {
    val isHighlight = clip.type == RecorderViewModel.ClipType.HIGHLIGHT
    val name = if (isHighlight) "高光片段" else "录制片段"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isHighlight) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(FlashColors.HighlightOrange)
            )
            Spacer(Modifier.width(12.dp))
        } else {
            Spacer(Modifier.width(15.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = FlashColors.TextPrimary)
            Text(formatClipTime(clip.time), fontSize = 13.sp, color = FlashColors.TextSecondary)
        }
        Text("查看", fontSize = 13.sp, color = FlashColors.Accent)
    }
}

// ---------------- 信息卡 ----------------

@Composable
private fun InfoSection() {
    SectionTitle("工作原理")
    Spacer(Modifier.height(10.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(2.dp, CardShape)
            .clip(CardShape)
            .background(FlashColors.White)
            .border(1.dp, FlashColors.Divider, CardShape)
            .padding(20.dp),
    ) {
        InfoLine("预录缓冲", "每秒分片，环形保留最近 30 秒 / 80MB")
        InfoLine("高光识别", "音频 RMS × 3 倍 或 画面差分 > 15% 触发，5 秒冷却")
        InfoLine("前 30 秒回录", "高光触发时从缓冲提取并合并保存")
        InfoLine("输出格式", "H.264 / AAC / MP4，原生 MediaMuxer 合并")
    }
}

@Composable
private fun InfoLine(title: String, desc: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = FlashColors.TextPrimary, modifier = Modifier.width(96.dp))
        Text(desc, fontSize = 14.sp, color = FlashColors.TextSecondary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(Locale.getDefault()),
        fontSize = 12.sp,
        color = FlashColors.TextSecondary,
        fontWeight = FontWeight.SemiBold,
    )
}

// ---------------- 工具 ----------------

private fun stateLabel(s: RecorderViewModel.UiState): String = when {
    s.isRecording -> "录制中"
    s.isStandby -> "预录待机"
    s.isSaving -> "保存中"
    else -> "未启动"
}

private fun stateHint(s: RecorderViewModel.UiState): String = when {
    s.isStandby -> "已缓冲最近 30 秒，随时可录制"
    s.isSaving -> "正在合并分片，请稍候"
    else -> "点击「开始监控」以授权并预录"
}

fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun formatClipTime(t: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(t))
