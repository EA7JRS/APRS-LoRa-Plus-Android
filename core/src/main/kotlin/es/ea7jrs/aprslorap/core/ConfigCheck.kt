/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale

/**
 * Check of the tracker's configuration before it is stored: the same rules as the WebUI of the tracker
 * (data_embed/script.js, cfgRules; the idea of wx3cfg for the WX3in1), on the JSON the Config tab edits.
 * Errors should stop the store; warnings and notes do not. A safe fix only changes the field it names.
 */
object ConfigCheck {
    enum class Severity { ERROR, WARN, INFO }

    /** [value] to put at [path] of the configuration; [random]: "password" or "pin" = a new one made by [randomValue]. */
    data class Fix(val label: String, val path: List<Any>, val value: Any? = null, val random: String? = null)
    data class Finding(val id: String, val severity: Severity, val where: String, val title: String, val why: String, val fix: Fix? = null)
    data class Result(val findings: List<Finding>, val passed: List<String>) {
        val errors: Int get() = findings.count { it.severity == Severity.ERROR }
        val warnings: Int get() = findings.count { it.severity == Severity.WARN }
    }

    private val CALL = Regex("^(?=[A-Z0-9]*[0-9])(?=[A-Z0-9]*[A-Z])[A-Z0-9]{3,6}(-([0-9]|1[0-5]))?$")
    private val ALIAS = Regex("^[A-Z0-9]{1,6}(-([0-9]|1[0-5]))?$")
    private val WIDE = Regex("^WIDE([1-7])-([0-7])$")
    private const val STD_FREQ = 433_775_000L

    /** Amateur limits (MHz) per IARU region and the Region 1 digital channels, as in the WebUI. National rules prevail. */
    private val BANDS = mapOf(1 to listOf(430.0 to 440.0), 2 to listOf(420.0 to 450.0, 902.0 to 928.0), 3 to listOf(430.0 to 440.0))
    val R1_CHANNELS = listOf(433.625, 433.65, 433.675, 433.7, 433.725, 433.75, 433.775, 434.45, 434.475, 434.5, 434.525, 434.55, 434.575,
        434.855, 438.025, 438.05, 438.075, 438.1, 438.125, 438.15, 438.175, 439.9125)

    fun inBand(region: Int, hz: Long): Boolean = (BANDS[region] ?: BANDS[1]!!).any { hz / 1e6 >= it.first && hz / 1e6 <= it.second }

    /** Hops a path asks for (WIDEn-N asks for N; anything else counts one) and the largest n of its WIDEn-N. */
    fun pathHops(path: List<String>): Pair<Int, Int> {
        var hops = 0; var worst = 0
        for (t in path) {
            val m = WIDE.find(t)
            if (m != null) { hops += m.groupValues[2].toInt(); worst = maxOf(worst, m.groupValues[1].toInt()) } else hops += 1
        }
        return hops to worst
    }

    private fun Map<String, Any?>.flag(key: String): Boolean = when (val v = this[key]) { is Boolean -> v; is Number -> v.toInt() != 0; is String -> v == "1" || v == "true"; else -> false }
    private fun Map<String, Any?>.text(key: String): String = when (val v = this[key]) { null -> ""; is Double -> if (v == Math.rint(v)) v.toLong().toString() else v.toString(); else -> v.toString() }

