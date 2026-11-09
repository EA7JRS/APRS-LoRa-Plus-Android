/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import es.ea7jrs.aprslorap.core.RotorFollow
import es.ea7jrs.aprslorap.core.RotorPointing
import es.ea7jrs.aprslorap.core.RotorProto
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Link with an APRS LoRa+ Rotor box (ESP32, ESP32-S3, Pico 2 W or Raspberry Pi; repository EA7JRS/APRS-LoRa-Plus-Rotor):
 * the same JSON lines over BLE or over TCP (WiFi, port 4534). Independent of the tracker link: it can stay up on its own and,
 * with "Follow the tracker", the tracker's pointing (event `rot`) goes to the rotor box.
 */
object RotorLink {
    interface Transport { fun send(line: String); fun close() }

    @Volatile private var transport: Transport? = null
    @Volatile private var appCtx: Context? = null
    private val lines = RotorProto.LineAssembler()
    private val cfg = RotorProto.ConfigAssembler()
    @Volatile private var lastAz: Double? = null
    @Volatile private var lastEl: Double? = null
    @Volatile private var lastSentMs = 0L
    @Volatile private var pendingConfig: List<String> = emptyList()

    val connected: Boolean get() = transport != null && AppState.rotorInfo.value != null

    @Synchronized
    fun connectBle(ctx: Context, address: String) {
        disconnect()
        appCtx = ctx.applicationContext
        transport = RotorBle(ctx.applicationContext, address)
        applyFollow(ctx)
    }

    @Synchronized
    fun connectTcp(ctx: Context, host: String, port: Int) {
        disconnect()
        appCtx = ctx.applicationContext
        transport = RotorTcp(host, port)
        applyFollow(ctx)
    }

    @Synchronized
    fun disconnect() {
        transport?.close()
        transport = null
        clear()
        AppState.rotorLinkStatus.value = "Off"
    }

    private fun clear() {
        lines.reset()
        AppState.rotorInfo.value = null
        AppState.rotorAuthed.value = false
        AppState.rotorBusy.value = false
        AppState.rotorCat.value = null
        catSetupKey = ""
        lastAz = null; lastEl = null
    }

    fun send(line: String) { transport?.send(line) }

    /** Moving commands: told why nothing happens when there is no link. */
    fun command(line: String): Boolean {
        if (transport == null) { AppState.error.value = "Connect to the rotor first"; return false }
        send(line)
        return true
    }

    // ------------------------------------------------------------------ called by the transports

    internal fun onLinkUp() {
        clear()
        send(RotorProto.info())
        val pin = appCtx?.let { Settings(it).rotorPin } ?: ""
        if (pin.isNotEmpty()) send(RotorProto.auth(pin))
        send(RotorProto.subscribe(500))
        send(RotorProto.get())
    }

    internal fun onLinkDown(status: String) {
        clear()
        AppState.rotorLinkStatus.value = status
    }

    internal fun onBytes(b: ByteArray) { for (l in lines.feed(b)) onLine(l) }

    private fun onLine(line: String) {
        when (val ev = RotorProto.parse(line)) {
            is RotorProto.Event.InfoEv -> { AppState.rotorInfo.value = ev.info; if (ev.info.cat) send(RotorProto.cat()); sendWind() }
            is RotorProto.Event.Cat -> AppState.rotorCat.value = ev.state
            is RotorProto.Event.Pos -> { AppState.rotorPos.value = ev.pos; maybeFetchWeather() }
            is RotorProto.Event.Raw -> AppState.rotorRaw.value = ev
            is RotorProto.Event.ConfigPiece -> cfg.add(ev)?.let { text ->
                AppState.rotorConfig.value = text
                AppState.rotorBusy.value = false
                AppState.rotorMessage.value = "Configuration read (${text.length} characters)"
            }
            is RotorProto.Event.Ok -> when (ev.command) {
                "auth" -> { AppState.rotorAuthed.value = true; AppState.rotorMessage.value = ""; sendWind() }
                "cfgset" -> if (ev.done) {
                    AppState.rotorBusy.value = false
                    AppState.rotorMessage.value = "Configuration saved: the rotor box restarts"
                } else {
                    // One piece confirmed: next one (the box keeps them in order)
                    val rest = pendingConfig
                    if (rest.isNotEmpty()) { pendingConfig = rest.drop(1); send(rest.first()) }
                }
                "cal", "here" -> { AppState.rotorMessage.value = "Calibration saved"; send(RotorProto.raw()) }
                "reboot" -> AppState.rotorMessage.value = "The rotor box restarts"
                else -> {}
            }
            is RotorProto.Event.Err -> {
                if (ev.command == "auth") AppState.rotorAuthed.value = false
                if (ev.command == "cfgset") { AppState.rotorBusy.value = false; pendingConfig = emptyList() }
                AppState.rotorMessage.value = when (ev.message) {
                    "auth required" -> "The rotor box needs its PIN (type it and press Send PIN)"
                    "ptt not allowed" -> "PTT is off in the rotor box (configuration cat.ptt)"
                    "bad pin" -> "Wrong PIN"
                    else -> "${ev.command.ifEmpty { "rotor" }}: ${ev.message}"
                }
            }
            is RotorProto.Event.Hello, null -> {}
        }
    }

