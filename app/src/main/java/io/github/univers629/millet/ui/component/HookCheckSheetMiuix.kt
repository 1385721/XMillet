package io.github.univers629.millet.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.univers629.millet.core.RootHelper
import io.github.univers629.millet.core.XposedPrefsBridge
import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 检测过程中逐条输出的一行结果。
 */
private data class HookCheckLogLine(
    val text: String,
    val level: HookDiagnosticLevel,
    val suggestion: String? = null,
)

/**
 * 首页「运行环境与服务检测」底部面板：打开时逐条流式输出检测结果，
 * 检测结束后给出总览卡片与逐项详情，底部保留重新检测与操作入口。
 *
 * 检测项本身来自 [generateHookDiagnostics] 的同一次快照，流式输出只是把
 * 已有的结果按顺序展开，期间顶部计数与日志同步推进。
 */
@Composable
fun HookCheckSheetMiuix(
    show: Boolean,
    runtimeStatus: PowerKeeperBinderClient.RuntimeStatusSnapshot,
    kernelFreezer: RootHelper.KernelFreezer = RootHelper.KernelFreezer.UNKNOWN,
    frameworkState: XposedPrefsBridge.XposedFrameworkState = XposedPrefsBridge.XposedFrameworkState(),
    onRequestScope: (() -> Unit)? = null,
    onHotReloadStale: (() -> Unit)? = null,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    kernelEvidenceConfirmed: Boolean = false,
    kernelEvidenceSource: String = "UNCONFIRMED",
    milletBackendState: String = "UNCONFIRMED",
) {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val bodyMaxHeight = (screenHeight * 0.52f).coerceIn(220.dp, 460.dp)

    val diagnostics = remember(
        runtimeStatus,
        kernelFreezer,
        frameworkState,
        kernelEvidenceConfirmed,
        kernelEvidenceSource,
        milletBackendState,
    ) {
        generateHookDiagnostics(
            runtimeStatus = runtimeStatus,
            kernelFreezer = kernelFreezer,
            frameworkState = frameworkState,
            kernelEvidenceConfirmed = kernelEvidenceConfirmed,
            kernelEvidenceSource = kernelEvidenceSource,
            milletBackendState = milletBackendState,
        )
    }

    // 只有辅助信息可从“必要项”里排除；官方策略链路与冻结证据始终参与总览。
    val requiredDiagnostics = diagnostics.filterNot { it.optional }
    val totalCount = requiredDiagnostics.size
    val passedCount = requiredDiagnostics.count { it.level == HookDiagnosticLevel.PASSED }
    val overallLevel = when {
        requiredDiagnostics.all { it.level == HookDiagnosticLevel.PASSED } -> HookDiagnosticLevel.PASSED
        requiredDiagnostics.any { it.level == HookDiagnosticLevel.FAILED } -> HookDiagnosticLevel.FAILED
        else -> HookDiagnosticLevel.WARNING
    }

    var runToken by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    val logLines = remember { mutableStateListOf<HookCheckLogLine>() }
    val clock = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }

    LaunchedEffect(show, runToken) {
        if (!show) {
            running = false
            return@LaunchedEffect
        }
        revealed = 0
        logLines.clear()
        running = true
        delay(220)
        diagnostics.forEachIndexed { index, item ->
            logLines += HookCheckLogLine(
                text = "${LocalTime.now().format(clock)}  ${glyphOf(item.level)}  ${item.title} · ${item.summaryText}",
                level = item.level,
                suggestion = item.suggestion.takeIf { item.level != HookDiagnosticLevel.PASSED },
            )
            revealed = index + 1
            if (index != diagnostics.lastIndex) delay(90)
        }
        running = false
    }

    val clipboard = LocalClipboardManager.current
    // OverlayBottomSheet 的关闭动画由 DismissState 驱动，这里提前取出来供各按钮复用。
    val dismissState = LocalDismissState.current

    OverlayBottomSheet(
        show = show,
        title = "运行环境与服务检测",
        backgroundColor = colorScheme.surface,
        insideMargin = DpSize(16.dp, 8.dp),
        startAction = {
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(buildReportText(diagnostics)))
                },
            ) {
                Icon(
                    imageVector = MiuixIcons.Copy,
                    contentDescription = "复制检测结果",
                    tint = colorScheme.onBackground,
                )
            }
        },
        endAction = {
            IconButton(
                onClick = {
                    onDismiss()
                    dismissState?.invoke()
                },
            ) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = "关闭",
                    tint = colorScheme.onBackground,
                )
            }
        },
        onDismissRequest = {
            onDismiss()
            dismissState?.invoke()
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HookCheckStreamHeader(
                    running = running,
                    level = overallLevel,
                    checkedCount = revealed,
                    streamTotal = diagnostics.size,
                    passedCount = passedCount,
                    totalCount = totalCount,
                )

                HookCheckConsole(lines = logLines, running = running)

                val showRequestScopeAction = onRequestScope != null &&
                    frameworkState.isServiceConnected &&
                    frameworkState.missingScopes.isNotEmpty()
                val showHotReloadAction = onHotReloadStale != null &&
                    frameworkState.isServiceConnected &&
                    frameworkState.staleTargets.isNotEmpty()
                if (showRequestScopeAction || showHotReloadAction) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (showRequestScopeAction) {
                            TextButton(
                                text = "授权作用域",
                                onClick = onRequestScope,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (showHotReloadAction) {
                            TextButton(
                                text = "热重载 (${frameworkState.staleTargets.size})",
                                onClick = onHotReloadStale,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) {
                    TextButton(
                        text = "正在检测…",
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    TextButton(
                        text = "重新检测",
                        onClick = {
                            runToken++
                            onRefresh()
                        },
                        modifier = Modifier.weight(1f),
                    )

                    Spacer(Modifier.width(12.dp))

                    TextButton(
                        text = "完成",
                        onClick = {
                            onDismiss()
                            dismissState?.invoke()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

/**
 * 流式检测的顶部状态条：检测中显示进度，结束后切换为与检测项一致的结论卡片。
 */
@Composable
private fun HookCheckStreamHeader(
    running: Boolean,
    level: HookDiagnosticLevel,
    checkedCount: Int,
    streamTotal: Int,
    passedCount: Int,
    totalCount: Int,
) {
    if (!running) {
        OverallStatusHeader(level = level, passedCount = passedCount, totalCount = totalCount)
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "正在检测系统环境",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = colorScheme.onSurface,
        )
        Text(
            text = "已检查 $checkedCount/$streamTotal 项 · 结果会随检测逐条输出",
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * 流式日志窗口：等宽字体、按等级着色、自动滚到最新一行，可长按选中复制。
 */
@Composable
private fun HookCheckConsole(
    lines: List<HookCheckLogLine>,
    running: Boolean,
) {
    val consoleScroll = rememberScrollState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            consoleScroll.animateScrollTo(consoleScroll.maxValue)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 132.dp, max = 240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .verticalScroll(consoleScroll),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (lines.isEmpty()) {
            Text(
                text = if (running) "等待检测…" else "点击「重新检测」开始",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onSurfaceVariantSummary,
            )
        } else {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    lines.forEach { line ->
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = line.text,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colorForLevel(line.level),
                            )
                            val hint = line.suggestion
                            if (hint != null) {
                                Text(
                                    text = "   \u21b3 $hint",
                                    fontSize = 10.5.sp,
                                    lineHeight = 15.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colorForLevel(line.level).copy(alpha = 0.85f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun glyphOf(level: HookDiagnosticLevel): String = when (level) {
    HookDiagnosticLevel.PASSED -> "✓"
    HookDiagnosticLevel.WARNING -> "!"
    HookDiagnosticLevel.FAILED -> "×"
}

private fun colorForLevel(level: HookDiagnosticLevel): Color = when (level) {
    HookDiagnosticLevel.PASSED -> Color(0xFF34C759)
    HookDiagnosticLevel.WARNING -> Color(0xFFFF9500)
    HookDiagnosticLevel.FAILED -> Color(0xFFFF3B30)
}

private fun buildReportText(diagnostics: List<HookDiagnosticEntry>): String = buildString {
    appendLine("XMillet 运行环境与服务检测")
    appendLine(LocalTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
    diagnostics.forEach { item ->
        appendLine()
        appendLine("${glyphOf(item.level)} ${item.title} · ${item.summaryText}")
        appendLine(item.targetClassOrMethod)
        appendLine(item.detailText)
        item.suggestion?.let { appendLine("建议：$it") }
    }
}