/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * Sending DAPNET pages through the network's REST API (the phone needs internet and a DAPNET account).
 * POST [DEFAULT_URL] with HTTP basic auth (DAPNET user and password) and a JSON body:
 * {"text":..., "callSignNames":[...], "transmitterGroupNames":[...], "emergency":false}
 * DAPNET names (callsigns and transmitter groups) are lower case.
 */
object Dapnet {
    const val DEFAULT_URL = "http://www.hampager.de:8080/calls"
    const val DEFAULT_GROUP = "all"
    const val MAX_TEXT = 80

    /** "EA7ABC, ea1xyz-9 dl1aaa" -> ["ea7abc", "ea1xyz", "dl1aaa"]: DAPNET names have no SSID. */
    fun names(text: String): List<String> =
        text.split(',', ';', ' ').map { it.trim().substringBefore('-').lowercase() }.filter { it.isNotEmpty() }.distinct()

    /** "EA-All, dl-ns" -> ["ea-all", "dl-ns"]: transmitter group names keep their hyphens. */
    fun groups(text: String): List<String> =
        text.split(',', ';', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    /** Error text, or null if the page can be sent. */
    fun check(text: String, to: List<String>, groups: List<String>): String? {
        if (to.isEmpty()) return "No recipient"
        if (to.any { !it.matches(Regex("[a-z0-9]{3,8}")) }) return "Invalid callsign"
        if (groups.isEmpty()) return "No transmitter group"
        if (groups.any { !it.matches(Regex("[a-z0-9-]{1,32}")) }) return "Invalid transmitter group"
        if (text.isBlank()) return "Empty text"
        if (text.length > MAX_TEXT) return "Text longer than $MAX_TEXT characters"
        if (text.any { it.code > 126 || it.code < 32 }) return "Use plain ASCII (no accents)"
        return null
    }

    fun body(text: String, to: List<String>, groups: List<String>, emergency: Boolean = false): String =
        Json.encode(
            linkedMapOf(
                "text" to text, "callSignNames" to to, "transmitterGroupNames" to groups, "emergency" to emergency,
            ),
        )

    fun authorization(user: String, password: String): String =
        "Basic " + java.util.Base64.getEncoder().encodeToString("${user.lowercase()}:$password".toByteArray(Charsets.UTF_8))
}
