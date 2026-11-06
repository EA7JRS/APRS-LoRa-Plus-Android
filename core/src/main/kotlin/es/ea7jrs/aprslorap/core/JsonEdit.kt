/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * Edits of a parsed JSON tree (the tracker's configuration) without changing the original: a path is a list of keys (String) and
 * list positions (Int).
 */
object JsonEdit {
    @Suppress("UNCHECKED_CAST")
    fun set(root: Any?, path: List<Any>, value: Any?): Any? {
        if (path.isEmpty()) return value
        return when (val key = path[0]) {
            is String -> {
                val m = LinkedHashMap((root as? Map<String, Any?>) ?: emptyMap())
                m[key] = set(m[key], path.drop(1), value)
                m
            }
            is Int -> {
                val l = ArrayList((root as? List<Any?>) ?: emptyList())
                if (key in l.indices) l[key] = set(l[key], path.drop(1), value)
                l
            }
            else -> root
        }
    }

    /** Text typed for a field to the value of the same type as the one it replaces; null if it does not fit (a number that is not one). */
    fun typed(old: Any?, text: String): Any? = when (old) {
        is Boolean -> text.trim().lowercase().let { if (it == "true") true else if (it == "false") false else null }
        is Number -> {
            val t = text.trim()
            if (old is Double && (old != Math.floor(old) || t.contains('.'))) t.toDoubleOrNull()
            else t.toLongOrNull() ?: t.toDoubleOrNull()
        }
        is String -> text
        null -> text
        else -> null
    }

    /** Fields that should not be shown in clear (WiFi and APRS-IS passwords, PIN, tokens). */
    fun isSecret(key: String): Boolean {
        val k = key.lowercase()
        return k.contains("password") || k.contains("passcode") || k.contains("token") || k.contains("secret") || k == "controlpin" || k == "pin"
    }
}
