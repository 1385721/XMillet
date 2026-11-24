package io.github.univers629.millet.ui.model

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Immutable

/**
 * 稳定的应用图标引用包装器。
 *
 * `android.content.pm.ApplicationInfo` 未重写 `equals` / `hashCode`，每次重扫生成新实例会击穿
 * Compose 的跳过优化并导致列表全量重组；此处仅按 `(uid, packageName, sourceDir)` 判等，
 * 同时提供无 `ApplicationInfo` 实例的测试工厂以支持纯 JVM 单元测试。
 */
@Immutable
class AppIconSource private constructor(
    val uid: Int,
    val packageName: String,
    val sourceDir: String?,
    val applicationInfo: ApplicationInfo?,
) {
    constructor(info: ApplicationInfo) : this(
        uid = info.uid,
        packageName = info.packageName.orEmpty(),
        sourceDir = info.sourceDir,
        applicationInfo = info,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppIconSource) return false
        return uid == other.uid &&
            packageName == other.packageName &&
            sourceDir == other.sourceDir
    }

    override fun hashCode(): Int {
        var result = uid
        result = 31 * result + packageName.hashCode()
        result = 31 * result + (sourceDir?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "AppIconSource(uid=$uid, packageName=$packageName, sourceDir=$sourceDir)"

    companion object {
        fun fromOrNull(info: ApplicationInfo?): AppIconSource? = info?.let(::AppIconSource)

        internal fun forTest(
            uid: Int,
            packageName: String,
            sourceDir: String? = null,
        ): AppIconSource = AppIconSource(
            uid = uid,
            packageName = packageName,
            sourceDir = sourceDir,
            applicationInfo = null,
        )
    }
}
