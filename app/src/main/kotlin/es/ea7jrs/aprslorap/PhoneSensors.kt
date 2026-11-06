/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import es.ea7jrs.aprslorap.core.PhoneMath
import kotlin.math.roundToInt

/**
 * The phone's own sensors:
 * - tilt (gravity): the phone as the inclinometer of the rotor mount, sent to the tracker with `att` every second;
 * - compass (rotation vector, true north with the magnetic declination of the place): to line up the rotor's north;
 * - barometer: pressure, QNH with a known altitude, barometric altitude.
 * The screens that show them, and the `att` sender, ask for them ([use] / [release]); with nobody using them they are off.
 */
object PhoneSensors {
    data class Reading(
        val pitchDeg: Double? = null, val rollDeg: Double? = null,             // raw tilt (before the zero of the mount)
        val headingDeg: Double? = null, val declinationDeg: Double? = null,     // true heading of the top of the phone
        val pressureHpa: Double? = null,
        val available: List<String> = emptyList(),
    )

    private var sm: SensorManager? = null
    private var app: Context? = null
    private val users = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())
    private var reading = Reading()
    private var lastPublishMs = 0L
    private var declination: Double? = null
    private var declinationAtMs = 0L
    private val rot = FloatArray(9)
    private val orient = FloatArray(3)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                    if (e.sensor.type == Sensor.TYPE_ACCELEROMETER && hasGravity) return
                    val t = PhoneMath.tilt(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
                    reading = reading.copy(pitchDeg = t?.first, rollDeg = t?.second)
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rot, e.values)
                    SensorManager.getOrientation(rot, orient)
                    val decl = declination()
                    var h = Math.toDegrees(orient[0].toDouble()) + (decl ?: 0.0)
                    h = (h % 360 + 360) % 360
                    reading = reading.copy(headingDeg = h, declinationDeg = decl)
                }
                Sensor.TYPE_PRESSURE -> reading = reading.copy(pressureHpa = e.values[0].toDouble())
            }
            val now = System.currentTimeMillis()
            if (now - lastPublishMs >= 250) { lastPublishMs = now; AppState.phoneSensors.value = reading }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    private var hasGravity = false

    /** Magnetic declination where the phone (or the tracker) is, refreshed every 10 minutes. */
    private fun declination(): Double? {
        val now = System.currentTimeMillis()
        if (declination != null && now - declinationAtMs < 600_000) return declination
        declinationAtMs = now
        val ctx = app ?: return declination
        val l = try { GpsAid.lastRealLocation(ctx) } catch (_: Exception) { null }
        val g = AppState.gnss.value
        val lat = l?.latitude ?: g?.lat ?: return declination
        val lon = l?.longitude ?: g?.lon ?: return declination
        val alt = (l?.altitude ?: g?.altitudeM ?: 0.0).toFloat()
        declination = GeomagneticField(lat.toFloat(), lon.toFloat(), alt, now).declination.toDouble()
        return declination
    }

    fun use(ctx: Context, who: String) {
        main.post {
            if (users.isEmpty()) start(ctx.applicationContext)
            users.add(who)
        }
    }

    fun release(who: String) {
        main.post {
            users.remove(who)
            if (users.isEmpty()) stop()
        }
    }

    private fun start(ctx: Context) {
        app = ctx
        val m = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm = m
        hasGravity = m.getDefaultSensor(Sensor.TYPE_GRAVITY) != null
        val names = ArrayList<String>()
        for (type in listOf(Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_PRESSURE)) {
            val s = m.getDefaultSensor(type) ?: continue
            if (type == Sensor.TYPE_ACCELEROMETER && hasGravity) continue
            m.registerListener(listener, s, SensorManager.SENSOR_DELAY_UI)
            names.add(s.name)
        }
        for (s in m.getSensorList(Sensor.TYPE_ALL)) if (s.name !in names && names.size < 40) names.add(s.name + " (not used)")
        reading = Reading(available = names)
        AppState.phoneSensors.value = reading
    }

    private fun stop() {
        sm?.unregisterListener(listener)
        sm = null
        stopAtt()
        AppState.phoneSensors.value = Reading()
    }

    // ------------------------------------------------------------------ the phone as the rotor inclinometer (`att`)

    private var attOn = false
    private val attTick = object : Runnable {
        override fun run() {
            if (!attOn) return
            val ctx = app
            val r = reading
            val c = AppState.controller
            if (ctx != null && c != null && AppState.controlReady.value && r.pitchDeg != null && r.rollDeg != null) {
                val s = Settings(ctx)
                if (!Licence.isValid(s)) { AppState.attStatus.value = "Callsign not validated in RadioID"; stopAtt(); return }
                val p = r.pitchDeg - s.attZeroPitch
                val ro = r.rollDeg - s.attZeroRoll
                c.control("att", "p" to (p * 10).roundToInt() / 10.0, "r" to (ro * 10).roundToInt() / 10.0)
                AppState.attStatus.value = String.format(java.util.Locale.US, "Sending: pitch %.1f°, roll %.1f°", p, ro)
            } else {
                AppState.attStatus.value = if (r.pitchDeg == null) "Lay the phone face up on the mount" else "Waiting for the tracker link"
            }
            main.postDelayed(this, 1000)
        }
    }

    fun startAtt(ctx: Context) {
        use(ctx, "att")
        main.post { if (!attOn) { attOn = true; main.post(attTick) } }
    }

    fun stopAtt() {
        main.post {
            if (attOn) { attOn = false; main.removeCallbacks(attTick); AppState.attStatus.value = "Off" }
            if ("att" in users) release("att")
        }
    }

    /** The mount is level now: the current tilt becomes the zero. */
    fun setZero(ctx: Context) {
        val r = reading
        if (r.pitchDeg == null || r.rollDeg == null) return
        val s = Settings(ctx)
        s.attZeroPitch = r.pitchDeg; s.attZeroRoll = r.rollDeg
    }
}