    // ------------------------------------------------------------------ wind protection

    @Volatile private var weatherCheckMs = 0L

    /** New internet weather (NetWeatherFetcher): to the box, which parks on strong gusts (wind.on in its configuration). */
    fun onNetWeather(d: es.ea7jrs.aprslorap.core.NetWeather.Data) { sendWind(d) }

    private fun sendWind(d: es.ea7jrs.aprslorap.core.NetWeather.Data? = AppState.netWx.value) {
        val info = AppState.rotorInfo.value ?: return
        if (d == null || !info.wind || transport == null) return
        if (info.pinRequired && !AppState.rotorAuthed.value) return         // "wx" needs the PIN: no error every 15 min
        RotorProto.wx(d, System.currentTimeMillis() / 1000)?.let { send(it) }
    }

    /** With only the rotor box linked (no tracker control), the weather is still fetched on the usual interval. */
    private fun maybeFetchWeather() {
        val now = System.currentTimeMillis()
        if (now - weatherCheckMs < 30_000) return
        weatherCheckMs = now
        val ctx = appCtx ?: return
        if (AppState.rotorInfo.value?.wind != true) return
        NetWeatherFetcher.maybeFetch(ctx, Settings(ctx))
    }

    // ------------------------------------------------------------------ configuration

    fun readConfig() {
        if (!command(RotorProto.configGet())) return
        AppState.rotorBusy.value = true
        AppState.rotorMessage.value = "Reading the configuration..."
    }

    /** Sends the whole configuration piece by piece (each one waits for its "ok"). */
    fun writeConfig(json: String) {
        val compact = try {
            es.ea7jrs.aprslorap.core.Json.encode(es.ea7jrs.aprslorap.core.Json.parseObject(json) ?: throw IllegalArgumentException())
        } catch (e: Exception) {
            AppState.rotorMessage.value = "That is not valid JSON"
            return
        }
        val pieces = RotorProto.configSet(compact)
        pendingConfig = pieces.drop(1)
        if (!command(pieces.first())) { pendingConfig = emptyList(); return }
        AppState.rotorBusy.value = true
        AppState.rotorMessage.value = "Saving the configuration (${pieces.size} pieces)..."
    }

    // ------------------------------------------------------------------ following the tracker

    /** New pointing from the tracker (event `rot`, any thread). */
    fun onPointing(p: RotorPointing) {
        val ctx = appCtx ?: return
        if (transport == null) return
        val s = Settings(ctx)
        if (!s.rotorFollow || s.rotorFollowMode != 0) return
        val info = AppState.rotorInfo.value ?: return
        if (info.pinRequired && !AppState.rotorAuthed.value) return
        val now = System.currentTimeMillis()
        val el = if (info.hasEl) p.elDeg.coerceIn(info.limits.minEl, info.limits.maxEl) else null
        if (now - lastSentMs < 1000 || !RotorProto.worthSending(lastAz, lastEl, p.azDeg, el, s.rotorStep)) return
        lastAz = p.azDeg; lastEl = el; lastSentMs = now
        send(RotorProto.pos(p.azDeg, el))
        AppState.rotorFollowSent.value = String.format(Locale.US, "Sent %.1f°%s%s", p.azDeg,
            if (el != null) String.format(Locale.US, " / %.1f°", el) else "", p.name?.let { " ($it)" } ?: "")
    }

    // ------------------------------------------------------------------ CAT: satellite Doppler from the tracker

    @Volatile private var lastCatTrackMs = 0L
    @Volatile private var catSetupKey = ""

