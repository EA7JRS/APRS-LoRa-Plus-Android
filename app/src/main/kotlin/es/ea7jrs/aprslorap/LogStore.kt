/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import es.ea7jrs.aprslorap.core.Adif
import es.ea7jrs.aprslorap.core.Eqsl
import es.ea7jrs.aprslorap.core.Logbook
import es.ea7jrs.aprslorap.core.Lotw
import es.ea7jrs.aprslorap.core.Qso
import es.ea7jrs.aprslorap.core.QrzLog
import es.ea7jrs.aprslorap.core.Spots
import java.io.ByteArrayInputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Provider
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The log of the app (files/logbook.adi): contacts logged by hand or from CW, logs imported from qFT8 (ADIF), and the
 * QSL services: QRZ.com logbook, LoTW (signed here with the TrustedQSL certificate), eQSL, World Radio League (through
 * its desktop app, which listens to WSJT-X UDP, and through QRZ) and ADIF out for qFT8 or anything else.
 */
object LogStore {
    data class Lotw12(val call: String, val dxcc: Int, val notAfter: Date?)

    private var app: Context? = null
    private var settings: Settings? = null
    private var book = Logbook()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "logbook").apply { isDaemon = true } }
    private const val FILE = "logbook.adi"
    private const val P12 = "lotw.p12"
    const val PROGRAM = "APRS LoRa+ Android"

    @Synchronized
    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        settings = Settings(ctx)
        val f = File(ctx.filesDir, FILE)
        book = try { if (f.exists()) Logbook.load(f.readText()) else Logbook() } catch (_: Exception) { Logbook() }
        publish()
        AppState.lotwCert.value = try { certInfo() } catch (_: Exception) { null }
    }

    private fun publish() { AppState.qsoLog.value = book.qsos.sortedByDescending { it.minutes } }
    private fun status(s: String) { AppState.logStatus.value = s }
    fun version(): String = try { val c = app!!; c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }

    private fun save() {
        val c = app ?: return
        val tmp = File(c.filesDir, "$FILE.tmp")
        tmp.writeText(book.adif(PROGRAM, version(), true))
        tmp.renameTo(File(c.filesDir, FILE))
        publish()
    }

    fun newId(): String = UUID.randomUUID().toString().take(12)

    // ------------------------------------------------------------------ editing

    fun add(q: Qso) { io.execute { book.add(q); save(); if (settings?.wrlUdp == true) sendWrl(listOf(q)) } }
    fun update(q: Qso) { io.execute { book.replace(q); save() } }
    fun delete(id: String) { io.execute { book.remove(id); save() } }

    /** ADIF from a file (qFT8's export, another logger...). */
    fun import(ctx: Context, uri: Uri) {
        io.execute {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.ISO_8859_1) } ?: return@execute
                val (added, merged) = book.import(text) { newId() }
                save()
                status("ADIF read: $added new contacts, $merged already in the log")
            } catch (e: Exception) { status("ADIF not read: ${e.message}") }
        }
    }

    /** The log as ADIF for others (qFT8, WRL by hand, any logger): shared or saved where the user picks. */
    fun exportText(): String = book.adif(PROGRAM, version(), false)

    fun share(ctx: Context) {
        io.execute {
            try {
                val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
                val f = File(dir, "log_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".adi")
                f.writeText(exportText())
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, f.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(send, "Send the log (ADIF)").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { status("Cannot share: ${e.message}") }
        }
    }

    fun saveTo(ctx: Context, uri: Uri) {
        io.execute {
            try { ctx.contentResolver.openOutputStream(uri)?.use { it.write(exportText().toByteArray(Charsets.ISO_8859_1)) }; status("Log saved (${book.qsos.size} contacts)") }
            catch (e: Exception) { status("Not saved: ${e.message}") }
        }
    }

    // ------------------------------------------------------------------ uploads

    private fun canUpload(): Boolean {
        val s = settings ?: return false
        if (!Licence.isValid(s)) { status("Uploads need the callsign validated (Connect tab)"); return false }
        return true
    }

    private fun today(fmt: String) = SimpleDateFormat(fmt, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun http(url: String, body: String? = null, contentType: String = "application/x-www-form-urlencoded"): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000; c.readTimeout = 60_000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "$PROGRAM/${version()}")
        if (body != null) {
            c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", contentType)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream ?: c.inputStream).use { it.readBytes().toString(Charsets.ISO_8859_1) }
        c.disconnect()
        if (code >= 400) throw java.io.IOException("HTTP $code")
        return text
    }

    /** Sends to every service that is set up the contacts it has not had yet. */
    fun uploadAll() {
        io.execute {
            if (!canUpload()) return@execute
            val s = settings!!
            val out = ArrayList<String>()
            if (s.qrzKey.isNotBlank()) out += uploadQrz(s)
            if (AppState.lotwCert.value != null) out += uploadLotw(s)
            if (s.eqslUser.isNotBlank() && s.eqslPassword.isNotEmpty()) out += uploadEqsl(s)
            if (s.wrlUdp && s.wrlHost.isNotBlank()) { val p = book.qsos.filter { !it.wrlSent }; if (p.isNotEmpty()) sendWrl(p); out += "WRL: ${p.size} sent to ${s.wrlHost}" }
            status(if (out.isEmpty()) "No QSL service set up (Log > Services)" else out.joinToString(" · "))
        }
    }

    private fun uploadQrz(s: Settings): String {
        var ok = 0; var fail = ""
        for (q in book.qsos.filter { !it.qrzSent }) {
            try {
                val (good, msg) = QrzLog.insertResult(http(QrzLog.URL, QrzLog.insertBody(s.qrzKey, q)))
                if (good) { book.replace(q.with("QRZCOM_QSO_UPLOAD_STATUS" to "Y", "QRZCOM_QSO_UPLOAD_DATE" to today("yyyyMMdd"))); ok++ }
                else { fail = msg; if (msg.contains("key", true)) break }
            } catch (e: Exception) { fail = "QRZ: ${e.message}"; break }
        }
        save()
        return "QRZ: $ok sent" + if (fail.isNotEmpty()) " ($fail)" else ""
    }

    private fun uploadEqsl(s: Settings): String {
        var ok = 0; var fail = ""
        for (q in book.qsos.filter { !it.eqslSent && it.band.isNotEmpty() }) {
            try {
                when (Eqsl.uploadResult(http(Eqsl.uploadUrl(s.eqslUser, s.eqslPassword, s.eqslNick, q)))) {
                    Eqsl.Result.ADDED, Eqsl.Result.DUPLICATE -> { book.replace(q.with("EQSL_QSL_SENT" to "Y", "EQSL_QSLSDATE" to today("yyyyMMdd"))); ok++ }
                    Eqsl.Result.LOGIN -> { fail = "wrong user or password"; break }
                    Eqsl.Result.ERROR -> fail = "eQSL refused ${q.call}"
                }
            } catch (e: Exception) { fail = e.message ?: "error"; break }
        }
        save()
        return "eQSL: $ok sent" + if (fail.isNotEmpty()) " ($fail)" else ""
    }

    private fun uploadLotw(s: Settings): String {
        val pending = book.qsos.filter { !it.lotwSent }
        if (pending.isEmpty()) return "LoTW: nothing new"
        return try {
            val (key, cert) = loadP12(s.lotwP12Password)
            val info = certInfo() ?: return "LoTW: no certificate"
            val st = Lotw.Station(info.call, info.dxcc, Spotter.locator().take(6), s.lotwCqz, s.lotwItuz, s.lotwIota)
            val (tq8, ids) = Lotw.sign(pending, st, key, cert.encoded, "$PROGRAM ${version()}")
            if (ids.isEmpty()) return "LoTW: the contacts have no band"
            val boundary = "----alp" + System.currentTimeMillis()
            val c = URL(Lotw.UPLOAD).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000; c.readTimeout = 120_000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("User-Agent", "$PROGRAM/${version()}")
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.outputStream.use { o ->
                o.write("--$boundary\r\nContent-Disposition: form-data; name=\"upfile\"; filename=\"log.tq8\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                o.write(tq8)
                o.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val html = c.inputStream.use { it.readBytes().toString(Charsets.ISO_8859_1) }
            c.disconnect()
            val (ok, msg) = Lotw.uploadResult(html)
            if (ok) {
                for (q in pending.filter { it.id in ids }) book.replace(q.with("LOTW_QSL_SENT" to "Y", "LOTW_QSLSDATE" to today("yyyyMMdd")))
                save()
                "LoTW: ${ids.size} signed and sent"
            } else msg
        } catch (e: Exception) { "LoTW: ${e.message}" }
    }

    /** WRL (and anything else that listens to WSJT-X): "QSO Logged" and "Logged ADIF" for each contact, as WSJT-X sends them. */
    private fun sendWrl(qsos: List<Qso>) {
        val s = settings ?: return
        if (s.wrlHost.isBlank()) return
        try {
            DatagramSocket().use { sock ->
                val addr = InetAddress.getByName(s.wrlHost.trim())
                for (q in qsos) {
                    val on = q.minutes * 60_000
                    val f = q.freqMHz.toDoubleOrNull()?.let { (it * 1e6).toLong() } ?: 0L
                    val a = Spots.WsjtxUdp.qsoLogged(Spotter.UDP_ID, on, on, q.call, q["GRIDSQUARE"], f, q.modeLabel, q["RST_SENT"], q["RST_RCVD"],
                        q["TX_PWR"], q["COMMENT"], q["NAME"], q["STATION_CALLSIGN"], q["MY_GRIDSQUARE"])
                    val b = Spots.WsjtxUdp.loggedAdif(Spotter.UDP_ID, Adif.file(listOf(q.export()), PROGRAM, version()))
                    sock.send(DatagramPacket(a, a.size, addr, s.wrlPort)); sock.send(DatagramPacket(b, b.size, addr, s.wrlPort))
                    book.replace(q.with("APP_ALP_WRL_SENT" to "Y"))
                }
            }
            save()
        } catch (e: Exception) { status("WRL: ${e.message}") }
    }

    // ------------------------------------------------------------------ confirmations

    fun syncConfirmations() {
        io.execute {
            val s = settings ?: return@execute
            val out = ArrayList<String>()
            if (s.lotwUser.isNotBlank() && s.lotwPassword.isNotEmpty()) out += try {
                val since = s.lotwSince.ifEmpty { "1990-01-01" }
                val text = http(Lotw.reportUrl(s.lotwUser, s.lotwPassword, since, certInfo()?.call ?: ""))
                if (!text.contains("<EOH>", ignoreCase = true)) "LoTW: login refused"
                else { val n = book.confirm(text, Logbook.LOTW_CONFIRMED); s.lotwSince = today("yyyy-MM-dd"); "LoTW: $n confirmed" }
            } catch (e: Exception) { "LoTW: ${e.message}" }
            if (s.eqslUser.isNotBlank() && s.eqslPassword.isNotEmpty()) out += try {
                val html = http(Eqsl.INBOX, Eqsl.inboxBody(s.eqslUser, s.eqslPassword, s.eqslSince.ifEmpty { "19900101" }, s.eqslNick))
                val err = Eqsl.inboxError(html)
                val link = Eqsl.inboxLink(html)
                when {
                    err != null -> err
                    link == null -> "eQSL: nothing new"
                    else -> { val n = book.confirm(http(link), Logbook.EQSL_CONFIRMED); s.eqslSince = today("yyyyMMdd"); "eQSL: $n confirmed" }
                }
            } catch (e: Exception) { "eQSL: ${e.message}" }
            if (s.qrzKey.isNotBlank()) out += try {
                val m = QrzLog.parse(http(QrzLog.URL, QrzLog.fetchBody(s.qrzKey, s.qrzSince.ifEmpty { "1990-01-01" })))
                when {
                    m["RESULT"] == "OK" -> { val n = book.confirm(m["ADIF"] ?: "", Logbook.QRZ_CONFIRMED); s.qrzSince = today("yyyy-MM-dd"); "QRZ: $n confirmed" }
                    m["REASON"]?.contains("no log entries", true) == true || m["COUNT"] == "0" -> "QRZ: nothing new"
                    else -> "QRZ: ${m["REASON"] ?: m["RESULT"] ?: "?"}"
                }
            } catch (e: Exception) { "QRZ: ${e.message}" }
            save()
            status(if (out.isEmpty()) "No QSL service set up (Log > Services)" else out.joinToString(" · "))
        }
    }

    // ------------------------------------------------------------------ TrustedQSL certificate

    private fun bouncyCastle(): Provider? = try { Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider").getDeclaredConstructor().newInstance() as Provider } catch (_: Throwable) { null }

    private fun keyStore(bytes: ByteArray, password: String): KeyStore {
        var last: Exception? = null
        for (p in listOf<Provider?>(null, bouncyCastle())) {
            try {
                val ks = if (p == null) KeyStore.getInstance("PKCS12") else KeyStore.getInstance("PKCS12", p)
                ks.load(ByteArrayInputStream(bytes), password.toCharArray())
                return ks
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("PKCS12")
    }

    private fun loadP12(password: String, bytes: ByteArray? = null): Pair<PrivateKey, X509Certificate> {
        val b = bytes ?: File(app!!.filesDir, P12).readBytes()
        val ks = keyStore(b, password)
        val alias = ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) } ?: throw IllegalStateException("the file has no private key")
        val key = ks.getKey(alias, password.toCharArray()) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate
        val platformKey = try { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(key.encoded)) } catch (_: Exception) { key }
        return platformKey to cert
    }

    private fun certInfo(): Lotw12? {
        val c = app ?: return null
        if (!File(c.filesDir, P12).exists()) return null
        val cert = loadP12(settings!!.lotwP12Password).second
        val dn = cert.subjectX500Principal.getName("RFC2253")
        val call = Lotw.dnField(dn, Lotw.OID_CALL) ?: return null
        val dxcc = Lotw.dnField(dn, Lotw.OID_DXCC)?.toIntOrNull() ?: 0
        return Lotw12(call, dxcc, cert.notAfter)
    }

    /** The callsign certificate saved from TrustedQSL (.p12, with its private key). */
    fun importCertificate(ctx: Context, uri: Uri, password: String) {
        io.execute {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("cannot read the file")
                loadP12(password, bytes)
                File(ctx.filesDir, P12).writeBytes(bytes)
                settings!!.lotwP12Password = password
                AppState.lotwCert.value = certInfo()
                status("LoTW certificate: ${AppState.lotwCert.value?.call ?: "?"} (DXCC ${AppState.lotwCert.value?.dxcc ?: "?"})")
            } catch (e: Exception) { status("Certificate not loaded: ${e.message} (wrong password?)") }
        }
    }

    fun removeCertificate() { io.execute { app?.let { File(it.filesDir, P12).delete() }; AppState.lotwCert.value = null } }
}
