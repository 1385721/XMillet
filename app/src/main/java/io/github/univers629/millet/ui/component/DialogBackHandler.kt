package io.github.univers629.millet.ui.component

import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView

/**
 * 在独立窗口（Dialog）内容里接管系统返回。
 *
 * Miuix 的 WindowDialog 走平台 Dialog，返回事件由 Dialog 窗口自己的 OnBackPressedDispatcher
 * 消费；而 WindowNavigationEventScope 在 Dialog 窗口里找不到 navigationevent owner，会退回
 * Activity 的 dispatcher，导致返回事件丢失、对话框按返回键无反应。这里直接挂在当前窗口的
 * OnBackPressedDispatcher 上兜底。
 */
@Composable
fun DialogBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val view = LocalView.current
    val currentOnBack by rememberUpdatedState(onBack)
    var callback by remember(view) { mutableStateOf<OnBackPressedCallback?>(null) }

    DisposableEffect(view) {
        fun register() {
            if (callback != null) return
            val dispatcher = view.findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher
                ?: return
            val registered = object : OnBackPressedCallback(enabled) {
                override fun handleOnBackPressed() {
                    currentOnBack()
                }
            }
            dispatcher.addCallback(registered)
            callback = registered
        }

        register()
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = register()
            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        view.addOnAttachStateChangeListener(listener)

        onDispose {
            view.removeOnAttachStateChangeListener(listener)
            callback?.remove()
            callback = null
        }
    }

    SideEffect { callback?.isEnabled = enabled }
}
