/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context

/** User settings, kept in SharedPreferences. */
class Settings(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("aprslorap", Context.MODE_PRIVATE)

    var callsign: String
        get() = p.getString("call", "")!!.trim().uppercase()
        set(v) { p.edit().putString("call", v.trim().uppercase()).apply() }

    /** Callsign of the tracker, as set in its WebUI. If it equals [callsign], the tracker sends the ACKs, not the app. */
    var trackerCallsign: String
        get() = p.getString("tracker_call", "")!!.trim().uppercase()
        set(v) { p.edit().putString("tracker_call", v.trim().uppercase()).apply() }

    /** Digipeater path of the frames the app sends, e.g. "WIDE1-1,WIDE2-1". */
    var path: String
        get() = p.getString("path", "WIDE1-1,WIDE2-1")!!
        set(v) { p.edit().putString("path", v.trim()).apply() }

    var deviceAddress: String
        get() = p.getString("device", "")!!
        set(v) { p.edit().putString("device", v).apply() }

    /** Name the tracker advertised when it was chosen (only for display). */
    var deviceName: String
        get() = p.getString("device_name", "")!!
        set(v) { p.edit().putString("device_name", v).apply() }

    var igateEnabled: Boolean
        get() = p.getBoolean("igate", false)
        set(v) { p.edit().putBoolean("igate", v).apply() }

    var isHost: String
        get() = p.getString("is_host", "rotate.aprs2.net")!!
        set(v) { p.edit().putString("is_host", v.trim()).apply() }

    var isPort: Int
        get() = p.getInt("is_port", 14580)
        set(v) { p.edit().putInt("is_port", v).apply() }

    // DAPNET account (to send pages through the network API; needs internet)
    var dapnetUser: String
        get() = p.getString("dapnet_user", "")!!
        set(v) { p.edit().putString("dapnet_user", v.trim().lowercase()).apply() }

    var dapnetPassword: String
        get() = p.getString("dapnet_pass", "")!!
        set(v) { p.edit().putString("dapnet_pass", v).apply() }

    var dapnetGroup: String
        get() = p.getString("dapnet_group", es.ea7jrs.aprslorap.core.Dapnet.DEFAULT_GROUP)!!
        set(v) { p.edit().putString("dapnet_group", v.trim().lowercase()).apply() }

    var dapnetUrl: String
        get() = p.getString("dapnet_url", es.ea7jrs.aprslorap.core.Dapnet.DEFAULT_URL)!!
        set(v) { p.edit().putString("dapnet_url", v.trim()).apply() }

    /** NMEA 0183 TCP server on the phone for other apps (OpenCPN, Navionics...). */
    var nmeaServer: Boolean
        get() = p.getBoolean("nmea_server", false)
        set(v) { p.edit().putBoolean("nmea_server", v).apply() }

    var nmeaPort: Int
        get() = p.getInt("nmea_port", 10110)
        set(v) { p.edit().putInt("nmea_port", v).apply() }

    /** Tracker GNSS as the phone location (the app must be the mock location app in the developer options). */
    var mockLocation: Boolean
        get() = p.getBoolean("mock_location", false)
        set(v) { p.edit().putBoolean("mock_location", v).apply() }

    // Telegraph key on the phone (MORSE mode of the tracker)
    /** "keyboard" (USB / Bluetooth HID: VBand, Vail), "midi" (Vail in MIDI mode), "serial" (USB serial adapter). */
    var cwInput: String
        get() = p.getString("cw_input", "keyboard")!!
        set(v) { p.edit().putString("cw_input", v).apply() }

    var cwKeyer: es.ea7jrs.aprslorap.core.KeyerMode
        get() = runCatching { es.ea7jrs.aprslorap.core.KeyerMode.valueOf(p.getString("cw_keyer", "IAMBIC_B")!!) }.getOrDefault(es.ea7jrs.aprslorap.core.KeyerMode.IAMBIC_B)
        set(v) { p.edit().putString("cw_keyer", v.name).apply() }

    var cwWpm: Int
        get() = p.getInt("cw_wpm", 18)
        set(v) { p.edit().putInt("cw_wpm", v.coerceIn(5, 60)).apply() }

    /** true: real-time keying (your own timing, delay [cwDelayMs]); false: the phone decodes and the tracker sends text. */
    var cwRealtime: Boolean
        get() = p.getBoolean("cw_realtime", true)
        set(v) { p.edit().putBoolean("cw_realtime", v).apply() }

    var cwDelayMs: Int
        get() = p.getInt("cw_delay", 150)
        set(v) { p.edit().putInt("cw_delay", v.coerceIn(0, 1000)).apply() }

    var cwSidetone: Boolean
        get() = p.getBoolean("cw_sidetone", true)
        set(v) { p.edit().putBoolean("cw_sidetone", v).apply() }

    var cwToneHz: Int
        get() = p.getInt("cw_tone", 700)
        set(v) { p.edit().putInt("cw_tone", v.coerceIn(300, 1200)).apply() }

    var cwDitLine: es.ea7jrs.aprslorap.core.SerialLine
        get() = runCatching { es.ea7jrs.aprslorap.core.SerialLine.valueOf(p.getString("cw_dit_line", "DSR")!!) }.getOrDefault(es.ea7jrs.aprslorap.core.SerialLine.DSR)
        set(v) { p.edit().putString("cw_dit_line", v.name).apply() }

    var cwDahLine: es.ea7jrs.aprslorap.core.SerialLine
        get() = runCatching { es.ea7jrs.aprslorap.core.SerialLine.valueOf(p.getString("cw_dah_line", "CTS")!!) }.getOrDefault(es.ea7jrs.aprslorap.core.SerialLine.CTS)
        set(v) { p.edit().putString("cw_dah_line", v.name).apply() }

    /** Where the app downloads the satellite file (sats.txt) it sends to the tracker. */
    var satDataUrl: String
        get() = p.getString("sat_url", es.ea7jrs.aprslorap.core.Sat.DEFAULT_DATA_URL)!!.ifBlank { es.ea7jrs.aprslorap.core.Sat.DEFAULT_DATA_URL }
        set(v) { p.edit().putString("sat_url", v.trim()).apply() }

    // Callsign validated in RadioID.net (DMR ID / CCS7); see Licence
    val validatedCall: String get() = p.getString("valid_call", "")!!
    val validatedIds: String get() = p.getString("valid_ids", "")!!
    val validatedName: String get() = p.getString("valid_name", "")!!
    val validatedCountry: String get() = p.getString("valid_country", "")!!
    val validatedAtMs: Long get() = p.getLong("valid_at", 0L)

    fun saveValidation(call: String, ids: String, name: String, country: String) {
        p.edit().putString("valid_call", call).putString("valid_ids", ids).putString("valid_name", name)
            .putString("valid_country", country).putLong("valid_at", System.currentTimeMillis()).apply()
    }

    fun clearValidation() {
        p.edit().remove("valid_call").remove("valid_ids").remove("valid_name").remove("valid_country").remove("valid_at").apply()
    }

    val pathList: List<String> get() = path.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
