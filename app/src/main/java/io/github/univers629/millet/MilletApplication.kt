package io.github.univers629.millet

import android.app.Application
import io.github.univers629.millet.core.PredictiveBackController
import io.github.univers629.millet.core.PrefReader
import io.github.univers629.millet.core.XposedPrefsBridge

/**
 * 应用入口：把本应用的设置镜像到 LibXposed 框架的远程配置，并应用启动期才能生效的开关。
 *
 * 之所以需要镜像：Hook 运行在 system_server / powerkeeper 里，读不到应用私有的
 * SharedPreferences（SELinux app_data_file 限制），只能通过框架的 RemotePreferences 通道取配置。
 */
class MilletApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        XposedPrefsBridge.install(this)
        // 返回通道必须在本进程任何窗口创建前决定：ViewRootImpl 构造时读取该位。
        PredictiveBackController.applyPredictiveBackEnabled(
            this,
            PrefReader.openUiPrefs(this).getBoolean(KEY_PREDICTIVE_BACK, false),
        )
    }

    private companion object {
        const val KEY_PREDICTIVE_BACK = "ui_predictive_back"
    }
}
