package io.github.univers629.millet.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.univers629.millet.core.RootHelper
import io.github.univers629.millet.core.VersionGate
import io.github.univers629.millet.core.XposedPrefsBridge
import io.github.univers629.millet.ipc.HookCoverage
import io.github.univers629.millet.ipc.PowerKeeperBinderClient
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Hook 点位与系统环境状态等级。
 */
enum class HookDiagnosticLevel {
    PASSED,
    WARNING,
    FAILED
}

/**
 * 单个 Hook 点位或运行环境的检测条目模型。
 */
data class HookDiagnosticEntry(
    val id: String,
    val title: String,
    val targetClassOrMethod: String,
    val level: HookDiagnosticLevel,
    val summaryText: String,
    val detailText: String,
    val suggestion: String? = null,
    /**
     * 是否属于「可选能力」。
     *
     * 可选项不计入顶部「N 项检测中已就绪 M 项」与整体状态：它们只影响便利性，
     * 不影响模块本体是否工作。例如「电量与性能尚未完成握手」「有 Root 管理器但未授权」——
     * 这两项在开机瞬间、或用户从未授权过时都会短暂出现，不能因此把整页判成未就绪。
     */
    val optional: Boolean = false
)

/**
 * 依据当前运行时状态快照与系统环境构建诊断条目列表。
 */
