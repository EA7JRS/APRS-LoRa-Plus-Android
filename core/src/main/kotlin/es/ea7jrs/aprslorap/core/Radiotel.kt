// Copyright (C) 2026 Manuel - EA7JRS
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Distress, urgency and safety voice messages to read on channel 16 (or 2182 kHz): MAYDAY, MAYDAY RELAY, PAN PAN and
// SECURITE, in Spanish or English, with the vessel data, position and UTC time. Same text and tests as `radiotel` of the
// plotter core. MAYDAY natures are in the order of the DSC distress natures (100..110).
package es.ea7jrs.aprslorap.core

import kotlin.math.abs
import kotlin.math.round

object Radiotel {
    enum class Kind { MAYDAY, MAYDAY_RELAY, PAN_PAN, SECURITE }

    data class Input(
        val kind: Kind = Kind.MAYDAY,
        val english: Boolean = false,
        val name: String = "",
        val callsign: String = "",
        val mmsi: String = "",
        val lat: Double? = null,
        val lon: Double? = null,
        val utcMin: Int = -1,
        val nature: Int = 0,
        val persons: Int = -1,
        val description: String = "",
        val extra: String = "",
        val relayName: String = "",
        val relayMmsi: String = "",
        val relayLat: Double? = null,
        val relayLon: Double? = null,
        val relayUtcMin: Int = -1,
        val relayPersons: Int = -1,
    )

    private class Nat(val es: String, val en: String, val helpEs: String, val helpEn: String)

    private const val IAI = "SOLICITO AUXILIO INMEDIATO"
    private const val IAE = "I REQUIRE IMMEDIATE ASSISTANCE"
    private val MAYDAY = listOf(
        Nat("INCENDIO / EXPLOSIÓN", "FIRE / EXPLOSION", IAI, IAE),
        Nat("VÍA DE AGUA", "FLOODING", IAI, IAE),
        Nat("ABORDAJE", "COLLISION", IAI, IAE),
        Nat("VARADA", "GROUNDING", IAI, IAE),
        Nat("ESCORA, PELIGRO DE ZOZOBRA", "LISTING, IN DANGER OF CAPSIZING", IAI, IAE),
        Nat("HUNDIMIENTO", "SINKING", IAI, IAE),
        Nat("SIN GOBIERNO Y A LA DERIVA", "DISABLED AND ADRIFT", IAI, IAE),
        Nat("PELIGRO GRAVE E INMINENTE", "IN GRAVE AND IMMINENT DANGER", IAI, IAE),
        Nat("ABANDONAMOS EL BARCO", "ABANDONING SHIP", "SOLICITO RESCATE INMEDIATO", "I REQUIRE IMMEDIATE RESCUE"),
        Nat("PIRATERÍA / ATAQUE ARMADO", "PIRACY / ARMED ATTACK", IAI, IAE),
        Nat("HOMBRE AL AGUA", "MAN OVERBOARD", "SOLICITO AUXILIO INMEDIATO PARA LA BÚSQUEDA", "I REQUIRE IMMEDIATE ASSISTANCE TO SEARCH"),
    )
    private val PAN = listOf(
        Nat("AVERÍA DE MOTOR, SIN PROPULSIÓN", "ENGINE FAILURE, NO PROPULSION", "SOLICITO REMOLQUE", "I REQUIRE A TOW"),
        Nat("AVERÍA DEL TIMÓN", "STEERING FAILURE", "SOLICITO ASISTENCIA", "I REQUIRE ASSISTANCE"),
        Nat("PERSONA ENFERMA O HERIDA A BORDO", "SICK OR INJURED PERSON ON BOARD", "SOLICITO CONSEJO MÉDICO", "I REQUIRE MEDICAL ADVICE"),
        Nat("EVACUACIÓN MÉDICA", "MEDICAL EVACUATION", "SOLICITO EVACUACIÓN MÉDICA", "I REQUIRE MEDICAL EVACUATION"),
        Nat("HOMBRE AL AGUA, LO ESTAMOS BUSCANDO", "MAN OVERBOARD, WE ARE SEARCHING", "SOLICITO QUE LOS BARCOS CERCANOS VIGILEN",
            "I REQUEST VESSELS IN THE AREA TO KEEP A LOOKOUT"),
        Nat("SIN COMBUSTIBLE", "OUT OF FUEL", "SOLICITO REMOLQUE", "I REQUIRE A TOW"),
        Nat("URGENCIA", "URGENCY", "SOLICITO ASISTENCIA", "I REQUIRE ASSISTANCE"),
    )
    private val SEC = listOf(
        Nat("PELIGRO PARA LA NAVEGACIÓN", "NAVIGATIONAL HAZARD", "", ""),
        Nat("OBJETO A LA DERIVA", "DRIFTING OBJECT", "", ""),
        Nat("AVISO METEOROLÓGICO", "WEATHER WARNING", "", ""),
        Nat("MANIOBRA RESTRINGIDA / REMOLQUE", "RESTRICTED IN ABILITY TO MANOEUVRE / TOWING", "", ""),
        Nat("AVISO DE SEGURIDAD", "SAFETY MESSAGE", "", ""),
    )

