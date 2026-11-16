package io.github.univers629.millet.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.SignalWifiOff
import androidx.compose.material.icons.rounded.SyncDisabled
import androidx.compose.material.icons.rounded.Troubleshoot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.univers629.millet.core.FreezeModeLabels
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.PrefsKeys
import io.github.univers629.millet.core.RootHelper
import io.github.univers629.millet.core.SystemBatteryOptimizationHelper
import io.github.univers629.millet.core.SETTING_MILLET_NO_RESTRICT_APP
import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import io.github.univers629.millet.ui.component.AppIconImage
import io.github.univers629.millet.ui.component.DropdownItem
import io.github.univers629.millet.ui.component.ListPopupDefaults
import io.github.univers629.millet.ui.component.StatusTagMiuix
import io.github.univers629.millet.ui.component.WarningCard
import io.github.univers629.millet.ui.component.WarningLevel
import io.github.univers629.millet.ui.component.XMilletIcons
import io.github.univers629.millet.ui.theme.LocalEnableBlur
import io.github.univers629.millet.ui.util.BlurredBar
import io.github.univers629.millet.ui.util.rememberBlurBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 单应用策略详情页（复刻 KernelSU AppProfileScreenMiuix 布局与交互）：
 * 完整支持 8 个应用级策略集合与严格互斥联动，以及跨进程未冻结原因一键诊断。
 */
