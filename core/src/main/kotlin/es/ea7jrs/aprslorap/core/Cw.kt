/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/** Telegraphy for the key connected to the phone: keyer, decoder, Morse table and the inputs of the USB adapters. */

enum class KeyerMode { STRAIGHT, IAMBIC_A, IAMBIC_B }

/** Which line of the paddle an input is. STRAIGHT = an already timed key line (straight key, or a keyer inside the adapter). */
enum class KeyLine { DIT, DAH, STRAIGHT }

object Morse {
    private val table = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.", 'G' to "--.", 'H' to "....",
        'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.",
        'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
        'Y' to "-.--", 'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-", '5' to ".....", '6' to "-....",
        '7' to "--...", '8' to "---..", '9' to "----.",
        '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '/' to "-..-.", '=' to "-...-", '+' to ".-.-.", '-' to "-....-",
        '@' to ".--.-.", '(' to "-.--.", ')' to "-.--.-", ':' to "---...", '\'' to ".----.", '"' to ".-..-.", '!' to "-.-.--",
    )
    private val reverse = table.entries.associate { (k, v) -> v to k }

    fun pattern(c: Char): String? = table[c.uppercaseChar()]
    fun char(pattern: String): Char? = reverse[pattern]
    fun ditMs(wpm: Int): Long = 1200L / wpm.coerceIn(5, 60)
}

/**
 * Electronic keyer, driven by time: call [update] often (every 1-2 ms) with the paddle states; it returns the key line.
 * Dah = 3 dits, space between elements = 1 dit. Squeezing both paddles alternates dit and dah.
 * Iambic B: a press of the opposite paddle during an element (or the space after it) is remembered and sent even if released
 * (squeeze, release during a dah -> one more dit). Iambic A: only the paddles still held at the end of the space count.
 */
class IambicKeyer(wpm: Int, var mode: KeyerMode) {
    private enum class State { IDLE, MARK, SPACE }

    var wpm: Int = wpm
        set(v) { field = v.coerceIn(5, 60) }
    private val dit get() = Morse.ditMs(wpm)
    private var state = State.IDLE
    private var until = 0L
    private var element = ' '                   // '.' or '-' being sent or just sent
    private var ditMemory = false
    private var dahMemory = false

    fun reset() { state = State.IDLE; ditMemory = false; dahMemory = false; element = ' ' }

    fun update(now: Long, ditPressed: Boolean, dahPressed: Boolean): Boolean {
        if (mode == KeyerMode.STRAIGHT) return ditPressed || dahPressed
        if (mode == KeyerMode.IAMBIC_B && state != State.IDLE) {
            if (element == '-' && ditPressed) ditMemory = true      // only the opposite paddle is remembered
            if (element == '.' && dahPressed) dahMemory = true
        }
        if (state == State.MARK && now >= until) { state = State.SPACE; until += dit }
        if (state == State.SPACE && now >= until) state = State.IDLE
        if (state == State.IDLE) {
            val wantDit = ditPressed || ditMemory
            val wantDah = dahPressed || dahMemory
            ditMemory = false; dahMemory = false
            val next = when {
                wantDit && wantDah -> if (element == '.') '-' else '.'
                wantDit -> '.'
                wantDah -> '-'
                else -> null
            }
            if (next != null) {
                element = next
                state = State.MARK
                until = now + (if (next == '.') dit else 3 * dit)
            }
        }
        return state == State.MARK
    }
}

/**
 * Morse decoder from key down / up times. Adapts to the operator's speed (dit estimate from the marks), so it also follows
 * a straight key. [tick] returns the characters completed so far (' ' for a word space).
 */
class CwDecoder(wpm: Int) {
    private var ditEst = Morse.ditMs(wpm).toDouble()
    private val pattern = StringBuilder()
    private var downAt = -1L
    private var upAt = -1L
    private var wordDone = true

    val pending: String get() = pattern.toString()
    val speedWpm: Int get() = (1200.0 / ditEst).toInt()

    fun keyDown(t: Long) { downAt = t }

    fun keyUp(t: Long) {
        if (downAt < 0) return
        val mark = (t - downAt).toDouble()
        downAt = -1
        if (mark < 2 * ditEst) {
            pattern.append('.'); ditEst = 0.8 * ditEst + 0.2 * mark
        } else {
            pattern.append('-'); ditEst = 0.8 * ditEst + 0.2 * (mark / 3)
        }
        ditEst = ditEst.coerceIn(20.0, 240.0)
        upAt = t
        wordDone = false
    }

    fun tick(t: Long): String {
        if (downAt >= 0 || upAt < 0) return ""
        val gap = t - upAt
        val out = StringBuilder()
        if (pattern.isNotEmpty() && gap > 2 * ditEst) {
            out.append(Morse.char(pattern.toString()) ?: '*')
            pattern.clear()
        }
        if (!wordDone && pattern.isEmpty() && gap > 5 * ditEst) {
            out.append(' ')
            wordDone = true
        }
        return out.toString()
    }
}

/** Keyboard-type adapters (VBand, Vail in keyboard mode, home-made): Android key codes of the keys they send. */
object KeyboardAdapter {
    const val CTRL_LEFT = 113                   // KeyEvent.KEYCODE_CTRL_LEFT: dit (or the timed key line of a Vail keyer)
    const val CTRL_RIGHT = 114                  // KeyEvent.KEYCODE_CTRL_RIGHT: dah
    const val LEFT_BRACKET = 71                 // KeyEvent.KEYCODE_LEFT_BRACKET: '[' dit (some home-made adapters)
    const val RIGHT_BRACKET = 72                // KeyEvent.KEYCODE_RIGHT_BRACKET: ']' dah

    fun line(keyCode: Int): KeyLine? = when (keyCode) {
        CTRL_LEFT, LEFT_BRACKET -> KeyLine.DIT
        CTRL_RIGHT, RIGHT_BRACKET -> KeyLine.DAH
        else -> null
    }
}

/**
 * MIDI adapters (Vail in MIDI mode): note 0 = an element already timed by the adapter's keyer (or a straight key),
 * note 1 = dit paddle, note 2 = dah paddle (passthrough keyer). Note On with velocity 0 counts as Note Off. Running status.
 */
class MidiKeyParser {
    private var status = 0
    private val data = IntArray(2)
    private var count = 0

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): List<Pair<KeyLine, Boolean>> {
        val out = ArrayList<Pair<KeyLine, Boolean>>()
        for (i in offset until offset + length) {
            val b = bytes[i].toInt() and 0xFF
            if (b >= 0xF8) continue                                 // real-time bytes may come anywhere
            if (b >= 0x80) { status = if (b < 0xF0) b else 0; count = 0; continue }
            if (status == 0) continue
            val kind = status and 0xF0
            if (kind != 0x80 && kind != 0x90) { count = (count + 1) % (if (kind == 0xC0 || kind == 0xD0) 1 else 2); continue }
            data[count++] = b
            if (count < 2) continue
            count = 0
            val line = when (data[0]) { 0 -> KeyLine.STRAIGHT; 1 -> KeyLine.DIT; 2 -> KeyLine.DAH; else -> null } ?: continue
            out.add(line to (kind == 0x90 && data[1] > 0))
        }
        return out
    }
}

/** Serial adapters: which modem status line carries each paddle (the key closes DTR->DSR, RTS->CTS...). */
enum class SerialLine { DSR, CTS, CD, RI }
