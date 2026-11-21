# XMillet 优化说明（本 fork 相对上游 univers629/XMillet 的改动）

本分支 `optimize/hotpath` 聚焦**热路径性能**与**稳健性**优化，不动 UI 与核心 hook 行为，改动集中在 4 个文件。

## 已实施改动

### 优化 #1 — 日志等级解析缓存（`core/Logger.kt`）
- 原实现每条日志都调用 `Level.fromConfig(prefs.logLevel)`，内部做 `lowercase(Locale.ROOT)` + `when` 匹配，产生字符串分配。
- 改为 `resolveMinLevel()`：`logLevel` 配置字符串未变时直接复用缓存的 `Level` 枚举，仅引用比较；`@Volatile` 保证热重载后可见。
- 顺带移除 `emit()` 中未使用的 `formattedLine` 拼接（原本每次白做一次字符串拼接分配）。

### 优化 #2 — Hook 透传快路径（`core/ModernHookInstaller.kt`）
- `buildHooker` 的 `BEFORE` / `BEFORE_AND_AFTER` 分支，原本无论是否声明回调都做 `chain.args.toTypedArray()` + `origArgs.copyOf()` 两次数组分配。
- 新增快路径：`BEFORE` 且 `before == null`、`BEFORE_AND_AFTER` 且 `before == null && after == null` 时直接 `chain.proceed()`，省掉两次分配。
- 注：libxposed API 102 的 `Chain.getArgs()` 返回 `List<Object>`，有回调时这两次转换仍必需，未删。

### 优化 #3 — 日志落盘复用 writer（`core/Logger.kt`）
- 原实现每条日志 `new SimpleDateFormat` + `FileWriter(...).use{}` 开/关 fd。
- 改为单线程 `XMillet-LogIo` Handler 内复用 `SimpleDateFormat` 与常开 `FileWriter`，每条 `flush()`。
- 处理日志轮转：`initSystemLogRotation()` rename `log→last.log` 前先 `closeLocalWriter()`，下次写入按路径存在性自动重建 writer；热重载 `onHotReloading()` 也关闭 writer。

### 优化 #4 — RootShell 哨兵随机化（`core/RootShell.kt`）
- 原哨兵为固定前缀 + 可预测递增序号，命令输出理论上可伪造/撞车提前结束读取。
- 改为每次附加随机 UUID 段（去掉连字符），并用 `printf '%s\n'` 替代 `echo`，规避不同 shell 对 `-e` / 反斜杠转义的解释差异；命令后补换行确保哨兵独占一行。

### 文档一致性（`core/HookInstallRegistry.kt`）
- 注释里写死的「51 条以上」已与 README 的点位数漂移，改为「随版本持续增长」。

## 未实施（评估后放弃 / 留待后续）
- **UI 大文件拆分**（`TombStatsScreenMiuix.kt` 100KB 等）：纯可维护性，风险/收益比低，本轮不动。
- **`PrefReader` 增量重载改名**：语义问题不影响正确性，本轮不动。
- **`PrefsSnapshot` 加 `logLevelEnum` 字段**：会把改动扩散到数据结构，`Logger` 内部缓存已达同等效果，放弃。

## 构建状态
- 上游要求 Gradle 9.3.1（`gradle-wrapper.properties`），本机需下载该发行版后用 `./gradlew :app:compileDebugKotlin` 验证。

## 来源与许可
- 上游：https://github.com/univers629/XMillet （GPL-3.0）
- 本 fork 保留原 LICENSE 与署名，遵循 GPL-3.0 开源。
