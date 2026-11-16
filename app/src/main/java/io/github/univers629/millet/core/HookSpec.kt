package io.github.univers629.millet.core

/**
 * Hook 拦截时机枚举。
 */
enum class HookMode {
    /** 仅在目标方法执行前回调（可通过 param.result = ... 提前短路原方法） */
    BEFORE,

    /** 仅在目标方法执行后回调（可读取或改写 param.result） */
    AFTER,

    /** 同时挂载 before 与 after 回调 */
    BEFORE_AND_AFTER,

    /** 完全替换目标方法实现（不自动调原实现，除非显式调用 param.invokeOriginal()） */
    REPLACE
}

/**
 * 声明式单点 Hook 规格描述（Modern LibXposed 结构）。
 *
 * @property id 规格编号（如 "S1".."S20"、"P1".."P20"）
 * @property name 语义化名称（用于日志与错误定位）
 * @property targetClass 目标类全限定名
 * @property methodName 目标方法名；若为 `null` 则代表 Hook 构造器 `<init>`
 * @property paramTypes 参数类型列表（支持 `Class<*>` 或类名 `String`）
 * @property paramResolver 可选的运行期动态参数解析器（如 S5 ProcessErrorStateRecord#appNotResponding）
 * @property mode 拦截时机（BEFORE / AFTER / BEFORE_AND_AFTER / REPLACE）
 * @property priority 优先级（默认 50）
 * @property gate 安装期门控条件（默认恒真；如 S9/S10 按 SDK_INT、S16–S18 按 simplifyLog、P4 按 OS1.0）
 * @property successLog 安装成功后输出的日志文案（null 表示原规格无成功回执文案）
 * @property failureHint 安装失败时的补充告警文案
 * @property before 前置回调实现
 * @property after 后置回调实现
 * @property replace 整方法替换回调实现
 */
data class HookSpec(
    val id: String,
    val name: String,
    val targetClass: String,
    val methodName: String?,
    val paramTypes: List<Any> = emptyList(),
    val paramResolver: ((ClassLoader, Class<*>) -> List<Any>?)? = null,
    val mode: HookMode = HookMode.BEFORE,
    val priority: Int = 50,
    val gate: () -> Boolean = { true },
    val successLog: String? = null,
    val failureHint: String? = null,
    val before: ((UnifiedHookParam) -> Unit)? = null,
    val after: ((UnifiedHookParam) -> Unit)? = null,
    val replace: ((UnifiedHookParam) -> Any?)? = null
)
