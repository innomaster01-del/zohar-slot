package com.zohar.slotar.walk

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Phone position and orientation without Google Play services: the best of the fused (Android 12+), GPS and network
 * locations, and the true-north heading of
 * the back camera (rotation-vector sensor + magnetic declination). Also the tilt, so the app knows when the phone is
 * held up (AR view) or lies flat (map view).
 */
class WalkSensors(context: Context) : SensorEventListener, LocationListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val lm = context.getSystemService(LocationManager::class.java)
    @Volatile var location: Location? = null; private set
    @Volatile var cameraHeading = Double.NaN; private set     // degrees from true north, of the back camera's view direction
    @Volatile var pitchUp = 0.0; private set                  // 0 = phone flat (screen up), 90 = upright
    @Volatile var headingAccuracy = 3; private set            // SensorManager.SENSOR_STATUS_* of the magnetometer
    private var declination = 0f
    private val r = FloatArray(9)

    /** Whether the app has precise (fine) location; with approximate only, fixes are hundreds of metres off. */
    private val app = context.applicationContext
    val precise: Boolean get() = app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        // fused (GPS + Wi-Fi + cell + motion sensors, Android 12+) first, then raw GPS and network
        val providers = buildList { if (android.os.Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER); add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER) }
        for (p in providers) runCatching {
            if (lm?.allProviders?.contains(p) == true && lm.isProviderEnabled(p)) {
                lm.requestLocationUpdates(p, 0L, 0f, this, Looper.getMainLooper())
                // a last-known fix only if it is fresh (< 2 min): an old one starts the route in the wrong place
                lm.getLastKnownLocation(p)?.let { if (System.currentTimeMillis() - it.time < 120_000) onLocationChanged(it) }
            }
        }
    }
    fun stop() { sm?.unregisterListener(this); runCatching { lm?.removeUpdates(this) } }

    override fun onLocationChanged(l: Location) {
        if (better(l, location)) {
            location = l
            declination = GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), l.time).declination
        }
    }
    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(r, e.values)
        // device → world (east, north, up). The back camera looks along the device's −Z axis.
        val east = -r[2].toDouble(); val north = -r[5].toDouble(); val up = -r[8].toDouble()
        pitchUp = Math.toDegrees(kotlin.math.acos((-up).coerceIn(-1.0, 1.0)))    // 0 = camera straight down (phone flat), 90 = level
        if (hypot(east, north) > 0.15) cameraHeading = (Math.toDegrees(atan2(east, north)) + declination + 360) % 360
    }
    override fun onAccuracyChanged(s: Sensor, accuracy: Int) { if (s.type == Sensor.TYPE_MAGNETIC_FIELD) headingAccuracy = accuracy }
    companion object {
        /**
         * Which fix to keep: whichever provider, the more accurate one wins; a newer fix replaces an older one when it is
         * not much worse, and anything replaces a fix that is more than 8 s old.
         */
        fun better(l: Location, cur: Location?): Boolean {
            if (cur == null) return true
            val newer = l.time - cur.time
            return when {
                newer > 8_000 -> true
                newer < -2_000 -> false
                l.accuracy <= cur.accuracy -> true
                else -> newer > 1_500 && l.accuracy <= cur.accuracy * 1.4f + 3f
            }
        }
    }
    @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
}
