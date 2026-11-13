/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import es.ea7jrs.aprslorap.core.Cat
import es.ea7jrs.aprslorap.core.RotorProto
import es.ea7jrs.aprslorap.core.SatState
import java.util.Locale

/**
 * CAT of a radio plugged into the phone by USB (OTG): the radio's own USB socket (FT-991A, FT-891), a CAT cable (FT-817 /
 * 818: CT-62 type) or Yaesu's SCU-17 interface (CAT on its first port, PTT on the RTS line of the second, and the audio as
 * a USB sound card). The engines are in the core ([Cat]); here the serial port, the loop and what the rest of the app uses:
 * frequency, mode, VFO, split, CTCSS, PTT and the Doppler of the tracker's satellite. State in [AppState.phoneCat], in the
 * same shape as the radio of the rotor box.
 */
object CatLink {
    private const val ACTION_PERMISSION = "es.ea7jrs.aprslorap.USB_CAT_PERMISSION"

    data class Device(val key: String, val label: String, val ports: Int)

    @Volatile private var running = false
    @Volatile private var engine: Cat.Engine? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var pttPort: UsbSerialPort? = null         // second port of the adapter: PTT on RTS (SCU-17)
    @Volatile private var appCtx: Context? = null
    @Volatile private var rigName = ""
    @Volatile private var linePtt = false                        // PTT keyed with the RTS line, not by CAT
    private val tracker = Cat.Tracker()
    @Volatile private var trackKey = ""
    private var receiverOn = false
    private val lock = Any()