    /** New `sat` event of the tracker (every 2 s in SAT mode): downlink / uplink and range rate to the rotor box's radio. */
    fun onSat(st: es.ea7jrs.aprslorap.core.SatState) {
        val ctx = appCtx ?: return
        if (transport == null) return
        val info = AppState.rotorInfo.value ?: return
        if (!info.cat || (info.pinRequired && !AppState.rotorAuthed.value)) return
        val s = Settings(ctx)
        if (!s.rotorCatTrack) return
        val rr = st.rangeRateKmS ?: return
        if (!st.ok || (st.elDeg ?: -1.0) < -2.0) return                // only while the satellite is (almost) in view
        val now = System.currentTimeMillis()
        if (now - lastCatTrackMs < 1500) return
        val txs = AppState.satTransmitters.value
        val t = txs.firstOrNull { it.index == s.rotorCatTx }
            ?: AppState.satReceiver.value?.tx?.let { i -> txs.firstOrNull { it.index == i } }
            ?: txs.firstOrNull { it.downHz > 0 } ?: return
        lastCatTrackMs = now
        // New satellite / transmitter: the radio's mode and split as the transmitter needs (once)
        val up = s.rotorCatUplink && t.upHz > 0
        val key = "${st.name}/${t.index}/$up"
        if (key != catSetupKey) {
            catSetupKey = key
            RotorProto.satMode(t.mode)?.let { send(RotorProto.catMode(it)) }
            if (AppState.rotorCat.value?.split != up) send(RotorProto.catSplit(up))
            // CTCSS of the uplink: the one chosen in the app or the one in the transmitter's description (SO-50, AO-91...)
            if (up) (s.rotorCatTone.takeIf { it > 0 } ?: RotorProto.satTone(t.description))?.let { send(RotorProto.catTone(it)) }
        }
        send(RotorProto.catTrack(st.name ?: "SAT", maxOf(0L, t.downHz), if (s.rotorCatUplink) maxOf(0L, t.upHz) else 0L, rr))
    }

    // ------------------------------------------------------------------ following an APRS station or a CW beacon from the app

    @Volatile private var follower: Thread? = null
    @Volatile private var lastHeardAsk = 0L

    /** Starts / stops the follower of modes 1 (APRS station) and 2 (CW beacon) after a settings change. */
    @Synchronized
    fun applyFollow(ctx: Context) {
        appCtx = ctx.applicationContext
        val s = Settings(ctx)
        val want = s.rotorFollow && s.rotorFollowMode != 0
        if (want && follower == null) {
            follower = Thread({ followLoop() }, "rotor-follow").also { it.isDaemon = true; it.start() }
        } else if (!want) {
            follower?.interrupt(); follower = null
        }
        lastAz = null; lastEl = null
    }

    private fun followLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            try { followStep() } catch (e: Exception) { AppState.rotorFollowSent.value = "Follow: ${e.message}" }
        }
    }

    /** One second of following: where the target is now, seen from the tracker's GPS, and the order if it moved enough. */
    private fun followStep() {
        val ctx = appCtx ?: return
        val s = Settings(ctx)
        if (!s.rotorFollow || s.rotorFollowMode == 0) return
        if (transport == null) return
        val info = AppState.rotorInfo.value ?: return
        if (info.pinRequired && !AppState.rotorAuthed.value) { AppState.rotorFollowSent.value = "Waiting for the rotor PIN"; return }
        val g = AppState.gnss.value
        val oLat = g?.lat; val oLon = g?.lon
        if (oLat == null || oLon == null) { AppState.rotorFollowSent.value = "No tracker GPS position yet (tracker link and GNSS)"; return }
        val oAlt = g.altitudeM ?: 0.0
        val now = System.currentTimeMillis()
        val target: Triple<String, Pair<Double, Double>, Double>          // name, position, altitude
        if (s.rotorFollowMode == 1) {
            // The tracker's heard list (LoRa and FSK, with course and speed also from Mic-E): refreshed every 10 s
            if (now - lastHeardAsk >= 10_000 && AppState.controlReady.value) {
                lastHeardAsk = now
                AppState.controller?.control("heard", "cnt" to 12)
            }
            val since = if (AppState.heardAtMs.value > 0) (now - AppState.heardAtMs.value) / 1000.0 else 0.0
            val aim = RotorFollow.aimAtStation(AppState.heard.value, s.rotorFollowCall, s.rotorFollowFilter, s.rotorFollowMaxMin,
                s.rotorPredict, s.rotorPredictMaxS, since)
            if (aim == null) {
                AppState.rotorFollowSent.value = if (s.rotorFollowCall.isNotBlank()) "${s.rotorFollowCall}: not heard with position" else "No station with position that passes the filter"
                return
            }
            target = Triple(aim.call + if (aim.estimated) "~" else "", aim.lat to aim.lon, aim.altM)
        } else {
            // Text of the tracker's CW listening and of the phone's CW decoder (Modes tab)
            val b = RotorFollow.parseCwBeacon(AppState.cwRxText.value.takeLast(200)) ?: RotorFollow.parseCwBeacon(AppState.modemText.value.takeLast(200))
            if (b == null) { AppState.rotorFollowSent.value = "No CW beacon with locator decoded yet"; return }
            target = Triple((if (b.call.isNotEmpty()) b.call + " " else "") + b.locator, b.lat to b.lon, 0.0)
        }
        val lk = RotorFollow.look(oLat, oLon, oAlt, target.second.first, target.second.second, target.third)
        val el = if (info.hasEl) lk.elDeg.coerceIn(maxOf(0.0, info.limits.minEl), info.limits.maxEl) else null
        val text = String.format(Locale.US, "%s: %.1f°%s, %.1f km", target.first, lk.azDeg, if (el != null) String.format(Locale.US, " / %.1f°", el) else "", lk.km)
        if (now - lastSentMs < 1000 || !RotorProto.worthSending(lastAz, lastEl, lk.azDeg, el, s.rotorStep)) {
            AppState.rotorFollowSent.value = "Following $text"
            return
        }
        lastAz = lk.azDeg; lastEl = el; lastSentMs = now
        send(RotorProto.pos(lk.azDeg, el))
        AppState.rotorFollowSent.value = "Sent $text"
    }
}