@Composable
fun AppProfileScreenMiuix(
    app: InstalledAppUiModel,
    runtimeStatus: PowerKeeperBinderClient.RuntimeStatusSnapshot = PowerKeeperBinderClient.RuntimeStatusSnapshot(),
    onBack: () -> Unit,
    onPolicyChanged: () -> Unit,
    onQueryUnfrozenReason: (String) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { PrefReader.openUiPrefs(context) }
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    var appProcesses by remember(app.packageName, app.uid) {
        mutableStateOf(AppProcessSnapshotCache.get(app.packageName).orEmpty())
    }
    var isScanningProcesses by remember { mutableStateOf(false) }
    var hasScannedProcesses by remember(app.packageName, app.uid) {
        mutableStateOf(AppProcessSnapshotCache.get(app.packageName) != null)
    }

    suspend fun scanProcesses() {
        if (isScanningProcesses) return
        isScanningProcesses = true
        val list = withContext(Dispatchers.IO) {
            // 优先用 system_server 采集的快照（含每个进程挂载的服务），App 侧不需要 root
            val fromSystem = PowerKeeperBinderClient.queryProcessSnapshot()
                ?.filter {
                    it.packageName == app.packageName ||
                        it.uid == app.uid ||
                        it.processName.startsWith("${app.packageName}:")
                }
                .orEmpty()
            if (fromSystem.isNotEmpty()) {
                RootHelper.sortProcessEntries(fromSystem)
            } else {
                // 模块未激活时不再回退 root 采集，进程列表留空，避免后台拉起 su 弹授权框
                emptyList()
            }
        }
        appProcesses = list
        AppProcessSnapshotCache.put(app.packageName, list)
        hasScannedProcesses = true
        isScanningProcesses = false
    }

    val refreshProcesses: () -> Unit = {
        coroutineScope.launch { scanProcesses() }
    }

    LaunchedEffect(app.packageName, app.uid) {
        // 等转场动画放完再扫描：AnimatedContent 的进场动画是 360ms（MainActivity:517），
        // 扫描结果若在动画中途落地，「正在扫描」的小占位会被撑成 N 行进程，
        // 整页重排会落在动画帧上，造成卡顿。
        delay(PROCESS_SCAN_DELAY_MS)
        scanProcesses()
    }

    var inWhite by remember(app.packageName) {
        mutableStateOf(app.packageName in PrefReader.readUiStringSet(prefs, PrefsKeys.WHITE_APP_SET))
    }
    var inMessage by remember(app.packageName) {
        mutableStateOf(app.packageName in PrefReader.readUiStringSet(prefs, PrefsKeys.MESSAGE_APP_SET))
    }
    var inForceFreeze by remember(app.packageName) {
        mutableStateOf(app.packageName in PrefReader.readUiStringSet(prefs, PrefsKeys.FORCE_FREEZE_SET))
    }
    var inBlockThaw by remember(app.packageName) {
        mutableStateOf(app.packageName in PrefReader.readUiStringSet(prefs, PrefsKeys.BLOCK_THAW_SET))
    }
    var inForbidSync by remember(app.packageName) {
        mutableStateOf(app.packageName in PrefReader.readUiStringSet(prefs, PrefsKeys.FORBID_SYNC_BINDER_SET))
    }
    var inNotFreezeFgs by remember(app.packageName) {
        mutableStateOf(app.packageName in PrefReader.readUiStringSet(prefs, PrefsKeys.NOT_FREEZE_WHEN_HAS_FGS_SET))
    }
    var inIgnoreNetworkWake by remember(app.packageName) {
        mutableStateOf(app.packageName in PrefReader.readUiStringSet(prefs, PrefsKeys.IGNORE_NETWORK_WAKE_SET))
    }
    // 系统「无限制」的唯一事实来源是 Settings.System.MILLET_NO_RESTRICT_APP。
    // 读到它就把「免冻结白名单」开关点亮，并把包名补进白名单集合 ——
    // 这样开关、列表胶囊、系统策略三者说的是同一件事（开关开着就是真的在名单里）。
    var systemUnrestricted by remember(app.packageName) { mutableStateOf(false) }

    // 系统省电策略的只读回显：不提供绑定/初始化按钮，进页面自动读系统真值。
    var systemPolicyLabel by remember(app.packageName) { mutableStateOf("读取中…") }

    suspend fun refreshSystemPolicy() {
        val (unrestricted, label) = withContext(Dispatchers.IO) {
            val value = readNoRestrict(context, app.packageName)
            value to readSystemPolicyLabel(context, app.packageName)
        }
        systemUnrestricted = unrestricted
        systemPolicyLabel = label
        // 开关只跟随模块名单；系统侧是否「无限制」由上方提示条单独展示，
        // 避免系统名单没清干净时把开关顶回开启状态，让用户以为点不动。
    }
    LaunchedEffect(app.packageName) {
        refreshSystemPolicy()
    }

    fun buildMembershipSet(key: String, add: Boolean): Set<String> {
        val currentSet = PrefReader.readUiStringSet(prefs, key)
        if (add) currentSet.add(app.packageName) else currentSet.remove(app.packageName)
        return currentSet
    }

    fun updateMemberships(vararg changes: Pair<String, Boolean>) {
        if (changes.isEmpty()) return
        val updates = LinkedHashMap<String, Set<String>>(changes.size)
        for ((key, add) in changes) {
            updates[key] = buildMembershipSet(key, add)
        }
        PrefReader.putStringSetsAndNotify(prefs, updates, changes.first().first)
        onPolicyChanged()
    }

    fun updateMembership(key: String, add: Boolean) {
        updateMemberships(key to add)
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop, blurActive) {
                TopAppBar(
                    color = barColor,
                    title = "应用策略配置",
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                modifier = Modifier.graphicsLayer {
                                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                },
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回",
                                tint = colorScheme.onBackground
                            )
                        }
                    },
                    actions = {
                        val showTopPopup = remember { mutableStateOf(false) }
                        IconButton(
                            modifier = Modifier.padding(end = 12.dp),
                            onClick = { showTopPopup.value = true },
                            holdDownState = showTopPopup.value
                        ) {
                            Icon(
                                imageVector = MiuixIcons.MoreCircle,
                                tint = colorScheme.onSurface,
                                contentDescription = "更多操作"
                            )
                        }
                        OverlayListPopup(
                            show = showTopPopup.value,
                            popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                            alignment = PopupPositionProvider.Align.TopEnd,
                            onDismissRequest = { showTopPopup.value = false },
                            content = {
                                ListPopupColumn {
                                    val items = listOf(
                                        "诊断为什么未被冻结",
                                        "启动该应用",
                                        "系统应用详情页"
                                    )
                                    items.forEachIndexed { idx, text ->
                                        DropdownItem(
                                            text = text,
                                            optionSize = items.size,
                                            index = idx,
                                            onSelectedIndexChange = {
                                                showTopPopup.value = false
                                                when (idx) {
                                                    0 -> onQueryUnfrozenReason(app.packageName)
                                                    1 -> {
                                                        val launchIntent = context.packageManager
                                                            .getLaunchIntentForPackage(app.packageName)
                                                        if (launchIntent != null) {
                                                            runCatching { context.startActivity(launchIntent) }
                                                        } else {
                                                            Toast.makeText(context, "该应用无桌面启动入口", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                    2 -> {
                                                        val intent = Intent(
                                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                            Uri.fromParts("package", app.packageName, null)
                                                        )
                                                        runCatching { context.startActivity(intent) }
                                                    }
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(top = 12.dp)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = innerPadding,
                overscrollEffect = null
            ) {
                item {
                    // 1. 顶部应用信息卡片（复刻 KernelSU AppProfileMiuix）
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                        insideMargin = PaddingValues(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIconImage(
                                applicationInfo = app.applicationInfo,
                                label = app.appName,
                                modifier = Modifier.size(64.dp)
                            )
                            Column(
                                modifier = Modifier
                                    .padding(start = 12.dp, end = 8.dp)
                                    .weight(1f)
                            ) {
                                Text(
                                    text = app.appName,
                                    color = colorScheme.onSurface,
                                    fontWeight = FontWeight(600),
                                    fontSize = 17.sp,
                                    modifier = Modifier.basicMarquee(),
                                    maxLines = 1,
                                    softWrap = false
                                )
                                if (app.versionName.isNotEmpty()) {
                                    Text(
                                        text = "${app.versionName} (${app.versionCode})",
                                        fontSize = 12.sp,
                                        color = colorScheme.onSurfaceVariantSummary,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.basicMarquee(),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                Text(
                                    text = app.packageName,
                                    fontSize = 12.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.basicMarquee(),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                StatusTagMiuix(
                                    label = "UID ${app.uid}",
                                    backgroundColor = colorScheme.primary.copy(alpha = 0.85f),
                                    contentColor = colorScheme.onPrimary
                                )
                                if (app.isSystem) {
                                    StatusTagMiuix(
                                        label = "SYSTEM",
                                        backgroundColor = colorScheme.secondaryContainer,
                                        contentColor = colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }

                    // 2. 系统省电策略「无限制」提示卡片
                    if (systemUnrestricted) {
                        WarningCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                            message = "该应用在系统「省电策略」中被设为「无限制 (MILLET_NO_RESTRICT_APP)」。除非开启下方「强制冻结」，否则系统默认不冻结该应用。",
                            level = WarningLevel.Notice
                        )
                    }

                    // 3. 运行中子进程与内核冻结诊断（按应用专属深入查看）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SmallTitle(
                            text = "运行中子进程与冻结诊断 (${appProcesses.size})",
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = refreshProcesses,
                            enabled = !isScanningProcesses,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = "刷新进程状态",
                                tint = colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                        insideMargin = PaddingValues(14.dp)
                    ) {
                        if (appProcesses.isEmpty()) {
                            // 冻结/墓碑状态下进程可能已被回收，此时「无进程」本身就是正常结果，
                            // 与列表行显示的「已冻结」不是矛盾，这里把原因写清楚。
                            // 三种占位文案共用同一个固定高度：否则「准备 → 扫描 → 结果」之间
                            // 卡片会反复收缩，动画期间任何一次尺寸变化都会掉帧。
                            val uidFrozen = app.uid in runtimeStatus.frozenUids
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(96.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = when {
                                        isScanningProcesses -> "正在扫描进程..."
                                        !hasScannedProcesses -> "正在准备进程快照..."
                                        uidFrozen -> "该应用已被冻结，当前没有存活进程"
                                        else -> "该应用当前暂无运行中进程"
                                    },
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    color = colorScheme.onSurface
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = when {
                                        isScanningProcesses -> "请稍候..."
                                        !hasScannedProcesses -> "转场结束后立即读取进程表"
                                        uidFrozen -> "系统侧记录该 uid 处于冻结状态，进程随冻结被回收；应用被唤醒后会重新出现"
                                        else -> "未载入内存或进程已冷终止，点击右上角刷新可重新探测"
                                    },
                                    fontSize = 12.sp,
                                    color = colorScheme.onSurfaceVariantSummary
                                )
                            }
                        } else {
                            val totalMemKb = appProcesses.sumOf { it.rssKb }
                            val totalMemFormatted = RootHelper.formatMemSize(totalMemKb)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "共 ${appProcesses.size} 个进程",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colorScheme.onSurfaceVariantSummary
                                )
                                if (totalMemFormatted.isNotEmpty()) {
                                    Text(
                                        text = "总物理内存：$totalMemFormatted",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colorScheme.primary
                                    )
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                appProcesses.forEachIndexed { index, proc ->
                                    if (index > 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(0.6.dp)
                                                .background(colorScheme.outline.copy(alpha = 0.2f))
                                        )
                                    }
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // 头部：主/子进程标识 + 进程名 + 冻结/等待状态标签
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            val isMain = proc.processName == app.packageName
                                            Row(
                                                modifier = Modifier.weight(1f, fill = false),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = if (isMain) "● 主进程 " else "○ 子进程 ",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = if (isMain) colorScheme.primary else colorScheme.onSurfaceVariantSummary
                                                )
                                                Text(
                                                    text = proc.processName,
                                                    fontWeight = FontWeight(600),
                                                    fontSize = 13.5.sp,
                                                    color = colorScheme.onSurface,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    modifier = Modifier.basicMarquee()
                                                )
                                            }

                                            Spacer(Modifier.size(8.dp))

                                            // 状态徽章：冻结方式统一走 FreezeModeLabels，与首页列表同一口径
                                            val isUidFrozen = app.uid in runtimeStatus.frozenUids
                                            val modeType = proc.freezerType
                                            val modeColor = FreezeModeLabels.colorHex(modeType)
                                            when {
                                                modeColor != null -> {
                                                    StatusTagMiuix(
                                                        label = FreezeModeLabels.processLabel(modeType),
                                                        backgroundColor = Color(modeColor),
                                                        contentColor = Color.White
                                                    )
                                                }
                                                isUidFrozen -> {
                                                    StatusTagMiuix(
                                                        label = FreezeModeLabels.TOMBSTONE,
                                                        backgroundColor = colorScheme.primary,
                                                        contentColor = colorScheme.onPrimary
                                                    )
                                                }
                                                else -> {
                                                    StatusTagMiuix(
                                                        label = "活跃等待",
                                                        backgroundColor = colorScheme.secondaryContainer,
                                                        contentColor = colorScheme.onSecondaryContainer
                                                    )
                                                }
                                            }
                                        }

                                        // 核心指标行 1：实际物理内存与 PID
                                        val rssText = RootHelper.formatMemSize(proc.rssKb).ifEmpty { "0 KB" }
                                        Text(
                                            text = "物理内存: $rssText · PID: ${proc.pid}",
                                            fontSize = 12.sp,
                                            color = colorScheme.onSurfaceVariantSummary
                                        )

                                        // 核心指标行 2：运行时长与内核等待通道
                                        val uptimeText = proc.uptime.ifEmpty { "存活中" }
                                        Text(
                                            text = "运行时长: $uptimeText · 内核通道: ${proc.wchan}",
                                            fontSize = 12.sp,
                                            color = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.85f)
                                        )

                                        // 挂载服务展示
                                        if (proc.services.isNotEmpty()) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = "挂载服务 (${proc.services.size} 个):",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = colorScheme.primary
                                                )
                                                proc.services.forEach { svc ->
                                                    Text(
                                                        text = "└─ $svc",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = colorScheme.onSurfaceVariantSummary,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        modifier = Modifier.basicMarquee()
                                                    )
                                                }
                                            }
                                        } else {
                                            Text(
                                                text = "挂载服务: 无活跃服务",
                                                fontSize = 11.sp,
                                                color = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.65f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. 未冻结原因实时诊断入口
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        ArrowPreference(
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.Troubleshoot,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.primary
                                )
                            },
                            title = "诊断「为什么没被冻结」",
                            summary = "跨进程诊断未冻结原因",
                            onClick = { onQueryUnfrozenReason(app.packageName) }
                        )
                    }

                    // 4. 冻结豁免与消息策略组
                    SmallTitle(text = "冻结豁免与保活策略")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = XMilletIcons.AppPolicy,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "加入 XMillet 免冻结白名单",
                            summary = "永不冻结此应用（开启后自动关闭消息应用与强制冻结，并同步设为系统省电策略无限制）",
                            checked = inWhite,
                            onCheckedChange = { checked ->
                                inWhite = checked
                                if (checked) {
                                    inMessage = false
                                    inForceFreeze = false
                                    inBlockThaw = false
                                    inForbidSync = false
                                    inIgnoreNetworkWake = false
                                    inNotFreezeFgs = false
                                    updateMemberships(
                                        PrefsKeys.WHITE_APP_SET to true,
                                        PrefsKeys.MESSAGE_APP_SET to false,
                                        PrefsKeys.FORCE_FREEZE_SET to false,
                                        PrefsKeys.BLOCK_THAW_SET to false,
                                        PrefsKeys.FORBID_SYNC_BINDER_SET to false,
                                        PrefsKeys.IGNORE_NETWORK_WAKE_SET to false,
                                        PrefsKeys.NOT_FREEZE_WHEN_HAS_FGS_SET to false
                                    )

                                    // 开启免冻结白名单：同步把系统「省电策略」改为无限制
                                    // （模块在线时由 system_server 直接写，不需要 root）
                                    systemUnrestricted = true
                                    coroutineScope.launch {
                                        val ok = SystemBatteryOptimizationHelper.setAppIgnoringBatteryOptimization(context, app.packageName)
                                        refreshSystemPolicy()
                                        if (!ok) {
                                            inWhite = false
                                            updateMembership(PrefsKeys.WHITE_APP_SET, false)
                                            Toast.makeText(
                                                context,
                                                "系统省电策略写入失败：需要模块在线或 Root 授权",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                } else {
                                    updateMembership(PrefsKeys.WHITE_APP_SET, false)
                                    // 关闭免冻结白名单：系统「省电策略」恢复默认
                                    systemUnrestricted = false
                                    coroutineScope.launch {
                                        val ok = SystemBatteryOptimizationHelper.restoreAppBatteryOptimizationToDefault(context, app.packageName)
                                        refreshSystemPolicy()
                                        if (!ok) {
                                            inWhite = true
                                            updateMembership(PrefsKeys.WHITE_APP_SET, true)
                                            Toast.makeText(
                                                context,
                                                "恢复系统省电策略失败：需要模块在线或 Root 授权",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            }
                        )
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.NotificationsActive,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "设为消息应用",
                            summary = "允许收到网络推送包后短暂解冻处理消息，与免冻结白名单互斥",
                            checked = inMessage,
                            onCheckedChange = { checked ->
                                inMessage = checked
                                if (checked && inWhite) {
                                    inWhite = false
                                    updateMemberships(
                                        PrefsKeys.MESSAGE_APP_SET to true,
                                        PrefsKeys.WHITE_APP_SET to false
                                    )
                                } else {
                                    updateMembership(PrefsKeys.MESSAGE_APP_SET, checked)
                                }
                            }
                        )
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.PlayCircleOutline,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "存在前台服务时不冻结",
                            summary = "当 Aurogon 检测到活跃 ForegroundService 时阻止冻结",
                            checked = inNotFreezeFgs,
                            onCheckedChange = { checked ->
                                inNotFreezeFgs = checked
                                if (checked && inWhite) {
                                    inWhite = false
                                    updateMemberships(
                                        PrefsKeys.NOT_FREEZE_WHEN_HAS_FGS_SET to true,
                                        PrefsKeys.WHITE_APP_SET to false
                                    )
                                } else {
                                    updateMembership(PrefsKeys.NOT_FREEZE_WHEN_HAS_FGS_SET, checked)
                                }
                            }
                        )
                    }

                    // 5. 强力冻结与解冻压制组（带平滑折叠展开动画）
                    SmallTitle(text = "强力冻结与唤醒压制")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.AcUnit,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "强制冻结",
                            summary = "剥夺系统黑名单、无限制名单与后台豁免，退后台即纳入冻结",
                            checked = inForceFreeze,
                            onCheckedChange = { checked ->
                                inForceFreeze = checked
                                if (checked) {
                                    if (inWhite) {
                                        inWhite = false
                                        updateMemberships(
                                            PrefsKeys.FORCE_FREEZE_SET to true,
                                            PrefsKeys.WHITE_APP_SET to false
                                        )
                                    } else {
                                        updateMembership(PrefsKeys.FORCE_FREEZE_SET, true)
                                    }
                                } else {
                                    inBlockThaw = false
                                    inForbidSync = false
                                    inIgnoreNetworkWake = false
                                    updateMemberships(
                                        PrefsKeys.FORCE_FREEZE_SET to false,
                                        PrefsKeys.BLOCK_THAW_SET to false,
                                        PrefsKeys.FORBID_SYNC_BINDER_SET to false,
                                        PrefsKeys.IGNORE_NETWORK_WAKE_SET to false
                                    )
                                }
                            }
                        )
                        AnimatedVisibility(
                            visible = inForceFreeze,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Column {
                                SwitchPreference(
                                    startAction = {
                                        Icon(
                                            imageVector = Icons.Rounded.Block,
                                            contentDescription = null,
                                            modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                            tint = colorScheme.onBackground
                                        )
                                    },
                                    title = "禁止后台解冻",
                                    summary = "仅放行 Activity 启动与切换前台解冻，强力压制其他后台唤醒",
                                    checked = inBlockThaw,
                                    onCheckedChange = { checked ->
                                        inBlockThaw = checked
                                        updateMembership(PrefsKeys.BLOCK_THAW_SET, checked)
                                    }
                                )
                                SwitchPreference(
                                    startAction = {
                                        Icon(
                                            imageVector = Icons.Rounded.SyncDisabled,
                                            contentDescription = null,
                                            modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                            tint = colorScheme.onBackground
                                        )
                                    },
                                    title = "阻止同步 Binder 唤醒",
                                    summary = "拦截 Sync Binder / Check Binder 与 MilletHandler what=13 唤醒",
                                    checked = inForbidSync,
                                    onCheckedChange = { checked ->
                                        inForbidSync = checked
                                        updateMembership(PrefsKeys.FORBID_SYNC_BINDER_SET, checked)
                                    }
                                )
                                SwitchPreference(
                                    startAction = {
                                        Icon(
                                            imageVector = Icons.Rounded.SignalWifiOff,
                                            contentDescription = null,
                                            modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                            tint = colorScheme.onBackground
                                        )
                                    },
                                    title = "忽略网络唤醒",
                                    summary = "不再因推送、网络变化唤醒该应用，冻结会一直保持到手动打开；代价是消息延迟送达",
                                    checked = inIgnoreNetworkWake,
                                    onCheckedChange = { checked ->
                                        inIgnoreNetworkWake = checked
                                        updateMembership(PrefsKeys.IGNORE_NETWORK_WAKE_SET, checked)
                                    }
                                )
                            }
                        }
                    }

                    // 6. 系统省电策略（只读）
                    //    真值来自 Settings.System.MILLET_NO_RESTRICT_APP，进页面自动读取，
                    //    没有「绑定 / 初始化」按钮；要改就去系统设置里改。
                    SmallTitle(text = "系统省电策略")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        ArrowPreference(
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.BatteryChargingFull,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "当前策略",
                            summary = systemPolicyLabel,
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        )
                    }

                    Spacer(
                        Modifier.height(
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() +
                                24.dp
                        )
                    )
                }
            }
        }
    }
}

/**
 * 判定某个包是否在系统「省电策略 = 无限制」名单里。
 *
 * 权威来源是 `Settings.System.MILLET_NO_RESTRICT_APP`（Greezer 真正认的名单），
 * 无需 root 即可读；注意这里判定的是「任意包名」，与 `BatteryPolicyHelper.isInNoRestrictSetting`
 * （只判定本应用）用途不同。
 */
private fun readNoRestrict(context: Context, packageName: String): Boolean =
    runCatching {
        Settings.System.getString(context.contentResolver, SETTING_MILLET_NO_RESTRICT_APP)
    }.getOrNull()
        ?.split(',')
        ?.any { it.trim() == packageName }
        ?: false

/** 把系统省电策略翻译成一句话，供只读行展示。 */
private fun readSystemPolicyLabel(context: Context, packageName: String): String = when {
    readNoRestrict(context, packageName) -> "无限制（系统不会主动冻结该应用）"
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(packageName) == true -> "已忽略电池优化（AOSP 白名单）"
    else -> "智能推荐（系统可随时冻结该应用）"
}

/**
 * 进程扫描的启动延迟。
 *
 * 取 460ms：AnimatedContent 的进场动画为 360ms，加上 60ms 的起步延迟（见 MainActivity:514-517），
 * 留出约 40ms 余量，确保扫描落点稳定在动画结束之后。
 */
private const val PROCESS_SCAN_DELAY_MS = 460L

/**
 * 各应用最近一次进程快照的内存缓存。
 *
 * 用处：从进程列表反复进出详情页时，卡片可以直接用上次的快照占位，避免先渲染
 * 「正在扫描」再撑开成 N 行 —— 这种尺寸跳变本身就是转场卡顿的主要来源之一。
 * 只保留最近若干条，进程退出即失效，不做持久化。
 */
private object AppProcessSnapshotCache {
    private const val MAX_ENTRIES = 8

    private val entries =
        object : LinkedHashMap<String, List<RootHelper.ProcessRawEntry>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<RootHelper.ProcessRawEntry>>?
            ): Boolean = size > MAX_ENTRIES
        }

    @Synchronized
    fun get(packageName: String): List<RootHelper.ProcessRawEntry>? = entries[packageName]

    @Synchronized
    fun put(packageName: String, snapshot: List<RootHelper.ProcessRawEntry>) {
        entries[packageName] = snapshot
    }
}
