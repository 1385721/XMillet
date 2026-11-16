package io.github.univers629.millet.ui.component.effect

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.isActive

/**
 * HyperCeiler 官方 OS3 OOBE 同款光效背景组件（GlowBackground）：
 * - 对齐 HyperCeiler RenderViewLayout 架构：采用 0.2f 降采样硬件加速渲染（像素计算减少 96%）；
 * - 5.0 倍线性插值放大形成柔和的漫反射流动光晕，用于高刷屏（120fps）下的渲染；
 * - 纯黑底色承载 OKLab 颜色空间混合、Simplex 噪声底色流光与开场环形扩散光圈（glow.glsl）；
 * - 适配 Compose 的 layerBackdrop 离屏缓冲捕获机制，使前景的毛玻璃组件能直接采样到流光光效；
 * - 不区分深浅色模式，保持 OS3 OOBE 的统一视觉。
 */
@Composable
fun GlowBackground(
    modifier: Modifier = Modifier,
    bgModifier: Modifier = Modifier,
    needAdmission: Boolean = true,
    circleYOffset: Float = 0.1f,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val painter = remember(context) {
            GlowPainter(context).apply {
                needAdmission(needAdmission)
                setCircleYOffset(circleYOffset)
            }
        }
        val brush = remember(painter) {
            ShaderBrush(painter.runtimeShader)
        }

        var animTime by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(Unit) {
            var lastNano = System.nanoTime()
            var time = 0.0f
            var timeDirection = 1.0f
            while (isActive) {
                withFrameNanos { frameNano ->
                    val delta = ((frameNano - lastNano) * 1.0E-9).toFloat()
                    time += delta * timeDirection
                    if (timeDirection > 0.0f) {
                        if (time >= 120.0f) timeDirection = -1.0f
                    } else if (time <= 2.0f) {
                        timeDirection = 1.0f
                    }
                    lastNano = frameNano
                    animTime = time
                }
            }
        }

        Box(
            modifier = modifier.background(Color.Black)
        ) {
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .then(bgModifier)
                    .drawBehind {
                        val t = animTime
                        val w = size.width
                        val h = size.height
                        if (w > 0f && h > 0f) {
                            // 对齐 HyperCeiler RenderViewLayout 的 mChildScale = 0.2f
                            // 降采样到 20% 分辨率计算着色器，像素吞吐缩减 25 倍，硬件插值放大天然形成柔和漫反射
                            val scaleFactor = 0.2f
                            val invScale = 1.0f / scaleFactor // 5.0f
                            val lowW = (w * scaleFactor).coerceAtLeast(1f)
                            val lowH = (h * scaleFactor).coerceAtLeast(1f)

                            painter.needAdmission(needAdmission)
                            painter.setAnimTime(t)
                            painter.setResolution(lowW, lowH)
                            painter.setCircleYOffset(circleYOffset)

                            // 底层垫纯黑（对应 RenderViewLayout 中的 attachView(..., -16777216)）
                            drawRect(Color.Black)

                            scale(scale = invScale, pivot = Offset.Zero) {
                                drawRect(
                                    brush = brush,
                                    size = Size(lowW, lowH)
                                )
                            }
                        }
                    }
            )
            content()
        }
    } else {
        BgEffectBackground(
            dynamicBackground = true,
            isOs3Effect = true,
            modifier = modifier,
            bgModifier = bgModifier,
            effectBackground = true,
            isDarkTheme = true,
            content = content
        )
    }
}
