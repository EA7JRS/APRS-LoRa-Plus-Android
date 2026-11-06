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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/** KISS-over-BLE service of the APRS LoRa+ firmware (the one used by the aprs.fi app too). */
object TrackerUuids {
    val SERVICE: UUID = UUID.fromString("00000001-ba2a-46c9-ae49-01b0961f68bb")
    val FROM_PHONE: UUID = UUID.fromString("00000002-ba2a-46c9-ae49-01b0961f68bb")     // the phone writes here
    val TO_PHONE: UUID = UUID.fromString("00000003-ba2a-46c9-ae49-01b0961f68bb")       // the tracker notifies here
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Control service of the APRS LoRa+ firmware (docs/BLE_CONTROL.md): JSON commands and events, and raw NMEA
    val CONTROL: UUID = UUID.fromString(es.ea7jrs.aprslorap.core.ControlUuids.SERVICE)
    val CONTROL_CMD: UUID = UUID.fromString(es.ea7jrs.aprslorap.core.ControlUuids.COMMAND)
    val CONTROL_EVT: UUID = UUID.fromString(es.ea7jrs.aprslorap.core.ControlUuids.EVENT)
    val CONTROL_NMEA: UUID = UUID.fromString(es.ea7jrs.aprslorap.core.ControlUuids.NMEA)
    val CONTROL_FIRMWARE: UUID = UUID.fromString(es.ea7jrs.aprslorap.core.ControlUuids.FIRMWARE)
}

/** Looks for trackers that offer the KISS service. */
class BleScanner(ctx: Context) {
    private val adapter: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var callback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun start(onFound: (name: String, address: String) -> Unit): Boolean {
        val scanner = adapter?.bluetoothLeScanner ?: return false
        stop()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // The tracker sends its name in the scan response: the first result of a device may come without it,
                // so every result is passed on and the list keeps the best name it saw
                val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                    ?: try { result.device.name } catch (e: SecurityException) { null }
                onFound(name ?: "", result.device.address)
            }
        }
        callback = cb
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(TrackerUuids.SERVICE)).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        callback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        callback = null
    }
}

/**
 * GATT client. Android allows ONE GATT operation at a time, so every write (KISS chunks, control commands, notification
 * subscriptions) goes through one queue and the next starts when the previous one is confirmed. A write the stack refuses
 * (busy) goes back to the front of the queue and is tried again shortly: losing a chunk would break a KISS frame or a JSON line.
 */
