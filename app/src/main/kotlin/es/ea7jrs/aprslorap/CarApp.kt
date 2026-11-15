/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

// Android Auto screens of the app (panels 17 and 18 of the design canvas, approved by Manuel 2026-11-15), with the Car App
// Library: categories IoT (big keys for the tracker and the rotor box) and Weather (alerts, nearby APRS weather stations and the
// nearest METAR). Messages stay as notifications (MessagingStyle), which is what the car reads aloud and answers by voice.
// Nothing here transmits without a validated callsign; SOS asks for confirmation.

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import es.ea7jrs.aprslorap.core.CarText
import es.ea7jrs.aprslorap.core.RotorProto
import es.ea7jrs.aprslorap.core.WxCalc
import java.util.Locale

class CarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = DevicesScreen(carContext)
    }
}

/** A screen that redraws itself every [everyMs] while it is on the car's display (live tracker and rotor state). */
abstract class LiveScreen(ctx: CarContext, private val everyMs: Long = 5000) : Screen(ctx) {
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { invalidate(); h.postDelayed(this, everyMs) } }
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { h.postDelayed(tick, everyMs) }
            override fun onStop(owner: LifecycleOwner) { h.removeCallbacks(tick) }
        })
    }

    protected val settings: Settings get() = Settings(carContext)

    protected fun icon(res: Int, tint: CarColor? = null): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).also { b -> tint?.let { b.setTint(it) } }.build()

    protected fun toast(text: String) = CarToast.makeText(carContext, text, CarToast.LENGTH_LONG).show()

    /** The tracker can be commanded: link with the control service, and a validated callsign for what transmits. */
    protected fun canCommand(transmits: Boolean): Boolean {
        val c = AppState.controller
        if (c == null || !AppState.controlReady.value) { toast("Tracker not connected"); return false }
        if (transmits && !Licence.isValid(settings)) { toast("Callsign not validated: nothing is transmitted"); return false }
        return true
    }
}

/** IoT: six big keys (panel 17 / 18). */
class DevicesScreen(ctx: CarContext) : LiveScreen(ctx) {
    override fun onGetTemplate(): Template {
        val eaiOn = AlertCenter.eai.value?.let { it.sos == 2 || it.fm } == true
        val rot = AppState.rotorPos.value
        val rotorLinked = AppState.rotorInfo.value != null
        val follow = settings.rotorFollow && settings.rotorFollowMode == 0
        val items = ItemList.Builder()
            .addItem(GridItem.Builder().setTitle(if (eaiOn) "SOS ON" else "SOS V-27").setText(if (eaiOn) "beacon every 30 s" else "asks first")
                .setImage(CarIcon.Builder(IconCompat.createWithBitmap(V27.bitmap())).build(), GridItem.IMAGE_TYPE_LARGE)
                .setOnClickListener { if (canCommand(true)) screenManager.push(SosConfirmScreen(carContext)) }.build())
            .addItem(GridItem.Builder().setTitle("BEACON").setText("send now")
                .setImage(icon(R.drawable.car_beacon, CarColor.GREEN))
                .setOnClickListener { if (canCommand(true)) { AppState.controller?.control("beacon"); toast("Beacon requested") } }.build())
            .addItem(GridItem.Builder().setTitle("I'M OK").setText(if (eaiOn) "stop SOS" else "reset man down")
                .setImage(icon(R.drawable.car_ok))
                .setOnClickListener {
                    if (canCommand(false)) {
                        AppState.controller?.control("eai", "s" to if (eaiOn) "stop" else "ok")
                        AlertCenter.silence()
                        toast(if (eaiOn) "SOS stopped" else "I'm OK sent")
                    }
                }.build())
            .addItem(GridItem.Builder().setTitle("MESSAGE").setText("quick text")
                .setImage(icon(R.drawable.car_message))
                .setOnClickListener { if (canCommand(true)) screenManager.push(PeersScreen(carContext)) }.build())
            .addItem(GridItem.Builder().setTitle("ROTOR").setText(
                    if (!rotorLinked) "not linked" else listOfNotNull(rot?.azDeg?.let { String.format(Locale.US, "%03.0f°", it) },
                        if (follow) "following" else "follow tracker").joinToString(" · "))
                .setImage(icon(R.drawable.car_rotor, CarColor.BLUE))
                .setOnClickListener {
                    if (!rotorLinked) { toast("Rotor box not linked"); return@setOnClickListener }
                    val s = settings
                    val on = !follow
                    s.rotorFollowMode = 0; s.rotorFollow = on
                    RotorLink.applyFollow(carContext)
                    toast(if (on) "Rotor follows the tracker" else "Rotor follow off")
                    invalidate()
                }.build())
            .addItem(GridItem.Builder().setTitle("PARK").setText("rotor")
                .setImage(icon(R.drawable.car_park))
                .setOnClickListener { if (rotorLinked && RotorLink.command(RotorProto.park())) toast("Rotor parking") else toast("Rotor box not linked") }.build())
            .build()
        val call = settings.callsign.ifBlank { "APRS LoRa+" }
        return GridTemplate.Builder()
            .setTitle("$call · ${AppState.bleStatus.value}")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items)
            .setActionStrip(ActionStrip.Builder()
                .addAction(Action.Builder().setTitle("Weather").setOnClickListener { screenManager.push(WeatherScreen(carContext)) }.build())
                .build())
            .build()
    }
}

