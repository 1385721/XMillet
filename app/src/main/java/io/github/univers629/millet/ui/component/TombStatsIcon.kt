package io.github.univers629.millet.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 「墓碑统计」图标：圆拱顶墓碑实心剪影（圆润底角，居中于 24dp 网格）+ EvenOdd 镂空三根递增圆角柱。
 * 严格对齐 Miuix Original 24dp 光学画框（Y: 2.6..21.4，高 18.8dp），与底栏其余图标视觉体量一致。
 */
val TombStatsIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "TombStats",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            // 墓碑外形：顶部半圆拱 (圆心 12, 9.6，半径 7 -> 顶边 y=2.6) + 1.5dp 圆润底角 (底边 y=21.4)
            moveTo(5f, 19.9f)
            lineTo(5f, 9.6f)
            arcTo(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 19f, y1 = 9.6f)
            lineTo(19f, 19.9f)
            arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 17.5f, y1 = 21.4f)
            lineTo(6.5f, 21.4f)
            arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 5f, y1 = 19.9f)
            close()

            // 镂空三根圆角递增柱
            roundedBar(left = 8f, top = 13.8f, right = 9.8f, bottom = 18.2f, radius = 0.6f)
            roundedBar(left = 11.1f, top = 11.2f, right = 12.9f, bottom = 18.2f, radius = 0.6f)
            roundedBar(left = 14.2f, top = 8.6f, right = 16f, bottom = 18.2f, radius = 0.6f)
        }
    }.build()
}

private fun PathBuilder.roundedBar(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radius: Float,
) {
    moveTo(left + radius, top)
    lineTo(right - radius, top)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = right, y1 = top + radius)
    lineTo(right, bottom - radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = right - radius, y1 = bottom)
    lineTo(left + radius, bottom)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = left, y1 = bottom - radius)
    lineTo(left, top + radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = left + radius, y1 = top)
    close()
}
