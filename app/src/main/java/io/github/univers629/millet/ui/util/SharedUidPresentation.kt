package io.github.univers629.millet.ui.util

import android.content.pm.ApplicationInfo

data class StatsPackageMember(
    val packageName: String,
    val label: String,
    val applicationInfo: ApplicationInfo? = null,
)

internal fun sortedStatsMemberPackages(packageNames: List<String>?): List<String> =
    packageNames.orEmpty().filter { it.isNotBlank() }.distinct().sorted()

internal fun statsGroupLabel(members: List<StatsPackageMember>): String {
    val names = members.take(2).joinToString(" + ") { it.label.ifBlank { it.packageName } }
    return when {
        members.isEmpty() -> "共享应用"
        members.size > 2 -> "$names 等${members.size}个应用"
        else -> names
    }
}

internal fun canStackStatsMemberIcons(memberCount: Int, availableIcons: List<Boolean>): Boolean =
    memberCount == 2 && availableIcons.size == 2 && availableIcons.all { it }