    val connected: Boolean get() = running && port != null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != ACTION_PERMISSION) return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) open(c.applicationContext)
            else { running = false; AppState.phoneCatStatus.value = "USB permission refused" }
        }
    }

    private fun key(d: UsbSerialDriver) = String.format(Locale.US, "%04x:%04x", d.device.vendorId, d.device.productId)

    /** USB serial adapters plugged in now. */
    fun devices(ctx: Context): List<Device> {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return try {
            UsbSerialProber.getDefaultProber().findAllDrivers(usb).map { d ->
                val name = (try { d.device.productName } catch (_: Exception) { null }) ?: d.javaClass.simpleName.removeSuffix("SerialDriver")
                Device(key(d), "$name (${key(d)}, ${d.ports.size} port${if (d.ports.size == 1) "" else "s"})", d.ports.size)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun driver(ctx: Context, settings: Settings): UsbSerialDriver? {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        val all = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
        return all.firstOrNull { key(it) == settings.catDevice } ?: all.firstOrNull()
    }

    fun connect(context: Context) {
        val ctx = context.applicationContext
        disconnect()
        appCtx = ctx
        running = true
        val settings = Settings(ctx)
        val usb = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
        val d = try { driver(ctx, settings) } catch (_: Exception) { null }
        if (usb == null || d == null) { running = false; AppState.phoneCatStatus.value = "No USB serial adapter: plug in the radio or its CAT cable (OTG)"; return }
        if (usb.hasPermission(d.device)) { open(ctx); return }
        if (!receiverOn) {
            ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverOn = true
        }
        val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ACTION_PERMISSION).setPackage(ctx.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)       // the system adds the result extras
        usb.requestPermission(d.device, pi)
        AppState.phoneCatStatus.value = "Allow the USB device"
    }

    private fun open(ctx: Context) {
        if (!running) return
        val settings = Settings(ctx)
        try {
            val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            val d = driver(ctx, settings) ?: throw IllegalStateException("adapter unplugged")
            val model = Cat.MODELS.firstOrNull { it.name == settings.catModel } ?: Cat.MODELS[4]
            val connection = usb.openDevice(d.device) ?: throw IllegalStateException("cannot open the USB device")
            val idx = settings.catPort.coerceIn(0, d.ports.size - 1)
            val p = d.ports[idx]
            p.open(connection)
            p.setParameters(settings.catBaud, 8, if (model.stopBits == 2) UsbSerialPort.STOPBITS_2 else UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try { p.setDTR(settings.catDtr); p.setRTS(settings.catRts) } catch (_: Exception) {}
            // PTT on the RTS line of the other port (SCU-17, or the radio's own second port with "PC keying" on RTS)
            linePtt = false
            if (settings.catPttLine && d.ports.size > 1) {
                try {
                    val q = d.ports[if (idx == 0) 1 else 0]
                    q.open(connection)
                    q.setRTS(false); q.setDTR(false)
                    pttPort = q
                    linePtt = true
                } catch (_: Exception) { pttPort = null }
            }
            val e = Cat.make(model.proto, settings.catCivAddr)
            e.allowPtt = settings.catPtt && Licence.isValid(settings)
            engine = e
            port = p
            rigName = model.name.substringBefore(" /") + " · " + model.proto.label + " · " + settings.catBaud
            tracker.stop(); trackKey = ""
            AppState.phoneCatStatus.value = "Port open (${d.javaClass.simpleName.removeSuffix("SerialDriver")}, port ${idx + 1} of ${d.ports.size}): waiting for the radio"
            publish(e)
            thread = Thread({ loop(p, e) }, "cat-usb").apply { isDaemon = true; start() }
        } catch (ex: Exception) {
            running = false
            closePorts()
            AppState.phoneCatStatus.value = "CAT: ${ex.message ?: ex.javaClass.simpleName}"
        }
    }

    private fun loop(p: UsbSerialPort, e: Cat.Engine) {
        val buf = ByteArray(256)
        var lastPublish = 0L
        var wasLinked = false
        try {
            while (running) {
                val now = android.os.SystemClock.elapsedRealtime()
                val out = synchronized(lock) { tracker.step(e, now); e.tick(now) }
                if (out.isNotEmpty()) p.write(Cat.bytes(out), 300)
                val n = p.read(buf, 20)
                if (n > 0) synchronized(lock) { e.feed(buf, n) }
                if (now - lastPublish >= 250) {
                    lastPublish = now
                    publish(e)
                    if (e.state.link != wasLinked) {
                        wasLinked = e.state.link
                        AppState.phoneCatStatus.value = if (wasLinked) "Connected" else "The radio does not answer: check the model, the baud rate (radio menu CAT RATE) and the port"
                    }
                }
            }
        } catch (ex: Exception) {
            if (running) AppState.phoneCatStatus.value = "CAT link lost: ${ex.message ?: "USB unplugged"}"
        } finally {
            try { if (e.state.ptt) keyLine(false) } catch (_: Exception) {}
            running = false
            closePorts()
            engine = null
            AppState.phoneCat.value = null
        }
    }

    private fun publish(e: Cat.Engine) {
        val st = synchronized(lock) {
            val s = e.state
            RotorProto.CatState(rigName, s.link, s.freq, s.mode, s.txFreq.takeIf { it > 0 }, s.split, s.vfo.toString(), s.ptt, s.smeter, e.allowPtt,
                RotorProto.CatTracking(true, tracker.active, tracker.name, tracker.doppler), if (s.tone > 0) s.tone / 10.0 else null)
        }
        if (AppState.phoneCat.value != st) AppState.phoneCat.value = st
    }

    private fun closePorts() {
        try { pttPort?.close() } catch (_: Exception) {}
        try { port?.close() } catch (_: Exception) {}
        pttPort = null; port = null
    }

    fun disconnect() {
        val was = running
        running = false
        thread?.let { t -> if (t !== Thread.currentThread()) try { t.join(400) } catch (_: Exception) {} }
        thread = null
        closePorts()
        engine = null
        AppState.phoneCat.value = null
        if (was) AppState.phoneCatStatus.value = "Off"
        if (receiverOn) { try { appCtx?.unregisterReceiver(receiver) } catch (_: Exception) {}; receiverOn = false }
    }

    // ------------------------------------------------------------------ commands

    private fun <T> cmd(f: (Cat.Engine) -> T): T? {
        val e = engine ?: return null
        return synchronized(lock) { f(e) }
    }
    private fun linked() = engine?.state?.link == true

    fun freq(hz: Long): Boolean = hz > 0 && linked() && cmd { it.setFreq(hz) } == true
    fun mode(m: String): Boolean = linked() && cmd { it.setMode(m) } == true
    fun swapVfo(): Boolean = linked() && cmd { it.toggleVfo() } == true
    fun split(on: Boolean): Boolean = linked() && cmd { it.setSplit(on) } == true
    fun txFreq(hz: Long): Boolean = linked() && cmd { it.setTxFreq(hz) } == true
    /** CTCSS on transmit in Hz, 0 = off. */
    fun tone(hz: Double): Boolean = linked() && cmd { it.setTone(Math.round(hz * 10).toInt()) } == true

    /** PTT, only while a key is held. False when it is not allowed (setting off, callsign not validated) or there is no link. */
    fun ptt(on: Boolean): Boolean {
        val e = engine ?: return false
        if (on && (!e.allowPtt || !e.state.link)) return false
        if (linePtt) { keyLine(on); synchronized(lock) { e.state.ptt = on }; return true }
        return synchronized(lock) { e.setPtt(on) }
    }
    private fun keyLine(on: Boolean) { try { pttPort?.setRTS(on) } catch (_: Exception) {} }

    /** The PTT setting changed while connected. */
    fun applyPtt(settings: Settings) { cmd { it.allowPtt = settings.catPtt && Licence.isValid(settings) } }

    // ------------------------------------------------------------------ satellite Doppler from the tracker

    /** New `sat` event of the tracker (every 2 s in SAT mode): this radio follows the transmitter with Doppler. */
    fun onSat(st: SatState) {
        val ctx = appCtx ?: return
        val e = engine ?: return
        if (!e.state.link) return
        val s = Settings(ctx)
        if (!s.catTrack) { if (tracker.active) synchronized(lock) { tracker.stop() }; return }
        val rr = st.rangeRateKmS ?: return
        if (!st.ok || (st.elDeg ?: -1.0) < -2.0) return                // only while the satellite is (almost) in view
        val txs = AppState.satTransmitters.value
        val t = txs.firstOrNull { it.index == s.rotorCatTx }
            ?: AppState.satReceiver.value?.tx?.let { i -> txs.firstOrNull { it.index == i } }
            ?: txs.firstOrNull { it.downHz > 0 } ?: return
        val up = s.catUplink && t.upHz > 0
        val key = "${st.name}/${t.index}/$up"
        synchronized(lock) {
            if (key != trackKey) {                                      // new satellite / transmitter: mode, split and tone once
                trackKey = key
                RotorProto.satMode(t.mode)?.let { e.setMode(it) }
                if (e.state.split != up) e.setSplit(up)
                if (up) (t.toneHz ?: RotorProto.satTone(t.description))?.let { e.setTone(Math.round(it * 10).toInt()) }
            }
            tracker.update(st.name ?: "SAT", maxOf(0L, t.downHz), if (up) t.upHz else 0L, rr, android.os.SystemClock.elapsedRealtime())
        }
    }
}

/**
 * The radio under CAT, wherever it is: the one plugged into the phone if it answers, else the one on the rotor box. What
 * TUNE (DX cluster, IBP), band B, the spots and the log use.
 */
object Rig {
    val phone: RotorProto.CatState? get() = AppState.phoneCat.value?.takeIf { it.link }
    val box: RotorProto.CatState? get() = AppState.rotorCat.value?.takeIf { it.link }
    val state: RotorProto.CatState? get() = phone ?: box
    val linked: Boolean get() = state != null

    /** Tunes every radio under CAT (phone and rotor box). Returns their names, empty if there is none. */
    fun tune(hz: Long, mode: String?): List<String> {
        val done = ArrayList<String>()
        if (phone != null && CatLink.freq(hz)) { mode?.let { CatLink.mode(it) }; done.add("CAT radio") }
        if (box != null) {
            RotorLink.send(RotorProto.catFreq(hz))
            mode?.let { RotorLink.send(RotorProto.catMode(it)) }
            done.add(if (done.isEmpty()) "CAT radio" else "rotor box radio")
        }
        return done
    }
}
