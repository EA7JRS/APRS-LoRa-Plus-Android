/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat

/** Foreground service: keeps the link with the tracker, the messaging and the iGate alive with the screen off. */
class TrackerService : Service() {
    companion object {
        const val ACTION_STOP = "es.ea7jrs.aprslorap.STOP"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, TrackerService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TrackerService::class.java).setAction(ACTION_STOP))
        }
    }

    private var ble: TrackerBle? = null
    private var controller: Controller? = null
    private var isClient: IsClient? = null
    private var nmeaServer: NmeaServer? = null
    private var mock: MockLocation? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (controller != null) return START_STICKY        // already running
        Spotter.init(this)
        AlertCenter.init(this)
        NavtexHub.init(this)
        DscHub.init(this)
        Beacon406Hub.init(this)
        stopping = false
        val settings = Settings(this)
        val notifier = Notifier(this)
        AppState.notifier = notifier
        try {
            ServiceCompat.startForeground(
                this, Notifier.ID_SERVICE, notifier.foreground("Starting..."),
                if (android.os.Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            // Android 14+: a connectedDevice service needs the Bluetooth permission already granted (e.g. it was revoked
            // and the system restarted this sticky service). Android 12+: it may not start from the background.
            AppState.error.value = "Could not start the link: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        if (settings.callsign.isEmpty() || settings.deviceAddress.isEmpty()) {
            AppState.error.value = "Set your callsign and choose a tracker first"
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        val bleLink = TrackerBle(
            this,
            onBytes = { controller?.onBleBytes(it) },
            onStatus = { AppState.bleStatus.value = it; if (!stopping) notifier.updateForeground("Tracker: $it") },
            onControlBytes = { controller?.onControlBytes(it) },
            onNmeaBytes = { controller?.onNmeaBytes(it) },
            onControlReady = { controller?.onControlReady(it) },
            onLog = { AppState.addLog(it) },
        )
        ble = bleLink
        AppState.ble = bleLink
        Licence.recheckIfOld(settings)
        if (!Licence.isValid(settings)) AppState.notice.value = "Callsign not validated in RadioID: listening only"
        if (settings.igateEnabled && Licence.isValid(settings)) {
            isClient = IsClient(
                settings.isHost, settings.isPort,
                loginLine = { controller?.loginLine ?: "" },
                onStatus = { AppState.igateStatus.value = it },
            )
        } else {
            AppState.igateStatus.value = "Off"
        }
        if (settings.nmeaServer) nmeaServer = NmeaServer(settings.nmeaPort) { AppState.nmeaStatus.value = it }.also { it.start() }
        else AppState.nmeaStatus.value = "Off"
        AppState.nmeaOut = nmeaServer
        if (settings.mockLocation) mock = MockLocation(this) { AppState.mockStatus.value = it }.takeIf { it.start() }
        else AppState.mockStatus.value = "Off"
        AppState.appContext = applicationContext
        val c = Controller(
            settings, notifier,
            transmitBytes = { bleLink.write(it) },
            isSend = { isClient?.send(it) },
            sendControlLine = { bleLink.writeControl(it) },
            sendControlFast = { bleLink.writeControl(it, fast = true) },
            setTrackerNmea = { bleLink.setNmea(it) },
            nmeaServer = nmeaServer,
            mock = mock,
        )
        controller = c
        AppState.controller = c
        isClient?.start()
        RotctlLink.apply(settings)
        bleLink.connect(settings.deviceAddress)
        ExtraReceivers.start(this, settings)
        AppState.running.value = true
        return START_STICKY
    }

    private fun shutdown() {
        stopping = true
        FirmwareUpdater.cancel()
        ble?.disconnect(); ble = null
        AppState.ble = null
        isClient?.stop(); isClient = null
        CwEngine.stop()
        controller?.stop(); controller = null
        nmeaServer?.stop(); nmeaServer = null; AppState.nmeaOut = null
        mock?.stop(); mock = null
        RotctlLink.stopAll()
        ExtraReceivers.stop()
        PhoneSensors.stopAtt()
        AppState.controller = null
        AppState.controlReady.value = false
        AppState.running.value = false
        AppState.igateStatus.value = "Off"
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