    fun check(root: Map<String, Any?>): Result {
        val out = ArrayList<Finding>(); val passed = ArrayList<String>()
        fun pass(name: String, before: Int) { if (out.size == before) passed.add(name) }

        // callsigns of the beacon profiles
        var n0 = out.size
        val beacons = root.objList("beacons")
        val nocall = beacons.withIndex().filter { it.value.text("callsign").trim().uppercase().startsWith("NOCALL") }.map { it.index + 1 }
        if (nocall.isNotEmpty()) out += Finding("nocall", Severity.ERROR, "beacons · " + nocall.joinToString(", "),
            if (nocall.size > 1) "${nocall.size} beacon profiles still use NOCALL" else "Callsign is still NOCALL",
            "The tracker does not transmit with a NOCALL callsign. Enter your callsign and SSID, for example EA7XXX-7.")
        for ((i, b) in beacons.withIndex()) {
            val call = b.text("callsign").trim().uppercase()
            if (call.startsWith("NOCALL") || !b.containsKey("callsign")) continue
            if (!CALL.matches(call)) out += Finding("call$i", Severity.ERROR, "beacons · ${i + 1}", "\"$call\" is not a valid callsign",
                "3 to 6 letters and digits (at least one of each) and an optional SSID from -0 to -15.")
        }
        if (beacons.isNotEmpty()) pass("Callsigns of the beacon profiles", n0)

        // path
        root.obj("other")?.takeIf { it.containsKey("path") }?.let { other ->
            n0 = out.size
            val path = other.text("path").uppercase().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val bad = path.firstOrNull { t -> !ALIAS.matches(t) || (WIDE.find(t)?.let { it.groupValues[2].toInt() > it.groupValues[1].toInt() } ?: false) }
            if (bad != null) out += Finding("path", Severity.ERROR, "other · path", "\"$bad\" is not a valid path element",
                "Use aliases such as WIDE1-1 or WIDE2-1 separated by commas. In WIDEn-N, N cannot be greater than n.")
            else {
                val (hops, worst) = pathHops(path)
                if (hops > 2 || worst > 2) out += Finding("path", Severity.WARN, "other · path", "Path ${path.joinToString(",")} asks for $hops hop" + (if (hops == 1) "" else "s"),
                    "On LoRa a tracker normally needs WIDE1-1 only: every extra hop repeats the packet on a slow, shared channel.",
                    Fix("Set WIDE1-1", listOf("other", "path"), "WIDE1-1"))
            }
            pass("Path", n0)
        }

        // LoRa profiles: amateur band, IARU channel, the LoRa APRS settings on 433.775
        val loras = root.objList("lora")
        n0 = out.size
        for ((i, l) in loras.withIndex()) {
            val freq = l.long("frequency") ?: 0L
            if (l.flag("ais") || freq <= 0) continue
            val region = (l.int("iaruRegion") ?: 1).coerceIn(1, 3)
            val where = "lora · ${i + 1}"
            val mhz = String.format(Locale.US, "%.3f", freq / 1e6)
            if (!inBand(region, freq)) {
                out += Finding("freq$i", Severity.WARN, where, "$mhz MHz is outside the amateur band of IARU Region $region",
                    "Do not select this profile there. If it is meant for another region, set that region on the profile; if not, choose a channel of your band plan (national rules always prevail).")
                continue
            }
            if (region == 1 && R1_CHANNELS.none { Math.abs(it * 1e6 - freq) < 1000 }) out += Finding("freq$i", Severity.WARN, where,
                "$mhz MHz is not an IARU Region 1 digital channel", "LoRa APRS uses 433.775 MHz unless your local network says otherwise.",
                Fix("Use 433.775 MHz", listOf("lora", i, "frequency"), STD_FREQ))
            val sf = l.int("spreadingFactor"); val bw = l.long("signalBandwidth"); val cr = l.int("codingRate4")
            if (Math.abs(freq - STD_FREQ) < 1000 && sf != null && bw != null && cr != null && (sf != 12 || bw != 125000L || cr != 5))
                out += Finding("std$i", Severity.INFO, where, "SF$sf / ${bw / 1000} kHz / CR 4/$cr on 433.775 MHz",
                    "Only stations with the same settings decode it. The LoRa APRS network on 433.775 uses SF12, 125 kHz, CR 4/5.")
        }
        if (loras.isNotEmpty()) pass("LoRa frequencies within the IARU band plan", n0)

        // WiFi AP password (WPA2: 8 to 63 characters)
        root.obj("wifiAP")?.takeIf { it.containsKey("password") }?.let { w ->
            n0 = out.size
            val ap = w.text("password")
            val fix = Fix("Generate password", listOf("wifiAP", "password"), random = "password")
            when {
                ap == "1234567890" -> out += Finding("ap", Severity.ERROR, "wifiAP · password", "WiFi AP password is the factory default 1234567890",
                    "Anyone within range can open the tracker's WebUI and change the configuration.", fix)
                ap.isNotEmpty() && ap.length < 8 -> out += Finding("ap", Severity.ERROR, "wifiAP · password", "WiFi AP password has ${ap.length} characters",
                    "WPA2 needs at least 8: with a shorter one the access point does not start.", fix)
                ap.isEmpty() -> out += Finding("ap", Severity.WARN, "wifiAP · password", "WiFi AP without password",
                    "The access point is open: anyone within range can open the tracker's WebUI.", fix)
            }
            pass("WiFi AP password", n0)
        }

        // Bluetooth and KISS links
        val bt = root.obj("bluetooth")
        val kiss = root.obj("kissNet")
        if (bt != null) {
            n0 = out.size
            if (bt.flag("active") && bt.flag("useBLE") && bt.text("controlPin") == "123456") out += Finding("pin", Severity.WARN, "bluetooth · controlPin",
                "Control PIN is the factory default 123456",
                "Anyone nearby with the APRS LoRa+ app could control the tracker. After storing a new PIN, press Forget pairing in Connect and pair again with it.",
                Fix("Generate PIN", listOf("bluetooth", "controlPin"), random = "pin"))
            pass("Bluetooth control PIN", n0)
        }
        if (kiss != null) {
            n0 = out.size
            val port = kiss.text("tcpPort").ifEmpty { "8001" }
            if (bt != null && bt.flag("active") && bt.flag("useKISS") && kiss.flag("enabled")) out += Finding("kiss", Severity.WARN, "bluetooth + kissNet",
                "Bluetooth KISS and KISS over WiFi (TCP $port) are both on",
                "Two programs connected at the same time (this app by Bluetooth and S.A.T.-APRS+ by WiFi, for example) will both transmit. Keep one KISS link for sending.")
            if (kiss.flag("enabled") && kiss.text("allowed").trim().isEmpty()) out += Finding("kissopen", Severity.WARN, "kissNet · allowed",
                "KISS over WiFi accepts any address",
                "There is no password: anyone on the network can transmit with your callsign. Add your computer or network to the allowed addresses (e.g. 192.168.1.).")
            pass("KISS links", n0)
        }
        return Result(out, passed)
    }

