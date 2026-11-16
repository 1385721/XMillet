package io.github.univers629.millet.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import io.github.univers629.millet.BuildConfig
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.PrefsDefaults
import io.github.univers629.millet.core.PrefsKeys
import io.github.univers629.millet.ui.BackupRestoreHelper
import io.github.univers629.millet.ui.component.MiuixConfirmDialog
import io.github.univers629.millet.ui.component.TombStatsIcon
import io.github.univers629.millet.ui.component.XMilletIcons
import io.github.univers629.millet.ui.theme.LocalEnableBlur
import io.github.univers629.millet.ui.theme.UiThemeState
import io.github.univers629.millet.ui.util.BlurredBar
import io.github.univers629.millet.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Paste
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreenMiuix(
    themeState: UiThemeState,
    configVersion: Int = 0,
    bottomInnerPadding: Dp,
    onOpenAppearance: () -> Unit = {},
    onOpenOobe: () -> Unit = {},
    onOpenAbout: () -> Unit,
    onConfigImportedOrReset: () -> Unit,
) {
    val context = LocalContext.current
    val sp = remember { PrefReader.openUiPrefs(context) }
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    var disableKill by remember(configVersion) {
        mutableStateOf(PrefReader.readUiBoolean(sp, PrefsKeys.DISABLE_KILL))
    }
    var disableCharge by remember(configVersion) {
        mutableStateOf(PrefReader.readUiBoolean(sp, PrefsKeys.DISABLE_CHARGE))
    }
    var disableScreenOnThaw by remember(configVersion) {
        mutableStateOf(PrefReader.readUiBoolean(sp, PrefsKeys.DISABLE_SCREEN_ON_THAW))
    }
    var blockPowerSaveWhitelist by remember(configVersion) {
        mutableStateOf(PrefReader.readUiBoolean(sp, PrefsKeys.BLOCK_ADD_POWER_SAVE_WHITELIST_APPS))
    }
    var batteryObserve by remember(configVersion) {
        mutableStateOf(PrefReader.readUiBoolean(sp, PrefsKeys.BATTERY_CHANGE_OBSERVE))
    }
    var isLog by remember(configVersion) {
        mutableStateOf(PrefReader.readUiBoolean(sp, PrefsKeys.IS_LOG))
    }
    var logToLocal by remember(configVersion) {
        mutableStateOf(PrefReader.readUiBoolean(sp, PrefsKeys.LOG_TO_LOCAL))
    }
    var simplifyLog by remember(configVersion) {
        mutableStateOf(PrefReader.readUiBoolean(sp, PrefsKeys.SIMPLIFY_LOG))
    }

    var enableDoze by remember(configVersion) {
        mutableStateOf(PrefReader.readUiBoolean(sp, PrefsKeys.ENABLE_DOZE))
    }
    var skipStateCheck by remember(configVersion) {
        mutableStateOf(PrefReader.readUiBoolean(sp, PrefsKeys.SKIP_STATE_CHECK))
    }
    var disableMotionListener by remember(configVersion) {
        mutableStateOf(PrefReader.readUiBoolean(sp, PrefsKeys.DISABLE_MOTION_LISTENER))
    }
    var dozeLog by remember(configVersion) {
        mutableStateOf(PrefReader.readUiBoolean(sp, PrefsKeys.DOZE_LOG))
    }

    val logLevels = listOf("debug", "info", "warn", "error")
    val logLevelLabels = listOf("Debug (调试)", "Info (信息)", "Warn (警告)", "Error (错误)")
    var currentLogLevel by remember(configVersion) {
        mutableStateOf(sp.getString(PrefsKeys.LOG_LEVEL, PrefsDefaults.LOG_LEVEL) ?: PrefsDefaults.LOG_LEVEL)
    }

    val logTypes = listOf("xposed", "file")
    val logTypeLabels = listOf("LSPosed 框架日志 + Logcat", "仅写入本地日志文件")
    var currentLogType by remember(configVersion) {
        mutableStateOf(sp.getString(PrefsKeys.LOG_TYPE, PrefsDefaults.LOG_TYPE) ?: PrefsDefaults.LOG_TYPE)
    }

    var showRebootNotice by remember { mutableStateOf(false) }

    val aliasDefault = remember {
        ComponentName(context.packageName, "${context.packageName}.ui.MainActivityAlias")
    }
    var showLauncherIcon by remember {
        val stateDefault = context.packageManager.getComponentEnabledSetting(aliasDefault)
        mutableStateOf(stateDefault != PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
    }

    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var exportedJsonPreview by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                val json = BackupRestoreHelper.exportToJson(context)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "JSON 备份导出成功", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val json = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).readText()
                }.orEmpty()
                if (json.isNotBlank()) {
                    BackupRestoreHelper.importFromJson(context, json)
                    onConfigImportedOrReset()
                    Toast.makeText(context, "配置导入并同步成功", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    if (showRebootNotice) {
        MiuixConfirmDialog(
            show = true,
            title = "需重启系统生效",
            message = "「墓碑与进程跟踪注入」控制系统服务启动期的核心底层注入，修改后请重启手机或软重启系统框架生效。",
            confirmText = "知道了",
            dismissText = null,
            onConfirm = { showRebootNotice = false },
            onDismiss = { showRebootNotice = false }
        )
    }

    if (showResetConfirmDialog) {
        MiuixConfirmDialog(
            show = true,
            title = "恢复默认配置",
            message = "确定要清空所有自定义应用名单并恢复初始开关状态（禁止杀后台=开启，消息应用=微信）吗？",
            confirmText = "确认重置",
            dismissText = "取消",
            onConfirm = {
                showResetConfirmDialog = false
                BackupRestoreHelper.resetToDefaults(context)
                onConfigImportedOrReset()
                Toast.makeText(context, "已恢复出厂默认配置", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showResetConfirmDialog = false }
        )
    }

    if (exportedJsonPreview != null) {
        MiuixConfirmDialog(
            show = true,
            title = "当前配置 JSON",
            message = exportedJsonPreview ?: "",
            confirmText = "复制到剪贴板",
            dismissText = "关闭",
            onConfirm = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("XMillet_Config", exportedJsonPreview))
                Toast.makeText(context, "已复制 JSON 到剪贴板", Toast.LENGTH_SHORT).show()
                exportedJsonPreview = null
            },
            onDismiss = { exportedJsonPreview = null }
        )
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop, blurActive) {
                TopAppBar(
                    color = barColor,
                    title = "设置",
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
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = innerPadding,
                overscrollEffect = null
            ) {
                item {
                    SmallTitle(
                        text = "外观与主题",
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        OverlayDropdownPreference(
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.DarkMode,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "颜色模式",
                            items = listOf("跟随系统", "始终浅色", "始终深色"),
                            selectedIndex = themeState.darkMode,
                            onSelectedIndexChange = { themeState.updateDarkMode(it) }
                        )

                        ArrowPreference(
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.ColorLens,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "外观个性化",
                            summary = "悬浮底栏、玻璃材质和动态取色",
                            onClick = onOpenAppearance
                        )

                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = TombStatsIcon,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "墓碑统计",
                            summary = "在底栏显示墓碑统计页：冻结、唤醒与拦截次数",
                            checked = themeState.showTombStatsTab,
                            onCheckedChange = { themeState.updateShowTombStatsTab(it) }
                        )

                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Show,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "显示桌面启动图标",
                            summary = "关闭后可从 LSPosed 管理器的模块设置中打开 XMillet",
                            checked = showLauncherIcon,
                            onCheckedChange = { visible ->
                                showLauncherIcon = visible
                                context.packageManager.setComponentEnabledSetting(
                                    aliasDefault,
                                    if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                    PackageManager.DONT_KILL_APP
                                )
                            }
                        )
                    }

                    SmallTitle(
                        text = "Millet 墓碑冻结全局策略"
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.CleaningServices,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "禁止息屏与夜间清理后台",
                            summary = "拦截 PowerCheckerController 与 PowerStateMachine自动杀后台",
                            checked = disableKill,
                            onCheckedChange = {
                                disableKill = it
                                PrefReader.putBooleanAndNotify(sp, PrefsKeys.DISABLE_KILL, it)
                            }
                        )
                        SwitchPreference(
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
                            title = "禁止插电时全量解冻",
                            summary = "拦截 MilletBrightHandler what=16 与 Greezer 的插电解冻",
                            checked = disableCharge,
                            onCheckedChange = {
                                disableCharge = it
                                PrefReader.putBooleanAndNotify(sp, PrefsKeys.DISABLE_CHARGE, it)
                            }
                        )
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.LightMode,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "亮屏时保持冻结",
                            summary = "拦截 Greezer 的 screen on 解冻；前台拉起仍会正常唤醒",
                            checked = disableScreenOnThaw,
                            onCheckedChange = {
                                disableScreenOnThaw = it
                                PrefReader.putBooleanAndNotify(sp, PrefsKeys.DISABLE_SCREEN_ON_THAW, it)
                            }
                        )
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
                            title = "禁止篡改省电白名单",
                            summary = "拦截 CommonAdapter#add/removePowerSaveWhitelistApps自动改动",
                            checked = blockPowerSaveWhitelist,
                            onCheckedChange = {
                                blockPowerSaveWhitelist = it
                                PrefReader.putBooleanAndNotify(
                                    sp,
                                    PrefsKeys.BLOCK_ADD_POWER_SAVE_WHITELIST_APPS,
                                    it
                                )
                            }
                        )
                    }

                    SmallTitle(text = "Doze 深度睡眠策略")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.Bedtime,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "启用 Doze 省电控制增强",
                            summary = "接管 DeviceIdleController，息屏10秒后自动推入 Deep Doze",
                            checked = enableDoze,
                            onCheckedChange = {
                                enableDoze = it
                                PrefReader.putBooleanAndNotify(sp, PrefsKeys.ENABLE_DOZE, it)
                            }
                        )
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.FastForward,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "跳过 Doze 中间传感检查",
                            summary = "当状态进入IDLE_PENDING(2)时直接跃迁至 IDLE_MAINTENANCE(6)",
                            checked = skipStateCheck,
                            onCheckedChange = {
                                skipStateCheck = it
                                PrefReader.putBooleanAndNotify(sp, PrefsKeys.SKIP_STATE_CHECK, it)
                            }
                        )
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.DirectionsWalk,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "屏蔽 Doze 移动传感器唤醒",
                            summary = "DeviceIdleController#motionLocked，移动手机不退出深度睡眠",
                            checked = disableMotionListener,
                            onCheckedChange = {
                                disableMotionListener = it
                                PrefReader.putBooleanAndNotify(sp, PrefsKeys.DISABLE_MOTION_LISTENER, it)
                            }
                        )
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Notes,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "开启 Doze 状态转换日志",
                            summary = "记录亮灭屏电量及 Doze 状态机切换详情",
                            checked = dozeLog,
                            onCheckedChange = {
                                dozeLog = it
                                PrefReader.putBooleanAndNotify(sp, PrefsKeys.DOZE_LOG, it)
                            }
                        )
                    }

                    SmallTitle(text = "日志与电量诊断")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.BugReport,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "开启详细运行日志",
                            summary = "输出冻结、解冻压制、广播拦截与 ANR 吞噬日志",
                            checked = isLog,
                            onCheckedChange = {
                                isLog = it
                                PrefReader.putBooleanAndNotify(sp, PrefsKeys.IS_LOG, it)
                            }
                        )
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.BatteryAlert,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "开启电量变化消耗记录",
                            summary = "监听电池百分比变化并统计每 1% 电量消耗耗时",
                            checked = batteryObserve,
                            onCheckedChange = {
                                batteryObserve = it
                                PrefReader.putBooleanAndNotify(sp, PrefsKeys.BATTERY_CHANGE_OBSERVE, it)
                            }
                        )
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.SaveAlt,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "保存日志到本地文件",
                            summary = "异步追加写入 /data/system/XMillet/log",
                            checked = logToLocal,
                            onCheckedChange = {
                                logToLocal = it
                                PrefReader.putBooleanAndNotify(sp, PrefsKeys.LOG_TO_LOCAL, it)
                            }
                        )
                        SwitchPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Tune,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "启用墓碑日志与进程级跟踪",
                            summary = "跟踪单进程冻结并输出墓碑日志(F)/(T)，需重启生效",
                            checked = simplifyLog,
                            onCheckedChange = {
                                simplifyLog = it
                                PrefReader.putBooleanAndNotify(sp, PrefsKeys.SIMPLIFY_LOG, it)
                                showRebootNotice = true
                            }
                        )
                        OverlayDropdownPreference(
                            title = "最低日志级别",
                            items = logLevelLabels,
                            selectedIndex = logLevels.indexOf(currentLogLevel.lowercase()).coerceAtLeast(0),
                            onSelectedIndexChange = { idx ->
                                val newLevel = logLevels.getOrElse(idx) { PrefsDefaults.LOG_LEVEL }
                                currentLogLevel = newLevel
                                PrefReader.putStringAndNotify(sp, PrefsKeys.LOG_LEVEL, newLevel)
                            }
                        )
                        OverlayDropdownPreference(
                            title = "日志输出方式",
                            items = logTypeLabels,
                            selectedIndex = logTypes.indexOf(currentLogType.lowercase()).coerceAtLeast(0),
                            onSelectedIndexChange = { idx ->
                                val newType = logTypes.getOrElse(idx) { PrefsDefaults.LOG_TYPE }
                                currentLogType = newType
                                PrefReader.putStringAndNotify(sp, PrefsKeys.LOG_TYPE, newType)
                            }
                        )
                    }

                    SmallTitle(text = "配置备份与恢复")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        ArrowPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Backup,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "导出 JSON 备份文件",
                            summary = "将全部 ${PrefsKeys.ALL_KEYS.size} 项配置与名单保存为本地 JSON 文件",
                            onClick = {
                                val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date())
                                exportLauncher.launch("XMillet_Backup_$dateStr.json")
                            }
                        )

                        ArrowPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Import,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "从 JSON 文件导入备份",
                            summary = "选择已保存的 JSON 文件恢复策略并实时通知系统生效",
                            onClick = {
                                importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                            }
                        )

                        ArrowPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Copy,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "预览并复制配置 JSON",
                            summary = "查看当前 ${PrefsKeys.ALL_KEYS.size} 个配置键的 JSON 文本并复制到剪贴板",
                            onClick = {
                                exportedJsonPreview = BackupRestoreHelper.exportToJson(context)
                            }
                        )

                        ArrowPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Paste,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "从剪贴板导入配置 JSON",
                            summary = "直接读取剪贴板中的 JSON 备份文本并应用",
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                                if (clipText.isEmpty()) {
                                    Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                                } else {
                                    runCatching {
                                        BackupRestoreHelper.importFromJson(context, clipText)
                                        onConfigImportedOrReset()
                                        Toast.makeText(context, "已从剪贴板恢复配置", Toast.LENGTH_SHORT).show()
                                    }.onFailure { e ->
                                        Toast.makeText(context, "剪贴板 JSON 解析失败：${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        )

                        ArrowPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Reset,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "恢复默认配置",
                            summary = "清空所有自定义应用集合并恢复初始推荐开关",
                            onClick = { showResetConfirmDialog = true }
                        )
                    }

                    SmallTitle(text = "关于")
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        ArrowPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Tasks,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "权限配置向导",
                            summary = "重新检查所需应用权限",
                            onClick = onOpenOobe
                        )
                        ArrowPreference(
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Info,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(24.dp),
                                    tint = colorScheme.onBackground
                                )
                            },
                            title = "关于 XMillet",
                            summary = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
                            onClick = onOpenAbout
                        )
                    }

                    Spacer(Modifier.height(bottomInnerPadding))
                }
            }
        }
    }
}
