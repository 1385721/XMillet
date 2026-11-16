package io.github.univers629.millet.core

import java.lang.reflect.Member

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