    /** A new WiFi password (12 characters without look-alikes) or a 6-digit PIN that is not the factory one. */
    fun randomValue(kind: String, rnd: java.util.Random = java.security.SecureRandom()): String {
        if (kind == "pin") { var p: Int; do { p = 100000 + rnd.nextInt(900000) } while (p == 123456); return p.toString() }
        val abc = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        return (1..12).map { abc[rnd.nextInt(abc.length)] }.joinToString("")
    }

    /**
     * The configuration with a fix applied and the value that was put (to show a new password or PIN). The value keeps the
     * type of the field it replaces (a PIN stored as a number stays a number).
     */
    fun apply(root: Map<String, Any?>, fix: Fix, rnd: java.util.Random = java.security.SecureRandom()): Pair<Map<String, Any?>, String> {
        val text = fix.random?.let { randomValue(it, rnd) } ?: fix.value.toString()
        var old: Any? = root
        for (k in fix.path) old = when (k) { is String -> (old as? Map<*, *>)?.get(k); is Int -> (old as? List<*>)?.getOrNull(k); else -> null }
        val value: Any? = if (old is Number) text.toLongOrNull() ?: text else text
        @Suppress("UNCHECKED_CAST")
        return (JsonEdit.set(root, fix.path, value) as Map<String, Any?>) to text
    }
}
