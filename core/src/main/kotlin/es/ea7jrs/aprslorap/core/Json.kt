/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * Minimal JSON for the control protocol (the core has no dependencies).
 * Objects become Map<String, Any?>, arrays List<Any?>, numbers Double, plus String, Boolean and null.
 */
object Json {
    class ParseException(msg: String) : Exception(msg)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (!p.end()) throw ParseException("extra text at ${p.i}")
        return v
    }

    /** Parses an object, or null if the text is not a valid JSON object. */
    fun parseObject(text: String): Map<String, Any?>? =
        try { @Suppress("UNCHECKED_CAST") (parse(text) as? Map<String, Any?>) } catch (e: ParseException) { null }

    fun encode(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v)
            is Double -> if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) sb.append(v.toLong()) else sb.append(v)
            is Float -> write(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString()); sb.append(':'); write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) { if (!first) sb.append(','); first = false; write(sb, item) }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun end() = i >= s.length
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun fail(what: String): Nothing = throw ParseException("$what at $i")

        fun value(): Any? {
            if (end()) fail("unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }

        fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail("bad literal")
            i += w.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (!end() && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (end() || s[i] != '"') fail("expected key")
                val k = str()
                ws()
                if (end() || s[i] != ':') fail("expected :")
                i++; ws()
                m[k] = value()
                ws()
                if (end()) fail("unexpected end")
                when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> fail("expected , or }") }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (!end() && s[i] == ']') { i++; return l }
            while (true) {
                ws()
                l.add(value())
                ws()
                if (end()) fail("unexpected end")
                when (s[i]) { ',' -> i++; ']' -> { i++; return l }; else -> fail("expected , or ]") }
            }
        }

        fun str(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (end()) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (end()) fail("bad escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad \\u")
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail("bad \\u"))
                                i += 4
                            }
                            else -> fail("bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull() ?: fail("bad number")
        }
    }
}

/** Typed reads from a parsed JSON object. */
fun Map<String, Any?>.str(key: String): String? = this[key] as? String
fun Map<String, Any?>.num(key: String): Double? = (this[key] as? Number)?.toDouble()
fun Map<String, Any?>.int(key: String): Int? = num(key)?.toInt()
fun Map<String, Any?>.long(key: String): Long? = num(key)?.toLong()
fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.objList(key: String): List<Map<String, Any?>> = (this[key] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
fun Map<String, Any?>.strList(key: String): List<String> = (this[key] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
