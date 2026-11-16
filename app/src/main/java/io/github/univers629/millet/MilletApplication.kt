package io.github.univers629.millet

import android.app.Application
import io.github.univers629.millet.core.XposedPrefsBridge

/**
 * 应用入口：只负责把本应用的设置镜像到 LibXposed 框架的远程配置。
 *
 * 之所以需要这一层：Hook 运行在 system_server / powerkeeper 里，读不到应用私有的
 * SharedPreferences（SELinux app_data_file 限制），只能通过框架的 RemotePreferences 通道取配置。
 */
class MilletApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        XposedPrefsBridge.install(this)
    }
}
