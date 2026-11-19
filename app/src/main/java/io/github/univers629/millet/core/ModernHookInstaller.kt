package io.github.univers629.millet.core

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * 基于现代化 LibXposed API 102 的 Hook 安装引擎。
 */
object ModernHookInstaller {

    /**
     * 按顺序批量安装声明式 Hook 列表。
     */
    fun installAll(xposed: XposedInterface, classLoader: ClassLoader, specs: List<HookSpec>) {
        for (spec in specs) {
            install(xposed, classLoader, spec)
        }
    }

    /**
     * 安装单个 HookSpec。
     * @return 是否实际安装成功
     */
    fun install(xposed: XposedInterface, classLoader: ClassLoader, spec: HookSpec): Boolean {
        val allowed = try {
            spec.gate()
        } catch (t: Throwable) {
            Logger.error("gate check [${spec.id}:${spec.name}] failed", t)
            false
        }
        if (!allowed) {
            HookInstallRegistry.record(spec.id, spec.name, HookInstallRegistry.State.SKIPPED_GATED)
            return false
        }

        return try {
            val clazz = XReflect.findClassIfExists(spec.targetClass, classLoader)
                ?: throw ClassNotFoundException("Target class not found: ${spec.targetClass}")

            val rawParams = spec.paramResolver?.invoke(classLoader, clazz) ?: spec.paramTypes
            val resolvedClasses = Array(rawParams.size) { index ->
                resolveParamClass(rawParams[index], classLoader)
            }

            val executable: Executable = if (spec.methodName == null) {
                XReflect.findConstructorExact(clazz, *resolvedClasses)
            } else {
                XReflect.findMethodExact(clazz, spec.methodName, *resolvedClasses)
            }

            val builder = xposed.hook(executable)
                .setPriority(spec.priority)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            // API 102 起支持 Hook 编号：热重载时框架按编号把旧句柄交给新代码，
            // 同名编号再挂载会原子替换，避免同一个点位叠出两层回调。
            if (xposed.apiVersion >= XposedInterface.API_102) {
                builder.setId(spec.id)
            }
            builder.intercept(buildHooker(spec))

            // 目标方法可能已被 JIT 内联进调用方，此时只挂钩子不反优化会「显示已安装但不触发」。
            deoptimizeTarget(xposed, executable, spec.id)

            if (!spec.successLog.isNullOrEmpty()) {
                Logger.alwaysWarn(spec.successLog)
            }
            HookInstallRegistry.record(spec.id, spec.name, HookInstallRegistry.State.INSTALLED)
            true
        } catch (t: Throwable) {
            Logger.error("install ${spec.id}(${spec.name}) failed", t)
            if (!spec.failureHint.isNullOrEmpty()) {
                Logger.alwaysWarn(spec.failureHint)
            }
            HookInstallRegistry.record(
                spec.id,
                spec.name,
                HookInstallRegistry.State.FAILED,
                t.javaClass.simpleName
            )
            false
        }
    }

