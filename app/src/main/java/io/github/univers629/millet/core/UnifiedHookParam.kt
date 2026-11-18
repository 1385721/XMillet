package io.github.univers629.millet.core

import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Constructor
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * Modern LibXposed (API 102) 的方法拦截上下文接口。
 */
interface UnifiedHookParam {
    val thisObject: Any?
    val args: Array<Any?>
    var result: Any?
    var isSkipped: Boolean
    var throwable: Throwable?
    val method: Member?

    fun getArg(index: Int): Any? = args.getOrNull(index)

    fun setArg(index: Int, value: Any?) {
        if (index in args.indices) {
            args[index] = value
        }
    }

    fun returnEarly(returnValue: Any?) {
        result = returnValue
        isSkipped = true
    }

    /**
     * 按目标方法真实返回类型安全短路拦截（用于「拒绝 / 阻止」类 Hook）。
     * 仅对 void / boolean / 集合 / 数组设置兼容空值；遇到未知返回类型（例如 int/long/String）
     * 时不触碰 `result`，降级为仅打警告不短路，防止 LibXposed 类型校验抛出 ClassCastException 或拆箱 NPE。
     *
     * @return 是否实际设置了短路返回值
     */
    fun blockSafely(): Boolean {
        val m = method
        val returnType = when (m) {
            is Method -> m.returnType
            is Constructor<*> -> Void.TYPE
            else -> null
        }
        val safeValue: Any? = when {
            returnType == null -> {
                runCatching { Logger.alwaysWarn("blockSafely: 无法识别目标方法返回类型，跳过短路") }
                return false
            }
            returnType == Void.TYPE -> null
            returnType == Boolean::class.javaPrimitiveType ||
                returnType == Boolean::class.javaObjectType -> false
            Set::class.java.isAssignableFrom(returnType) -> emptySet<Any>()
            List::class.java.isAssignableFrom(returnType) ||
                Collection::class.java.isAssignableFrom(returnType) -> emptyList<Any>()
            returnType.isArray -> ReflectArray.newInstance(returnType.componentType!!, 0)
            else -> {
                runCatching {
                    Logger.alwaysWarn(
                        "blockSafely: ${m?.declaringClass?.simpleName}#${m?.name} 返回类型 ${returnType.simpleName} 不支持自动短路，已退化放行"
                    )
                }
                return false
            }
        }
        result = safeValue
        return true
    }

    fun invokeOriginal(): Any?
}

/**
 * 统一 HookParam 默认实现。
 */
class UnifiedHookParamImpl(
    override val thisObject: Any?,
    override val args: Array<Any?>,
    override val method: Member? = null,
    private val originalInvoker: (() -> Any?)? = null
) : UnifiedHookParam {

    private var _result: Any? = null
    override var isSkipped: Boolean = false
    override var throwable: Throwable? = null

    override var result: Any?
        get() = _result
        set(value) {
            _result = value
            isSkipped = true
        }

    fun setResultInternal(value: Any?) {
        _result = value
    }

    override fun invokeOriginal(): Any? {
        return originalInvoker?.invoke()
    }
}
