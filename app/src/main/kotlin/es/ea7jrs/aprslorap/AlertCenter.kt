/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import es.ea7jrs.aprslorap.core.AisVessel
import es.ea7jrs.aprslorap.core.AlertKind
import es.ea7jrs.aprslorap.core.Alerts
import es.ea7jrs.aprslorap.core.ControlEvent
import es.ea7jrs.aprslorap.core.EmergencyAlert
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Emergency alerts: APRS beacon or message, AIS distress or collision (from the tracker's alarm, the AIS list or what the app
 * decodes itself), own EAI and man down (event "eai"). Opens the window over any screen, a full-screen notification when the
 * app is not in front, and the phone's "buzzer": alarm tone (alarm stream: it sounds in silent mode), vibration and torch.
 */
object AlertCenter {
    const val CH_ALERTS = "alerts"
    private const val ID_ALERT = 7

    val items = MutableStateFlow<List<EmergencyAlert>>(emptyList())
    val eai = MutableStateFlow<ControlEvent.Eai?>(null)
    val sounding = MutableStateFlow(false)

    private val queue = Alerts.Queue()
    private var ctx: Context? = null
    private var settings: Settings? = null
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private var beat: ScheduledFuture<*>? = null
    private var tone: ToneGenerator? = null
    private var torchId: String? = null
    private var torchOn = false
    private var startedMs = 0L
    private const val MAX_MS = 10 * 60_000L                 // like the tracker: the alarm stops by itself after 10 minutes

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        settings = Settings(c.applicationContext)
        val ch = NotificationChannel(CH_ALERTS, "Emergency alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)                         // the app plays the alarm itself (alarm stream, its own rhythm)
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        c.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    // ---- sources

    fun onLed(l: ControlEvent.Led) { Alerts.fromLed(l, System.currentTimeMillis())?.let { offer(it) } }

    fun onAis(v: AisVessel) { Alerts.fromAis(v, System.currentTimeMillis())?.let { offer(it) } }

    fun onAprs(call: String, text: String, fromMsg: Boolean) { Alerts.fromAprs(call, text, fromMsg, System.currentTimeMillis())?.let { offer(it) } }

    fun onEai(e: ControlEvent.Eai) {
        eai.value = e
        val now = System.currentTimeMillis()
        val list = Alerts.fromEai(e, settings?.callsign ?: "", now)
        for (k in listOf("eai", "mandown")) if (list.none { it.key == k }) synchronized(queue) { queue.end(k) }
        for (a in list) offer(a)
        publish()
    }

    /** An official alert (OfficialAlertsFetcher): the window, and the alarm only from the level chosen in the settings. */
    fun onOfficial(o: es.ea7jrs.aprslorap.core.OfficialAlert, sound: Boolean) {
        offer(EmergencyAlert("oa:" + o.id, AlertKind.OFFICIAL, o.event, o.area, false, null, System.currentTimeMillis(), false, o), sound)
    }

    /** A NAVTEX message of subject D (search and rescue, distress relay, piracy). */
    fun onNavtex(e: NavtexHub.Entry) = offer(NavtexHub.alertOf(e))

    /** A DSC distress alert, relay or acknowledgement. */
    fun onDsc(c: es.ea7jrs.aprslorap.core.Dsc.Call) { dscCalls[DscHub.alertOf(c).key] = c; offer(DscHub.alertOf(c)) }

    /** A 406 MHz distress beacon (not a self-test or a test protocol). */
    fun onBeacon406(m: es.ea7jrs.aprslorap.core.Beacon406.Message) { val a = Beacon406Hub.alertOf(m); beacons406[a.key] = m; offer(a) }

    /** The beacon message behind each 406 MHz alert. */
    val beacons406 = java.util.concurrent.ConcurrentHashMap<String, es.ea7jrs.aprslorap.core.Beacon406.Message>()

    /** The DSC call behind each DSC alert (position, nature, time). */
    val dscCalls = java.util.concurrent.ConcurrentHashMap<String, es.ea7jrs.aprslorap.core.Dsc.Call>()

    private fun offer(a: EmergencyAlert, sound: Boolean = true) {
        val isNew = synchronized(queue) { queue.offer(a, System.currentTimeMillis()) }
        publish()
        if (isNew) {
            if (sound) startAlarm(a.kind.distress || (a.official?.level ?: 0) >= 3)
            notify(a)
            toCar(a)
        }
    }

    private fun publish() {
        val list = synchronized(queue) { queue.items }
        for (k in items.value.map { it.key } - list.map { it.key }.toSet()) AppState.notifier?.clear(CAR_PREFIX + k)
        items.value = list
        if (list.isEmpty()) { stopAlarm(); cancelNotification() }
    }

    // ---- user actions

    /** SILENCE ALARM: the phone and the tracker (its buzzer, LED and flash; its alarm screen stays). */
    fun silence() {
        stopAlarm()
        val c = AppState.controller ?: return
        if (AppState.trackerLeds.value?.emg == true) c.control("alarm", "s" to "off")
    }

    fun close(key: String) {
        synchronized(queue) { queue.close(key, System.currentTimeMillis()) }
        if (key.startsWith("navtex:")) NavtexHub.acknowledge()          // closing the window is the manual reset of the alarm
        AppState.notifier?.clear(CAR_PREFIX + key)
        publish()
    }

    // ---- Android Auto: the same alert as a conversation, read aloud by the car

    const val CAR_PREFIX = "ALERT:"

    private fun toCar(a: EmergencyAlert) {
        val n = AppState.notifier ?: return
        var lat: Double? = null
        var lon: Double? = null
        when (a.kind) {
            AlertKind.AIS_DISTRESS, AlertKind.AIS_COLLISION -> a.mmsi?.let { AppState.aisVessels.value[it] }?.let { lat = it.lat; lon = it.lon }
            AlertKind.EAI, AlertKind.MAN_DOWN -> {}
            AlertKind.OFFICIAL -> { lat = a.official?.lat; lon = a.official?.lon }
            AlertKind.NAVTEX -> es.ea7jrs.aprslorap.core.Navtex.positions(a.text).firstOrNull()?.let { lat = it.first; lon = it.second }
            AlertKind.DSC -> dscCalls[a.key]?.let { lat = it.lat; lon = it.lon }
            AlertKind.BEACON406 -> beacons406[a.key]?.let { lat = it.lat; lon = it.lon }
            else -> {
                val s = AppState.stations.value.firstOrNull { it.call.equals(a.who, true) }
                val h = AppState.heard.value.firstOrNull { it.call.equals(a.who, true) }
                lat = s?.lat ?: h?.lat; lon = s?.lon ?: h?.lon
            }
        }
        val e = eai.value
        // distance and direction first, in words: the car reads "8.3 km south-west, Mic-E Emergency..."
        val where = n.where(lat, lon)
        val text = when (a.kind) {
            AlertKind.EAI -> listOfNotNull("Own emergency on this tracker", e?.let { eaiSource(it) },
                (e?.left ?: e?.fmLeft)?.let { "${Alerts.formatLeft(it.toLong())} left" }).joinToString(". ")
            AlertKind.MAN_DOWN -> "No key touched for ${e?.manDownMin ?: 0} min: EAI starts in ${e?.manDownLeft ?: 30} s. Mark as read means I'm OK."
            AlertKind.OFFICIAL -> a.official?.let { o -> listOf(o.event, o.area, where ?: "", o.text, o.instruction).filter { it.isNotBlank() }.joinToString(". ") } ?: a.text
            else -> listOf(where ?: "", a.text).filter { it.isNotBlank() }.joinToString(". ")
        }
        val replyTo = if (a.kind == AlertKind.NAVTEX || a.kind == AlertKind.DSC || a.kind == AlertKind.BEACON406) null else if (a.kind == AlertKind.APRS_BEACON || a.kind == AlertKind.APRS_MESSAGE || a.kind == AlertKind.MOB || a.kind == AlertKind.ROAD) a.who.uppercase() else null
        // avatar: the station's APRS symbol; AIS: the APRS yacht (distress) or ship symbol, as the tracker shows them
        val icon = when (a.kind) {
            AlertKind.AIS_DISTRESS -> n.symbolIcon(a.who, '/', 'Y')
            AlertKind.AIS_COLLISION -> n.symbolIcon(a.who, '/', 's')
            AlertKind.ROAD -> androidx.core.graphics.drawable.IconCompat.createWithBitmap(V27.bitmap())   // V-27 as the avatar (panel 18)
            AlertKind.EAI, AlertKind.MAN_DOWN, AlertKind.OFFICIAL, AlertKind.NAVTEX, AlertKind.DSC, AlertKind.BEACON406 -> null
            else -> n.symbolIcon(a.who)
        }
        n.alert(CAR_PREFIX + a.key, a.official?.title ?: a.kind.title, a.who, text.ifBlank { a.kind.title }, replyTo, lat, lon, icon)
    }

    /** "Mark as read" in the car: silences the alarm; for the man-down warning it is "I'm OK". */
    fun acknowledgedInCar(key: String) {
        silence()
        if (key == "mandown") AppState.controller?.control("eai", "s" to "ok")
    }

    // ---- the phone's "buzzer"

    @Synchronized
    private fun startAlarm(distress: Boolean) {
        val s = settings ?: return
        stopAlarmLocked()
        startedMs = System.currentTimeMillis()
        sounding.value = true
        if (s.alertSound) tone = try { ToneGenerator(AudioManager.STREAM_ALARM, 100) } catch (_: Exception) { null }
        if (s.alertVibrate) vibrate(distress)
        if (s.alertTorch) torchId = findTorch()
        // distress: fast (0.25 s on / 0.25 s off, like the tracker's alarm); warning: 1 s every 2.5 s
        val period = if (distress) 500L else 2500L
        val on = if (distress) 250 else 1000
        beat = exec.scheduleAtFixedRate({
            try {
                if (System.currentTimeMillis() - startedMs > MAX_MS) { stopAlarm(); return@scheduleAtFixedRate }
                tone?.startTone(if (distress) ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK else ToneGenerator.TONE_PROP_BEEP2, on)
                torch(true)
                exec.schedule({ torch(false) }, on.toLong(), TimeUnit.MILLISECONDS)
            } catch (_: Exception) {}
        }, 0, period, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stopAlarm() { stopAlarmLocked() }

    private fun stopAlarmLocked() {
        beat?.cancel(false)
        beat = null
        try { tone?.stopTone(); tone?.release() } catch (_: Exception) {}
        tone = null
        torch(false)
        torchId = null
        try { vibrator()?.cancel() } catch (_: Exception) {}
        sounding.value = false
    }

    private fun vibrator(): Vibrator? {
        val c = ctx ?: return null
        if (Build.VERSION.SDK_INT >= 31) return c.getSystemService(VibratorManager::class.java)?.defaultVibrator
        @Suppress("DEPRECATION")
        return c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private fun vibrate(distress: Boolean) {
        val v = vibrator() ?: return
        val pattern = if (distress) longArrayOf(0, 250, 250) else longArrayOf(0, 1000, 1500)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        try {
            @Suppress("DEPRECATION")
            v.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
        } catch (_: Exception) {}
    }

    private fun findTorch(): String? = try {
        val cm = ctx?.getSystemService(CameraManager::class.java)
        cm?.cameraIdList?.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    } catch (_: Exception) { null }

    private fun torch(on: Boolean) {
        val id = torchId ?: return
        if (on == torchOn) return
        try { ctx?.getSystemService(CameraManager::class.java)?.setTorchMode(id, on); torchOn = on } catch (_: Exception) {}
    }

    // ---- notification (full screen when the app is not in front or the phone is locked)

    @SuppressLint("MissingPermission")
    private fun notify(a: EmergencyAlert) {
        val c = ctx ?: return
        val nmc = NotificationManagerCompat.from(c)
        if (!nmc.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(c, ID_ALERT, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = listOf(a.who, a.text).filter { it.isNotBlank() }.joinToString(" · ")
        val b = NotificationCompat.Builder(c, CH_ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(a.official?.title ?: a.kind.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(if (a.kind.distress || (a.official?.level ?: 0) >= 3) 0xFFFF3B3B.toInt() else 0xFFFFB347.toInt())
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
        if (a.kind == AlertKind.ROAD) b.setLargeIcon(V27.bitmap())          // the V-27 beacon next to the title
        try { nmc.notify(ID_ALERT, b.build()) } catch (_: Exception) {}
    }

    private fun cancelNotification() {
        val c = ctx ?: return
        try { NotificationManagerCompat.from(c).cancel(ID_ALERT) } catch (_: Exception) {}
    }

    /** Short label of who raised the own EAI. */
    fun eaiSource(e: ControlEvent.Eai): String = when {
        e.by.isNotBlank() -> "Started by ${e.by} · “EAI <code>”"
        e.fm && e.fmBy == "mandown" -> "Started by the man-down timer"
        e.fm && e.fmBy == "app" -> "Started from this app"
        e.fm -> "Started from the tracker menu"
        else -> "SOS beacon of this tracker"
    }

    fun isOwn(k: AlertKind) = k == AlertKind.EAI || k == AlertKind.MAN_DOWN
}
