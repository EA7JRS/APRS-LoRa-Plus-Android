/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import es.ea7jrs.aprslorap.core.Json
import es.ea7jrs.aprslorap.core.SigId
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Artemis signal database on the phone: `files/sigid/data.sqlite` (only that member of the release tar, by HTTP range),
 * `index.json` (names -> page IDs) and the media opened so far (`files/sigid/media/<pageid>.png|ogg`).
 */
object SigIdDb {
    data class Info(val version: Int, val date: String, val signals: Int)

    @Volatile var signals: List<SigId.Signal> = emptyList()
        private set
    @Volatile private var pageIds: Map<String, String> = emptyMap()

    private fun dir(ctx: Context) = File(ctx.filesDir, "sigid").also { it.mkdirs() }
    private fun dbFile(ctx: Context) = File(dir(ctx), "data.sqlite")

    private fun get(url: String, range: String? = null, max: Int = 4 shl 20): ByteArray {
        var target = URL(url)
        repeat(5) {
            val c = target.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "APRS-LoRa-Plus-Android")
            if (range != null) c.setRequestProperty("Range", "bytes=$range")
            val code = c.responseCode
            if (code in 300..399) { target = URL(target, c.getHeaderField("Location")); c.disconnect(); return@repeat }
            if (code != 200 && code != 206) { c.disconnect(); throw IllegalStateException("HTTP $code from ${target.host}") }
            if (range != null && code != 206) { c.disconnect(); throw IllegalStateException("the server does not send parts of the file") }
            val out = ByteArrayOutputStream()
            c.inputStream.use { inp ->
                val buf = ByteArray(16384)
                while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > max) throw IllegalStateException("too big") }
            }
            c.disconnect()
            return out.toByteArray()
        }
        throw IllegalStateException("too many redirects")
    }

    /** Removes the database and its images / audio from the phone (the Artemis data is optional: the user downloads it). */
    fun delete(ctx: Context) {
        dir(ctx).deleteRecursively()
        signals = emptyList()
        AppState.sigIdInfo.value = null
        AppState.sigIdMessage.value = "Signal database removed."
    }

    /** Loads the database already on the phone (any thread). */
    fun load(ctx: Context): Info? {
        val f = dbFile(ctx)
        if (!f.exists()) { AppState.sigIdInfo.value = null; return null }
        return try {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                fun <T> multi(sql: String, read: (android.database.Cursor) -> Pair<Int, T>): Map<Int, List<T>> {
                    val m = HashMap<Int, MutableList<T>>()
                    db.rawQuery(sql, null).use { c -> while (c.moveToNext()) { val (k, v) = read(c); m.getOrPut(k) { ArrayList() }.add(v) } }
                    return m
                }
                val freqs = multi("SELECT SIG_ID, VALUE FROM frequency") { it.getInt(0) to it.getLong(1) }
                val bws = multi("SELECT SIG_ID, VALUE FROM bandwidth") { it.getInt(0) to it.getLong(1) }
                val modes = multi("SELECT SIG_ID, VALUE FROM mode") { it.getInt(0) to (it.getString(1) ?: "") }
                val mods = multi("SELECT SIG_ID, VALUE FROM modulation") { it.getInt(0) to (it.getString(1) ?: "") }
                val cats = multi("SELECT c.SIG_ID, l.VALUE FROM category c JOIN categorylabel l ON l.CLB_ID = c.CLB_ID") { it.getInt(0) to (it.getString(1) ?: "") }
                val locs = multi("SELECT SIG_ID, VALUE FROM location") { it.getInt(0) to (it.getString(1) ?: "") }
                val docs = multi("SELECT SIG_ID, DOC_ID, TYPE, EXTENSION FROM documents") { it.getInt(0) to SigId.Doc(it.getInt(1), it.getString(2) ?: "", it.getString(3) ?: "") }
                val list = ArrayList<SigId.Signal>()
                db.rawQuery("SELECT SIG_ID, NAME, URL, DESCRIPTION FROM signals", null).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getInt(0)
                        list.add(SigId.Signal(id, c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                            freqs[id].orEmpty(), bws[id].orEmpty(), modes[id].orEmpty(), mods[id].orEmpty(), cats[id].orEmpty(), locs[id].orEmpty(), docs[id].orEmpty()))
                    }
                }
                var info = Info(0, "", list.size)
                db.rawQuery("SELECT VERSION, DATE FROM info", null).use { c -> if (c.moveToFirst()) info = Info(c.getInt(0), c.getString(1) ?: "", list.size) }
                signals = list.sortedBy { it.name.lowercase() }
                File(dir(ctx), "index.json").takeIf { it.exists() }?.let { pageIds = SigId.parseIndex(it.readText()) }
                AppState.sigIdInfo.value = info
                info
            }
        } catch (e: Exception) {
            AppState.sigIdMessage.value = "Cannot read the database: ${e.message}"
            null
        }
    }

    /** Checks the latest release and, if newer (or [force]), downloads only data.sqlite and the index. */
    fun update(ctx: Context, force: Boolean = false) {
        if (AppState.sigIdBusy.value) return
        AppState.sigIdBusy.value = true
        val app = ctx.applicationContext
        Thread({
            try {
                AppState.sigIdMessage.value = "Checking the latest database..."
                val rel = SigId.parseRelease(String(get(SigId.RELEASE_INFO_URL), Charsets.UTF_8)) ?: throw IllegalStateException("unreadable release information")
                val have = AppState.sigIdInfo.value?.version ?: 0
                if (!force && have >= rel.version) { AppState.sigIdMessage.value = "Up to date (version $have)"; return@Thread }
                AppState.sigIdMessage.value = "Downloading version ${rel.version} (data only)..."
                val head = get(rel.url, "0-16383", 1 shl 16)
                val (off, size) = SigId.findMember(head, "data.sqlite") ?: throw IllegalStateException("data.sqlite not found at the start of the archive")
                if (size <= 0 || size > (64 shl 20)) throw IllegalStateException("unexpected size $size")
                val data = get(rel.url, "$off-${off + size - 1}", size.toInt() + 1)
                if (data.size.toLong() != size || !SigId.isSqlite(data)) throw IllegalStateException("the downloaded part is not the database")
                val tmp = File(dir(app), "data.sqlite.new")
                tmp.writeBytes(data)
                SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("PRAGMA quick_check", null).use { c -> if (!c.moveToFirst() || c.getString(0) != "ok") throw IllegalStateException("damaged database") }
                }
                val index = try { get(SigId.INDEX_URL, null, 4 shl 20) } catch (e: Exception) { null }
                index?.let { File(dir(app), "index.json").writeBytes(it) }
                if (!tmp.renameTo(dbFile(app))) { dbFile(app).delete(); tmp.renameTo(dbFile(app)) }
                File(dir(app), "media").deleteRecursively()                      // media may change between versions
                val info = load(app)
                AppState.sigIdMessage.value = "Database version ${info?.version}: ${info?.signals} signals (${size / 1024} KB downloaded)"
            } catch (e: Exception) {
                AppState.sigIdMessage.value = "Update failed: ${e.message}"
            } finally {
                AppState.sigIdBusy.value = false
            }
        }, "sigid-update").start()
    }

    /** The spectrum image ("png") or audio ("ogg") of [s], downloaded the first time. Blocking: call off the main thread. */
    fun media(ctx: Context, s: SigId.Signal, ext: String): File? {
        val page = pageIds[s.name] ?: return null
        val f = File(File(dir(ctx), "media").also { it.mkdirs() }, "$page.$ext")
        if (f.exists() && f.length() > 0) return f
        return try { f.writeBytes(get(SigId.mediaUrl(page, ext), null, 16 shl 20)); f } catch (e: Exception) { null }
    }
}
