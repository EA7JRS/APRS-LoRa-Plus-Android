// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// AIS (marine VHF) vessels received by the tracker: T-TWR AIS mode (SA868), an external AIS module on its UART, or the phone's
// RTL-SDR (sent with "aisin"). Events "ais" and "aislist", command "aisset". Protocol: docs/BLE_CONTROL.md, docs/AIS.md.
package es.ea7jrs.aprslorap.core

import java.util.Locale

data class AisVessel(
    val mmsi: Long, val cls: String, val name: String, val callsign: String, val shipType: Int, val status: Int,
    val lat: Double?, val lon: Double?, val posAgeS: Int?, val sogKn: Double?, val cogDeg: Double?, val heading: Int?, val ageS: Int,
    val rangeNm: Double?, val bearingDeg: Int?, val cpaNm: Double?, val tcpaMin: Double?, val danger: Boolean,
    val destination: String = "", val lengthM: Int = 0, val beamM: Int = 0, val draughtM: Double? = null, val eta: String = "",
    val imo: Long = 0, val altitudeM: Int? = null, val text: String = "", val channel: String = "", val messages: Int = 0,
    val receivedAtMs: Long = 0,
) {
    val title: String get() = name.ifBlank { Ais.distressName(mmsi).ifBlank { String.format(Locale.US, "%09d", mmsi) } }

    companion object {
        fun parse(o: Map<String, Any?>, nowMs: Long): AisVessel? {
            val m = o.long("m") ?: return null
            return AisVessel(
                m, o.str("cl") ?: "?", o.str("n") ?: "", o.str("c") ?: "", o.int("t") ?: 0, o.int("st") ?: 15,
                o.num("lat"), o.num("lon"), o.int("pa"), o.num("sog"), o.num("cog"), o.int("hdg"), o.int("age") ?: 0,
                o.num("nm"), o.int("brg"), o.num("cpa"), o.num("tcpa"), o.bool("dng") ?: false,
                o.str("ds") ?: "", o.int("len") ?: 0, o.int("bm") ?: 0, o.num("dr"), o.str("eta") ?: "", o.long("imo") ?: 0, o.int("alt"),
                o.str("txt") ?: "", o.str("ch") ?: "", o.int("msgs") ?: 0, nowMs,
            )
        }
    }
}

object Ais {
    /** Merges an update into what the app already knows (list items carry fewer fields than the "ais" event). */
    fun merge(old: AisVessel?, new: AisVessel): AisVessel {
        if (old == null) return new
        return new.copy(
            name = new.name.ifBlank { old.name }, callsign = new.callsign.ifBlank { old.callsign },
            shipType = if (new.shipType != 0) new.shipType else old.shipType,
            destination = new.destination.ifBlank { old.destination }, lengthM = if (new.lengthM > 0) new.lengthM else old.lengthM,
            beamM = if (new.beamM > 0) new.beamM else old.beamM, draughtM = new.draughtM ?: old.draughtM, eta = new.eta.ifBlank { old.eta },
            imo = if (new.imo > 0) new.imo else old.imo, text = new.text.ifBlank { old.text }, channel = new.channel.ifBlank { old.channel },
            messages = maxOf(new.messages, old.messages),
        )
    }

    fun distressName(mmsi: Long) = when (mmsi / 1_000_000) { 970L -> "AIS-SART"; 972L -> "MOB"; 974L -> "EPIRB"; else -> "" }

    fun className(c: String) = when (c) {
        "A" -> "Class A"; "B" -> "Class B"; "S" -> "Base station"; "N" -> "Aid to navigation"; "R" -> "SAR aircraft"; "T" -> "Distress beacon"; else -> "?"
    }

    fun shipTypeName(t: Int): String = when {
        t == 0 -> ""
        t in 20..29 -> "Wing in ground"
        t == 30 -> "Fishing"; t == 31 || t == 32 -> "Towing"; t == 33 -> "Dredging"; t == 34 -> "Diving"; t == 35 -> "Military"
        t == 36 -> "Sailing"; t == 37 -> "Pleasure craft"
        t in 40..49 -> "High speed craft"
        t == 50 -> "Pilot"; t == 51 -> "Search and rescue"; t == 52 -> "Tug"; t == 53 -> "Port tender"; t == 54 -> "Anti-pollution"
        t == 55 -> "Law enforcement"; t == 58 -> "Medical transport"
        t in 60..69 -> "Passenger"; t in 70..79 -> "Cargo"; t in 80..89 -> "Tanker"
        else -> "Other"
    }

    fun statusName(s: Int): String = when (s) {
        0 -> "Under way using engine"; 1 -> "At anchor"; 2 -> "Not under command"; 3 -> "Restricted manoeuvrability"
        4 -> "Constrained by draught"; 5 -> "Moored"; 6 -> "Aground"; 7 -> "Engaged in fishing"; 8 -> "Under way sailing"
        11 -> "Towing astern"; 12 -> "Pushing ahead"; 14 -> "AIS-SART active"; else -> ""
    }

    /** Command to change the tracker's AIS settings (only the given ones). */
    fun settings(channel: Int? = null, cpaNm: Double? = null, tcpaMin: Int? = null, guardNm: Double? = null, alarm: Boolean? = null, sart: Boolean? = null): String {
        val f = ArrayList<Pair<String, Any?>>()
        channel?.let { f.add("ch" to it) }
        cpaNm?.let { f.add("cpa" to Math.round(it * 10).toInt()) }
        tcpaMin?.let { f.add("tcpa" to it) }
        guardNm?.let { f.add("guard" to Math.round(it * 10).toInt()) }
        alarm?.let { f.add("alarm" to it) }
        sart?.let { f.add("sart" to it) }
        return Control.command("aisset", *f.toTypedArray())
    }

    fun summary(v: AisVessel): String {
        val p = ArrayList<String>()
        if (v.rangeNm != null) p.add(String.format(Locale.US, "%.2f nm", v.rangeNm) + (v.bearingDeg?.let { " $it°" } ?: ""))
        if (v.sogKn != null) p.add(String.format(Locale.US, "%.1f kn", v.sogKn) + (v.cogDeg?.let { String.format(Locale.US, " %.0f°", it) } ?: ""))
        if (v.cpaNm != null && v.tcpaMin != null && v.tcpaMin > 0 && v.tcpaMin < 600) p.add(String.format(Locale.US, "CPA %.2f nm in %.0f min", v.cpaNm, v.tcpaMin))
        shipTypeName(v.shipType).takeIf { it.isNotEmpty() }?.let { p.add(it) }
        return p.joinToString(" · ")
    }
}