fun generateHookDiagnostics(
    runtimeStatus: PowerKeeperBinderClient.RuntimeStatusSnapshot,
    kernelFreezer: RootHelper.KernelFreezer = RootHelper.KernelFreezer.UNKNOWN,
    frameworkState: XposedPrefsBridge.XposedFrameworkState = XposedPrefsBridge.XposedFrameworkState()
): List<HookDiagnosticEntry> {
    val entries = ArrayList<HookDiagnosticEntry>()
    val coverage = runtimeStatus.hookCoverage

    // 0. 运行环境：宿主 ROM 决定了哪些点位本来就存在，必须先摆出来，
    //    否则用户会把「本版本没有这个子系统」误读成「模块装坏了」。
    entries.add(
        HookDiagnosticEntry(
            id = "run_env",
            title = "运行环境",
            targetClassOrMethod = "ro.mi.os.version.name / ro.miui.ui.version.name",
            level = if (VersionGate.romTier == null) {
                HookDiagnosticLevel.WARNING
            } else {
                HookDiagnosticLevel.PASSED
            },
            summaryText = VersionGate.describeRom(),
            detailText = when (VersionGate.romTier) {
                VersionGate.RomTier.MIUI14 ->
                    "MIUI 14 按旧版 Millet 路径适配，P4 云名单热更新 Hook 使用与澎湃 OS1 相同的版本门控。" +
                        "双开相关 notifyExcuteServices 与 updateTargetList Hook 按宿主方法和能力条件安装；" +
                        "各点位的安装结果见覆盖情况。"
                VersionGate.RomTier.OS1, VersionGate.RomTier.OS2 ->
                    "澎湃 OS 早期版本保留 com.miui.powerkeeper.millet 子系统，云控白名单、灭屏清理与无限制名单均在该子系统内接管。"
                VersionGate.RomTier.OS3 ->
                    "澎湃 OS3 已移除 com.miui.powerkeeper.millet 与 com.miui.process 整块子系统，" +
                        "模块改用 KillProcessController 等新入口接管清理与冻结；缺失的旧点位属于版本差异，不是安装失败。"
                null ->
                    "未能读取 ro.mi.os.version.name 与 ro.miui.ui.version.name，无法判断宿主版本；下面的条目按运行时探测结果展示。"
            }
        )
    )

    // 0.5 LibXposed 框架服务能力、作用域与热重载状态
    if (frameworkState.isServiceConnected) {
        val missingScopes = frameworkState.missingScopes
        val staleTargets = frameworkState.staleTargets
        val fwLevel = when {
            !frameworkState.supportsRemotePrefs -> HookDiagnosticLevel.FAILED
            missingScopes.isNotEmpty() || staleTargets.isNotEmpty() -> HookDiagnosticLevel.WARNING
            else -> HookDiagnosticLevel.PASSED
        }
        val capsSummary = buildString {
            append("${frameworkState.frameworkName} ${frameworkState.frameworkVersion} (API ${frameworkState.apiVersion})")
            append(" | 远程配置=${if (frameworkState.supportsRemotePrefs) "支持" else "不支持"}")
            append(", 系统作用域=${if (frameworkState.supportsSystemHook) "支持" else "不支持"}")
            append(", 作用域=${if (frameworkState.scope.isEmpty()) "空" else frameworkState.scope.joinToString(",")}")
            if (frameworkState.runningTargets.isNotEmpty()) {
                val targetDesc = frameworkState.runningTargets.joinToString(", ") { t ->
                    "${t.processName}:${t.state.name}(v${t.loadedVersionCode})"
                }
                append(" | 目标状态: $targetDesc")
            }
        }
        val fwSuggestion = when {
            !frameworkState.supportsRemotePrefs -> "当前 Xposed 框架未开启 PROP_CAP_REMOTE，无法下发 RemotePreferences 设置"
            missingScopes.isNotEmpty() -> "作用域缺少 ${missingScopes.joinToString("、")}，可点击下方「授权作用域」"
            staleTargets.isNotEmpty() -> "检测到 ${staleTargets.size} 个目标进程仍运行旧代代码，可点击下方「热重载」无需重启更新"
            else -> null
        }
        entries.add(
            HookDiagnosticEntry(
                id = "libxposed_service",
                title = "LibXposed 框架服务",
                targetClassOrMethod = "XposedService (API ${frameworkState.apiVersion})",
                level = fwLevel,
                summaryText = when {
                    !frameworkState.supportsRemotePrefs -> "缺少远程配置能力"
                    missingScopes.isNotEmpty() -> "作用域缺失 ${missingScopes.size} 项"
                    staleTargets.isNotEmpty() -> "${staleTargets.size} 个进程待热重载"
                    else -> "能力与作用域正常"
                },
                detailText = capsSummary,
                suggestion = fwSuggestion,
                optional = fwLevel != HookDiagnosticLevel.FAILED
            )
        )
    }

    // 1. System Server 核心服务
    val sysLevel = when {
        runtimeStatus.binderAlive && runtimeStatus.greezeReady -> HookDiagnosticLevel.PASSED
        runtimeStatus.binderAlive -> HookDiagnosticLevel.WARNING
        else -> HookDiagnosticLevel.FAILED
    }
    entries.add(
        HookDiagnosticEntry(
            id = "system_server",
            title = "System Server 核心服务",
            targetClassOrMethod = "android / ActivityManagerService & GreezeManagerService",
            level = sysLevel,
            summaryText = when (sysLevel) {
                HookDiagnosticLevel.PASSED -> "已激活"
                HookDiagnosticLevel.WARNING -> "Binder 服务在线，Greeze 核心待捕获"
                HookDiagnosticLevel.FAILED -> "未连接系统服务"
            },
            detailText = when (sysLevel) {
                HookDiagnosticLevel.PASSED -> "已成功拦截 AMS 广播注册链路并挂载 SystemBinderService，核心逻辑全部就绪。"
                HookDiagnosticLevel.WARNING -> "SystemBinderService 已就绪并建立通信通道，正在等待系统 Greeze 服务完成初始化。"
                HookDiagnosticLevel.FAILED -> if (frameworkState.isServiceConnected && !frameworkState.hasSystemScope) {
                    "XposedService 报告作用域尚未勾选「system（系统框架）」。请先授权作用域并重启系统。"
                } else {
                    "无法连接至 system_server。请检查 LSPosed 中是否已勾选 XMillet 的「系统框架 (system)」作用域并重启系统。"
                }
            },
            suggestion = if (sysLevel == HookDiagnosticLevel.FAILED) {
                if (frameworkState.isServiceConnected && !frameworkState.hasSystemScope) {
                    "可点击下方「授权作用域」后重启系统"
                } else {
                    "请在 LSPosed 作用域中确认勾选「系统框架」"
                }
            } else null
        )
    )

    // 2. 电量与性能 PowerKeeper 策略
    //    目标子系统随 ROM 版本不同：OS3 起 millet 已移除，仍宣称 MilletPolicy 会误导用户。
    val capMillet = coverage.capability(HookCoverage.CAP_MILLET)
    val capController = coverage.capability(HookCoverage.CAP_CONTROLLER)
    val pkTarget = when {
        capMillet == true -> "com.miui.powerkeeper.millet.MilletPolicy & FreezeBinder"
        capMillet == false && capController == true ->
            "com.miui.powerkeeper.controller.KillProcessController & FrozenAppController"
        capMillet == false -> "com.miui.powerkeeper（本版本无 millet / KillProcessController 点位）"
        else -> "com.miui.powerkeeper（宿主能力位未上报）"
    }
    val pkLevel = when {
        runtimeStatus.powerKeeperConnected -> HookDiagnosticLevel.PASSED
        runtimeStatus.binderAlive -> HookDiagnosticLevel.WARNING
        else -> HookDiagnosticLevel.FAILED
    }
    entries.add(
        HookDiagnosticEntry(
            id = "powerkeeper",
            title = "电量与性能 PowerKeeper",
            targetClassOrMethod = pkTarget,
            level = pkLevel,
            summaryText = when (pkLevel) {
                HookDiagnosticLevel.PASSED -> "已连接"
                HookDiagnosticLevel.WARNING -> "等待双向握手"
                HookDiagnosticLevel.FAILED -> "未连接"
            },
            detailText = when (pkLevel) {
                HookDiagnosticLevel.PASSED -> when {
                    capMillet == true ->
                        "云控白名单屏蔽、灭屏清理拦截与无限制名单同步均已在该版本存在的 millet 子系统上接管。"
                    capMillet == false ->
                        "该版本没有 millet 子系统，清理与冻结已改由 KillProcessController 等点位接管；" +
                            "下面「Hook 安装点覆盖」列出的是本机真实装上的点位。"
                    else ->
                        "已与「电量与性能」建立双向 Binder 回调；具体接管了哪些点位见下方安装点覆盖。"
                }
                HookDiagnosticLevel.WARNING -> if (frameworkState.isServiceConnected && !frameworkState.hasPowerKeeperScope) {
                    "XposedService 报告作用域尚未勾选「com.miui.powerkeeper」，授权后强行停止电量与性能即可接入。"
                } else {
                    "系统服务已就绪，但「电量与性能」尚未完成双向 Binder 回调注册。亮灭屏一次或强行停止电量与性能即可自动握手。"
                }
                HookDiagnosticLevel.FAILED ->
                    "未能连接至 com.miui.powerkeeper。请在 LSPosed 中确认勾选「电量与性能」作用域。"
            },
            suggestion = when (pkLevel) {
                HookDiagnosticLevel.WARNING -> if (frameworkState.isServiceConnected && !frameworkState.hasPowerKeeperScope) {
                    "可点击下方「授权作用域」"
                } else {
                    "请按电源键灭屏并亮屏一次，或在设置中结束「电量与性能」"
                }
                HookDiagnosticLevel.FAILED -> "请在 LSPosed 作用域中勾选「电量与性能」"
                else -> null
            },
            // 系统服务已就绪、只是 powerkeeper 还没被拉起完成握手时，属于可选能力，
            // 不计入就绪度：该项在下一次亮灭屏或推送时补齐；作用域明确未勾选时计入失败/待修。
            optional = pkLevel == HookDiagnosticLevel.WARNING &&
                (!frameworkState.isServiceConnected || frameworkState.hasPowerKeeperScope)
        )
    )

    // 3. Hook 安装点覆盖：两侧进程各自记账、汇总后展示真实数量
    entries.add(buildCoverageEntry(runtimeStatus))

    // 4. 小米 Greeze 墓碑驱动服务
    val greezeLevel = if (runtimeStatus.greezeReady) HookDiagnosticLevel.PASSED else HookDiagnosticLevel.FAILED
    entries.add(
        HookDiagnosticEntry(
            id = "greeze_service",
            title = "小米 Greeze 墓碑驱动服务",
            targetClassOrMethod = "com.miui.server.greeze.GreezeManagerService",
            level = greezeLevel,
            summaryText = if (greezeLevel == HookDiagnosticLevel.PASSED) "运行中" else "未捕获",
            detailText = if (greezeLevel == HookDiagnosticLevel.PASSED)
                "GreezeManagerService 句柄已正常持有，底层 AIDL 驱动接口正常，支持 freezeAction 与 thawUid 压制。"
            else
                "未检测到 GreezeManagerService 实例。请确认当前设备为小米官方澎湃 OS（OS1 / OS2 / OS3）。"
        )
    )

    // 5. Aurogon 神隐模式监听
    val aurogonLevel = if (runtimeStatus.aurogonReady) {
        HookDiagnosticLevel.PASSED
    } else if (runtimeStatus.binderAlive) {
        HookDiagnosticLevel.WARNING
    } else {
        HookDiagnosticLevel.FAILED
    }
    entries.add(
        HookDiagnosticEntry(
            id = "aurogon_mode",
            title = "Aurogon 神隐模式 (前台/音视频感知)",
            targetClassOrMethod = "com.miui.server.greeze.AurogonImmobulusMode",
            level = aurogonLevel,
            summaryText = if (aurogonLevel == HookDiagnosticLevel.PASSED) "已就绪" else "待捕获",
            detailText = if (aurogonLevel == HookDiagnosticLevel.PASSED)
                "Aurogon 实例已捕获，前台服务状态检测与消息应用豁免联动正常。"
            else
                "Aurogon 实例尚未初始化，音视频播放与前台服务免冻感知将由系统默认逻辑兜底。"
        )
    )

    // 6. Doze 深度睡眠状态机与省电白名单
    val dozeLevel = if (runtimeStatus.dozeReady) {
        HookDiagnosticLevel.PASSED
    } else if (runtimeStatus.binderAlive) {
        HookDiagnosticLevel.WARNING
    } else {
        HookDiagnosticLevel.FAILED
    }
    entries.add(
        HookDiagnosticEntry(
            id = "doze_controller",
            title = "Doze 状态机与省电优化白名单",
            targetClassOrMethod = "com.android.server.DeviceIdleController",
            level = dozeLevel,
            summaryText = if (dozeLevel == HookDiagnosticLevel.PASSED) "已接管" else "待捕获",
            detailText = if (dozeLevel == HookDiagnosticLevel.PASSED)
                "DeviceIdleController 实例已就绪，Doze 状态迁移监听与电池优化白名单/黑名单同步链路通畅。"
            else
                "DeviceIdleController 待捕获。"
        )
    )

    // 7. 内核冻结器（cgroup v2 / v1）—— 仅描述内核能力，不是功能前提
    val hasKernelFreezer = kernelFreezer == RootHelper.KernelFreezer.CGROUP_V2 ||
        kernelFreezer == RootHelper.KernelFreezer.CGROUP_V1
    val cgroupLevel = if (hasKernelFreezer) HookDiagnosticLevel.PASSED else HookDiagnosticLevel.WARNING
    entries.add(
        HookDiagnosticEntry(
            id = "kernel_freezer",
            title = "内核冻结器 (CGroup v2 / v1)",
            targetClassOrMethod = when (kernelFreezer) {
                RootHelper.KernelFreezer.CGROUP_V2 -> "/sys/fs/cgroup/apps/cgroup.freeze (cgroup2)"
                RootHelper.KernelFreezer.CGROUP_V1 -> "/sys/fs/cgroup/freezer (freezer.state)"
                else -> "未发现 cgroup.freeze / freezer 挂载点"
            },
            level = cgroupLevel,
            summaryText = when (kernelFreezer) {
                RootHelper.KernelFreezer.CGROUP_V2 -> "支持 (CGroup v2)"
                RootHelper.KernelFreezer.CGROUP_V1 -> "支持 (CGroup v1)"
                RootHelper.KernelFreezer.NONE -> "内核未提供 cgroup 冻结器"
                RootHelper.KernelFreezer.UNKNOWN -> "未能探测"
            },
            detailText = when (kernelFreezer) {
                RootHelper.KernelFreezer.CGROUP_V2 ->
                    "内核已启用 CGroup v2 Freezer（freezer 控制器下放到 apps / system 子树，冻结粒度 apps/uid_<uid>/pid_<pid>），冻结的进程在 wchan 上表现为 do_freezer_trap，图标标为 V2。"
                RootHelper.KernelFreezer.CGROUP_V1 ->
                    "内核只提供旧版 CGroup v1 Freezer，冻结的进程在 wchan 上表现为 __refrigerator，图标标为 V1。这是正常能力差异，不影响墓碑冻结。"
                RootHelper.KernelFreezer.NONE ->
                    "本机内核没有可用的 cgroup 冻结器，墓碑冻结由小米 Greeze 服务自行实现，进程 wchan 会落到 do_signal_stop（图标标为 Kill）。该项不影响功能。"
                RootHelper.KernelFreezer.UNKNOWN ->
                    "冻结器类型由模块在 system_server 内探测（/sys/fs/cgroup 与 /proc/<pid>/wchan 的读权限都在框架侧），" +
            "当前未拿到结果，请确认模块已加载后重新检测。"
            },
            suggestion = if (hasKernelFreezer) null else "该检测只用于说明冻结机制类型，不是功能前提，可忽略"
        )
    )

    // 8. 当前墓碑冻结 UID 监测
    val frozenCount = runtimeStatus.frozenUids.size
    entries.add(
        HookDiagnosticEntry(
            id = "frozen_monitor",
            title = "实时墓碑冻结状态",
            targetClassOrMethod = "GreezeManagerService#getFrozenUids",
            level = HookDiagnosticLevel.PASSED,
            summaryText = "${frozenCount} 个应用已冻结",
            detailText = if (frozenCount > 0)
                "当前共有 $frozenCount 个应用处于 Greeze 墓碑冻结状态，CPU 占用降为 0%，等待快速唤醒。"
            else
                "当前没有处于冻结状态的应用。后台应用满足闲置与空闲条件时将自动进入墓碑。"
        )
    )

    return entries
}

