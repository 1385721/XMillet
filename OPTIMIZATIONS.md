# XMillet 优化说明（本 fork 相对上游 univers629/XMillet 的改动）

本分支 `optimize/hotpath` 聚焦**热路径性能**与**稳健性**优化，并在日志设置上做了功能性调整（新增 OFF 等级、去除落盘），改动集中在若干文件。

> 版本对齐：已合并上游 v1.4.0（merge `7c292c7`）。以下「已实施改动」为 fork 相对上游的持续改动；涉及上游重构的部分（下拉控件/状态架构）已按 v1.4.0 同步更新。

## 已实施改动

### 优化 #1 — 日志等级解析缓存（`core/Logger.kt`）
- 原实现每条日志都调用 `Level.fromConfig(prefs.logLevel)`，内部做 `lowercase(Locale.ROOT)` + `when` 匹配，产生字符串分配。
- 改为 `resolveMinLevel()`：`logLevel` 配置字符串未变时直接复用缓存的 `Level` 枚举，仅引用比较；`@Volatile` 保证热重载后可见。
- 顺带移除 `emit()` 中未使用的 `formattedLine` 拼接（原本每次白做一次字符串拼接分配）。

### 优化 #2 — Hook 透传快路径（`core/ModernHookInstaller.kt`）
- `buildHooker` 的 `BEFORE` / `BEFORE_AND_AFTER` 分支，原本无论是否声明回调都做 `chain.args.toTypedArray()` + `origArgs.copyOf()` 两次数组分配。
- 新增快路径：`BEFORE` 且 `before == null`、`BEFORE_AND_AFTER` 且 `before == null && after == null` 时直接 `chain.proceed()`，省掉两次分配。
- 注：libxposed API 102 的 `Chain.getArgs()` 返回 `List<Object>`，有回调时这两次转换仍必需，未删。

### 优化 #3 — 已撤销：日志落盘（原「复用 writer」，`core/Logger.kt`）
- 原实现每条日志 `new SimpleDateFormat` + `FileWriter(...).use{}` 开/关 fd；本分支一度改为单线程 `XMillet-LogIo` Handler 内复用 `SimpleDateFormat` 与常开 `FileWriter`。
- 按最新需求**完全移除日志落盘**：删除 `LOCAL_LOG_PATH`/`LOCAL_LAST_LOG_PATH` 写入、`appendLineToLocalFile()`/`ensureLocalWriter()`/`closeLocalWriter()`、`initSystemLogRotation()` 及 `emit()` 的落盘分支；日志只输出到 Modern LibXposed / Android Logcat，彻底避免文件 IO。
- `LOCAL_LOG_DIR` 常量保留（`BugreportHelper` 用它拼 `freeze_stats.json` 路径）；`postIoTask()` 与 Handler 基础设施保留（供 `/proc` 等非阻塞读取复用）。
- `ModernHookEntry.bootstrapSystemServer()` 同步去掉 `initSystemLogRotation()` 调用；`BugreportHelper` 不再收集 `xmillet_log.txt`/`xmillet_last.log`。

### 功能 — 日志等级新增「关闭(OFF)」（`core/Logger.kt` / `ui/screen/SettingsScreenMiuix.kt`）
- `Level` 新增 `OFF("-", 5)`（优先级高于 ERROR），`fromConfig` 识别 `off`/`o`。
- `emit()` 开头显式 `if (minLevel == OFF) return`——因 ERROR 有 `level != ERROR` 穿透逻辑，仅靠优先级无法拦截 ERROR，必须靠此门保证 OFF 时**含 alwaysWarn/error 在内完全不输出**。
- 设置页日志卡片新增「日志等级」下拉（关闭/仅错误/警告/信息/调试）：选「关闭」同步写 `IS_LOG=false`，避免 hook 侧 `if (!prefs.isLog)` 门控与等级不一致；等级与开关双向联动。
- **v1.4.0 适配上游 UI 重构**：上游把配置状态从局部 `remember(configVersion)` 改为 `PrefsSnapshot` 响应式流（`SettingsViewModel` / `PolicyRepository`），并把控件从 `OverlayDropdownPreference` + material `Icons` 全换成 `GlassDropdownPreference(backdrop=...)` + `MiuixIcons`。本 fork 下拉同步迁移：从 `uiState.prefs.logLevel` 读，经 `SettingsViewModel.setLogLevel` → `PolicyRepository.setLogLevel(level, syncIsLog)` 写入（`syncIsLog=true` 时连同 `IS_LOG` 一起写），图标用 `MiuixIcons.Glass.Log`。迁移前会报 `Unresolved reference: OverlayDropdownPreference / Icons` 编译失败。

### 优化 #4 — RootShell 哨兵随机化（`core/RootShell.kt`）
- 原哨兵为固定前缀 + 可预测递增序号，命令输出理论上可伪造/撞车提前结束读取。
- 改为每次附加随机 UUID 段（去掉连字符），并用 `printf '%s\n'` 替代 `echo`，规避不同 shell 对 `-e` / 反斜杠转义的解释差异；命令后补换行确保哨兵独占一行。

### 文档一致性（`core/HookInstallRegistry.kt`）
- 注释里写死的「51 条以上」已与 README 的点位数漂移，改为「随版本持续增长」。

## 未实施（评估后放弃 / 留待后续）
- **UI 大文件拆分**：fork 曾认为风险/收益比低而不动；上游 v1.4.0 已自行实施（新增 `ui/screen/tombstats/TombStatsSections.kt`，`TombStatsScreenMiuix.kt` 瘦身为壳），本 fork 随 merge 继承，无需自己做。
- **`PrefReader` 增量重载改名**：语义问题不影响正确性，本轮不动。
- **`PrefsSnapshot` 加 `logLevelEnum` 字段**：上游 v1.4.0 的 `PrefsSnapshot` 已有字符串 `logLevel`（经 `PrefsSchema.normalizeLogLevel` 规范化），fork 只需读它并在 `Logger` 内部缓存解析结果，无需再加 enum 字段扩散数据结构。

## 构建状态（v1.4.0，2026-11-26）
- 上游要求 **Gradle 9.8.0 + AGP 9.5.2 + JDK 21**（`gradle-wrapper.properties` / `build.gradle.kts`），并因 miuix 0.9.5-rc01 元数据要求 `compileSdk=37` + `compileSdkMinor=2`（SDK Platform 37.2）。旧 9.3.1 不够用。
- 本机验证命令：
  ```bash
  ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --no-daemon --console=plain   # 32 套 / 168/168
  ./gradlew :app:assembleRelease --no-daemon --console=plain                              # 出 fork 签名包
  ```
- 官方 Gradle 源下载可能卡死，可用腾讯云镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-9.8.0-bin.zip`（151,611,662 B）放入 wrapper 缓存。

## 来源与许可
- 上游：https://github.com/univers629/XMillet （GPL-3.0）
- 本 fork 保留原 LICENSE 与署名，遵循 GPL-3.0 开源。
