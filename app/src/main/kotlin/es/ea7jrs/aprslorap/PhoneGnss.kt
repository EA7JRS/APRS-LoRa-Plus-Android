/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import es.ea7jrs.aprslorap.core.GnssSky
import es.ea7jrs.aprslorap.core.SkySat
import es.ea7jrs.aprslorap.core.SkyView
import kotlin.math.sqrt

/**
 * The phone's own GNSS (satellites from GnssStatus, DOP from its NMEA, accuracy, speed, altitude) and the sensors shown on the
 * DISP > GPS page (linear acceleration, rotation, light, magnetic field, battery). Only while that page is open.
 */
object PhoneGnss {
    data class State(
        val running: Boolean = false, val status: String = "Off", val sky: SkyView = SkyView(emptyList()),
        val lat: Double? = null, val lon: Double? = null, val accuracyM: Double? = null, val speedKmh: Double? = null, val bearingDeg: Double? = null,
        val altitudeM: Double? = null, val altitudeMsl: Boolean = false, val fixTimeMs: Long? = null,
        val accelG: Double? = null, val gyroDps: Double? = null, val lux: Double? = null, val magUT: Double? = null,
        val battPct: Int? = null, val battTempC: Double? = null, val battV: Double? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private var users = 0
    private var lm: LocationManager? = null
    private var sm: SensorManager? = null
    private var app: Context? = null
    private var dops: Triple<Double?, Double?, Double?>? = null
    private var lastBatteryMs = 0L

    private fun set(f: (State) -> State) { AppState.phoneGnss.value = f(AppState.phoneGnss.value) }

    fun granted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun use(ctx: Context) = main.post { if (users++ == 0) start(ctx.applicationContext) }
    fun release() = main.post { if (users > 0 && --users == 0) stop() }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            // dual-frequency phones list a satellite once per signal: keep the best one
            val map = LinkedHashMap<String, SkySat>()
            for (i in 0 until status.satelliteCount) {
                val sys = GnssSky.fromAndroid(status.getConstellationType(i))
                val svid = status.getSvid(i)
                val cn0 = status.getCn0DbHz(i).toInt().takeIf { it > 0 }
                val s = SkySat(sys, svid, status.getElevationDegrees(i).toInt(), status.getAzimuthDegrees(i).toInt(), cn0, status.usedInFix(i))
                val k = "$sys$svid"
                val old = map[k]
                map[k] = if (old == null) s else old.copy(snr = maxOf(old.snr ?: 0, s.snr ?: 0).takeIf { it > 0 }, used = old.used || s.used)
            }
            val d = dops
            set { it.copy(sky = SkyView(map.values.toList(), d?.first, d?.second, d?.third, it.accuracyM), status = "Phone GNSS on") }
        }
        override fun onStopped() { set { it.copy(status = "Phone GNSS stopped") } }
    }

    private val nmea = OnNmeaMessageListener { line, _ -> GnssSky.gsaDops(line.trim())?.let { dops = it } }

    private val location = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val msl = Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()
            val alt = if (msl) l.mslAltitudeMeters else if (l.hasAltitude()) l.altitude else null
            set {
                it.copy(lat = l.latitude, lon = l.longitude, accuracyM = if (l.hasAccuracy()) l.accuracy.toDouble() else null,
                    speedKmh = if (l.hasSpeed()) l.speed * 3.6 else null, bearingDeg = if (l.hasBearing()) l.bearing.toDouble() else null,
                    altitudeM = alt, altitudeMsl = msl, fixTimeMs = l.time, sky = it.sky.copy(errorM = if (l.hasAccuracy()) l.accuracy.toDouble() else null))
            }
            battery()
        }
        @Deprecated("old API") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) { set { it.copy(status = "Location is off in Android") } }
    }

    private val sensors = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values
            val m = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
            when (e.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> set { it.copy(accelG = m / SensorManager.GRAVITY_EARTH) }
                Sensor.TYPE_GYROSCOPE -> set { it.copy(gyroDps = Math.toDegrees(m)) }
                Sensor.TYPE_LIGHT -> set { it.copy(lux = v[0].toDouble()) }
                Sensor.TYPE_MAGNETIC_FIELD -> set { it.copy(magUT = m) }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    @SuppressLint("MissingPermission")
    private fun start(ctx: Context) {
        app = ctx
        PhoneSensors.use(ctx, "gps-status")
        val s = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm = s
        for (t in listOf(Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LIGHT, Sensor.TYPE_MAGNETIC_FIELD)) {
            s.getDefaultSensor(t)?.let { s.registerListener(sensors, it, SensorManager.SENSOR_DELAY_UI) }
        }
        battery()
        if (!granted(ctx)) { set { it.copy(running = false, status = "Location permission needed for the phone GNSS") }; return }
        val l = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = l
        try {
            @Suppress("DEPRECATION") l.registerGnssStatusCallback(gnssCallback, main)
            @Suppress("DEPRECATION") l.addNmeaListener(nmea, main)
            l.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, location, Looper.getMainLooper())
            set { it.copy(running = true, status = if (l.isProviderEnabled(LocationManager.GPS_PROVIDER)) "Waiting for satellites..." else "Location is off in Android") }
        } catch (e: Exception) {
            set { it.copy(running = false, status = "Phone GNSS: " + (e.message ?: "error")) }
        }
    }

    private fun stop() {
        PhoneSensors.release("gps-status")
        sm?.unregisterListener(sensors); sm = null
        lm?.let { l ->
            try { l.unregisterGnssStatusCallback(gnssCallback) } catch (_: Exception) {}
            try { l.removeNmeaListener(nmea) } catch (_: Exception) {}
            try { l.removeUpdates(location) } catch (_: Exception) {}
        }
        lm = null
        set { it.copy(running = false, status = "Off") }
    }

    private fun battery() {
        val now = System.currentTimeMillis()
        if (now - lastBatteryMs < 10_000) return
        lastBatteryMs = now
        val i: Intent = app?.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        set {
            it.copy(battPct = if (level >= 0 && scale > 0) level * 100 / scale else null, battTempC = if (temp != Int.MIN_VALUE) temp / 10.0 else null,
                battV = if (mv > 0) mv / 1000.0 else null)
        }
    }
}