/**
 * 生成「Hook 安装点覆盖」条目。
 *
 * 数量来自两侧进程的真实账本（[io.github.univers629.millet.core.HookInstallRegistry]），
 * 而不是写死的常量。要求模块与 App 都较新才会两者齐备：缺数据时显示「未上报」，
 * 不把 0 当作结果。
 */
private fun buildCoverageEntry(
    runtimeStatus: PowerKeeperBinderClient.RuntimeStatusSnapshot
): HookDiagnosticEntry {
    val coverage = runtimeStatus.hookCoverage
    if (!coverage.hasData) {
        return HookDiagnosticEntry(
            id = "hook_coverage",
            title = "Hook 安装点覆盖",
            targetClassOrMethod = "HookInstallRegistry (system_server + powerkeeper)",
            level = HookDiagnosticLevel.WARNING,
            summaryText = "未上报",
            detailText = "当前连接的模块尚未回传安装点账本（该字段随模块升级加入）。" +
                "这里保持未知而不是显示 0，避免把「查不到」误报成「一个都没装上」。",
            suggestion = "更新模块并重启系统后重新检测"
        )
    }

    val total = coverage.totalCount
    val installed = coverage.installedCount
    val failed = coverage.failedIds
    val level = if (failed.isEmpty()) HookDiagnosticLevel.PASSED else HookDiagnosticLevel.WARNING

    val detail = buildString {
        if (coverage.sys.hasData) {
            append("系统侧 ").append(coverage.sys.installed).append('/').append(coverage.sys.total)
        } else {
            append("系统侧 未上报")
        }
        append("，电量与性能侧 ")
        if (coverage.pk.hasData) {
            append(coverage.pk.installed).append('/').append(coverage.pk.total)
        } else {
            append("未上报")
        }
        val unsupported = coverage.unsupportedIds
        if (unsupported.isNotEmpty()) {
            append("。本版本没有对应宿主类/方法而跳过：").append(unsupported.joinToString("、"))
        }
        val gated = coverage.gatedIds
        if (gated.isNotEmpty()) {
            append("。版本门控不满足而跳过：").append(gated.joinToString("、"))
        }
        if (failed.isNotEmpty()) {
            append("。安装时报错：").append(failed.joinToString("、"))
        }
        if (unsupported.isEmpty() && gated.isEmpty() && failed.isEmpty()) {
            append("。全部点位均已装上。")
        }
    }

    return HookDiagnosticEntry(
        id = "hook_coverage",
        title = "Hook 安装点覆盖",
        targetClassOrMethod = "HookInstallRegistry (system_server + powerkeeper)",
        level = level,
        summaryText = "$installed/$total 个安装点生效",
        detailText = detail,
        suggestion = if (failed.isEmpty()) null else "查看本地日志 /data/system/XMillet/log 中对应点位的失败原因"
    )
}