    private fun table(k: Kind) = when (k) { Kind.PAN_PAN -> PAN; Kind.SECURITE -> SEC; else -> MAYDAY }
    private fun nat(k: Kind, i: Int): Nat { val t = table(k); return t[if (i in t.indices) i else t.size - 1] }

    fun natureCount(k: Kind): Int = table(k).size
    fun natureName(k: Kind, i: Int, english: Boolean): String = nat(k, i).let { if (english) it.en else it.es }
    /** DSC distress nature (100..110) -> MAYDAY index, -1 if it is not one. */
    fun natureFromDsc(d: Int): Int = if (d in 100..110) d - 100 else -1

    /** Spanish names of the ship types of [Vessel.TYPES], for the description in Spanish. */
    val TYPE_ES = listOf("velero de recreo", "lancha de recreo", "mercante", "petrolero", "pasaje / ferry", "pesquero", "remolcador",
        "práctico", "alta velocidad", "vigilancia", "salvamento", "sanitario", "militar", "buceo", "velero grande", "WIG / aerodeslizador", "otro buque")

    private val ALPHA = listOf("ALFA", "BRAVO", "CHARLIE", "DELTA", "ECHO", "FOXTROT", "GOLF", "HOTEL", "INDIA", "JULIETT", "KILO", "LIMA", "MIKE",
        "NOVEMBER", "OSCAR", "PAPA", "QUEBEC", "ROMEO", "SIERRA", "TANGO", "UNIFORM", "VICTOR", "WHISKEY", "X-RAY", "YANKEE", "ZULU")
    private val DIG_ES = listOf("CERO", "UNO", "DOS", "TRES", "CUATRO", "CINCO", "SEIS", "SIETE", "OCHO", "NUEVE")
    private val DIG_EN = listOf("ZERO", "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE")

    fun phonetic(cs: String, english: Boolean): String = cs.uppercase().mapNotNull { c ->
        when (c) { in 'A'..'Z' -> ALPHA[c - 'A']; in '0'..'9' -> (if (english) DIG_EN else DIG_ES)[c - '0']; else -> null }
    }.joinToString(" ")

    fun digits(num: String, english: Boolean): String =
        num.filter { it in '0'..'9' }.map { (if (english) DIG_EN else DIG_ES)[it - '0'] }.joinToString(" ")

    fun position(lat: Double, lon: Double, english: Boolean): String {
        fun part(v: Double, w: Int, pos: String, neg: String): String {
            val a = abs(v)
            var d = a.toInt()
            var m = round((a - d) * 600.0) / 10.0
            if (m >= 60.0) { d++; m = 0.0 }
            var s = String.format(java.util.Locale.US, "%0${w}d %s %04.1f %s %s", d, if (english) "DEGREES" else "GRADOS", m,
                if (english) "MINUTES" else "MINUTOS", if (v >= 0) pos else neg)
            if (!english) s = s.replace('.', ',')
            return s
        }
        return part(lat, 2, if (english) "NORTH" else "NORTE", if (english) "SOUTH" else "SUR") + ", " +
            part(lon, 3, if (english) "EAST" else "ESTE", if (english) "WEST" else "OESTE")
    }

    private fun triple(w: String, en: Boolean) = if (en) "$w $w $w" else "$w, $w, $w"
    private fun hhmm(m: Int) = String.format(java.util.Locale.US, "%02d%02d UTC", (m / 60) % 24, m % 60)

