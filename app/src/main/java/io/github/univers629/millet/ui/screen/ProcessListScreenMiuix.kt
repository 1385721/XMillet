package io.github.univers629.millet.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.univers629.millet.core.FreezeModeLabels
import io.github.univers629.millet.ui.component.AppIconImage
import io.github.univers629.millet.ui.component.DropdownItem
import io.github.univers629.millet.ui.component.ListPopupDefaults
import io.github.univers629.millet.ui.component.StatusTagMiuix
import io.github.univers629.millet.ui.theme.LocalEnableBlur
import io.github.univers629.millet.ui.util.BlurredBar
import io.github.univers629.millet.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private enum class ProcessFilterMode(val label: String) {
    ALL("全部"),
    FROZEN("已冻结"),
    RUNNING("运行中")
}

/**
 * 进程列表界面：
 * - 扫描并展示当前活跃运行进程与内核/Greeze 冻结状态
 * - 支持按应用名、包名、UID/PID 检索
 * - 提供 全部 / 已冻结 / 运行中 快速分类筛选
 * - 点击进程条目可直达单应用策略与控制面板 (AppProfile)
 * - 支持下拉刷新与右上角一键全部解冻
 */
@Composable
fun ProcessListScreenMiuix(
    runningProcesses: List<RunningProcessUiModel>,
    bottomInnerPadding: Dp,
    filterIndex: Int = 0,
    onFilterIndexChange: (Int) -> Unit = {},
    onRefresh: () -> Unit,
    onThawAll: () -> Unit,
    onOpenAppProfile: (packageName: String, appName: String) -> Unit,
    onQueryUnfrozenReason: (packageName: String) -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    var searchQuery by rememberSaveable { mutableStateOf("") }
    val currentFilter = ProcessFilterMode.entries.getOrElse(filterIndex) { ProcessFilterMode.ALL }

    val frozenCount = remember(runningProcesses) {
        runningProcesses.count { it.isFrozen }
    }
    val runningCount = remember(runningProcesses, frozenCount) {
        runningProcesses.size - frozenCount
    }

    val filteredProcesses = remember(runningProcesses, searchQuery, currentFilter) {
        val q = searchQuery.trim().lowercase()
        runningProcesses.filter { proc ->
            val filterMatch = when (currentFilter) {
                ProcessFilterMode.ALL -> true
                ProcessFilterMode.FROZEN -> proc.isFrozen
                ProcessFilterMode.RUNNING -> !proc.isFrozen
            }
            val queryMatch = q.isEmpty() ||
                proc.appName.lowercase().contains(q) ||
                proc.packageName.lowercase().contains(q) ||
                proc.uid.toString().contains(q) ||
                proc.pids.any { it.toString().contains(q) }

            filterMatch && queryMatch
        }
    }

    var isRefreshing by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()
    var showTopPopup by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BlurredBar(backdrop, blurActive) {
                TopAppBar(
                    color = barColor,
                    title = "进程列表 (${filteredProcesses.size})",
                    actions = {
                        IconButton(
                            modifier = Modifier.padding(end = 4.dp),
                            onClick = onRefresh
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = "刷新进程列表",
                                tint = colorScheme.onSurface
                            )
                        }
                        IconButton(
                            modifier = Modifier.padding(end = 12.dp),
                            onClick = { showTopPopup = true },
                            holdDownState = showTopPopup
                        ) {
                            Icon(
                                imageVector = MiuixIcons.MoreCircle,
                                tint = colorScheme.onSurface,
                                contentDescription = "更多操作"
                            )
                        }
                        OverlayListPopup(
                            show = showTopPopup,
                            popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                            alignment = PopupPositionProvider.Align.TopEnd,
                            onDismissRequest = { showTopPopup = false },
                            content = {
                                val menuItems = listOf("一键解冻全部应用", "重新扫描进程")
                                ListPopupColumn {
                                    menuItems.forEachIndexed { idx, text ->
                                        DropdownItem(
                                            text = text,
                                            optionSize = menuItems.size,
                                            index = idx,
                                            onSelectedIndexChange = {
                                                showTopPopup = false
                                                when (idx) {
                                                    0 -> onThawAll()
                                                    1 -> onRefresh()
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            PullToRefresh(
                isRefreshing = isRefreshing,
                pullToRefreshState = pullToRefreshState,
                onRefresh = {
                    isRefreshing = true
                    onRefresh()
                    isRefreshing = false
                },
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 6.dp
                ),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = bottomInnerPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    overscrollEffect = null
                ) {
                    item {
                        Spacer(Modifier.height(0.dp))
                    }

                    // 1. Miuix 胶囊搜索框
                    item {
                        MiuixCapsuleSearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            placeholder = "搜索进程名称、包名或 PID/UID..."
                        )
                    }

                    // 2. 状态胶囊横向过滤条
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProcessFilterMode.entries.forEachIndexed { idx, mode ->
                                val selected = idx == filterIndex
                                val count = when (mode) {
                                    ProcessFilterMode.ALL -> runningProcesses.size
                                    ProcessFilterMode.FROZEN -> frozenCount
                                    ProcessFilterMode.RUNNING -> runningCount
                                }
                                val bgColor = if (selected) {
                                    colorScheme.primary
                                } else {
                                    colorScheme.surfaceContainerHigh
                                }
                                val txtColor = if (selected) {
                                    colorScheme.onPrimary
                                } else {
                                    colorScheme.onSurfaceVariantSummary
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(bgColor)
                                        .clickable { onFilterIndexChange(idx) }
                                        .padding(horizontal = 14.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        text = "${mode.label} ($count)",
                                        color = txtColor,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    // 3. 进程卡片列表
                    if (filteredProcesses.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                onClick = {
                                    searchQuery = ""
                                    onFilterIndexChange(0)
                                    onRefresh()
                                },
                                showIndication = true
                            ) {
                                BasicComponent(
                                    title = if (runningProcesses.isEmpty()) "暂无活跃应用进程" else "未找到匹配的进程",
                                    summary = if (runningProcesses.isEmpty()) "点击此卡片或右上角刷新按钮重新扫描" else "点击重置搜索与分类"
                                )
                            }
                        }
                    } else {
                        items(filteredProcesses, key = { "${it.packageName}:${it.uid}" }) { proc ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onOpenAppProfile(proc.packageName, proc.appName) },
                                showIndication = true,
                                pressFeedbackType = PressFeedbackType.Sink,
                                insideMargin = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AppIconImage(
                                        applicationInfo = proc.applicationInfo,
                                        label = proc.appName,
                                        modifier = Modifier
                                            .padding(end = 12.dp)
                                            .size(46.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = proc.appName,
                                            fontWeight = FontWeight(550),
                                            color = colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val descText = buildString {
                                            append("${proc.processCount}个进程")
                                            if (proc.frozenCount > 0) {
                                                append(" · ${proc.frozenCount}个已冻结")
                                            }
                                            if (proc.formattedMemSize.isNotEmpty()) {
                                                append(" · ${proc.formattedMemSize}")
                                            }
                                        }
                                        Text(
                                            text = descText,
                                            fontSize = 12.sp,
                                            color = colorScheme.onSurfaceVariantSummary,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "${proc.packageName} · UID: ${proc.uid}",
                                            fontSize = 11.sp,
                                            color = colorScheme.onSurfaceVariantSummary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    // 冻结状态与冻结方式固定在右侧，避免长包名把标签挤出可视区域
                                    Row(
                                        modifier = Modifier.padding(start = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        if (proc.isFrozen) {
                                            StatusTagMiuix(
                                                label = "已冻结",
                                                backgroundColor = colorScheme.primary,
                                                contentColor = colorScheme.onPrimary
                                            )
                                        } else {
                                            StatusTagMiuix(
                                                label = "运行中",
                                                backgroundColor = colorScheme.secondaryContainer,
                                                contentColor = colorScheme.onSecondaryContainer
                                            )
                                        }
                                        if (proc.isFrozen) {
                                            if (proc.freezerTypes.isEmpty()) {
                                                StatusTagMiuix(
                                                    label = FreezeModeLabels.TOMBSTONE,
                                                    backgroundColor = colorScheme.secondaryContainer,
                                                    contentColor = colorScheme.onSecondaryContainer
                                                )
                                            } else {
                                                for (fType in proc.freezerTypes) {
                                                    val modeColor = FreezeModeLabels.colorHex(fType)
                                                    StatusTagMiuix(
                                                        label = FreezeModeLabels.shortLabel(fType),
                                                        backgroundColor = if (modeColor != null) {
                                                            Color(modeColor)
                                                        } else {
                                                            colorScheme.secondaryContainer
                                                        },
                                                        contentColor = if (modeColor != null) {
                                                            Color.White
                                                        } else {
                                                            colorScheme.onSecondaryContainer
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Icon(
                                        imageVector = MiuixIcons.Basic.ArrowRight,
                                        contentDescription = "查看详情与进程列表",
                                        tint = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                                        modifier = Modifier
                                            .padding(start = 4.dp)
                                            .size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(bottomInnerPadding))
                    }
                }
            }
        }
    }
}
