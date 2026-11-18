package app.refocus.ui.viewer

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Reports how far the phone is tilted from where it was when tilt was switched on,
 * as (x, y) in -1..1 (±[MAX_ANGLE_DEG] degrees), low-pass filtered.
 */
@Composable
fun TiltEffect(enabled: Boolean, onTilt: (Float, Float) -> Unit) {
    val context = LocalContext.current
    val callback = rememberUpdatedState(onTilt)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: return@DisposableEffect onDispose { }
        val rot = FloatArray(9)
        val angles = FloatArray(3)
        var ref: FloatArray? = null
        var fx = 0f
        var fy = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, angles)
                val pitch = angles[1]
                val roll = angles[2]
                val r = ref ?: floatArrayOf(pitch, roll).also { ref = it }
                val max = Math.toRadians(MAX_ANGLE_DEG).toFloat()
                val x = ((roll - r[1]) / max).coerceIn(-1f, 1f)
                val y = ((pitch - r[0]) / max).coerceIn(-1f, 1f)
                fx += (x - fx) * 0.25f
                fy += (y - fy) * 0.25f
                callback.value(fx, fy)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
}

private const val MAX_ANGLE_DEG = 14.0