// ============================================================================================================================
// TCP (WiFi): rotor box on the network, port 4534
// ============================================================================================================================

private class RotorTcp(private val host: String, private val port: Int) : RotorLink.Transport {
    @Volatile private var running = true
    @Volatile private var out: OutputStream? = null
    @Volatile private var sock: Socket? = null
    private val writer = Executors.newSingleThreadExecutor()
    private val thread = Thread({ loop() }, "rotor-tcp").also { it.isDaemon = true; it.start() }

    private fun loop() {
        while (running) {
            try {
                AppState.rotorLinkStatus.value = "Connecting to $host:$port..."
                Socket().use { s ->
                    sock = s
                    s.connect(InetSocketAddress(host, port), 5000)
                    s.tcpNoDelay = true
                    out = s.getOutputStream()
                    AppState.rotorLinkStatus.value = "Connected (WiFi $host:$port)"
                    RotorLink.onLinkUp()
                    val inp = s.getInputStream()
                    val buf = ByteArray(1024)
                    while (running) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        RotorLink.onBytes(buf.copyOf(n))
                    }
                }
            } catch (e: Exception) {
                if (!running) break
                RotorLink.onLinkDown("No connection with $host:$port (${e.message}); retrying")
            } finally {
                out = null; sock = null
            }
            if (!running) break
            try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
        }
    }

    override fun send(line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        writer.execute { try { out?.let { it.write(bytes); it.flush() } } catch (_: Exception) {} }
    }

    override fun close() {
        running = false
        try { sock?.close() } catch (_: Exception) {}
        thread.interrupt()
        writer.shutdown()
    }
}

// ============================================================================================================================
// BLE: service 4c6f5261-2b00-4ea7-b000-000000000001 (commands ...0002, events ...0003)
// ============================================================================================================================

/**
 * Small GATT client for the rotor box. One GATT operation at a time (queue). The event characteristic needs an encrypted link:
 * reading it makes Android pair ("Just Works", Android may ask to confirm); the control itself is protected by the box's PIN.
 */
@SuppressLint("MissingPermission")
private class RotorBle(private val ctx: Context, private val address: String) : RotorLink.Transport {
    private sealed class Op {
        class Write(val data: ByteArray) : Op()
        class Subscribe(val ch: BluetoothGattCharacteristic) : Op()
        class Read(val ch: BluetoothGattCharacteristic) : Op()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var cmd: BluetoothGattCharacteristic? = null
    @Volatile private var evt: BluetoothGattCharacteristic? = null
    @Volatile private var wanted = true
    @Volatile private var ready = false
    @Volatile private var mtu = 23
    private val queue = ArrayDeque<Op>()
    private var busy = false
    private var reads = 0
    private var bondAsked = false

    private val svcUuid = UUID.fromString(RotorProto.SERVICE)
    private val cmdUuid = UUID.fromString(RotorProto.COMMAND)
    private val evtUuid = UUID.fromString(RotorProto.EVENT)

    init { handler.post { open() } }

    private fun open() {
        if (!wanted) return
        val device = try { adapter?.getRemoteDevice(address) } catch (e: IllegalArgumentException) { null }
        if (device == null) { RotorLink.onLinkDown("Invalid rotor address"); return }
        AppState.rotorLinkStatus.value = "Connecting (Bluetooth)..."
        gatt?.close()
        reset()
        gatt = try { device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE) } catch (e: SecurityException) { null }
        if (gatt == null) RotorLink.onLinkDown("Bluetooth permission is needed")
    }