    /** The whole message, one sentence per line, upper case, to read as it is. */
    fun message(i: Input): String {
        val en = i.english
        val name = if (i.name.isBlank()) (if (en) "[VESSEL NAME]" else "[NOMBRE DEL BARCO]") else i.name.uppercase()
        var ident = ""
        if (i.callsign.isNotBlank()) ident += (if (en) "CALL SIGN " else "DISTINTIVO ") + phonetic(i.callsign, en)
        if (i.mmsi.isNotBlank()) { if (ident.isNotEmpty()) ident += ", "; ident += "MMSI " + digits(i.mmsi, en) }
        fun pos(la: Double?, lo: Double?, t: Int): String {
            val s = if (en) "POSITION " else "SITUACIÓN "
            if (la == null || lo == null) return s + if (en) "[POSITION]" else "[SITUACIÓN]"
            return s + position(la, lo, en) + if (t >= 0) (if (en) " AT " else " A LAS ") + hhmm(t) else ""
        }
        fun persons(n: Int) = if (n == 1) (if (en) "1 PERSON ON BOARD" else "1 PERSONA A BORDO") else "$n " + if (en) "PERSONS ON BOARD" else "PERSONAS A BORDO"
        val t = nat(i.kind, i.nature)
        val sb = StringBuilder()
        fun line(s: String) { sb.append(s).append('\n') }
        val allSt = triple(if (en) "ALL STATIONS" else "A TODAS LAS ESTACIONES", en)
        val thisIs = (if (en) "THIS IS " else "AQUÍ ") + triple(name, en)
        when (i.kind) {
            Kind.MAYDAY -> {
                line(triple("MAYDAY", en)); line(thisIs)
                if (ident.isNotEmpty()) line(ident)
                line("MAYDAY $name" + if (i.callsign.isBlank()) "" else ", " + i.callsign.uppercase())
                line(pos(i.lat, i.lon, i.utcMin)); line(if (en) t.en else t.es); line(if (en) t.helpEn else t.helpEs)
            }
            Kind.MAYDAY_RELAY -> {
                val rn = if (i.relayName.isBlank()) (if (en) "UNKNOWN VESSEL" else "BUQUE DESCONOCIDO") else i.relayName.uppercase()
                line(triple("MAYDAY RELAY", en)); line(allSt); line(thisIs)
                if (ident.isNotEmpty()) line(ident)
                line((if (en) "RECEIVED THE FOLLOWING MAYDAY FROM " else "HE RECIBIDO EL SIGUIENTE MAYDAY DE ") + rn +
                    if (i.relayMmsi.isBlank()) "" else ", MMSI " + digits(i.relayMmsi, en))
                line(pos(i.relayLat, i.relayLon, i.relayUtcMin)); line(if (en) t.en else t.es)
                if (i.relayPersons >= 0) line(persons(i.relayPersons))
                line(if (en) "IMMEDIATE ASSISTANCE REQUIRED" else "NECESITA AUXILIO INMEDIATO")
                if (i.extra.isNotBlank()) line(i.extra.uppercase())
                line(if (en) "OVER" else "CAMBIO")
                return sb.toString()
            }
            Kind.PAN_PAN -> {
                line(triple("PAN PAN", en)); line(allSt); line(thisIs)
                if (ident.isNotEmpty()) line(ident)
                line(pos(i.lat, i.lon, i.utcMin)); line(if (en) t.en else t.es); line(if (en) t.helpEn else t.helpEs)
            }
            Kind.SECURITE -> {
                line(triple("SÉCURITÉ", en)); line(allSt); line(thisIs)
                if (ident.isNotEmpty()) line(ident)
                line(if (en) t.en else t.es)
                if (i.extra.isNotBlank()) line(i.extra.uppercase())
                if (i.lat != null && i.lon != null) line((if (en) "IN POSITION " else "EN SITUACIÓN ") + position(i.lat, i.lon, en))
                line(if (en) "OUT" else "TERMINADO")
                return sb.toString()
            }
        }
        if (i.persons >= 0) line(persons(i.persons))
        if (i.description.isNotBlank()) line(i.description.uppercase())
        if (i.extra.isNotBlank()) line(i.extra.uppercase())
        line(if (en) "OVER" else "CAMBIO")
        return sb.toString()
    }
}
