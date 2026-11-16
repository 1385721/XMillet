package io.github.univers629.millet.ui.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 监听手机重力/加速度传感器倾角（俯仰角 pitch 与横滚角 roll，弧度制），
 * 供悬浮底栏液态玻璃边缘高光（rememberGravityRotatedHighlight）随设备倾斜实时流转。
 */
@Composable
fun rememberDeviceTilt(): State<Pair<Float, Float>?> {
    val context = LocalContext.current
    val tiltState = remember { mutableStateOf<Pair<Float, Float>?>(0f to 0f) }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (sensorManager == null || sensor == null) {
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    val values = event?.values ?: return
                    if (values.size >= 2) {
                        // 将重力分量归一化为近似弧度角
                        val roll = (values[0] / 9.81f).coerceIn(-1.5f, 1.5f)
                        val pitch = (values[1] / 9.81f).coerceIn(-1.5f, 1.5f)
                        tiltState.value = pitch to roll
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            onDispose {
                sensorManager.unregisterListener(listener)
            }
        }
    }

    return tiltState
}
