package io.github.univers629.millet.core

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 控制应用是否把返回事件交给系统的 OnBackInvoked 通道（即「预测性返回」）。
 *
 * 该开关的实质是 [ApplicationInfo] 的 privateFlagsExt 第 8 位：位开启时 framework 在
 * ViewRootImpl 的 NativePreImeInputStage 里截获返回键，交给应用注册的 OnBackInvokedCallback；
 * 位关闭时返回键走传统 onBackPressed 通道。位值只在 ViewRootImpl 构造窗口时读取一次，
 * 因此切换后需要重建 Activity 才生效。
 *
 * 只在 Android 14 及以上调用：Android 13 的 ROM 会额外注册一个 priority 0 的兼容回调，
 * 把返回合成成 BACK 键事件重新入队，与自建回调互相触发形成自循环，返回键事件在输入阶段持续堆积直至 ANR。
 */
object PredictiveBackController {

    private const val METHOD_SET_ENABLE_ON_BACK_INVOKED = "setEnableOnBackInvokedCallback"
    private const val HIDDEN_API_SIGNATURE =
        "Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback"

    /**
     * 按开关值设置返回通道。Android 14 以下一律关闭：13 上 ROM 的兼容回调会把返回合成成
     * BACK 键事件，位开启时该事件又被输入阶段拦回 OnBackInvoked，形成自循环并 ANR。
     */
    fun applyPredictiveBackEnabled(app: Application, enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val target = enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(HIDDEN_API_SIGNATURE)
            val method = ApplicationInfo::class.java.getDeclaredMethod(
                METHOD_SET_ENABLE_ON_BACK_INVOKED,
                Boolean::class.javaPrimitiveType
            )
            method.isAccessible = true
            method.invoke(app.applicationInfo, target)
        }.onFailure { throwable ->
            Logger.warn("设置预测性返回失败: ${throwable.javaClass.simpleName}")
        }
    }
}
