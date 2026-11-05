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
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import es.ea7jrs.aprslorap.core.MidiKeyParser
import es.ea7jrs.aprslorap.core.SerialLine
import java.util.concurrent.locks.LockSupport

/**
 * MIDI adapters (Vail in MIDI mode, USB or Bluetooth MIDI): note 0 = element timed by the adapter, notes 1 / 2 = paddles.
 * On opening, the adapter is asked for MIDI mode (CC0 = 0, not saved by the Vail; its keyer type is left alone).
 */
class MidiKeyInput(ctx: Context) : CwInput {
    private val manager = ctx.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val handler = Handler(Looper.getMainLooper())
    private val devices = ArrayList<MidiDevice>()
    private val ports = ArrayList<MidiOutputPort>()

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = open(device)
    }

    @Suppress("DEPRECATION")
    override fun start() {
        val m = manager ?: run { AppState.cwInputStatus.value = "This phone has no MIDI support"; return }
        val list = m.devices.filter { it.outputPortCount > 0 }
        AppState.cwInputStatus.value = if (list.isEmpty()) "MIDI: plug in the adapter" else "MIDI: opening..."
        list.forEach { open(it) }
        m.registerDeviceCallback(callback, handler)
    }

    private fun open(info: MidiDeviceInfo) {
        if (info.outputPortCount == 0) return
        manager?.openDevice(info, { device ->
            if (device == null) { AppState.cwInputStatus.value = "MIDI: could not open the device"; return@openDevice }
            synchronized(devices) { devices.add(device) }
            if (info.inputPortCount > 0) {
                device.openInputPort(0)?.let { inPort ->                       // MIDI mode please (Vail: CC0 below 64)
                    try { inPort.send(byteArrayOf(0xB0.toByte(), 0x00, 0x00), 0, 3) } catch (_: Exception) {}
                    inPort.close()
                }
            }
            val port = device.openOutputPort(0) ?: return@openDevice
            synchronized(ports) { ports.add(port) }
            val parser = MidiKeyParser()
            port.connect(object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    for ((line, down) in parser.feed(msg, offset, count)) CwEngine.setLine(line, down)
                }
            })
            val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "device"
            AppState.cwInputStatus.value = "MIDI: $name"
        }, handler)
    }

    override fun stop() {
        manager?.unregisterDeviceCallback(callback)
        synchronized(ports) { ports.forEach { try { it.close() } catch (_: Exception) {} }; ports.clear() }
        synchronized(devices) { devices.forEach { try { it.close() } catch (_: Exception) {} }; devices.clear() }
    }
}

/**
 * USB serial adapters (FTDI, CP210x, CH34x, PL2303, CDC-ACM): the key closes an output line to an input line, e.g.
 * DTR -> DSR for the dit (or a straight key) and RTS -> CTS for the dah. DTR and RTS are set high; the input lines are
 * read every millisecond.
 */
class SerialKeyInput(private val ctx: Context, private val ditLine: SerialLine, private val dahLine: SerialLine) : CwInput {
    companion object { const val ACTION_PERMISSION = "es.ea7jrs.aprslorap.USB_PERMISSION" }

    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile private var running = false
    private var port: UsbSerialPort? = null
    private var thread: Thread? = null
    private var receiverOn = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == ACTION_PERMISSION) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) openPort()
                else AppState.cwInputStatus.value = "Serial: USB permission refused"
            }
        }
    }

    override fun start() {
        running = true
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull()
        if (driver == null) { AppState.cwInputStatus.value = "Serial: plug in the adapter and start again"; return }
        if (usb.hasPermission(driver.device)) { openPort(); return }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverOn = true
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,       // the system adds the result extras
        )
        usb.requestPermission(driver.device, pi)
        AppState.cwInputStatus.value = "Serial: allow the USB device"
    }

    private fun openPort() {
        if (!running) return
        try {
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull() ?: throw IllegalStateException("adapter unplugged")
            val connection = usb.openDevice(driver.device) ?: throw IllegalStateException("cannot open the USB device")
            val p = driver.ports[0]
            p.open(connection)
            try { p.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) } catch (_: Exception) {}
            p.setDTR(true)                                             // supply for the key contacts
            p.setRTS(true)
            port = p
            AppState.cwInputStatus.value = "Serial: ${driver.javaClass.simpleName.removeSuffix("SerialDriver")} (dit $ditLine, dah $dahLine)"
            thread = Thread({ poll(p) }, "cw-serial").also { it.priority = Thread.MAX_PRIORITY; it.start() }
        } catch (e: Exception) {
            AppState.cwInputStatus.value = "Serial: ${e.message}"
        }
    }

    private fun lineOn(lines: Set<UsbSerialPort.ControlLine>, line: SerialLine): Boolean = when (line) {
        SerialLine.DSR -> UsbSerialPort.ControlLine.DSR in lines
        SerialLine.CTS -> UsbSerialPort.ControlLine.CTS in lines
        SerialLine.CD -> UsbSerialPort.ControlLine.CD in lines
        SerialLine.RI -> UsbSerialPort.ControlLine.RI in lines
    }

    private fun poll(p: UsbSerialPort) {
        try {
            while (running) {
                val lines = p.controlLines
                CwEngine.setLine(es.ea7jrs.aprslorap.core.KeyLine.DIT, lineOn(lines, ditLine))
                CwEngine.setLine(es.ea7jrs.aprslorap.core.KeyLine.DAH, lineOn(lines, dahLine))
                LockSupport.parkNanos(1_000_000)
            }
        } catch (e: Exception) {
            AppState.cwInputStatus.value = "Serial: ${e.message}"
        } finally {
            CwEngine.setLine(es.ea7jrs.aprslorap.core.KeyLine.DIT, false)
            CwEngine.setLine(es.ea7jrs.aprslorap.core.KeyLine.DAH, false)
        }
    }

    override fun stop() {
        running = false
        thread?.join(200)
        thread = null
        try { port?.close() } catch (_: Exception) {}
        port = null
        if (receiverOn) { try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}; receiverOn = false }
    }
}
