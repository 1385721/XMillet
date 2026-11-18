<div align="center">

<img src="imgs/icon.webp" width="160" height="160" style="display: block; margin: 0 auto;" alt="XMillet icon" />

# XMillet

### 面向小米澎湃 OS 官方墓碑机制（Millet）的现代 LSPosed 控制与统计面板

![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-13%2B%20(API%2033)-3DDC84?style=flat-square&logo=android&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-21-437291?style=flat-square&logo=openjdk&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-9.3.1-02303A?style=flat-square&logo=gradle&logoColor=white)
![HyperOS](https://img.shields.io/badge/HyperOS-OS1.0%20~%20OS4.0-FF6900?style=flat-square&logo=xiaomi&logoColor=white)

[<img src="https://github.com/LSPosed.png" height="20" alt="LSPosed">](https://github.com/LSPosed)
![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-FF4088?style=flat-square)
[<img src="https://github.com/compose-miuix-ui.png" height="20" alt="Miuix">](https://github.com/compose-miuix-ui/miuix)
![Miuix](https://img.shields.io/badge/Miuix-0.9.4-C9603F?style=flat-square)

</div>

---

## 简介

**XMillet** 是针对小米澎湃 OS 官方墓碑机制（**Millet**，系统底层由 `GreezeManagerService` 与 `PowerKeeper` 协同调度与执行）构建的 Xposed 模块，基于 `libxposed` API 102 与 Miuix Compose UI 实现。它**不替换系统原有冻结机制**，而是接管系统侧与电量侧的关键冻结/解冻闸门，提供状态观测、分应用策略与唤醒拦截统计面板。

- **支持范围**：Android 13+（minSdk 33），实机适配 HyperOS 1.0 / 2.0 / 3.0 / 4.0（Android 13 ~ 17）
- **LSPosed 作用域**：仅需勾选「系统框架 (`system`)」与「电量与性能 (`com.miui.powerkeeper`)」
- **交互设计**：基于 [Miuix](https://github.com/compose-miuix-ui/miuix)（`0.9.4`）构建，支持液态玻璃悬浮底栏、实时毛玻璃模糊、动态取色（Monet）、首次引导（OOBE）与桌面图标隐藏

## 界面预览

<div align="center">

| 主页总览 | 进程列表 | 墓碑统计 |
| :---: | :---: | :---: |
| <img src="https://raw.githubusercontent.com/univers629/MacrodownNoteAssets/main/dev/XMillet/1.jpg" width="240" alt="XMillet 主页总览" /> | <img src="https://raw.githubusercontent.com/univers629/MacrodownNoteAssets/main/dev/XMillet/2.jpg" width="240" alt="XMillet 进程列表" /> | <img src="https://raw.githubusercontent.com/univers629/MacrodownNoteAssets/main/dev/XMillet/3.jpg" width="240" alt="XMillet 墓碑统计" /> |

</div>

## 核心能力

### 1. 冻结与解冻闸门控制
- 接管 `freezeUids` / `freezeProcess` / `freezePids` 与 `thawUid(s)` / `thawUidAsync` / `thawAll` 等关键入口
- 支持按开关拦截**插电解冻**、**网络唤醒**与**同步 Binder 解冻**，并在查杀检查路径提供阻止查杀保护
- 采用 `libxposed RemotePreferences` 配置通道，常规策略调整支持热重载，无需反复重启设备

### 2. 分应用策略
- 支持为每个应用独立配置：**免冻白名单**、**消息应用**、**强力冻结**、**有前台服务时不冻结**、**忽略网络唤醒**以及 **Doze 白/黑名单**
- 广播放行：联动 `isAllowBroadcast` / `deferBroadcastForMiui`，在严格冻结的同时保障消息应用正常收发推送
- 云名单兼容：直接走系统 `GlobalFeatureConfigureHelper`，不自行篡改解析底层数据库
- 支持系统/三方应用筛选、搜索定位，以及策略配置的导出备份与一键恢复

### 3. 内核态观测与实时状态
- 通过跨进程 Binder IPC 实时读取 `system_server` 中的 Greeze（Millet 底层冻结服务）运行态与 Hook 实装状态
- 冻结状态直接取自 `/sys/fs/cgroup` 内核真值，「主页」「进程列表」与「应用详情」同源一致

### 4. 墓碑统计面板
- 在 `system_server` 内使用内存定长结构与紧凑 `v1` 行协议聚合与持久化，自动过滤无独立包名的沙盒/隔离进程（`90000..99999`），并兼容 MIUI 双开应用包名与图标解析
- 提供**今日 / 近 7 天 / 开机以来**三个统计窗口：涵盖总览指标、息屏唤醒频次、唤醒原因分类（用户打开、网络、Binder、广播通知、亮屏、充电等）、分时亮/息屏柱状分布图、XMillet 拦截明细、多口径应用排行（「仅三方」筛选 + 搜索 + 单应用详情弹窗）以及最近动态时间线
- 支持在设置页一键开启或关闭统计采集（关闭后自动隐藏底栏「墓碑统计」入口）与重置统计数据

## 版本适配与 Hook 探测

模块在注入前会对目标类与方法逐一探测：系统侧共 **30 个**点位、电量侧共 **28 个**点位。当 HyperOS 3.0 / 4.0 等新版本移除部分旧接口（如 `com.miui.powerkeeper.millet` 相关点位）时，对应 Hook 会自动安全跳过，并在应用内自检弹窗标明「版本差异」，避免误报异常。

## 实机核验

四台不同代际设备的实机核验 Hook 实装情况如下：

| 设备 | 代号 | 系统版本 | Android | 内核 | Root | LSPosed | Hook 实装（系统侧 + 电量侧） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Redmi K40 游戏增强版 (M2012K10C) | `ares` | OS1.0.4.0.TKJCNXM | 13 (SDK 33) | `4.14.186-g844fd19c6` | Magisk | v2.2.1-log (7940) | 23/30 + 25/28 |
| Xiaomi Pad 5 Pro 12.4 (22081281AC) | `dagu` | OS2.0.10.0.ULZCNXM | 14 (SDK 34) | `4.19.157-perf-gb57830672689` | Magisk | v2.2.1-log (7939) | 25/30 + 25/28 |
| REDMI K80 Pro (24122RKC7C) | `miro` | OS3.0.304.0.WOMCNXM | 16 (SDK 36) | `6.6.118-android15-8-gc44b714366cc-abogki519650608-4k` | KernelSU | v2.2.1 (7940) | 26/30 + 17/28 |
| Xiaomi 15 (24129PN74C) | `dada` | OS4.0.4.0.XOCCNXM | 17 (SDK 37) | `6.6.118-android15-8-gf72a02f47f4b-abogki551678766-4k` | KernelSU | v2.2.1 (7939) | 29/30 + 14/28 |

> 默认配置下关闭「简化日志 (`simplifyLog`)」，开启该开关后前三台设备的系统侧 Hook 实装数可分别升至 `29/30`、`28/30`、`26/30`；`dada` 一行采集于开启 `simplifyLog` 状态。

## 使用与构建

### 使用步骤
1. 安装 XMillet APK；
2. 在 **LSPosed 管理器** 中启用本模块，并仅勾选作用域：**系统框架 (`system`)** 与 **电量与性能 (`com.miui.powerkeeper`)**；
3. 重启设备（或按需软重启相关作用域），打开 XMillet 即可完成 OOBE 检测并开始管理。

### 构建

构建环境需要 **JDK 21**，支持 `arm64-v8a` 架构：

```bash
./gradlew :app:assembleDebug
```

## 开源许可

本项目基于 [GNU General Public License v3.0](LICENSE) 开源发布。

---

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)
