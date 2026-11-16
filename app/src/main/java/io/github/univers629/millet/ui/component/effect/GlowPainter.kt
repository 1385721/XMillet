package io.github.univers629.millet.ui.component.effect

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.univers629.millet.R

/**
 * HyperCeiler 同款光效 Shader 画笔（GlowPainter）
 * 承载 OKLab 颜色空间混合、Simplex 噪声底色流光与开场扩散光圈效果。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class GlowPainter(context: Context) {

    companion object {
        private const val TAG = "GlowPainter"
    }

    private var uScale2 = 0.82f
    private var uSpeed2 = 0.49f
    private var uColorInMin = 0.3f
    private var uColorInMax = 1.0f
    private var uColorOutMin = 0.3f
    private var uColorOutMax = 0.86f
    private var uColorMidPoint = 0.47f
    private var uUseOklab = 1.0f
    private var uColorBlack = floatArrayOf(0.961f, 0.157f, 0.157f)
    private var uColorMid = floatArrayOf(0.604f, 0.659f, 0.961f)
    private var uColorWhite = floatArrayOf(0.302f, 0.29f, 0.843f)
    private var uScale = 1.3f
    private var uSpeed = 0.4f
    private var uBrightnessInMin = 0.25f
    private var uBrightnessInMax = 1.0f
    private var uBrightnessOutMin = 0.25f
    private var uBrightnessOutMax = 1.0f
    private var uShowCircle = 1.0f
    private var uCircleThickness = 0.4f
    private var uCircleFinalRadius = 1.0f
    private var uCircleYOffset = 0.1f
    private var uCircleSpeed = 0.9f
    private var uCircleColorFreq = 1.0f
    private var uCircleColorSpeed = 0.0f
    private var uCircleEasing = 1.4f
    private var uCircleAnimationOffset = 0.0f
    private var uMaskDelay = 0.3f
    private var uMaskThickness = 0.3f
    private var uCircleScreenBlend = 1.0f
    private var uCircleAddBlend = 0.04f
    private var uCircleColorOffset = 0.25f
    private var uCircleUVDistort = 0.0f
    private var uColorToDistortWidthRatio = 0.6f
    private var uDistortStartTime = 0.2f
    private var uDistortEndTime = 0.3f
    private var uDistortStart = 0.0f
    private var uDistortEnd = 1.0f
    private var uStripeFrequency = 0.0f
    private var uStripeStrengthX = 0.0f
    private var uStripeStrengthY = 0.0f
    private var uStripeUVDistort = 0.0f

    val runtimeShader: RuntimeShader

    init {
        val shaderCode = loadShader(context, R.raw.glow)
        runtimeShader = RuntimeShader(shaderCode)

        runtimeShader.setFloatUniform("uTime", 0.0f)
        runtimeShader.setFloatUniform("uResolution", 1080.0f, 2400.0f)
        runtimeShader.setFloatUniform("uScale2", uScale2)
        runtimeShader.setFloatUniform("uSpeed2", uSpeed2)
        runtimeShader.setFloatUniform("uColorInMin", uColorInMin)
        runtimeShader.setFloatUniform("uColorInMax", uColorInMax)
        runtimeShader.setFloatUniform("uColorOutMin", uColorOutMin)
        runtimeShader.setFloatUniform("uColorOutMax", uColorOutMax)
        runtimeShader.setFloatUniform("uColorMidPoint", uColorMidPoint)
        runtimeShader.setFloatUniform("uUseOklab", uUseOklab)
        runtimeShader.setFloatUniform("uColorBlack", uColorBlack)
        runtimeShader.setFloatUniform("uColorMid", uColorMid)
        runtimeShader.setFloatUniform("uColorWhite", uColorWhite)
        runtimeShader.setFloatUniform("uScale", uScale)
        runtimeShader.setFloatUniform("uSpeed", uSpeed)
        runtimeShader.setFloatUniform("uBrightnessInMin", uBrightnessInMin)
        runtimeShader.setFloatUniform("uBrightnessInMax", uBrightnessInMax)
        runtimeShader.setFloatUniform("uBrightnessOutMin", uBrightnessOutMin)
        runtimeShader.setFloatUniform("uBrightnessOutMax", uBrightnessOutMax)
        runtimeShader.setFloatUniform("uShowCircle", uShowCircle)
        runtimeShader.setFloatUniform("uCircleThickness", uCircleThickness)
        runtimeShader.setFloatUniform("uCircleFinalRadius", uCircleFinalRadius)
        runtimeShader.setFloatUniform("uCircleYOffset", uCircleYOffset)
        runtimeShader.setFloatUniform("uCircleSpeed", uCircleSpeed)
        runtimeShader.setFloatUniform("uCircleColorFreq", uCircleColorFreq)
        runtimeShader.setFloatUniform("uCircleColorSpeed", uCircleColorSpeed)
        runtimeShader.setFloatUniform("uCircleEasing", uCircleEasing)
        runtimeShader.setFloatUniform("uCircleAnimationOffset", uCircleAnimationOffset)
        runtimeShader.setFloatUniform("uMaskDelay", uMaskDelay)
        runtimeShader.setFloatUniform("uMaskThickness", uMaskThickness)
        runtimeShader.setFloatUniform("uCircleScreenBlend", uCircleScreenBlend)
        runtimeShader.setFloatUniform("uCircleAddBlend", uCircleAddBlend)
        runtimeShader.setFloatUniform("uCircleColorOffset", uCircleColorOffset)
        runtimeShader.setFloatUniform("uCircleUVDistort", uCircleUVDistort)
        runtimeShader.setFloatUniform("uColorToDistortWidthRatio", uColorToDistortWidthRatio)
        runtimeShader.setFloatUniform("uDistortStartTime", uDistortStartTime)
        runtimeShader.setFloatUniform("uDistortEndTime", uDistortEndTime)
        runtimeShader.setFloatUniform("uDistortStart", uDistortStart)
        runtimeShader.setFloatUniform("uDistortEnd", uDistortEnd)
        runtimeShader.setFloatUniform("uStripeFrequency", uStripeFrequency)
        runtimeShader.setFloatUniform("uStripeStrengthX", uStripeStrengthX)
        runtimeShader.setFloatUniform("uStripeStrengthY", uStripeStrengthY)
        runtimeShader.setFloatUniform("uStripeUVDistort", uStripeUVDistort)
    }

    fun getRenderEffect(): RenderEffect {
        return RenderEffect.createShaderEffect(runtimeShader)
    }

    fun setAnimTime(value: Float) {
        runtimeShader.setFloatUniform("uTime", value)
    }

    fun setResolution(width: Float, height: Float) {
        runtimeShader.setFloatUniform("uResolution", width, height)
    }

    fun setCircleYOffset(value: Float) {
        uCircleYOffset = value
        runtimeShader.setFloatUniform("uCircleYOffset", value)
    }

    fun needAdmission(need: Boolean) {
        uShowCircle = if (need) 1.0f else 0.0f
        runtimeShader.setFloatUniform("uShowCircle", uShowCircle)
    }

    private fun loadShader(context: Context, resId: Int): String {
        return try {
            context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load glow shader", e)
            ""
        }
    }
}