    private fun reset() {
        cmd = null; evt = null; ready = false; reads = 0; bondAsked = false
        synchronized(queue) { queue.clear(); busy = false }
    }

    override fun send(line: String) {
        if (!ready || cmd == null) return
        val data = line.toByteArray(Charsets.UTF_8)
        val size = (mtu - 3).coerceAtLeast(20)
        synchronized(queue) { for (i in data.indices step size) queue.addLast(Op.Write(data.copyOfRange(i, minOf(data.size, i + size)))) }
        handler.post { pump() }
    }

    override fun close() {
        wanted = false
        handler.removeCallbacksAndMessages(null)
        handler.post {
            try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) {}
            gatt = null
            reset()
        }
    }

    private fun pump() {
        val g = gatt ?: return
        val op: Op
        synchronized(queue) {
            if (busy || queue.isEmpty()) return
            op = queue.removeFirst()
            busy = true
        }
        val ok = try {
            when (op) {
                is Op.Write -> {
                    val ch = cmd ?: return done()
                    if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, op.data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
                    else legacyWrite(g, ch, op.data)
                }
                is Op.Subscribe -> subscribe(g, op.ch)
                is Op.Read -> g.readCharacteristic(op.ch)
            }
        } catch (e: SecurityException) { false }
        if (!ok) {
            synchronized(queue) { queue.addFirst(op); busy = false }
            handler.postDelayed({ pump() }, 50)
        }
    }

    private fun done() {
        synchronized(queue) { busy = false }
        pump()
    }

    @Suppress("DEPRECATION")
    private fun legacyWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ch.value = data
        return g.writeCharacteristic(ch)
    }

    @Suppress("DEPRECATION")
    private fun subscribe(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(ch, true)) return false
        val cccd = ch.getDescriptor(TrackerUuids.CCCD) ?: run { handler.post { done() }; return true }
        return if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0
        else { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
    }

    /** Encrypted read of the event characteristic answered: success = paired; otherwise wait for (or ask) the pairing. */
    private fun onRead(g: BluetoothGatt, status: Int) {
        synchronized(queue) { busy = false }
        val e = evt ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            synchronized(queue) { queue.addFirst(Op.Subscribe(e)) }
            pump()
            return
        }
        reads++
        if (reads > 6) { RotorLink.onLinkDown("The rotor box refuses the pairing: forget it in Android's Bluetooth settings and try again"); g.disconnect(); return }
        AppState.rotorLinkStatus.value = "Pairing with the rotor box (confirm it if Android asks)..."
        val bonded = try { g.device.bondState } catch (_: SecurityException) { BluetoothDevice.BOND_NONE }
        if (bonded == BluetoothDevice.BOND_NONE && !bondAsked) {
            bondAsked = true
            try { g.device.createBond() } catch (_: SecurityException) {}
        }
        handler.postDelayed({ if (gatt === g) { synchronized(queue) { queue.addFirst(Op.Read(e)) }; pump() } }, 2500)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                AppState.rotorLinkStatus.value = "Connected, preparing..."
                handler.post { if (!g.requestMtu(247)) g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.post {
                    reset()
                    try { g.close() } catch (_: SecurityException) {}
                    if (gatt === g) gatt = null
                    RotorLink.onLinkDown(if (wanted) "Connection lost, retrying..." else "Off")
                    if (wanted) handler.postDelayed({ open() }, 5000)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            handler.post { g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                val svc = g.getService(svcUuid)
                val c = svc?.getCharacteristic(cmdUuid)
                val e = svc?.getCharacteristic(evtUuid)
                if (status != BluetoothGatt.GATT_SUCCESS || c == null || e == null) {
                    RotorLink.onLinkDown("This device is not an APRS LoRa+ Rotor (or its Bluetooth is in SPP mode)")
                    wanted = false
                    g.disconnect()
                    return@post
                }
                cmd = c; evt = e
                synchronized(queue) { queue.clear(); queue.addLast(Op.Read(e)); busy = false }
                pump()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (d.characteristic.uuid == evtUuid && !ready) {
                    ready = true
                    AppState.rotorLinkStatus.value = "Connected (Bluetooth)"
                    synchronized(queue) { busy = false }
                    RotorLink.onLinkUp()
                    pump()
                } else done()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) { handler.post { done() } }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handler.post { onRead(g, status) }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) handler.post { onRead(g, status) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == evtUuid) RotorLink.onBytes(value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && ch.uuid == evtUuid) ch.value?.let { RotorLink.onBytes(it.copyOf()) }
        }
    }
}
