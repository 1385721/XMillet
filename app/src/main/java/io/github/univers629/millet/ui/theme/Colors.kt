package io.github.univers629.millet.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

data class NamedColor(
    val name: String,
    val color: Color
)

/**
 * XMillet 预设主题色列表（与 KernelSU Miuix 调色板保持一致，支持系统动态取色 + 15 种精选色）。
 */
val keyColorOptions: List<NamedColor> = listOf(
    NamedColor("红 (Red)", Color(0xFFF44336)),
    NamedColor("粉 (Pink)", Color(0xFFE91E63)),
    NamedColor("紫 (Purple)", Color(0xFF9C27B0)),
    NamedColor("深紫 (Deep Purple)", Color(0xFF673AB7)),
    NamedColor("靛蓝 (Indigo)", Color(0xFF3F51B5)),
    NamedColor("小米蓝 (Blue)", Color(0xFF2196F3)),
    NamedColor("青 (Cyan)", Color(0xFF00BCD4)),
    NamedColor("蓝绿 (Teal)", Color(0xFF009688)),
    NamedColor("绿 (Green)", Color(0xFF4CAF50)),
    NamedColor("黄 (Yellow)", Color(0xFFFFEB3B)),
    NamedColor("琥珀 (Amber)", Color(0xFFFFC107)),
    NamedColor("橙 (Orange)", Color(0xFFFF9800)),
    NamedColor("棕 (Brown)", Color(0xFF795548)),
    NamedColor("蓝灰 (Blue Grey)", Color(0xFF607D8B)),
    NamedColor("樱花粉 (Sakura)", Color(0xFFFF9CA8)),
)

@Composable
fun isInDarkTheme(themeMode: Int, systemDark: Boolean): Boolean {
    return when (themeMode) {
        1, 4 -> false  // 强制浅色
        2, 5 -> true   // 强制深色
        else -> systemDark
    }
}

fun Color.toArgbInt(): Int = this.toArgb()
