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
    /** u-blox AssistNow Predictive Orbits address, with the chipcode / token of the user's Thingstream account (empty: not used). */
    var agpsUrl: String
        get() = p.getString("agps_url", "")!!
        set(v) { p.edit().putString("agps_url", v.trim()).apply() }

    // Phone as the inclinometer of the rotor mount: tilt that counts as level
    var attZeroPitch: Double
        get() = p.getFloat("att_zero_p", 0f).toDouble()
        set(v) { p.edit().putFloat("att_zero_p", v.toFloat()).apply() }
    var attZeroRoll: Double
        get() = p.getFloat("att_zero_r", 0f).toDouble()
        set(v) { p.edit().putFloat("att_zero_r", v.toFloat()).apply() }

    // rotctld: client (to a rotor on the network) and server (for Look4Sat, GPredict...)
    var rotClient: Boolean
        get() = p.getBoolean("rot_client", false)
        set(v) { p.edit().putBoolean("rot_client", v).apply() }
    var rotClientHost: String
        get() = p.getString("rot_client_host", "")!!
        set(v) { p.edit().putString("rot_client_host", v.trim()).apply() }
    var rotClientPort: Int
        get() = p.getInt("rot_client_port", es.ea7jrs.aprslorap.core.RotCtl.DEFAULT_PORT)
        set(v) { p.edit().putInt("rot_client_port", v.coerceIn(1, 65535)).apply() }
    var rotClientStep: Double
        get() = p.getFloat("rot_client_step", 1f).toDouble()
        set(v) { p.edit().putFloat("rot_client_step", v.coerceIn(0.1, 20.0).toFloat()).apply() }
    // APRS LoRa+ Rotor box (Rotor tab): link, PIN, following the tracker's pointing
    var rotorTransport: Int                          // 0 BLE, 1 TCP (WiFi)
        get() = p.getInt("rotor_transport", 0)
        set(v) { p.edit().putInt("rotor_transport", v).apply() }
    var rotorAddress: String
        get() = p.getString("rotor_address", "")!!
        set(v) { p.edit().putString("rotor_address", v).apply() }
    var rotorName: String
        get() = p.getString("rotor_name", "")!!
        set(v) { p.edit().putString("rotor_name", v).apply() }
    var rotorHost: String
        get() = p.getString("rotor_host", "")!!
        set(v) { p.edit().putString("rotor_host", v.trim()).apply() }
    var rotorPort: Int
        get() = p.getInt("rotor_port", es.ea7jrs.aprslorap.core.RotorProto.DEFAULT_TCP_PORT)
        set(v) { p.edit().putInt("rotor_port", v.coerceIn(1, 65535)).apply() }
    var rotorPin: String
        get() = p.getString("rotor_pin", "123456")!!
        set(v) { p.edit().putString("rotor_pin", v.trim()).apply() }
    var rotorFollow: Boolean
        get() = p.getBoolean("rotor_follow", false)
        set(v) { p.edit().putBoolean("rotor_follow", v).apply() }
    /** What the rotor box follows: 0 the tracker's pointing, 1 an APRS station (tracker's heard list), 2 a CW beacon with locator. */
    var rotorFollowMode: Int
        get() = p.getInt("rotor_follow_mode", 0)
        set(v) { p.edit().putInt("rotor_follow_mode", v.coerceIn(0, 2)).apply() }
    var rotorFollowCall: String                      // empty = the last station heard that passes the filter
        get() = p.getString("rotor_follow_call", "")!!
        set(v) { p.edit().putString("rotor_follow_call", v.trim().uppercase()).apply() }
    var rotorFollowFilter: String
        get() = p.getString("rotor_follow_filter", "")!!
        set(v) { p.edit().putString("rotor_follow_filter", v.trim().uppercase()).apply() }
    var rotorFollowMaxMin: Int
        get() = p.getInt("rotor_follow_max_min", 30)
        set(v) { p.edit().putInt("rotor_follow_max_min", v.coerceIn(1, 720)).apply() }
    var rotorPredict: Boolean
        get() = p.getBoolean("rotor_predict", true)
        set(v) { p.edit().putBoolean("rotor_predict", v).apply() }
    var rotorPredictMaxS: Int
        get() = p.getInt("rotor_predict_max_s", 300)
        set(v) { p.edit().putInt("rotor_predict_max_s", v.coerceIn(10, 1800)).apply() }
    /** CAT on the rotor box: send the tracker's satellite Doppler (event `sat`) with `cattrack`. */
    var rotorCatTrack: Boolean
        get() = p.getBoolean("rotor_cat_track", false)
        set(v) { p.edit().putBoolean("rotor_cat_track", v).apply() }
    var rotorCatTx: Int                              // transmitter index of the satellite, -1 = the SAT receiver's or the first with downlink
        get() = p.getInt("rotor_cat_tx", -1)
        set(v) { p.edit().putInt("rotor_cat_tx", v).apply() }
    var rotorCatUplink: Boolean                      // also the uplink (split on the radio)
        get() = p.getBoolean("rotor_cat_uplink", false)
        set(v) { p.edit().putBoolean("rotor_cat_uplink", v).apply() }
    var rotorCatTone: Double                         // CTCSS for the uplink while following a satellite; 0 = from the transmitter's description
        get() = p.getFloat("rotor_cat_tone", 0f).toDouble()
        set(v) { p.edit().putFloat("rotor_cat_tone", v.coerceIn(0.0, 260.0).toFloat()).apply() }
    var rotorCatStep: Long
        get() = p.getLong("rotor_cat_step", 1000L)
        set(v) { p.edit().putLong("rotor_cat_step", v).apply() }
    var rotorStep: Double
        get() = p.getFloat("rotor_step", 2f).toDouble()
        set(v) { p.edit().putFloat("rotor_step", v.coerceIn(0.5, 20.0).toFloat()).apply() }

    var rotServer: Boolean
        get() = p.getBoolean("rot_server", false)
        set(v) { p.edit().putBoolean("rot_server", v).apply() }
    var rotServerPort: Int
        get() = p.getInt("rot_server_port", es.ea7jrs.aprslorap.core.RotCtl.DEFAULT_PORT)
        set(v) { p.edit().putInt("rot_server_port", v.coerceIn(1024, 65535)).apply() }

    // RepeaterBook: the user's own token and User-Agent (with contact e-mail), as RepeaterBook asks
    var rbToken: String
        get() = p.getString("rb_token", "")!!
        set(v) { p.edit().putString("rb_token", v.trim()).apply() }
    var rbUserAgent: String
        get() = p.getString("rb_agent", "APRS-LoRa-Plus-Android/1.0 (personal use; contact: )")!!
        set(v) { p.edit().putString("rb_agent", v.trim()).apply() }
    var rbNorthAmerica: Boolean
        get() = p.getBoolean("rb_na", false)
        set(v) { p.edit().putBoolean("rb_na", v).apply() }

    // SDR program or server under control
    var sdrKind: String
        get() = p.getString("sdr_kind", es.ea7jrs.aprslorap.core.Sdr.Kind.RIGCTL.name)!!
        set(v) { p.edit().putString("sdr_kind", v).apply() }
    var sdrHost: String
        get() = p.getString("sdr_host", "127.0.0.1")!!
        set(v) { p.edit().putString("sdr_host", v.trim()).apply() }
    var sdrPort: Int
        get() = p.getInt("sdr_port", es.ea7jrs.aprslorap.core.Sdr.Kind.RIGCTL.defaultPort)
        set(v) { p.edit().putInt("sdr_port", v.coerceIn(1, 65535)).apply() }
    var sdrFollow: String
        get() = p.getString("sdr_follow", "MANUAL")!!
        set(v) { p.edit().putString("sdr_follow", v).apply() }
    var sdrStepHz: Int
        get() = p.getInt("sdr_step", 100)
        set(v) { p.edit().putInt("sdr_step", v.coerceIn(1, 100_000)).apply() }
    var sdrOffsetHz: Int
        get() = p.getInt("sdr_offset", 0)
        set(v) { p.edit().putInt("sdr_offset", v).apply() }
    var sdrTx: Int
        get() = p.getInt("sdr_tx", -1)
        set(v) { p.edit().putInt("sdr_tx", v).apply() }
    // RTL-SDR receiver in the app
    var sdrPpm: Int
        get() = p.getInt("sdr_ppm", 0)
        set(v) { p.edit().putInt("sdr_ppm", v.coerceIn(-200, 200)).apply() }
    var sdrBiasTee: Boolean
        get() = p.getBoolean("sdr_biast", false)
        set(v) { p.edit().putBoolean("sdr_biast", v).apply() }
    var sdrGainPercent: Int                                   // -1 = automatic
        get() = p.getInt("sdr_gain", -1)
        set(v) { p.edit().putInt("sdr_gain", v.coerceIn(-1, 100)).apply() }
    var sdrSquelchDb: Int
        get() = p.getInt("sdr_sql", -200)
        set(v) { p.edit().putInt("sdr_sql", v).apply() }
    var sdrMode: String
        get() = p.getString("sdr_mode", "NFM")!!
        set(v) { p.edit().putString("sdr_mode", v).apply() }
    var sdrBandwidth: Int
        get() = p.getInt("sdr_bw", 0)
        set(v) { p.edit().putInt("sdr_bw", v.coerceAtLeast(0)).apply() }
    var sdrLastHz: Long
        get() = p.getLong("sdr_last_hz", 145_800_000L)
        set(v) { p.edit().putLong("sdr_last_hz", v).apply() }

    var dvMode: String
        get() = p.getString("dv_mode", "Auto")!!
        set(v) { p.edit().putString("dv_mode", v).apply() }

    var sdrRecordPasses: Boolean
        get() = p.getBoolean("sdr_rec", false)
        set(v) { p.edit().putBoolean("sdr_rec", v).apply() }

    /** More trackers as receivers (see core Receivers). */
    var extraReceivers: List<es.ea7jrs.aprslorap.core.Receivers.Entry>
        get() = es.ea7jrs.aprslorap.core.Receivers.decode(p.getString("extra_rx", "")!!)
        set(v) { p.edit().putString("extra_rx", es.ea7jrs.aprslorap.core.Receivers.encode(v)).apply() }

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

    // Spotting (Modes > Spotting): what the app decodes goes to PSK Reporter, WSPRnet, a WSJT-X UDP listener, a skimmer port
    var spotPsk: Boolean
        get() = p.getBoolean("spot_psk", false)
        set(v) { p.edit().putBoolean("spot_psk", v).apply() }

    var spotWsprnet: Boolean
        get() = p.getBoolean("spot_wsprnet", false)
        set(v) { p.edit().putBoolean("spot_wsprnet", v).apply() }

    var spotUdp: Boolean
        get() = p.getBoolean("spot_udp", false)
        set(v) { p.edit().putBoolean("spot_udp", v).apply() }

    /** Computer that listens to the WSJT-X UDP messages (GridTracker, JTAlert, RBN Aggregator...). */
    var spotUdpHost: String
        get() = p.getString("spot_udp_host", "")!!
        set(v) { p.edit().putString("spot_udp_host", v.trim()).apply() }

    var spotUdpPort: Int
        get() = p.getInt("spot_udp_port", es.ea7jrs.aprslorap.core.Spots.WsjtxUdp.PORT)
        set(v) { p.edit().putInt("spot_udp_port", v).apply() }

    var spotTelnet: Boolean
        get() = p.getBoolean("spot_telnet", false)
        set(v) { p.edit().putBoolean("spot_telnet", v).apply() }

    /** Port of the CW skimmer style telnet server (CW Skimmer uses 7300). */
    var spotTelnetPort: Int
        get() = p.getInt("spot_telnet_port", 7300)
        set(v) { p.edit().putInt("spot_telnet_port", v).apply() }

    /** Our locator set by hand; empty = from the tracker's GNSS or the phone. */
    var spotLocator: String
        get() = p.getString("spot_locator", "")!!
        set(v) { p.edit().putString("spot_locator", v.trim()).apply() }

    var spotAntenna: String
        get() = p.getString("spot_antenna", "")!!
        set(v) { p.edit().putString("spot_antenna", v.trim()).apply() }

    /** Dial frequency (Hz, USB) of the radio whose audio goes into the microphone; 0 = unknown (no spots). */
    var spotDialHz: Long
        get() = p.getLong("spot_dial_hz", 0L)
        set(v) { p.edit().putLong("spot_dial_hz", v).apply() }

    // Log (Log tab): QSL services
    /** QRZ.com logbook API key (QRZ: logbook settings; needs the XML subscription). */
    var qrzKey: String
        get() = p.getString("qrz_key", "")!!
        set(v) { p.edit().putString("qrz_key", v.trim()).apply() }

    /** LoTW web account (to read the confirmations). */
    var lotwUser: String
        get() = p.getString("lotw_user", "")!!
        set(v) { p.edit().putString("lotw_user", v.trim()).apply() }

    var lotwPassword: String
        get() = p.getString("lotw_pass", "")!!
        set(v) { p.edit().putString("lotw_pass", v).apply() }

    /** Password of the TrustedQSL certificate file (.p12), often empty. */
    var lotwP12Password: String
        get() = p.getString("lotw_p12_pass", "")!!
        set(v) { p.edit().putString("lotw_p12_pass", v).apply() }

    /** Station location for LoTW: CQ zone, ITU zone, IOTA (the DXCC comes from the certificate). */
    var lotwCqz: String
        get() = p.getString("lotw_cqz", "")!!
        set(v) { p.edit().putString("lotw_cqz", v.trim()).apply() }

    var lotwItuz: String
        get() = p.getString("lotw_ituz", "")!!
        set(v) { p.edit().putString("lotw_ituz", v.trim()).apply() }

    var lotwIota: String
        get() = p.getString("lotw_iota", "")!!
        set(v) { p.edit().putString("lotw_iota", v.trim()).apply() }

    /** eQSL.cc account and QTH nickname (empty = the default one). */
    var eqslUser: String
        get() = p.getString("eqsl_user", "")!!
        set(v) { p.edit().putString("eqsl_user", v.trim()).apply() }

    var eqslPassword: String
        get() = p.getString("eqsl_pass", "")!!
        set(v) { p.edit().putString("eqsl_pass", v).apply() }

    var eqslNick: String
        get() = p.getString("eqsl_nick", "")!!
        set(v) { p.edit().putString("eqsl_nick", v.trim()).apply() }

    /** Computer with the World Radio League app (WRL Integrations), which listens to WSJT-X UDP. */
    var wrlHost: String
        get() = p.getString("wrl_host", "")!!
        set(v) { p.edit().putString("wrl_host", v.trim()).apply() }

    /** Last confirmation dates asked for (yyyy-MM-dd / yyyyMMdd). */
    var lotwSince: String
        get() = p.getString("lotw_since", "")!!
        set(v) { p.edit().putString("lotw_since", v.trim()).apply() }

    var eqslSince: String
        get() = p.getString("eqsl_since", "")!!
        set(v) { p.edit().putString("eqsl_since", v.trim()).apply() }

    var qrzSince: String
        get() = p.getString("qrz_since", "")!!
        set(v) { p.edit().putString("qrz_since", v.trim()).apply() }

    var wrlUdp: Boolean
        get() = p.getBoolean("wrl_udp", false)
        set(v) { p.edit().putBoolean("wrl_udp", v).apply() }

    var wrlPort: Int
        get() = p.getInt("wrl_port", 2237)
        set(v) { p.edit().putInt("wrl_port", v).apply() }

    /** Power for the log (W), optional. */
    var logPower: String
        get() = p.getString("log_power", "")!!
        set(v) { p.edit().putString("log_power", v.trim()).apply() }
}