/** SOS: the tracker's EAI (emergency beacon with the GPS position every 30 s). With SSID -9 others see it as V-27. */
class SosConfirmScreen(ctx: CarContext) : LiveScreen(ctx, 60_000) {
    override fun onGetTemplate(): Template {
        val call = settings.callsign
        val road = es.ea7jrs.aprslorap.core.Alerts.ssid(call) == 9
        val text = "$call will send the emergency beacon with its GPS position every 30 s." +
            if (road) " With SSID -9, APRS LoRa+ shows it to others as a road emergency (V-27)." else ""
        return MessageTemplate.Builder(text)
            .setTitle("Start SOS on the tracker?")
            .setHeaderAction(Action.BACK)
            .setIcon(CarIcon.Builder(IconCompat.createWithBitmap(V27.bitmap())).build())
            .addAction(Action.Builder().setTitle("START SOS").setBackgroundColor(CarColor.RED).setOnClickListener {
                if (canCommand(true)) {
                    AppState.controller?.control("eai", "s" to "start")
                    toast("SOS started")
                }
                screenManager.pop()
            }.build())
            .addAction(Action.Builder().setTitle("Cancel").setOnClickListener { screenManager.pop() }.build())
            .build()
    }
}

/** MESSAGE: whom to (the last conversations); free dictation goes through the reply of the message notification. */
class PeersScreen(ctx: CarContext) : LiveScreen(ctx, 15_000) {
    override fun onGetTemplate(): Template {
        val peers = AppState.messages.value.sortedByDescending { it.timeMs }.map { it.peer }.distinct().take(6)
        val list = ItemList.Builder().setNoItemsMessage("No conversations yet")
        for (p in peers) {
            val st = AppState.stations.value.firstOrNull { it.call == p }
            val where = AppState.notifier?.where(st?.lat, st?.lon, words = false)
            list.addItem(Row.Builder().setTitle(p).also { r -> where?.let { r.addText(it) } }
                .setOnClickListener { screenManager.push(QuickTextScreen(carContext, p)) }.build())
        }
        return ListTemplate.Builder().setTitle("Message to").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

class QuickTextScreen(ctx: CarContext, private val peer: String) : LiveScreen(ctx, 60_000) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        for (t in QUICK) list.addItem(Row.Builder().setTitle(t).setOnClickListener {
            if (canCommand(true)) { AppState.controller?.sendMessage(peer, t); toast("Sent to $peer: $t") }
            screenManager.popToRoot()
        }.build())
        return ListTemplate.Builder().setTitle(peer).setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }

    companion object {
        val QUICK = listOf("On my way", "QRV", "Call me on the phone", "OK, received", "Need help", "Arriving in 10 min")
    }
}

/** Weather: the alert for this area, the nearest APRS weather stations (wind, pressure trend) and the nearest METAR. */
class WeatherScreen(ctx: CarContext) : LiveScreen(ctx, 30_000) {
    override fun onGetTemplate(): Template {
        val units = Fmt.units
        val (sk, sUnit) = WxCalc.speedFactor(units)
        val list = ItemList.Builder().setNoItemsMessage("No weather heard yet")
        val nowS = System.currentTimeMillis() / 1000
        AppState.officialAlerts.value.filter { it.level >= 1 && it.activeAt(nowS) }.maxByOrNull { it.level }?.let { o ->
            list.addItem(Row.Builder().setTitle(listOf(o.levelName, o.event).filter { it.isNotBlank() }.joinToString(" · "))
                .addText(listOf(o.src.title, o.area).filter { it.isNotBlank() }.joinToString(" · "))
                .setImage(icon(R.drawable.car_alert, if (o.level >= 3) CarColor.RED else CarColor.YELLOW)).build())
        }
        val wx = AppState.stations.value.filter { it.wx != null && it.lat != null }
            .mapNotNull { s -> Own.to(s.lat, s.lon)?.let { s to it } }.sortedBy { it.second.first }.take(4)
        for ((s, d) in wx) {
            val w = s.wx!!
            val wind = w.windKmh?.let { String.format(Locale.US, "%.0f %s", it * sk, sUnit) + (w.windDirDeg?.let { dd -> " " + CarText.direction(dd.toDouble(), false) } ?: "") }
            val press = w.pressureHpa?.let { String.format(Locale.US, "%.0f hPa %s", it, CarText.trendArrow(s.wxPressD3h)).trim() }
            list.addItem(Row.Builder().setTitle(listOfNotNull(s.call, w.tempC?.let { Fmt.temp(it, 0) }).joinToString(" · "))
                .addText(listOfNotNull(AppState.notifier?.where(s.lat, s.lon, words = false), wind, press).joinToString(" · "))
                .setImage(icon(R.drawable.car_weather)).build())
        }
        val metar = AviationFetcher.data.value.metars.mapNotNull { m -> Own.to(m.lat, m.lon)?.let { m to it } }.minByOrNull { it.second.first }
        metar?.let { (m, _) ->
            list.addItem(Row.Builder().setTitle("${m.icao} (METAR) · ${m.cat}" + (m.tempC?.let { " · " + Fmt.temp(it, 0) } ?: ""))
                .addText(listOfNotNull(AppState.notifier?.where(m.lat, m.lon, words = false), m.raw.substringAfter(' ').substringAfter(' ').take(60)).joinToString(" · "))
                .setImage(icon(R.drawable.car_plane)).build())
        }
        return ListTemplate.Builder().setTitle("Weather here").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}