    /**
     * 动态扫描目标类的 declaredMethods 并挂载拦截（专用于 S19 AnrHelper）。
     */
    fun installDeclaredMethods(
        xposed: XposedInterface,
        id: String,
        name: String,
        classLoader: ClassLoader,
        targetClassName: String,
        methodFilter: (Method) -> Boolean,
        hookBuilder: (Method) -> ((UnifiedHookParam) -> Unit)?
    ): Int {
        var hookedCount = 0
        try {
            val clazz = XReflect.findClassIfExists(targetClassName, classLoader)
                ?: throw ClassNotFoundException("Target class not found: $targetClassName")

            for (method in clazz.declaredMethods) {
                if (!methodFilter(method)) continue
                val beforeHandler = hookBuilder(method) ?: continue

                try {
                    val builder = xposed.hook(method)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    if (xposed.apiVersion >= XposedInterface.API_102) {
                        builder.setId("$id#${method.name}")
                    }
                    builder.intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                val param = UnifiedHookParamImpl(
                                    thisObject = chain.thisObject,
                                    args = chain.args.toTypedArray(),
                                    method = chain.executable,
                                    originalInvoker = { chain.proceed() }
                                )
                                try {
                                    beforeHandler(param)
                                } catch (t: Throwable) {
                                    Logger.error("before callback [$id:${method.name}] error", t)
                                }
                                if (param.isSkipped) {
                                    return param.result
                                }
                                return chain.proceed()
                            }
                        })
                    deoptimizeTarget(xposed, method, "$id#${method.name}")
                    hookedCount++
                } catch (inner: Throwable) {
                    Logger.error("install [$id:${method.name}] overload failed", inner)
                }
            }
        } catch (t: Throwable) {
            Logger.error("install $id($name) failed", t)
        }
        // S19 这类「扫一遍 declaredMethods 逐个挂」的入口，只要命中过一个方法就算该点位装机成功，
        // 具体挂载数量写进 detail 供诊断弹窗展示。
        HookInstallRegistry.record(
            id,
            name,
            if (hookedCount > 0) HookInstallRegistry.State.INSTALLED else HookInstallRegistry.State.FAILED,
            if (hookedCount > 0) "${hookedCount} methods" else "no method matched"
        )
        return hookedCount
    }

    /**
     * 反优化目标方法。
     *
     * 系统框架里的热点方法很容易被 JIT 内联进调用方，此时方法入口不再被走到，
     * 钩子会呈现「已安装但永不触发」的假成功。API 102 起提供 deoptimize，
     * 老框架或反优化失败时只降级为调试日志，不影响装机结果。
     */
    private fun deoptimizeTarget(xposed: XposedInterface, executable: Executable, tag: String) {
        if (xposed.apiVersion < XposedInterface.API_102) return
        try {
            val deoptimized = xposed.deoptimize(executable)
            Logger.debug("deoptimize $tag -> $deoptimized")
        } catch (t: Throwable) {
            Logger.debug("deoptimize $tag failed: ${t.javaClass.simpleName}")
        }
    }

    private fun resolveParamClass(raw: Any, classLoader: ClassLoader): Class<*> {
        return when (raw) {
            is Class<*> -> raw
            is String -> XReflect.findClass(raw, classLoader)
            else -> throw IllegalArgumentException("Unsupported param type descriptor: $raw")
        }
    }

    private fun buildHooker(spec: HookSpec): XposedInterface.Hooker {
        return object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                when (spec.mode) {
                    HookMode.REPLACE -> {
                        val param = UnifiedHookParamImpl(
                            thisObject = chain.thisObject,
                            args = chain.args.toTypedArray(),
                            method = chain.executable,
                            originalInvoker = { chain.proceed() }
                        )
                        return try {
                            val replacer = spec.replace
                            if (replacer != null) {
                                replacer(param)
                            } else {
                                chain.proceed()
                            }
                        } catch (t: Throwable) {
                            Logger.error("replace callback [${spec.id}:${spec.name}] error", t)
                            try {
                                chain.proceed()
                            } catch (_: Throwable) {
                                null
                            }
                        }
                    }

                    HookMode.BEFORE -> {
                        val origArgs = chain.args.toTypedArray()
                        val param = UnifiedHookParamImpl(
                            thisObject = chain.thisObject,
                            args = origArgs.copyOf(),
                            method = chain.executable,
                            originalInvoker = { chain.proceed() }
                        )
                        try {
                            spec.before?.invoke(param)
                        } catch (t: Throwable) {
                            Logger.error("before callback [${spec.id}:${spec.name}] error", t)
                        }
                        if (param.isSkipped) {
                            return param.result
                        }
                        return if (!param.args.contentEquals(origArgs)) {
                            chain.proceed(param.args)
                        } else {
                            chain.proceed()
                        }
                    }

                    HookMode.AFTER -> {
                        val result = chain.proceed()
                        val param = UnifiedHookParamImpl(
                            thisObject = chain.thisObject,
                            args = chain.args.toTypedArray(),
                            method = chain.executable,
                            originalInvoker = { chain.proceed() }
                        )
                        param.setResultInternal(result)
                        try {
                            spec.after?.invoke(param)
                        } catch (t: Throwable) {
                            Logger.error("after callback [${spec.id}:${spec.name}] error", t)
                        }
                        return param.result
                    }

                    HookMode.BEFORE_AND_AFTER -> {
                        val origArgs = chain.args.toTypedArray()
                        val param = UnifiedHookParamImpl(
                            thisObject = chain.thisObject,
                            args = origArgs.copyOf(),
                            method = chain.executable,
                            originalInvoker = { chain.proceed() }
                        )
                        try {
                            spec.before?.invoke(param)
                        } catch (t: Throwable) {
                            Logger.error("before callback [${spec.id}:${spec.name}] error", t)
                        }
                        if (param.isSkipped) {
                            return param.result
                        }
                        val result = if (!param.args.contentEquals(origArgs)) {
                            chain.proceed(param.args)
                        } else {
                            chain.proceed()
                        }
                        param.setResultInternal(result)
                        try {
                            spec.after?.invoke(param)
                        } catch (t: Throwable) {
                            Logger.error("after callback [${spec.id}:${spec.name}] error", t)
                        }
                        return param.result
                    }
                }
            }
        }
    }
}