/**
 * 依据 Droidspaces 规范设计、遵循 Miuix 规范的 Hook 点位与运行环境检测弹窗。
 */
@Composable
fun HookCheckDialogMiuix(
    show: Boolean,
    runtimeStatus: PowerKeeperBinderClient.RuntimeStatusSnapshot,
    kernelFreezer: RootHelper.KernelFreezer = RootHelper.KernelFreezer.UNKNOWN,
    frameworkState: XposedPrefsBridge.XposedFrameworkState = XposedPrefsBridge.XposedFrameworkState(),
    onRequestScope: (() -> Unit)? = null,
    onHotReloadStale: (() -> Unit)? = null,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    // 居中宽屏/横屏弹窗（如平板横屏）可用高度明显小于竖屏底部弹窗，须以容器上限 + weight(1f)
    // 优先保证头部总览与底部操作栏不被裁剪或与 LazyColumn 重叠。
    val isCompactOrWideWindow =
        configuration.screenHeightDp < 500 || configuration.screenWidthDp > configuration.screenHeightDp
    val maxDialogBodyHeight = if (isCompactOrWideWindow) {
        (screenHeight * 0.56f).coerceIn(200.dp, 460.dp)
    } else {
        (screenHeight * 0.68f).coerceIn(260.dp, 620.dp)
    }
    val diagnostics = remember(runtimeStatus, kernelFreezer, frameworkState) {
        generateHookDiagnostics(runtimeStatus, kernelFreezer, frameworkState)
    }
    // 可选项（可选能力）不参与就绪度统计，避免开机瞬间电源管理未拉起时整页变黄。
    val requiredDiagnostics = diagnostics.filterNot { it.optional }
    val passedCount = requiredDiagnostics.count { it.level == HookDiagnosticLevel.PASSED }
    val totalCount = requiredDiagnostics.size

    val overallLevel = when {
        requiredDiagnostics.all { it.level == HookDiagnosticLevel.PASSED } -> HookDiagnosticLevel.PASSED
        requiredDiagnostics.any { it.level == HookDiagnosticLevel.FAILED } -> HookDiagnosticLevel.FAILED
        else -> HookDiagnosticLevel.WARNING
    }

    WindowDialog(
        show = show,
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
        title = "运行环境与服务检测",
        onDismissRequest = onDismiss,
        content = {
            val dismissState = LocalDismissState.current

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxDialogBodyHeight)
                    .padding(top = 4.dp)
            ) {
                // 1. 顶部总览状态卡片 (对齐 Droidspaces 状态头)
                OverallStatusHeader(
                    level = overallLevel,
                    passedCount = passedCount,
                    totalCount = totalCount
                )

                Spacer(Modifier.height(12.dp))

                // 2. 诊断项目列表（占剩余高度，避免挤压底部按钮）
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(diagnostics, key = { it.id }) { item ->
                        DiagnosticItemCard(item = item)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 2.5 按需显示 LibXposed 快捷修复操作（缺失作用域授权 / 过期目标进程热重载）
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
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showRequestScopeAction) {
                            TextButton(
                                text = "授权作用域",
                                onClick = onRequestScope,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (showHotReloadAction) {
                            TextButton(
                                text = "热重载 (${frameworkState.staleTargets.size})",
                                onClick = onHotReloadStale,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // 3. 底部操作栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        text = "重新检测",
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(Modifier.width(12.dp))

                    TextButton(
                        text = "完成",
                        onClick = {
                            onDismiss()
                            dismissState?.invoke()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        }
    )
}

@Composable
private fun OverallStatusHeader(
    level: HookDiagnosticLevel,
    passedCount: Int,
    totalCount: Int
) {
    val containerColor = when (level) {
        HookDiagnosticLevel.PASSED -> Color(0xFF1B3B24)
        HookDiagnosticLevel.WARNING -> Color(0xFF3E2F1B)
        HookDiagnosticLevel.FAILED -> Color(0xFF381515)
    }

    val iconColor = when (level) {
        HookDiagnosticLevel.PASSED -> Color(0xFF34C759)
        HookDiagnosticLevel.WARNING -> Color(0xFFFF9500)
        HookDiagnosticLevel.FAILED -> Color(0xFFFF3B30)
    }

    val titleText = when (level) {
        HookDiagnosticLevel.PASSED -> "所有服务运行正常"
        HookDiagnosticLevel.WARNING -> "部分服务待就绪"
        HookDiagnosticLevel.FAILED -> "XMillet 核心服务未连接"
    }

    val tagText = when (level) {
        HookDiagnosticLevel.PASSED -> "全部生效"
        HookDiagnosticLevel.WARNING -> "部分就绪"
        HookDiagnosticLevel.FAILED -> "未激活"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .border(1.dp, iconColor.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (level) {
                    HookDiagnosticLevel.PASSED -> Icons.Rounded.CheckCircle
                    HookDiagnosticLevel.WARNING -> Icons.Rounded.Warning
                    HookDiagnosticLevel.FAILED -> Icons.Rounded.Cancel
                },
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(32.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = titleText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    StatusTagMiuix(
                        label = tagText,
                        backgroundColor = iconColor.copy(alpha = 0.25f),
                        contentColor = iconColor
                    )
                }

                Spacer(Modifier.height(2.dp))

                Text(
                    text = "$totalCount 项检测中已就绪 $passedCount 项",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }
        }
    }
}

@Composable
private fun DiagnosticItemCard(item: HookDiagnosticEntry) {
    val statusColor = when (item.level) {
        HookDiagnosticLevel.PASSED -> Color(0xFF34C759)
        HookDiagnosticLevel.WARNING -> Color(0xFFFF9500)
        HookDiagnosticLevel.FAILED -> Color(0xFFFF3B30)
    }

    val tagLabel = when (item.level) {
        HookDiagnosticLevel.PASSED -> "PASS"
        HookDiagnosticLevel.WARNING -> "WARN"
        HookDiagnosticLevel.FAILED -> "FAIL"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (item.level) {
                        HookDiagnosticLevel.PASSED -> Icons.Rounded.CheckCircle
                        HookDiagnosticLevel.WARNING -> Icons.Rounded.Warning
                        HookDiagnosticLevel.FAILED -> Icons.Rounded.Cancel
                    },
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(Modifier.width(10.dp))

                Text(
                    text = item.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                StatusTagMiuix(
                    label = tagLabel,
                    backgroundColor = statusColor.copy(alpha = 0.18f),
                    contentColor = statusColor
                )
            }

            Spacer(Modifier.height(6.dp))

            // 目标类或挂载点代码签名
            Text(
                text = item.targetClassOrMethod,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(colorScheme.surface.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            Spacer(Modifier.height(6.dp))

            // 状态描述
            Text(
                text = item.detailText,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = colorScheme.onSurfaceVariantSummary
            )

            // 操作建议提示
            if (item.suggestion != null) {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = MiuixIcons.Info,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = item.suggestion,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = statusColor
                    )
                }
            }
        }
    }
}