@SuppressLint("MissingPermission")
class TrackerBle(
    private val ctx: Context,
    private val onBytes: (ByteArray) -> Unit,                       // KISS
    private val onStatus: (String) -> Unit,
    private val onControlBytes: (ByteArray) -> Unit = {},           // control events (JSON lines)
    private val onNmeaBytes: (ByteArray) -> Unit = {},              // raw NMEA
    private val onControlReady: (Boolean) -> Unit = {},             // true: the tracker has the control service; false: link lost
    private val onLog: (String) -> Unit = {},
) {
    private sealed class Op {
        class Write(val ch: BluetoothGattCharacteristic, val data: ByteArray, val fast: Boolean = false) : Op()
        class Subscribe(val ch: BluetoothGattCharacteristic, val on: Boolean) : Op()
        class Run(val block: () -> Unit) : Op()                      // runs when the operations before it are done
        class Authenticate(val ch: BluetoothGattCharacteristic) : Op()   // encrypted read: makes Android pair (PIN) if needed
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var kissChar: BluetoothGattCharacteristic? = null
    @Volatile private var cmdChar: BluetoothGattCharacteristic? = null
    @Volatile private var nmeaChar: BluetoothGattCharacteristic? = null
    @Volatile private var firmwareChar: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false
    @Volatile private var controlAuthed = false                     // the control service accepted us (PIN pairing done)
    private var pairChar: BluetoothGattCharacteristic? = null
    private var pairStartMs = 0L
    private var bondRequested = false
    private var pairReads = 0
    @Volatile private var nmeaWanted = false
    private var address = ""
    @Volatile private var wanted = false
    @Volatile private var mtu = 23
    private val queue = ArrayDeque<Op>()
    private var busy = false

    val hasControl: Boolean get() = cmdChar != null

    fun connect(address: String) {
        this.address = address
        wanted = true
        open()
    }

    fun disconnect() {
        wanted = false
        handler.removeCallbacksAndMessages(null)
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        clearLink()
        onStatus("Not connected")
    }

    private fun clearLink() {
        controlAuthed = false
        pairChar = null
        val hadControl = cmdChar != null
        kissChar = null; cmdChar = null; nmeaChar = null; firmwareChar = null
        ready = false
        synchronized(queue) { queue.clear(); busy = false }
        if (hadControl) onControlReady(false)
    }

    private fun open() {
        val device = try { adapter?.getRemoteDevice(address) } catch (e: IllegalArgumentException) { null }
        if (device == null) { onStatus("Invalid tracker address"); return }
        onStatus("Connecting...")
        gatt?.close()
        clearLink()
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun retryLater() {
        if (!wanted) return
        handler.postDelayed({ if (wanted) open() }, 5000)
    }

    private fun chunks(data: ByteArray): List<ByteArray> {
        val size = (mtu - 3).coerceAtLeast(20)
        return (data.indices step size).map { data.copyOfRange(it, minOf(data.size, it + size)) }
    }

    private fun enqueue(ops: List<Op>) {
        synchronized(queue) { queue.addAll(ops) }
        pump()
    }

    /** Sends a complete KISS frame; it is queued and split to the MTU. */
    fun write(frame: ByteArray) {
        val ch = kissChar ?: return
        enqueue(chunks(frame).map { Op.Write(ch, it) })
    }

    /**
     * Sends one control command line (with its final '\n'). Ignored if the tracker has no control service.
     * [fast]: write without response (key events: less delay; the firmware times them by their own timestamps).
     */
    fun writeControl(line: String, fast: Boolean = false) {
        val ch = cmdChar ?: return
        enqueue(chunks(line.toByteArray(Charsets.UTF_8)).map { Op.Write(ch, it, fast) })
    }

    /** Part of a firmware image (without response; the tracker confirms every 4 KB). False if the tracker cannot take it. */
    fun writeFirmware(data: ByteArray): Boolean {
        val ch = firmwareChar ?: return false
        enqueue(chunks(data).map { Op.Write(ch, it, fast = true) })
        return true
    }

    /** Pending GATT operations (the firmware sender keeps this low so control commands are not delayed). */
    val pending: Int get() = synchronized(queue) { queue.size }

    /** Shortest connection interval while a firmware image goes out (more speed, more battery). */
    fun setHighPriority(on: Boolean) {
        try {
            gatt?.requestConnectionPriority(if (on) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        } catch (e: SecurityException) {}
    }

    /** NMEA from the tracker on / off (the firmware sends it only while the phone is subscribed). */
    fun setNmea(on: Boolean) {
        nmeaWanted = on
        val ch = nmeaChar ?: return
        enqueue(listOf(Op.Subscribe(ch, on)))
    }

    private fun pump() {
        if (!ready) return
        val g = gatt ?: return
        val op: Op
        synchronized(queue) {
            if (busy || queue.isEmpty()) return
            op = queue.removeFirst()
            busy = true
        }
        val ok = try {
            when (op) {
                is Op.Write -> writeChar(g, op.ch, op.data, op.fast)
                is Op.Subscribe -> subscribe(g, op.ch, op.on)
                is Op.Run -> { op.block(); synchronized(queue) { busy = false }; pump(); return }
                is Op.Authenticate -> { startPairing(g, op.ch); return }
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

    private fun writeChar(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray, fast: Boolean = false): Boolean {
        // The firmware offers WRITE and WRITE_NR; with response is the safe choice (one chunk at a time, confirmed),
        // except for key events, which go without response to save a round trip.
        val noResponse = (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val withResponse = (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val type = if ((fast && noResponse) || !withResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                   else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, data, type) == 0 else legacyWrite(g, ch, data, type)
    }

    @Suppress("DEPRECATION")
    private fun legacyWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray, type: Int): Boolean {
        ch.writeType = type
        ch.value = data
        return g.writeCharacteristic(ch)
    }

    /** Notifications on / off: local flag plus the CCCD descriptor of the characteristic. */
    @Suppress("DEPRECATION")
    private fun subscribe(g: BluetoothGatt, ch: BluetoothGattCharacteristic, on: Boolean): Boolean {
        if (!g.setCharacteristicNotification(ch, on)) return false
        val cccd = ch.getDescriptor(TrackerUuids.CCCD)
        if (cccd == null) { handler.post { done() }; return true }        // nothing to write: carry on
        val value = if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, value) == 0
        } else {
            cccd.value = value
            g.writeDescriptor(cccd)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStatus("Connected, preparing...")
                // Android keeps the service list of a device it met before: after a firmware update the new services
                // (the control service) would not show up. Empty that cache before discovering (hidden API, best effort).
                refreshCache(g)
                if (!g.requestMtu(247)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clearLink()
                g.close()
                if (gatt === g) gatt = null
                onStatus(if (wanted) "Connection lost, retrying..." else "Not connected")
                retryLater()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { g.disconnect(); return }      // -> DISCONNECTED -> retry
            val kiss = g.getService(TrackerUuids.SERVICE)
            val notifyChar = kiss?.getCharacteristic(TrackerUuids.TO_PHONE)
            val write = kiss?.getCharacteristic(TrackerUuids.FROM_PHONE)
            if (notifyChar == null || write == null) {
                onStatus("This device has no KISS service (is Bluetooth in KISS mode?)")
                return
            }
            val control = g.getService(TrackerUuids.CONTROL)
            val evt = control?.getCharacteristic(TrackerUuids.CONTROL_EVT)
            val cmd = control?.getCharacteristic(TrackerUuids.CONTROL_CMD)
            val nmea = control?.getCharacteristic(TrackerUuids.CONTROL_NMEA)
            val firmware = control?.getCharacteristic(TrackerUuids.CONTROL_FIRMWARE)
            onLog(
                "BLE services: KISS yes, control " + when {
                    control == null -> "NO (the firmware has no ble-control)"
                    evt == null || cmd == null -> "incomplete"
                    else -> "yes (NMEA ${if (nmea != null) "yes" else "no"}, firmware update ${if (firmware != null) "yes" else "no"})"
                },
            )

            val ops = ArrayList<Op>()
            ops.add(Op.Subscribe(notifyChar, true))
            ops.add(Op.Run { kissChar = write; onStatus(if (evt != null && cmd != null) "Connected" else "Connected (KISS only: firmware without ble-control)") })
            if (evt != null && cmd != null) {
                // Since firmware 5.3.4 the control service needs an encrypted link paired with the tracker's PIN
                // (WebUI > Bluetooth > Control PIN). KISS stays open, so APRS works meanwhile.
                ops.add(Op.Authenticate(evt))
                ops.add(
                    Op.Run {
                        if (!controlAuthed) return@Run
                        synchronized(queue) {
                            queue.addFirst(
                                Op.Run {
                                    cmdChar = cmd; nmeaChar = nmea; firmwareChar = firmware
                                    // NMEA asked for before or during the discovery: subscribe now (it runs right after this step)
                                    if (nmea != null && nmeaWanted) synchronized(queue) { queue.addFirst(Op.Subscribe(nmea, true)) }
                                    onStatus("Connected (control paired)")
                                    onControlReady(true)
                                },
                            )
                            queue.addFirst(Op.Subscribe(evt, true))
                        }
                    },
                )
            }
            synchronized(queue) { queue.clear(); queue.addAll(ops); busy = false }
            ready = true
            pump()
        }

        // Android 13 and newer
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            onPairRead(g, ch, status)
        }

        // Android 12 and older
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) onPairRead(g, ch, status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            done()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            done()
        }

        // Android 13 and newer
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            dispatch(ch, value)
        }

        // Android 12 and older
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) ch.value?.let { dispatch(ch, it) }
        }
    }

    // ------------------------------------------------------------------ PIN pairing of the control service

    private fun startPairing(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        pairChar = ch
        pairStartMs = System.currentTimeMillis()
        bondRequested = false
        pairReads = 0
        controlAuthed = false
        if (g.device.bondState != BluetoothDevice.BOND_BONDED) onStatus("Connected (KISS). Pairing the control service: enter the tracker's PIN")
        pairRead(g)
    }

    private fun pairRead(g: BluetoothGatt) {
        val ch = pairChar ?: return pairDone(false, "no control characteristic")
        pairReads++
        val ok = try { g.readCharacteristic(ch) } catch (e: SecurityException) { false }
        if (!ok) handler.postDelayed({ waitBond(g) }, 1000)
    }

    /** The encrypted read came back: success = paired; otherwise Android is pairing (or it failed). */
    private fun onPairRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
        if (ch !== pairChar && ch.uuid != pairChar?.uuid) { done(); return }
        if (status == BluetoothGatt.GATT_SUCCESS) { pairDone(true, null); return }
        // 5 = insufficient authentication, 15 = insufficient encryption, 137 = auth failed: pairing needed or under way
        handler.postDelayed({ waitBond(g) }, 1000)
    }

    /** Waits for Android's pairing (the PIN dialog) and reads again when it is done; asks for it if nobody did. */
    private fun waitBond(g: BluetoothGatt) {
        if (gatt !== g || !wanted) return
        val elapsed = System.currentTimeMillis() - pairStartMs
        when (g.device.bondState) {
            BluetoothDevice.BOND_BONDED ->
                if (pairReads < 4) pairRead(g) else pairDone(false, "the tracker refuses the pairing: forget it in the Connect tab and pair again")
            BluetoothDevice.BOND_BONDING ->
                if (elapsed < 120_000) handler.postDelayed({ waitBond(g) }, 1000) else pairDone(false, "no PIN entered")
            else -> when {
                !bondRequested -> {
                    bondRequested = true
                    val asked = try { g.device.createBond() } catch (e: SecurityException) { false }
                    if (!asked) pairDone(false, "Android did not start the pairing") else handler.postDelayed({ waitBond(g) }, 1000)
                }
                elapsed < 8_000 -> handler.postDelayed({ waitBond(g) }, 1000)
                else -> pairDone(false, "pairing refused or wrong PIN (WebUI > Bluetooth > Control PIN; if you changed it, forget the tracker in the Connect tab)")
            }
        }
    }

    private fun pairDone(ok: Boolean, reason: String?) {
        controlAuthed = ok
        pairChar = null
        if (ok) onLog("BLE: control service paired")
        else {
            onLog("BLE: control not available: $reason")
            onStatus("Connected (KISS only). Control: $reason")
        }
        done()
    }

    private fun refreshCache(g: BluetoothGatt) {
        try {
            g.javaClass.getMethod("refresh").invoke(g)
        } catch (e: Exception) {
            onLog("BLE: could not refresh the service cache (${e.javaClass.simpleName})")
        }
    }

    private fun dispatch(ch: BluetoothGattCharacteristic, value: ByteArray) {
        when (ch.uuid) {
            TrackerUuids.TO_PHONE -> onBytes(value)
            TrackerUuids.CONTROL_EVT -> onControlBytes(value)
            TrackerUuids.CONTROL_NMEA -> onNmeaBytes(value)
        }
    }
}

/** Forgets Android's pairing with the tracker (needed after changing the tracker's Control PIN). Hidden API, best effort. */
@SuppressLint("MissingPermission")
fun forgetTrackerPairing(ctx: Context, address: String): Boolean {
    val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
    val device = try { adapter.getRemoteDevice(address) } catch (e: IllegalArgumentException) { return false }
    if (device.bondState == BluetoothDevice.BOND_NONE) return true
    return try { device.javaClass.getMethod("removeBond").invoke(device) as? Boolean ?: false } catch (e: Exception) { false }
}
