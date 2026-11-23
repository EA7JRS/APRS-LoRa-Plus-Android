/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.abs
import kotlin.math.roundToInt

private var failures = 0
private var checks = 0

private fun check(cond: Boolean, what: String) {
    checks++
    if (!cond) { failures++; println("FAIL: $what") }
}

private fun <T> eq(actual: T, expected: T, what: String) = check(actual == expected, "$what: expected <$expected> got <$actual>")

private fun near(actual: Double, expected: Double, tol: Double, what: String) =
    check(abs(actual - expected) <= tol, "$what: expected ~$expected got $actual")

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

private fun testKiss() {
    val data = bytes(0x01, Kiss.FEND, 0x02, Kiss.FESC, 0x03)
    val enc = Kiss.encode(data)
    eq(enc.toList(), bytes(0xC0, 0x00, 0x01, 0xDB, 0xDC, 0x02, 0xDB, 0xDD, 0x03, 0xC0).toList(), "KISS escapes")
    // fragmented delivery, two frames glued together and garbage before
    val stream = bytes(0x55, 0x66) + enc + Kiss.encode(bytes(9, 8, 7))
    val dec = Kiss.Decoder()
    val got = ArrayList<List<Byte>>()
    for (chunk in stream.toList().chunked(3)) for (f in dec.feed(chunk.toByteArray())) got.add(f.toList())
    eq(got.size, 2, "KISS number of frames")
    if (got.size == 2) { eq(got[0], data.toList(), "KISS frame 1"); eq(got[1], bytes(9, 8, 7).toList(), "KISS frame 2") }
    // frames of another command (parameter, 0x01) are dropped
    eq(Kiss.Decoder().feed(bytes(0xC0, 0x01, 0x20, 0xC0)).size, 0, "KISS parameter frame ignored")
    // invalid escape: the frame is dropped but the next one still works
    val d2 = Kiss.Decoder()
    eq(d2.feed(bytes(0xC0, 0x00, 0xDB, 0x41, 0xC0)).size, 0, "KISS invalid escape")
    eq(d2.feed(Kiss.encode(bytes(1, 2))).size, 1, "KISS recovers after invalid escape")
}

private fun testAx25() {
    val f = Ax25Frame.create("EA7JRS-7", "APLRT1", listOf("WIDE1-1", "WIDE2-1"), "!3711.50N/00356.20W>test")!!
    val enc = f.encode()
    // destination "APLRT1": 'A'<<1 = 0x82, C bit set in the SSID byte (0xE0)
    eq(enc[0].toInt() and 0xFF, 0x82, "AX.25 first byte of the destination")
    eq(enc[6].toInt() and 0xFF, 0xE0, "AX.25 destination SSID byte")
    eq(enc[13].toInt() and 0xFF, 0x60 or (7 shl 1), "AX.25 source SSID byte")
    val back = Ax25Frame.decode(enc)
    check(back == f, "AX.25 roundtrip")
    eq(back?.toTnc2(), "EA7JRS-7>APLRT1,WIDE1-1,WIDE2-1:!3711.50N/00356.20W>test", "TNC2 text")
    // repeated bit
    val rep = Ax25Frame(Ax25Address("APRS"), Ax25Address("N0CALL"), listOf(Ax25Address("WIDE1", 1, true), Ax25Address("WIDE2", 1)), "x".toByteArray())
    eq(Ax25Frame.decode(rep.encode())?.toTnc2(), "N0CALL>APRS,WIDE1-1*,WIDE2-1:x", "digipeater H bit")
    // KISS + AX.25 chain, as it arrives over BLE
    val over = Kiss.Decoder().feed(Kiss.encode(enc)).map { Ax25Frame.decode(it) }
    check(over.size == 1 && over[0] == f, "KISS + AX.25")
    check(Ax25Frame.decode(bytes(1, 2, 3)) == null, "AX.25 too short")
    // TNC2 text
    val t = Tnc2.parse("EA7JRS-7>APLRT1,WIDE1-1*:!3711.50N/00356.20W>x")
    eq(t?.source?.text(), "EA7JRS-7", "TNC2 source")
    eq(t?.path?.get(0)?.repeated, true, "TNC2 repeated")
    check(Tnc2.parse("garbage") == null, "TNC2 garbage")
}

private fun testAprs() {
    val p = Aprs.parse("!4903.50N/07201.75W-Test 001234") as AprsPacket.Position
    near(p.lat, 49.058333, 1e-5, "lat uncompressed")
    near(p.lon, -72.029167, 1e-5, "lon uncompressed")
    eq(p.symbolTable, '/', "symbol table"); eq(p.symbolCode, '-', "symbol code"); eq(p.comment, "Test 001234", "comment")
    val s = Aprs.parse("=3711.50S\\00356.20E>x") as AprsPacket.Position
    near(s.lat, -37.191667, 1e-5, "south"); near(s.lon, 3.936667, 1e-5, "east")
    val ts = Aprs.parse("@092345z4903.50N/07201.75W>") as AprsPacket.Position
    near(ts.lat, 49.058333, 1e-5, "with timestamp")
    val c = Aprs.parse("=/5L!!<*e7>7P[") as AprsPacket.Position          // example of the APRS 1.0.1 specification
    near(c.lat, 49.5, 0.01, "compressed lat"); near(c.lon, -72.75, 0.01, "compressed lon")
    check(Aprs.parse("!garbage") is AprsPacket.Other, "invalid position")

    val m = Aprs.parse(":EA7JRS-7 :Hello there{123") as AprsPacket.Message
    eq(m.addressee, "EA7JRS-7", "addressee"); eq(m.text, "Hello there", "text"); eq(m.msgNo, "123", "number")
    val a = Aprs.parse(":EA7JRS-7 :ack123") as AprsPacket.Message
    check(a.isAck && a.msgNo == "123", "ack")
    val r = Aprs.parse(":EA7JRS-7 :rej9") as AprsPacket.Message
    check(r.isRej && r.msgNo == "9", "rej")
    val b = Aprs.parse(":BLN1     :Bulletin") as AprsPacket.Message
    check(b.isBulletin && b.msgNo == null, "bulletin")
    eq(Aprs.encodeMessage("ea7jrs-7", "Hi", "5"), ":EA7JRS-7 :Hi{5", "message encoding")
    eq(Aprs.encodeAck("N0CALL", "42"), ":N0CALL   :ack42", "ack encoding")
    check(Aprs.messageTextError("a".repeat(68)) != null, "text too long")
    check(Aprs.messageTextError("a{b") != null, "invalid character")
    check(Aprs.messageTextError("Hola, que tal?") == null, "valid text")
}

private fun testMessaging() {
    // bulletins and groups, late retries, SSID meanings and path advice (2026-11-23)
    run {
        var tg = 0L
        val out = ArrayList<String>()
        val g = MessageEngine("EA7JRS-7", { out.add(it) }, { tg })
        val b = g.onMessage("EA7URC-10", Aprs.parse(":BLN1     :Net tonight 2100z{7") as AprsPacket.Message)!!
        eq(listOf(b.peer, b.text), listOf("BLN1", "EA7URC-10> Net tonight 2100z"), "bulletin shown under its addressee, with who sent it")
        check(out.isEmpty(), "a bulletin is never acknowledged, even with a number")
        eq(g.onMessage("EA7URC-10", Aprs.parse(":BLN1     :Net tonight 2100z") as AprsPacket.Message), null, "the same bulletin again: once")
        tg += 31 * 60_000
        check(g.onMessage("EA7URC-10", Aprs.parse(":BLN1     :Net tonight 2100z") as AprsPacket.Message) != null, "the same bulletin after 30 min: shown again")
        check(g.onMessage("N0CALL", Aprs.parse(":ALL      :QRV 433.775") as AprsPacket.Message)?.peer == "ALL", "message to ALL")
        check(g.onMessage("N0CALL", Aprs.parse(":NWS-WARN :Tornado warning") as AprsPacket.Message)?.peer == "NWS-WARN", "NWS message")
        eq(g.onMessage("EA7JRS-7", Aprs.parse(":BLN2     :mine") as AprsPacket.Message), null, "our own bulletin heard back is not shown")
        eq(g.onMessage("N0CALL", Aprs.parse(":EA7ZZZ   :private") as AprsPacket.Message), null, "a message for somebody else is still ignored")
        val sb = g.sendMessage("BLN3", "Test").getOrThrow()
        eq(listOf(out.last(), sb.status), listOf<Any>(":BLN3     :Test", MessageStatus.ACKED), "sending a bulletin: no number, no retries")
        var tl = 0L
        val outL = ArrayList<String>()
        val l = MessageEngine("EA7JRS-7", { outL.add(it) }, { tl }, MessageEngine.LORA_WAITS)
        l.sendMessage("N0CALL", "x")
        for (s in listOf(30, 90, 210, 510)) { tl = s * 1000L; l.tick() }
        eq(listOf(outL.size, l.tries, l.all()[0].status), listOf<Any>(5, 5, MessageStatus.PENDING), "LoRa schedule: 5 sends, the last at 8.5 min")
        tl = 1410_000L; l.tick()
        eq(l.all()[0].status, MessageStatus.FAILED, "LoRa schedule: given up at 23.5 min")
    }
    val AC = AprsConventions
    check(AC.isGroupAddressee("BLN0") && AC.isGroupAddressee("blnWX") && AC.isGroupAddressee("SKYWARN") && AC.isGroupAddressee("CQ") && !AC.isGroupAddressee("EA7JRS") && !AC.isGroupAddressee("CQ1AA"), "group addressees")
    eq(listOf(AC.groupName("BLN1"), AC.groupName("BLN1WX"), AC.groupName("BLNA"), AC.groupName("NWS-WARN"), AC.groupName("QST")), listOf("Bulletin", "Group bulletin WX", "Announcement", "Weather service", "To everybody"), "group names")
    eq(listOf(AC.ssidMeaning("EA7JRS"), AC.ssidMeaning("EA7JRS-9"), AC.ssidMeaning("EA7JRS-13"), AC.ssidMeaning("bad call!")), listOf("main fixed station", "main mobile", "weather station", ""), "SSID meanings")
    check(AC.oneWay("EA7JRS-12") && !AC.oneWay("EA7JRS-9"), "-12 is a one-way tracker")
    eq(listOf("", "WIDE1-1", "WIDE1-1,WIDE2-1", "WIDE2-2", "EA7URC-3,WIDE2-1").map { AC.pathAdvice(it).first }, List(5) { AprsConventions.PathLevel.OK }, "paths in use")
    eq(listOf("WIDE1-1,WIDE2-2", "WIDE2-1,WIDE1-1").map { AC.pathAdvice(it).first }, List(2) { AprsConventions.PathLevel.NOTE }, "paths worth a note")
    eq(listOf("RELAY,WIDE", "WIDE3-3", "WIDE2-3", "WIDE7-7", "TRACE2-2", "WIDE2", "WIDE1-1,WIDE2-2,WIDE2-1", "HELLO WORLD").map { AC.pathAdvice(it).first }, List(8) { AprsConventions.PathLevel.BAD }, "paths not to use")
    var t = 1_000_000L
    val sent = ArrayList<String>()
    val e = MessageEngine("EA7JRS-7", { sent.add(it) }, { t })
    check(e.sendMessage("N0CALL", "a{b").isFailure, "invalid text refused")
    check(e.sendMessage("???", "hi").isFailure, "invalid callsign refused")
    val m1 = e.sendMessage("n0call", "Hola").getOrThrow()
    eq(sent, listOf(":N0CALL   :Hola{1"), "first send")
    eq(m1.status, MessageStatus.PENDING, "pending")
    t += 29_000; check(e.tick().isEmpty(), "no retry before 30 s")
    t += 1_000; eq(e.tick().size, 1, "retry at 30 s"); eq(sent.size, 2, "second frame")
    // the ack arrives
    e.onMessage("N0CALL", Aprs.parse(":EA7JRS-7 :ack1") as AprsPacket.Message)
    eq(e.all()[0].status, MessageStatus.ACKED, "acknowledged")
    t += 600_000; check(e.tick().isEmpty(), "no more retries after the ack")

    // message that is never acknowledged: 3 sends and then FAILED
    val sent2 = ArrayList<String>()
    var t2 = 0L
    val e2 = MessageEngine("EA7JRS-7", { sent2.add(it) }, { t2 })
    e2.sendMessage("N0CALL", "x")
    repeat(10) { t2 += 30_000; e2.tick() }
    eq(sent2.size, 3, "three sends in total")
    eq(e2.all()[0].status, MessageStatus.FAILED, "failed")

    // reception: ack, one single copy, repeat gets an ack again
    val sent3 = ArrayList<String>()
    var t3 = 5L
    val e3 = MessageEngine("EA7JRS-7", { sent3.add(it) }, { t3 })
    val inc = Aprs.parse(":EA7JRS-7 :Buenas{77") as AprsPacket.Message
    check(e3.onMessage("EA1ABC", inc) != null, "new message")
    check(e3.onMessage("EA1ABC", inc) == null, "duplicate not shown")
    eq(sent3, listOf(":EA1ABC   :ack77", ":EA1ABC   :ack77"), "ack for each copy")
    check(e3.onMessage("EA1ABC", Aprs.parse(":OTHER    :Hi{1") as AprsPacket.Message) == null, "not for me")
    val noAck = e3.onMessage("EA1ABC", Aprs.parse(":EA7JRS-7 :sin numero") as AprsPacket.Message)
    check(noAck != null && sent3.size == 2, "message without a number: no ack")
    // rej
    val e4 = MessageEngine("EA7JRS-7", { }, { 0L })
    e4.sendMessage("N0CALL", "x")
    e4.onMessage("N0CALL", Aprs.parse(":EA7JRS-7 :rej1") as AprsPacket.Message)
    eq(e4.all()[0].status, MessageStatus.REJECTED, "rejected")

    // same callsign as the tracker: the tracker sends the ACK, the app does not (but still shows the message once)
    check(MessageEngine.trackerSendsAcks("EA7JRS-7", "ea7jrs-7"), "same callsign and SSID")
    check(MessageEngine.trackerSendsAcks("EA7JRS", "EA7JRS-0"), "SSID 0 is no SSID")
    check(!MessageEngine.trackerSendsAcks("EA7JRS-9", "EA7JRS-7"), "different SSID")
    check(!MessageEngine.trackerSendsAcks("EA7JRS-7", ""), "tracker callsign not set")
    val sent5 = ArrayList<String>()
    val e5 = MessageEngine("EA7JRS-7", { sent5.add(it) }, { 0L }, sendAcks = false)
    val inc5 = Aprs.parse(":EA7JRS-7 :Hola{12") as AprsPacket.Message
    check(e5.onMessage("EA1ABC", inc5) != null, "message shown without sending an ack")
    check(e5.onMessage("EA1ABC", inc5) == null, "repeat still removed")
    check(sent5.isEmpty(), "no ack sent when the tracker sends it")
    // TxCLR: stop the retries
    val sent6 = ArrayList<String>()
    var t6 = 0L
    val e6 = MessageEngine("EA7JRS-7", { sent6.add(it) }, { t6 })
    e6.sendMessage("N0CALL", "a"); e6.sendMessage("EA1ABC", "b")
    eq(e6.cancelPending("EA1ABC").size, 1, "only that peer stopped")
    t6 += 30_000; e6.tick()
    eq(sent6.size, 3, "N0CALL still retried, EA1ABC not")
    eq(e6.cancelPending().size, 1, "the rest stopped")
    t6 += 120_000; check(e6.tick().isEmpty(), "nothing left to send")
    eq(e6.all().count { it.status == MessageStatus.FAILED }, 2, "both end as failed")
}

private fun testIGate() {
    eq(IGate.passcode("N0CALL"), 13023, "APRS-IS passcode of N0CALL")
    eq(IGate.passcode("n0call-7"), 13023, "passcode ignores the SSID and the case")
    val g = IGate("EA7JRS-7")
    val f = Tnc2.parse("EA1ABC>APRS,WIDE1-1*:!4903.50N/07201.75W-x")!!
    eq(g.toIsLine(f), "EA1ABC>APRS,WIDE1-1*,qAO,EA7JRS-7:!4903.50N/07201.75W-x", "IS line (receive-only: qAO)")
    eq(g.toIsLine(f), "EA1ABC>APRS,WIDE1-1*,qAO,EA7JRS-7:!4903.50N/07201.75W-x", "no duplicate filter in an RX iGate (IGATE-HINTS)")
    eq(g.toIsLine(Tnc2.parse("EA1ABC>APRS,TCPIP*:!x")!!), null, "TCPIP path")
    eq(g.toIsLine(Tnc2.parse("EA1ABC>APRS,TCPXX:!x")!!), null, "TCPXX path")
    eq(g.toIsLine(Tnc2.parse("EA1ABC>APRS,NOGATE:!x")!!), null, "NOGATE")
    eq(g.toIsLine(Tnc2.parse("EA1ABC>APRS,RFONLY:!x")!!), null, "RFONLY")
    eq(g.toIsLine(Tnc2.parse("EA1ABC>APRS:?APRS?")!!), null, "generic query")
    // the bytes are not touched: trailing spaces, NUL, Mic-E binary bytes; cut only at the first CR / LF
    val raw = Ax25Frame(Ax25Address("APRS"), Ax25Address("EA1ABC", 9), emptyList(), byteArrayOf(0x60, 0x1C, 0x00, 0x7F, 0xB0.toByte(), 0x20, 0x20, 0x0D, 0x41))
    val rawLine = g.toIsLine(raw)!!
    eq(rawLine.toByteArray(Charsets.ISO_8859_1).takeLast(7).toList(), byteArrayOf(0x60, 0x1C, 0x00, 0x7F, 0xB0.toByte(), 0x20, 0x20).toList(), "binary bytes and trailing spaces kept, cut at CR")
    eq(IGate.firstLine("ab\ncd\re"), "ab", "first line ends at the first LF or CR")
    // 3rd party: the RF header and "}" stripped, inner packet gated; not if it came from the internet
    eq(g.toIsLine(Tnc2.parse("EA7XX-10>APRS,WIDE1-1:}EA2XYZ>APDR16,WIDE2-1,EA7XX-10*:!3700.00N/00600.00W>")!!),
        "EA2XYZ>APDR16,WIDE2-1,EA7XX-10*,qAO,EA7JRS-7:!3700.00N/00600.00W>", "third party: inner packet gated")
    eq(g.toIsLine(Tnc2.parse("EA7XX-10>APRS:}EA2XYZ>APRS,TCPIP,EA7XX-10*::EA7JRS-7 :hi{1")!!), null, "third party from APRS-IS (TCPIP)")
    eq(g.toIsLine(Tnc2.parse("EA7XX-10>APRS:}EA2XYZ>APRS,TCPXX*,qAR,EA1ZZ::EA7JRS-7 :hi")!!), null, "third party with TCPXX / q construct")
    eq(g.toIsLine(Tnc2.parse("EA7XX-10>APRS:}garbage")!!), null, "malformed third party")
    // IS2 reception metadata on a "#is2-meta" line (aprsc doc/IS2PLAIN.md), paired with the frame by the tracker's hash
    eq(RxMeta(-97.5, 8.2, RxMeta.MODEM_LORA_APRS, 293, 433775000).line(),
        "#is2-meta rx_rssi_dbm=-97.5 rx_snr_db=8.2 rx_modem=2 rx_bitrate=293 rx_frequency=433775000", "is2-meta line")
    eq(RxMeta(-110.0, null, RxMeta.MODEM_AX25, 1200).line(), "#is2-meta rx_rssi_dbm=-110 rx_modem=1 rx_bitrate=1200", "is2-meta: only the known values")
    eq(RxMeta().line(), null, "no metadata, no line")
    eq(RxMeta(rssiDbm = 0.0, modem = 0).line(), null, "values out of range left out")
    eq(RxMeta.hash(f), 3827310285L, "frame hash = FNV-1a of SOURCE:info, as the tracker computes it")
    val rf = Control.parse("""{"e":"rf","s":"rx","rssi":-97,"snr":7.5,"m":2,"br":293,"f":433775000,"h":3827310285}""") as ControlEvent.Rf
    val mm = RxMetaMatcher()
    mm.offer(rf.hash!!, rf.rxMeta(), 1000)
    eq(mm.take(Tnc2.parse("EA9ZZZ>APRS:!x")!!, 1500), null, "another frame does not take the metadata")
    eq(mm.take(f, 1600)?.line(), "#is2-meta rx_rssi_dbm=-97 rx_snr_db=7.5 rx_modem=2 rx_bitrate=293 rx_frequency=433775000", "rf event paired with its frame")
    eq(mm.take(f, 1700), null, "used once")
    mm.offer(rf.hash!!, rf.rxMeta(), 1000)
    eq(mm.take(f, 7000), null, "too old")
    eq((Control.parse("""{"e":"rf","s":"rx","rssi":-80,"snr":0,"m":1,"br":1200}""") as ControlEvent.Rf).rxMeta().line(),
        "#is2-meta rx_rssi_dbm=-80 rx_modem=1 rx_bitrate=1200", "AX.25 (AFSK): no SNR")
    eq(g.loginLine("r/37/-4/100"), "user EA7JRS-7 pass 21434 vers APRSLoRaPlusAndroid 0.1 filter r/37/-4/100", "login line")
}

private fun testGeoStations() {
    // a frame explained: parts, fields and the way it travelled (2026-11-23)
    run {
        val PE = PacketExplain
        val f = Tnc2.parse("EA7KKK-9>APLJRT,EA7URC-3*,WIDE1*,WIDE2-1:!/7Zf:Pq2l>7RQBat=4.02V")!!
        val pos = Aprs.parse(f.infoText, f.destination.call) as AprsPacket.Position
        eq(PE.parts(f, pos.comment).map { it.kind }, listOf(PacketExplain.Kind.SOURCE, PacketExplain.Kind.SEP, PacketExplain.Kind.DEST, PacketExplain.Kind.SEP, PacketExplain.Kind.PATH, PacketExplain.Kind.SEP, PacketExplain.Kind.DATA, PacketExplain.Kind.COMMENT), "packet parts")
        eq(PE.parts(f, pos.comment).joinToString("") { it.text }, f.toTnc2(), "the parts put together are the frame")
        val fl = PE.fields(f, 48, 62.0).associate { it.name to (it.value to it.note) }
        eq(fl["Source"], "EA7KKK-9" to "main mobile", "explained: source and its SSID")
        eq(fl["Device (tocall)"], "APLJRT" to "APRS LoRa+ tracker", "explained: device")
        eq(fl["Type"]?.first, "Position, compressed, no messaging", "explained: type")
        eq(fl["cs bytes"]?.first, "altitude (T = Q)", "explained: the cs bytes carry the altitude")
        eq(fl["Course / speed"], "not in the packet" to "~ 62 km/h, 048° from 2 positions", "explained: course and speed worked out")
        eq(fl["Comment"]?.first, "Bat=4.02V", "explained: comment")
        eq(PE.hops(f).map { it.who to it.role }, listOf("EA7KKK-9" to PacketExplain.Role.TX, "EA7URC-3" to PacketExplain.Role.DIGI), "hops: sender and the digipeater that repeated it")
        eq(PE.hops(f)[1].note, "digipeater · used WIDE1", "hops: the alias it used")
        eq(PE.pathSummary(f), "1 hop used, 1 left.", "path summary")
        eq(PE.pathSummary(Tnc2.parse("EA7KKK-9>APLJRT:>hi")!!), "No path: heard directly.", "path summary: direct")
        val t = Tnc2.parse("EA7GAT-10>APLRG1:}EA1ZZZ-7>APDR16,TCPIP,EA7GAT-10*::EA7JRS-7 :hola{12")!!
        eq(PE.hops(t).map { it.role }, listOf(PacketExplain.Role.TX, PacketExplain.Role.IS, PacketExplain.Role.IGATE), "hops of a third-party frame: station, APRS-IS, iGate")
        eq(PE.hops(t)[1].who to PE.hops(t)[1].note, "APRS-IS" to "came through the internet", "hops: APRS-IS")
        val tq = Tnc2.parse("EA7GAT-10>APLRG1:}EA1ZZZ-7>APDR16,TCPIP*,qAC,T2SPA::EA7JRS-7 :hola{12")!!
        eq(PE.hops(tq)[1].who to PE.hops(tq)[1].note, "T2SPA" to "came straight from a verified client", "hops: the q construct in words")
        val tf = PE.fields(t).associate { it.name to it.value }
        eq(listOf(tf["Sent to the radio by"], tf["Source"], tf["To"], tf["Text"], tf["Number"]), listOf("EA7GAT-10", "EA1ZZZ-7", "EA7JRS-7", "hola", "12"), "explained: a message from APRS-IS")
        eq(PE.fields(Tnc2.parse("EA7WX>APRS:!3631.80N/00617.02W_090/005g010t068h55b10132")!!).first { it.name == "Weather" }.note, "the course / speed field carries the wind", "explained: weather station")
        eq(PE.compressedCs("!3631.80N/00617.02W>"), null, "cs bytes: not a compressed position")
        eq(listOf(PE.device("APDR16"), PE.device("APXYZ")), listOf("APRSdroid", ""), "device names")
        val tb = StationTable()
        check(tb.onFrame(f, 1000L).lastFrame === f, "the station keeps its last frame")
        // the trail of a moving station
        var tr = Motion.trail(emptyList(), 36.5, -6.2, 0L)
        tr = Motion.trail(tr, 36.5001, -6.2, 30_000L)
        eq(tr.size, 1, "trail: 11 m is the same point")
        tr = Motion.trail(tr, 36.51, -6.2, 60_000L)
        tr = Motion.trail(tr, 36.52, -6.2, 120_000L)
        eq(tr.map { it.lat }, listOf(36.5, 36.51, 36.52), "trail: oldest first")
        eq(Motion.trail(tr, 36.53, -6.2, 4 * 3600_000L).map { it.lat }, listOf(36.53), "trail: points of more than 3 h go")
        eq(Motion.trail(tr, 46.0, -6.2, 180_000L).size, 1, "trail: a jump starts it again")
        var long = emptyList<TrackPoint>()
        for (k in 0 until 100) long = Motion.trail(long, 36.0 + k * 0.001, -6.0, k * 30_000L)
        eq(long.size to long.last().lat, Motion.TRAIL_POINTS to 36.0 + 99 * 0.001, "trail: 80 points at most, the newest kept")
        val tt = StationTable()
        tt.onFrame(Tnc2.parse("EA7MOV-9>APRS:!3630.00N/00612.00W>")!!, 0L)
        tt.onFrame(Tnc2.parse("EA7MOV-9>APRS:>status")!!, 20_000L)
        eq(tt.onFrame(Tnc2.parse("EA7MOV-9>APRS:!3630.60N/00612.00W>")!!, 60_000L).trail.size, 2, "the table keeps the trail")
    }
    // course and speed of a station that sends none, from two positions (2026-11-23)
    eq(Motion.between(null, null, null, 36.5, -6.2, 60_000), null, "motion: no previous position")
    eq(Motion.between(36.5, -6.2, 0, 36.5, -6.2, 5_000), null, "motion: too soon")
    eq(Motion.between(36.5, -6.2, 0, 36.6, -6.2, 3_600_000), null, "motion: too long ago")
    eq(Motion.between(36.5, -6.2, 0, 36.5001, -6.2, 60_000), null to 0.0, "motion: it has not moved 40 m")
    val mv = Motion.between(36.5, -6.2, 0, 36.509, -6.2, 60_000)!!          // 1 km north in a minute
    check(mv.first == 0 && Math.abs(mv.second - 60.0) < 0.5, "motion: 1 km north in 60 s = 60 km/h, course 0")
    val me = Motion.between(36.5, -6.2, 0, 36.5, -6.19, 120_000)!!
    check(me.first == 90 && Math.abs(me.second - 26.8) < 0.5, "motion: east")
    eq(Motion.between(36.5, -6.2, 0, 40.0, -6.2, 60_000), null, "motion: an impossible jump")
    near(Geo.distanceKm(40.4168, -3.7038, 41.3874, 2.1686), 505.0, 5.0, "Madrid-Barcelona")
    near(Geo.bearingDeg(0.0, 0.0, 1.0, 0.0), 0.0, 1e-6, "bearing north")
    near(Geo.bearingDeg(0.0, 0.0, 0.0, 1.0), 90.0, 1e-6, "bearing east")
    eq(Geo.cardinal16(0.0), "N", "N"); eq(Geo.cardinal16(90.0), "E", "E"); eq(Geo.cardinal16(359.0), "N", "N again"); eq(Geo.cardinal16(225.0), "SW", "SW")
    eq(Nav.googleNavigationUri(37.1915, -3.9367), "google.navigation:q=37.191500,-3.936700", "Google Maps URI")
    eq(Nav.geoUri(37.1915, -3.9367, "EA7JRS-7"), "geo:37.191500,-3.936700?q=37.191500,-3.936700(EA7JRS-7)", "geo URI")

    val st = StationTable(maxStations = 2)
    st.onFrame(Tnc2.parse("EA1AAA>APRS:!4000.00N/00300.00W>x")!!, 10)
    st.onFrame(Tnc2.parse("EA1BBB>APRS:>status without position")!!, 20)
    val again = st.onFrame(Tnc2.parse("EA1AAA>APRS:>now just a status")!!, 30)
    eq(again.packets, 2, "packet counter"); check(again.lat != null, "keeps the last known position")
    eq(again.lastPositionMs, 10L, "age of the position")
    st.onFrame(Tnc2.parse("EA1CCC>APRS:!4100.00N/00300.00W>x")!!, 40)
    eq(st.recent().map { it.call }, listOf("EA1CCC", "EA1AAA"), "limit of stations (the oldest goes out)")
    eq(st.nearest(40.0, -3.0).first().first.call, "EA1AAA", "nearest")
}

private fun testJson() {
    val o = Json.parseObject("""{"a":1,"b":[true,false,null],"c":"x\"y\\z\n\u00e9","d":{"e":-2.5e1}}""")!!
    eq(o.int("a"), 1, "JSON number")
    eq(o["b"], listOf(true, false, null), "JSON array")
    eq(o.str("c"), "x\"y\\z\né", "JSON string escapes")
    eq(o.obj("d")?.num("e"), -25.0, "JSON nested object and exponent")
    check(Json.parseObject("{\"a\":") == null, "truncated JSON refused")
    check(Json.parseObject("[1,2]") == null, "an array is not an object")
    eq(Json.encode(linkedMapOf("c" to "mode", "m" to 5, "t" to "a\"b", "x" to 2.5, "l" to listOf("p", 1))),
        """{"c":"mode","m":5,"t":"a\"b","x":2.5,"l":["p",1]}""", "JSON encode")
    val back = Json.parseObject(Json.encode(mapOf("t" to "tab\there\u0001")))!!
    eq(back.str("t"), "tab\there\u0001", "JSON round trip with control characters")
}

private fun testControl() {
    eq(Control.command("mode", "m" to 5, id = 7), "{\"c\":\"mode\",\"id\":7,\"m\":5}\n", "command line")
    eq(Control.command("info"), "{\"c\":\"info\"}\n", "command without fields")

    // the examples of docs/BLE_CONTROL.md of the firmware
    val info = Control.parse("""{"e":"info","v":1,"fw":"5.3.2","board":"ttgo_t_beam_s3_SUPREME_v3",
 "caps":{"nmea":true,"dapnet":true,"cw":true,"modes":true},
 "mode":0,"modes":[{"m":0,"n":"APRS LoRa"},{"m":1,"n":"APRS FSK 9600"},{"m":5,"n":"DAPNET pager"}],
 "profile":0,"profiles":[{"call":"EA7JRS-7","label":"Coche"}],
 "lora":0,"loras":[{"f":433775000,"sf":12,"cr":5,"fsk":false}],
 "cwMsgs":["CQ CQ DE {CALL}","..."]}""".replace("\n", ""))
    check(info is ControlEvent.Info, "info event")
    val i = (info as ControlEvent.Info).info
    eq(i.firmware, "5.3.2", "firmware version"); check(i.hasNmea && i.hasDapnet && i.canChangeMode, "capabilities")
    eq(i.modes.map { it.mode }, listOf(0, 1, 5), "available modes"); eq(i.modes[2].name, "DAPNET pager", "mode name")
    eq(i.profiles[0], BeaconProfile("EA7JRS-7", "Coche"), "beacon profile")
    eq(i.loras[0].freqHz, 433_775_000L, "LoRa frequency"); eq(i.loras[0].text, "433.775 MHz SF12 CR4/5", "LoRa profile text")
    eq(i.cwMessages.size, 2, "CW messages")
    eq(i.radio, "lora", "radio missing = lora"); eq(i.sa868, null, "no SA868 block")
    // LilyGO T-TWR Plus (radio FM SA868, APRS AFSK 1200)
    val ttwr = (Control.parse("""{"e":"info","v":1,"fw":"5.3.5","board":"lilygo_t_twr_plus","caps":{"radio":"sa868","modes":true},"sa868":{"rev":21,"fw":"NiceRF","band":"VHF","ok":true},"mode":0,"modes":[{"m":0,"n":"APRS AFSK 1200"}]}""") as ControlEvent.Info).info
    eq(ttwr.radio, "sa868", "T-TWR radio"); eq(ttwr.sa868, Sa868Info(21, "NiceRF", "VHF", true), "T-TWR SA868 info")
    eq(ttwr.sa868?.text, "SA868 VHF, Rev2.1, NiceRF firmware", "T-TWR SA868 text"); eq(ttwr.modes[0].name, "APRS AFSK 1200", "T-TWR mode name")
    eq(ttwr.hasM17, false, "no m17 cap = false")
    eq(ttwr.lastReset, "", "no rst = normal reset")
    eq((Control.parse("""{"e":"info","v":1,"fw":"5.4.1","board":"lilygo_t_twr_plus","pmuOff":"VSYS LOW","caps":{}}""") as ControlEvent.Info).info.pmuOff, "VSYS LOW", "info pmuOff")
    run {
        val elf = "dc77508fc77e2f229c7126e66f58611cb6bc2157c54d23d06765c2828ff0cf66"
        val i = (Control.parse("""{"e":"info","v":1,"fw":"5.4.1","board":"lilygo_t_twr_plus","elf":"$elf","caps":{}}""") as ControlEvent.Info).info
        eq(i.elfSha, elf, "info elf")
        eq((Control.parse("""{"e":"gnss","fix":true,"sats":9,"pps":true}""") as ControlEvent.Position).gnss.pps, true, "gnss pps")
        eq((Control.parse("""{"e":"gnss","fix":false}""") as ControlEvent.Position).gnss.pps, null, "gnss without pps = null")
        val rel = FirmwareSite.parse("""{"version":"5.4.1","size":1500000,"sha256":"${"a".repeat(64)}","elfSha":"$elf","url":"firmware/x/firmware.bin","ota":true}""")
        eq(rel?.elfSha, elf, "ota json elfSha")
        eq(FirmwareSite.origin("5.4.1", elf, rel), FirmwareSite.Origin.OFFICIAL, "same build = OFFICIAL")
        eq(FirmwareSite.origin("5.4.1", "b".repeat(64), rel), FirmwareSite.Origin.CUSTOM, "other build of the same version = CUSTOM")
        eq(FirmwareSite.origin("5.4.0", elf, rel), FirmwareSite.Origin.UNKNOWN, "other version = UNKNOWN")
        eq(FirmwareSite.origin("5.4.1", "", rel), FirmwareSite.Origin.UNKNOWN, "tracker without elf = UNKNOWN")
        eq(FirmwareSite.origin("5.4.1", elf, rel?.copy(elfSha = null)), FirmwareSite.Origin.UNKNOWN, "site without elfSha = UNKNOWN")
    }
    (Control.parse("""{"e":"info","v":1,"fw":"5.4.1","board":"lilygo_t_twr_plus","rst":"TASK WDT","rstAt":"mode 11, 12 s","rstTask":"afskRx / loopTask","caps":{}}""") as ControlEvent.Info).info.let {
        eq(it.lastResetAt, "mode 11, 12 s", "info rstAt"); eq(it.lastResetTasks, "afskRx / loopTask", "info rstTask")
    }
    eq((Control.parse("""{"e":"sensors","charging":false,"chg":"stop"}""") as ControlEvent.SensorData).sensors.chargerState, "stop", "sensors chg = charger state")
    run {
        val bs = (Control.parse("""{"e":"sensors","bst":"discharging","bLeftH":5.5,"bPctH":-9.4,"bSave":true}""") as ControlEvent.SensorData).sensors
        eq(bs.batteryState, "discharging", "sensors bst")
        eq(bs.batteryHoursLeft, 5.5, "sensors bLeftH")
        eq(bs.batterySaving, true, "sensors bSave")
        val sky = (Control.parse("""{"e":"sky","s":[["G",5,45,310,38,1,3],["E",16,55,330,42,1]]}""") as ControlEvent.Sky).sky
        eq(sky.sats[0].l5, true, "sky bands: L5")
        eq(sky.sats[1].bands, null, "sky without bands (older firmware)")
    }
    eq((Control.parse("""{"e":"info","v":1,"fw":"5.4.0","board":"lilygo_t_twr_plus","rst":"LOW VOLTAGE","caps":{}}""") as ControlEvent.Info).info.lastReset, "LOW VOLTAGE", "info rst = abnormal last reset")
    val m17 = (Control.parse("""{"e":"info","v":1,"fw":"5.3.5","board":"lilygo_t_twr_plus","caps":{"radio":"sa868","modes":true,"m17":true},"sa868":{"rev":20,"fw":"OpenRTX","band":"UHF","ok":true,"modem":"M17","rx":433475000,"tx":433475000,"m17":433475000},"mode":10,"modes":[{"m":0,"n":"APRS AFSK 1200"},{"m":1,"n":"APRS FSK 9600"},{"m":10,"n":"M17"}]}""") as ControlEvent.Info).info
    eq(m17.hasM17, true, "T-TWR m17 cap"); eq(m17.sa868?.m17Hz, 433_475_000L, "M17 frequency")
    eq(m17.sa868?.channel, "M17, 433.4750 MHz", "SA868 channel text"); eq(TrackerMode.name(10), "M17", "M17 mode name"); eq(TrackerMode.isAprs(10), true, "M17 is an APRS mode")
    eq(Sa868Info(21, "NiceRF", "VHF", true, "AFSK 1200", 144_800_000, 145_000_000).channel, "AFSK 1200, RX 144.8000 / TX 145.0000 MHz", "SA868 split channel")
    // FM voice mode (T-TWR)
    val fmEv = Control.parse("""{"e":"fm","f":145650000,"tx":145050000,"sh":-1,"ofs":600000,"tm":1,"t":885,"d":23,"inv":false,"w":false,"hp":true,"m":true,"i":3,"n":"EA7RCE","step":12500,"sq":2,"sqm":1,"sqa":true,"nr":2,"edit":0,"busy":true,"ptt":false,"txs":0,"rssi":-97,"scan":0,"arts":2,"eai":0,"aprs":true,"qsyb":true,"tok":true,"spk":true}""") as ControlEvent.Fm
    check(fmEv.state.sat == null, "fm event without satellite")
    val fmSat = Control.parse("""{"e":"fm","f":145826100,"tx":145826100,"sh":0,"ofs":0,"tm":0,"t":885,"d":23,"inv":false,"w":true,"hp":true,"m":false,"i":0,"sat":{"n":"ISS (ZARYA)","id":25544,"tx":1,"f":145826100,"up":145824000,"dop":1100,"fine":0,"dok":true,"xup":false,"aprs":true,"tone":0.0,"sent":2,"heard":5,"az":123.4,"el":34.5,"txok":false,"why":"SAT TX off (WebUI)"}}""") as ControlEvent.Fm
    val fsat = fmSat.state.sat
    check(fsat != null && fsat.aprs && fsat.dopplerHz == 1100 && fsat.upHz == 145_824_000L && fsat.elDeg == 34.5 && !fsat.txOk && fsat.why == "SAT TX off (WebUI)", "fm event with satellite")
    eq(fmEv.state.channel.freqText, "145.6500", "FM frequency"); eq(fmEv.state.channel.shiftText, "-0.600 MHz", "FM shift"); eq(fmEv.state.channel.toneText, "T 88.5", "FM tone")
    eq(fmEv.state.channel.name, "EA7RCE", "FM memory name"); eq(fmEv.state.memIndex, 3, "FM memory index"); eq(fmEv.state.artsText, "ARTS: IN RANGE", "ARTS state")
    eq(fmEv.state.squelchAuto && fmEv.state.speakerFromEsp && fmEv.state.noiseReduction == 2, true, "FM squelch / NR")
    val mems = Control.parse("""{"e":"fmmems","max":32,"mem":[{"i":0,"n":"S20","f":145500000,"sh":0,"ofs":600000,"tm":0,"t":885,"d":23,"inv":false,"w":false,"hp":false},{"i":5,"n":"RPT","f":439050000,"sh":-1,"ofs":7600000,"tm":3,"t":885,"d":754,"inv":true,"w":true,"hp":true}]}""") as ControlEvent.FmMemories
    eq(mems.items.size, 2, "FM memories"); eq(mems.items[1].index, 5, "FM memory slot"); eq(mems.items[1].toneText, "DCS 754I", "FM DCS text")
    eq(FmQsy.announcedMHz(null, "146.940MHz T100 -060 Club"), 146.94, "QSY in comment"); eq(FmQsy.announcedMHz(null, "146.82 MHz T107"), 146.82, "QSY with space")
    eq(FmQsy.announcedMHz("146.94-R", "T100 -060"), 146.94, "QSY in object name"); eq(FmQsy.announcedMHz("EA7JRS-7", "Hola 145.5"), null, "no QSY")
    eq(FmQsy.inBand(145.65, false) && !FmQsy.inBand(439.05, false) && FmQsy.inBand(439.05, true), true, "QSY band")
    eq(TrackerMode.name(11), "FM voice", "FM mode name"); eq(TrackerMode.isAprs(11), true, "FM is an APRS mode")
    val bc = Control.parse("""{"e":"bcast","chip":"Si4735","on":true,"band":0,"f":101500,"vol":9,"mute":false,"mono":false,"rssi":45,"snr":22,"st":true,"seek":false,"ps":"RNE R 3 ","rt":"Hoy en Radio 3","pi":57873,"ct":"2026-11-09 13:05","ctOff":2,"bands":[0,1,2,3],"presets":[101500,999,0,0,0,0,0,0,0,0]}""") as ControlEvent.Bcast
    eq(bc.state.freqText, "101.50 MHz", "broadcast frequency"); eq(bc.state.station, "RNE R 3", "RDS name"); eq(bc.state.bands.size, 4, "Si4735 bands")
    eq(BcastState.freqText(bc.state.presets[1]), "999 kHz", "AM preset text")

    val st = Control.parse("""{"e":"status","mode":0,"profile":0,"lora":0,"digi":false,"smart":true,"sos":false,"batt":4.05,"up":3600,"bleKiss":true}""")
    eq((st as ControlEvent.Status).status, TrackerStatus(0, 0, 0, false, true, false, 4.05, 3600, true), "status event")

    val se = Control.parse("""{"e":"sensors","batt":4.05,"temp":21.4,"hum":55.0,"press":1013.2,"qnh":1015.0,"baroAlt":512.3,"heading":87.0,"moving":false,"imuTemp":30.1}""")
    val sensors = (se as ControlEvent.SensorData).sensors
    eq(sensors.temperatureC, 21.4, "temperature"); eq(sensors.qnhHpa, 1015.0, "QNH"); eq(sensors.boardTemperatureC, null, "missing field is null")

    val gn = Control.parse("""{"e":"gnss","fix":true,"lat":37.191512,"lon":-3.936701,"alt":512.0,"spd":0.0,"crs":0.0,"sats":11,"hdop":0.8,"age":420,"utc":"2026-11-05T07:45:00Z"}""")
    val g = (gn as ControlEvent.Position).gnss
    check(g.fix && g.satellites == 11 && g.lat == 37.191512 && g.lon == -3.936701, "GNSS event")

    val dh = Control.parse("""{"e":"dapnet","items":[{"ric":12345,"f":3,"own":true,"bad":false,"age":42,"rssi":-97,"t":"Texto del aviso"}]}""")
    eq((dh as ControlEvent.DapnetHistory).pages, listOf(Page(12345, 3, true, false, 42, -97, "Texto del aviso")), "DAPNET history")
    val pg = Control.parse("""{"e":"page","ric":8,"f":3,"own":false,"bad":true,"age":0,"rssi":-110,"t":"DB0ABC"}""")
    eq((pg as ControlEvent.NewPage).page.ric, 8L, "new page")

    eq(Control.parse("""{"e":"ok","c":"mode","id":7}"""), ControlEvent.Ok("mode", 7), "ok reply")
    eq(Control.parse("""{"e":"err","c":"lora","msg":"only in APRS modes"}"""), ControlEvent.Error("lora", null, "only in APRS modes"), "error reply")
    check(Control.parse("garbage") is ControlEvent.Unknown, "garbage is unknown")

    // full sensors event
    check((Control.parse("""{"e":"sensors","qnh":1016.0,"qs":"net"}""") as ControlEvent.SensorData).sensors.qnhSource == "net", "sensors qnh source")
    (Control.parse("""{"e":"sensors","net":{"src":"OWM","p":1016.0,"fc":"Rain likely","d3":-2.1}}""") as ControlEvent.SensorData).sensors.let {
        check(it.netForecast == "Rain likely" && it.netDelta3h == -2.1, "sensors net forecast")
    }
    val fs = (Control.parse("""{"e":"sensors","batt":4.1,"battPct":87,"gas":120.3,"iaq":42,"iaqLabel":"Good","wxSensor":"BME680","accel":[0.01,0.02,1.0],"gyro":[0,0,0],"flat":true,"charging":true,"vbus":5.1,"pmuTemp":31.5,"battMa":-120,"heap":150000}""") as ControlEvent.SensorData).sensors
    eq(fs.batteryPct, 87, "battPct"); eq(fs.accelG?.size, 3, "accel"); eq(fs.charging, true, "charging"); eq(fs.iaqLabel, "Good", "iaq label"); eq(fs.humidityPct, null, "absent is null"); eq(fs.freeHeap, 150000L, "heap")

    // tracker messages, heard stations, screen, actions, config pieces
    val tm = Control.parse("""{"e":"msgs","tot":2,"unread":1,"from":0,"items":[{"i":0,"d":"R","ep":1700000000,"st":0,"c":"EA1AAA","t":"hola"},{"i":1,"d":"T","ep":1,"st":4,"ack":12,"c":"EA1BBB","t":"adiós"}]}""") as ControlEvent.Messages
    eq(tm.items.size, 2, "msgs items"); check(tm.items[0].incoming && !tm.items[1].incoming, "msg direction"); eq(tm.items[1].state, 4, "msg state"); eq(tm.items[1].ackId, 12, "msg ack")
    val mc = Control.parse("""{"e":"msg","tot":3,"unread":2,"i":0,"d":"R","ep":5,"st":0,"c":"EA1CCC","t":"nuevo"}""") as ControlEvent.MessageChanged
    eq(mc.newest?.call, "EA1CCC", "msg change newest"); eq(mc.unread, 2, "msg change unread")
    eq((Control.parse("""{"e":"msg","tot":0,"unread":0}""") as ControlEvent.MessageChanged).newest, null, "msg change without messages")
    val hd = Control.parse("""{"e":"heard","tot":1,"pkts":9,"from":0,"items":[{"c":"EA1WX","sym":"_/","k":"WX","lat":40.1,"lon":-3.2,"alt":600,"age":30,"n":4,"dev":"X","wx":{"t":21,"h":50}}]}""") as ControlEvent.Heard
    eq(hd.items.single().call, "EA1WX", "heard call"); eq(hd.items.single().wx?.int("t"), 21, "heard wx"); eq(hd.items.single().path, null, "heard path absent")
    val sc = (Control.parse("""{"e":"screen","p":1,"cur":1,"t":"MESSAGES","r":"","age":120,"on":true,"rows":["a","","c"]}""") as ControlEvent.Screen).screen
    eq(sc.rows, listOf("a", "", "c"), "screen rows"); eq(sc.title, "MESSAGES", "screen title")
    val ap = (Control.parse("""{"e":"actlist","p":2,"pt":"MORSE","items":[{"n":"CW0","l":"CW 1"},{"n":"CW_LISTEN","l":"Listen","ok":false}]}""") as ControlEvent.Actions).page
    eq(ap.actions.map { it.allowed }, listOf(true, false), "actions allowed"); eq(ap.title, "MORSE", "actions page")
    eq(Control.parse("""{"e":"actlist","end":true,"pages":7}"""), ControlEvent.ActionsEnd(7), "actions end")
    eq(Control.parse("""{"e":"cfg","o":320,"n":900,"b":300,"d":"{\"x\":1}"}"""), ControlEvent.ConfigPiece(320, 900, 300, "{\"x\":1}"), "config piece")

    // chunked upload: every line fits, the text comes back whole (accents, emoji, quotes)
    val big = Json.encode(mapOf("beacons" to (1..40).map { mapOf("callsign" to "EA7JRS-$it", "comment" to "ñandú \uD83D\uDE00 \"q\"") }))
    val lines = Control.configSet(big, reboot = false)
    check(lines.size > 10, "long config is split (${lines.size} lines)")
    check(lines.all { it.toByteArray(Charsets.UTF_8).size <= Control.MAX_LINE_SAFE + 1 }, "every line within the limit")
    eq(lines.first(), Control.command("cfgset", "s" to "begin", "n" to big.toByteArray(Charsets.UTF_8).size, "reboot" to false), "begin line")
    eq(lines.last(), Control.command("cfgset", "s" to "end"), "end line")
    eq(lines.drop(1).dropLast(1).joinToString("") { Json.parseObject(it)!!.str("d")!! }, big, "chunks rebuild the config")

    // lines split across notifications, two in one, UTF-8 split in the middle of a character
    val la = LineAssembler()
    val whole = "{\"e\":\"page\",\"t\":\"señal\"}\n{\"e\":\"ok\",\"c\":\"x\"}\r\n".toByteArray(Charsets.UTF_8)
    val out = ArrayList<String>()
    for (k in whole.indices step 5) out += la.feed(whole.copyOfRange(k, minOf(whole.size, k + 5)))
    eq(out, listOf("{\"e\":\"page\",\"t\":\"señal\"}", "{\"e\":\"ok\",\"c\":\"x\"}"), "line assembler")
    eq((Control.parse(out[0]) as ControlEvent.NewPage).page.text, "señal", "UTF-8 across chunks")
}

private fun testJsonEdit() {
    val root = Json.parse("""{"a":{"x":1,"y":[10,20]},"b":"t","c":true,"d":1.5}""")
    eq(Json.encode(JsonEdit.set(root, listOf("a", "y", 1), 99)), """{"a":{"x":1,"y":[10,99]},"b":"t","c":true,"d":1.5}""", "set in a list")
    eq(Json.encode(JsonEdit.set(root, listOf("a", "z"), "n")), """{"a":{"x":1,"y":[10,20],"z":"n"},"b":"t","c":true,"d":1.5}""", "new key")
    eq(Json.encode(root), """{"a":{"x":1,"y":[10,20]},"b":"t","c":true,"d":1.5}""", "original untouched")
    eq(JsonEdit.typed(5.0, "7"), 7L, "integer field"); eq(JsonEdit.typed(1.5, "2"), 2.0, "decimal field accepts an integer"); eq(JsonEdit.typed(1.5, "2.25"), 2.25, "decimal field")
    eq(JsonEdit.typed(7.0, "abc"), null, "not a number"); eq(JsonEdit.typed(true, "FALSE"), false, "boolean field"); eq(JsonEdit.typed("a", " b"), " b", "text keeps spaces")
    check(JsonEdit.isSecret("wifiPassword") && JsonEdit.isSecret("passcode") && !JsonEdit.isSecret("callsign"), "secret keys")
}

private fun testDapnet() {
    eq(Dapnet.names("EA7ABC, ea1xyz-9 dl1aaa;EA7ABC"), listOf("ea7abc", "ea1xyz", "dl1aaa"), "DAPNET names")
    eq(Dapnet.check("hola", listOf("ea7abc"), listOf("all")), null, "valid page")
    eq(Dapnet.groups("EA-All, dl-ns ea-all"), listOf("ea-all", "dl-ns"), "transmitter groups keep hyphens")
    check(Dapnet.check("hola", listOf("ea7abc"), listOf("ea all!")) != null, "invalid group")
    check(Dapnet.check("x".repeat(81), listOf("ea7abc"), listOf("all")) != null, "page too long")
    check(Dapnet.check("hola", emptyList(), listOf("all")) != null, "no recipient")
    check(Dapnet.check("señal", listOf("ea7abc"), listOf("all")) != null, "non ASCII text")
    eq(Dapnet.body("Hi \"x\"", listOf("ea7abc"), listOf("ea-all")),
        """{"text":"Hi \"x\"","callSignNames":["ea7abc"],"transmitterGroupNames":["ea-all"],"emergency":false}""", "DAPNET body")
    eq(Dapnet.authorization("EA7JRS", "secret"), "Basic ZWE3anJzOnNlY3JldA==", "basic auth (user in lower case)")
}

private fun testNmea() {
    check(Nmea.valid("${'$'}GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\r\n"), "known GGA checksum")
    check(!Nmea.valid("${'$'}GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*48"), "bad checksum")
    eq(Nmea.type("!AIVDM,1,1,,A,B5NJ;PP005l4ot5Isbl03wsUkP06,0*76"), "VDM", "sentence type")
    eq(Nmea.utcParts("2026-11-05T07:45:09Z"), Pair("074509.00", "051126"), "UTC parts")
    val g = Gnss(true, 37.191512, -3.936701, 512.0, 18.52, 90.0, 11, 0.8, 420, "2026-11-05T07:45:09Z")
    val rmc = Nmea.rmc(g)!!
    eq(rmc.substringBefore('*'), "${'$'}GPRMC,074509.00,A,3711.4907,N,00356.2021,W,10.0,90.0,051126,,,A", "RMC")
    check(Nmea.valid(rmc), "RMC checksum")
    val gga = Nmea.gga(g)!!
    eq(gga.substringBefore('*'), "${'$'}GPGGA,074509.00,3711.4907,N,00356.2021,W,1,11,0.8,512.0,M,,M,,", "GGA")
    check(Nmea.valid(gga), "GGA checksum")
    eq(Nmea.rmc(g.copy(lat = null)), null, "no RMC without position")
}

private fun testCallsignCheck() {
    eq(CallsignCheck.base("ea7jrs-7"), "EA7JRS", "base callsign")
    eq(CallsignCheck.base("EA7JRS"), "EA7JRS", "no SSID")
    eq(CallsignCheck.base("2E0ABC"), "2E0ABC", "digit+letter prefix")
    eq(CallsignCheck.base("K1A"), "K1A", "short US callsign")
    eq(CallsignCheck.base("N0CALL-7"), null, "N0CALL refused")
    eq(CallsignCheck.base("HELLO"), null, "no digit")
    eq(CallsignCheck.base("EA7"), null, "no suffix")
    eq(CallsignCheck.url("EA7JRS"), "https://database.radioid.net/api/dmr/user/?callsign=EA7JRS", "RadioID URL")
    val json = """{"count":2,"results":[{"callsign":"EA7JRS","city":"X","country":"Spain","fname":"Manuel","id":2147001,"surname":"Canca","state":"Y"},
        {"callsign":"EA7JRS","country":"Spain","fname":"Manuel","id":2147002,"surname":"Canca"}]}""".replace("\n", "")
    val r = CallsignCheck.parse(json, "EA7JRS")!!
    check(r.found, "found in RadioID"); eq(r.ids, listOf(2147001L, 2147002L), "DMR IDs"); eq(r.name, "Manuel Canca", "name"); eq(r.country, "Spain", "country")
    check(!CallsignCheck.parse("""{"count":1,"results":[{"callsign":"EA7JRT","id":1}]}""", "EA7JRS")!!.found, "other callsign is not a match")
    check(!CallsignCheck.parse("""{"count":0,"results":[]}""", "EA7JRS")!!.found, "not found")
    eq(CallsignCheck.parse("<html>", "EA7JRS"), null, "bad answer")
}

/** Runs a keyer for [ms] milliseconds with paddle functions of time; returns the key-down intervals. */
private fun runKeyer(k: IambicKeyer, ms: Long, dit: (Long) -> Boolean, dah: (Long) -> Boolean): List<Pair<Long, Long>> {
    val marks = ArrayList<Pair<Long, Long>>()
    var down = -1L
    for (t in 0..ms) {
        val on = k.update(t, dit(t), dah(t))
        if (on && down < 0) down = t
        if (!on && down >= 0) { marks.add(down to t); down = -1 }
    }
    return marks
}

private fun testCw() {
    eq(Morse.pattern('k'), "-.-", "Morse pattern"); eq(Morse.char("...--"), '3', "Morse char"); eq(Morse.ditMs(20), 60L, "dit at 20 WPM")
    // 20 WPM: dit 60 ms, dah 180 ms, space 60 ms
    val a = IambicKeyer(20, KeyerMode.IAMBIC_A)
    eq(runKeyer(a, 300, { it < 250 }, { false }), listOf(0L to 60L, 120L to 180L, 240L to 300L), "held dit paddle: dits")
    val b = IambicKeyer(20, KeyerMode.IAMBIC_A)
    eq(runKeyer(b, 400, { false }, { it < 10 }), listOf(0L to 180L), "tap dah: one dah")
    val c = IambicKeyer(20, KeyerMode.IAMBIC_A)
    eq(runKeyer(c, 600, { it < 400 }, { it < 400 }), listOf(0L to 60L, 120L to 300L, 360L to 420L), "squeeze held 400 ms: dit dah dit, exact timing")
    // squeeze and release both during the first dah: B adds the opposite element, A stops
    val sqB = runKeyer(IambicKeyer(20, KeyerMode.IAMBIC_B), 600, { it < 150 }, { it < 150 })
    eq(sqB.map { it.second - it.first }, listOf(60L, 180L, 60L), "iambic B: extra dit after release")
    val sqA = runKeyer(IambicKeyer(20, KeyerMode.IAMBIC_A), 600, { it < 150 }, { it < 150 })
    eq(sqA.map { it.second - it.first }, listOf(60L, 180L), "iambic A: no extra element")
    val quick = runKeyer(IambicKeyer(20, KeyerMode.IAMBIC_B), 400, { it < 20 }, { false })
    eq(quick.size, 1, "iambic B: a quick dit is one dit (own paddle not remembered)")
    eq(runKeyer(IambicKeyer(20, KeyerMode.STRAIGHT), 100, { it in 10..40 }, { false }), listOf(10L to 41L), "straight key passes through")

    // decoder: "CQ" at 20 WPM, then a word space
    val d = CwDecoder(20)
    var t = 0L
    val sb = StringBuilder()
    fun send(p: String) {
        for (e in p) { d.keyDown(t); t += if (e == '.') 60 else 180; d.keyUp(t); t += 60; sb.append(d.tick(t)) }
        t += 120
        sb.append(d.tick(t))
    }
    send("-.-."); send("--.-")
    t += 400; sb.append(d.tick(t))
    eq(sb.toString(), "CQ ", "decoder")
    // a slower straight key (12 WPM, dit 100 ms) is followed after a few elements
    val slow = CwDecoder(20)
    var u = 0L
    val sb2 = StringBuilder()
    for (p in listOf("....", ".", ".-..", ".-..", "---")) {
        for (e in p) { slow.keyDown(u); u += if (e == '.') 100 else 300; slow.keyUp(u); u += 100; sb2.append(slow.tick(u)) }
        u += 250; sb2.append(slow.tick(u))
    }
    eq(sb2.toString(), "HELLO", "decoder adapts to a slower operator")

    eq(KeyboardAdapter.line(113), KeyLine.DIT, "Left Ctrl = dit"); eq(KeyboardAdapter.line(114), KeyLine.DAH, "Right Ctrl = dah")
    eq(KeyboardAdapter.line(71), KeyLine.DIT, "[ = dit"); eq(KeyboardAdapter.line(29), null, "other key")

    val mp = MidiKeyParser()
    eq(mp.feed(bytes(0x90, 0x01, 0x7F, 0x80, 0x01, 0x00)), listOf(KeyLine.DIT to true, KeyLine.DIT to false), "MIDI dit on/off")
    eq(mp.feed(bytes(0x90, 0x02, 0x7F, 0xF8, 0x02, 0x00)), listOf(KeyLine.DAH to true, KeyLine.DAH to false), "running status, vel 0 = off, clock byte ignored")
    eq(mp.feed(bytes(0xB0, 0x01, 0x32, 0x90, 0x00, 0x7F)), listOf(KeyLine.STRAIGHT to true), "control change skipped, note 0 = timed key")
    eq(mp.feed(bytes(0xC0, 0x08, 0x90, 0x05, 0x7F)), emptyList<Pair<KeyLine, Boolean>>(), "program change skipped, other notes ignored")

    eq(Control.command("k", "t" to 123456L, "d" to 1), "{\"c\":\"k\",\"t\":123456,\"d\":1}\n", "key event line starts as the firmware expects")
}

private fun testFirmware() {
    val sha = "ab".repeat(32)
    val r = FirmwareSite.parse("""{"version":"5.3.3","size":1834016,"sha256":"${sha.uppercase()}","url":"firmware/ttgo_t_beam_s3_SUPREME_v3/firmware.bin","ota":true}""")!!
    eq(r.url, "https://ea7jrs.github.io/APRS-LoRa-Plus/firmware/ttgo_t_beam_s3_SUPREME_v3/firmware.bin", "relative URL made absolute")
    eq(r.sha256, sha, "SHA-256 in lower case"); check(r.secondSlot && r.size == 1834016L, "size and slot")
    eq(FirmwareSite.parse("""{"version":"5.3.3","size":1834016,"sha256":"xyz","url":"a"}"""), null, "bad SHA refused")
    eq(FirmwareSite.manifestUrl("ttgo_t_beam_s3_SUPREME_v3"), "https://ea7jrs.github.io/APRS-LoRa-Plus/ota/ttgo_t_beam_s3_SUPREME_v3.json", "manifest URL")
    check(FirmwareSite.isNewer("5.3.10", "5.3.2"), "5.3.10 newer than 5.3.2 (numeric)")
    check(!FirmwareSite.isNewer("5.3.2", "5.3.2"), "same version"); check(!FirmwareSite.isNewer("5.2.9", "5.3.0"), "older")
    eq(FirmwareSite.sha256Hex("abc".toByteArray()), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "SHA-256 of abc")

    val w = OtaWindow(10_000)
    var sent = 0
    while (true) { val p = w.next(244) ?: break; sent += p.count() }
    eq(sent, 8192, "window: 2 blocks before the first confirmation")
    w.confirm(4096); eq(w.next(244), 8192 until 8436, "one more piece after a confirmation")
    while (w.next(244) != null) {}
    eq(w.sent, 10_000, "the last piece stops at the end"); check(!w.complete, "not complete before the last confirmation")
    w.confirm(10_000); check(w.complete && w.percent == 100, "complete")

    val pr = Control.parse("""{"e":"ota","off":8192}"""); eq(pr, ControlEvent.OtaProgress(8192, false), "OTA progress")
    eq(Control.parse("""{"e":"ota","off":10000,"done":true}"""), ControlEvent.OtaProgress(10000, true), "OTA done")
    check(Control.parse("""{"e":"fw","state":"available"}""") is ControlEvent.Unknown, "the tracker no longer sends its own WiFi update status")
}

private fun testSat() {
    val st = Control.parse("""{"e":"sat","ok":true,"n":"ISS (ZARYA)","id":25544,"i":3,"geo":false,"tx":4,"tle":1.2,"pos":"GPS","az":123.4,"el":45.0,"rng":850,"rr":-1.234,"aos":1790000000,"los":1790000600,"max":62}""")
    val s = (st as ControlEvent.Sat).state
    check(s.ok && s.name == "ISS (ZARYA)" && s.noradId == 25544 && s.index == 3 && !s.geostationary && s.transmitters == 4, "sat state")
    check(s.azDeg == 123.4 && s.elDeg == 45.0 && s.rangeKm == 850 && s.rangeRateKmS == -1.234 && s.maxElDeg == 62, "sat look angles")
    eq(s.aos, 1790000000L, "sat AOS"); eq(s.positionSource, "GPS", "sat position source")
    val none = (Control.parse("""{"e":"sat","ok":false,"pos":""}""") as ControlEvent.Sat).state
    check(!none.ok && none.name == null && none.azDeg == null && none.aos == null, "sat without data")

    val sl = Control.parse("""{"e":"satlist","tot":239,"sel":3,"from":0,"items":[{"i":0,"id":25544,"n":"ISS (ZARYA)","c":"U","t":4},{"i":1,"id":43770,"n":"FOSSASAT-1B","c":"L","t":1}]}""") as ControlEvent.SatList
    eq(sl.total, 239, "satlist total"); eq(sl.selected, 3, "satlist selected"); eq(sl.items.size, 2, "satlist items")
    eq(sl.items[1], SatEntry(1, 43770, "FOSSASAT-1B", "L", 1), "satlist item")

    val tl = Control.parse("""{"e":"sattxlist","items":[{"i":0,"m":"FM","b":0,"d":"Voice","up":145990000,"dn":437800000,"rx":"n/a","tx":"n/a"},{"i":1,"m":"AFSK","b":1200,"d":"APRS","up":0,"dn":145825000,"rx":"FSK","tx":"FSK","rxd":145826100}]}""") as ControlEvent.SatTransmitters
    eq(tl.items.size, 2, "transmitters"); eq(tl.items[1].baud, 1200, "transmitter baud"); eq(tl.items[1].rxDopplerHz, 145826100L, "transmitter Doppler")
    eq(tl.items[0].rxDopplerHz, null, "transmitter without Doppler"); eq(tl.items[1].rxKind, "FSK", "transmitter RX class")

    val rx = (Control.parse("""{"e":"satrx","on":true,"k":"FSK","f":437425310,"dop":-3120,"fine":0,"rssi":-104,"n":7,"tx":2,"txt":"EA4XYZ-1>APRS:>telemetry ok"}""") as ControlEvent.SatRx).receiver
    check(rx.on && rx.kind == "FSK" && rx.freqHz == 437425310L && rx.dopplerHz == -3120 && rx.rssi == -104 && rx.frames == 7 && rx.tx == 2, "satrx")
    eq(rx.text, "EA4XYZ-1>APRS:>telemetry ok", "satrx line")
    val off = (Control.parse("""{"e":"satrx","on":false}""") as ControlEvent.SatRx).receiver
    check(!off.on && off.kind == null && off.text == null, "satrx off")

    eq(Control.parse("""{"e":"sattx","ok":true,"f":437425000,"n":3}"""), ControlEvent.SatTxDone(true, 437425000L, 3), "sattx result")

    val rot = (Control.parse("""{"e":"rot","az":123.4,"el":45.0,"trk":true,"src":0,"pit":1.5,"rol":-2.0,"mob":true,"abs":200.0,"hdg":76.6,"name":"ISS (ZARYA)","km":0}""") as ControlEvent.Rotor).pointing
    check(rot.azDeg == 123.4 && rot.elDeg == 45.0 && rot.tracking && rot.source == 0 && rot.mobile && rot.absAzDeg == 200.0 && rot.headingDeg == 76.6, "rot")
    eq(rot.pitchDeg, 1.5, "rot pitch"); eq(rot.name, "ISS (ZARYA)", "rot name")
    val rot2 = (Control.parse("""{"e":"rot","az":10.0,"el":0.0,"trk":false,"src":2}""") as ControlEvent.Rotor).pointing
    check(!rot2.mobile && rot2.name == null && rot2.km == null && rot2.pitchDeg == null, "rot minimal")

    val tg = Control.parse("""{"e":"targets","sel":0,"src":2,"items":[{"i":0,"n":"Repetidor X","lat":37.1,"lon":-5.9,"alt":850,"f":145600000}]}""") as ControlEvent.Targets
    eq(tg.items, listOf(RotorTarget(0, "Repetidor X", 37.1, -5.9, 850, 145600000L)), "targets"); eq(tg.source, 2, "targets source")

    eq(TrackerMode.name(TrackerMode.SAT), "SAT", "SAT mode name")

    // sats.txt
    val blocks = StringBuilder()
    for (k in 0 until 4) blocks.append("S|${25544 + k}|SAT $k\n1 25544U 98067A   26300.50000000  .00016717  00000-0  10270-3 0  9993\n2 25544  51.6400 208.9163 0006317  69.9862  25.2906 15.49560000 00009\nT|145800000|145800000|437800000|437800000|FM|0|0|Voice\n")
    val data = blocks.toString()
    eq(Sat.checkData(data), null, "sats.txt accepted"); eq(Sat.countSatellites(data), 4, "sats.txt satellites")
    check(Sat.checkData("S|1|x\n") != null, "short sats.txt refused")
    check(Sat.checkData("x".repeat(500)) != null, "sats.txt without satellites refused")
    check(Sat.checkData(data.repeat(80)) != null, "sats.txt too big refused")
    val lines = Control.satData(data)
    check(lines.first().contains("\"c\":\"satdata\"") && lines.first().contains("\"s\":\"begin\"") && lines.first().contains("\"n\":${data.toByteArray().size}"), "satdata begin")
    check(lines.last().contains("\"s\":\"end\""), "satdata end")
    check(lines.all { it.toByteArray(Charsets.UTF_8).size <= Control.MAX_LINE_SAFE + 1 }, "satdata lines fit the tracker")
    val back = StringBuilder()
    for (l in lines) { val o = Json.parseObject(l.trim())!!; if (o.str("s") == "c") back.append(o.str("d")) }
    eq(back.toString(), data, "satdata chunks rebuild the file")

    // phone position
    eq(Sat.position(37.1915123456, -3.9367014, 512.4, 8.6f), "{\"c\":\"pos\",\"lat\":37.191512,\"lon\":-3.936701,\"alt\":512,\"acc\":9}\n", "pos command")
    eq(Sat.position(91.0, 0.0, null, null), null, "pos out of range"); eq(Sat.position(10.0, 20.0, null, null), "{\"c\":\"pos\",\"lat\":10,\"lon\":20}\n", "pos without alt")

    eq(Sat.relative(1000, 940), "in 1 min 00 s", "relative future"); eq(Sat.relative(1000, 1000), "now", "relative now"); eq(Sat.relative(0, 7500), "2 h 05 min ago", "relative past")
    eq(Sat.passText(1000, 1600, 900), "Next pass starts in 1 min 40 s", "pass waiting"); eq(Sat.passText(1000, 1600, 1100), "In view, ends in 8 min 20 s", "pass in view")
    eq(Sat.passText(1000, 1600, 2000), "Pass over", "pass over"); eq(Sat.passText(null, null, 0), null, "pass unknown")
    eq(Sat.compass(0.0), "N", "compass N"); eq(Sat.compass(359.0), "N", "compass wrap"); eq(Sat.compass(135.0), "SE", "compass SE"); eq(Sat.compass(-90.0), "W", "compass negative")

    // complete file: groups, favorites and the room of the tracker
    val all = "# APRS LoRa+ satellites (all bands, groups) 2026-11-09T03:17Z\n" +
        "S|48274|CSS (TIANHE)|U|s\n1 a\n2 b\n" +
        "S|43017|FOX-1B (AO-91)|U|a\n1 a\n2 b\nT|435250000|435250000|145960000|145960000|FM|0|0|FM rptr|670\n" +
        "S|25544|ISS (ZARYA)|U|as\n1 a\n2 b\nT|145825000|145825000|145825000|145825000|AFSK|1200|0|APRS digipeater\n" +
        "S|33591|NOAA 19|V|w\n1 a\n2 b\n" +
        "S|99999|LORASAT|L|\n1 a\n2 b\n"
    val fs = Sat.filter(all, "s", emptySet(), 100_000)
    eq(fs.count, 2, "filter: space stations"); eq(fs.total, 5, "filter: total"); check(fs.text.startsWith("# APRS LoRa+"), "filter keeps the header")
    eq(Sat.filter(all, "", emptySet(), 100_000).count, 5, "filter: no group = all")
    eq(Sat.filter(all, "w", setOf(43017), 100_000).count, 2, "filter: weather + a favorite")
    eq(Sat.filter(all, "l", emptySet(), 100_000).count, 1, "filter: LoRa by category")
    val fav = Sat.filter(all, "a", setOf(33591), 200)
    check(fav.text.contains("NOAA 19") && fav.dropped > 0, "filter: the favorite first when there is no room")
    eq(Sat.countSatellites(Sat.filter(all, "as", emptySet(), 100_000).text), 3, "filter output is a satellite file")
    eq(Sat.categoryName("V"), "2 m", "category V"); eq(Sat.categoryLabel(4), "Favorites", "category label")
    eq(Sat.favCommand(25544, true), "{\"c\":\"satfav\",\"id\":25544,\"on\":true}\n", "satfav command")
    eq(Sat.categoryCommand(6), "{\"c\":\"satcat\",\"k\":6}\n", "satcat command")
    eq(Sat.bandsText(Sat.BAND_2M or Sat.BAND_VHF or Sat.BAND_70CM or Sat.BAND_UHF), "2 m + 70 cm", "bands text")
    check(Sat.checkData(all.repeat(200), 900_000) == null && Sat.checkData(all.repeat(200)) != null, "the T-TWR takes a bigger file")
    val sl2 = Control.parse("""{"e":"satlist","tot":2,"sel":0,"from":0,"items":[{"i":0,"id":25544,"n":"ISS","c":"U","t":2,"b":15,"fav":true,"in":true},{"i":1,"id":1,"n":"X","c":"V","t":0,"b":0,"in":false}]}""") as ControlEvent.SatList
    check(sl2.items[0].favorite && sl2.items[0].inCategory && sl2.items[0].bands == 15 && !sl2.items[1].favorite && !sl2.items[1].inCategory, "satlist favorites and bands")
    val tl2 = Control.parse("""{"e":"sattxlist","items":[{"i":0,"m":"FM","b":0,"d":"rpt","up":145990000,"dn":437800000,"rx":"n/a","tx":"FM","tone":67.0},{"i":1,"m":"AFSK","b":1200,"d":"APRS","up":145825000,"dn":145825000,"rx":"APRS","tx":"APRS","aprs":true}]}""") as ControlEvent.SatTransmitters
    check(tl2.items[0].toneHz == 67.0 && !tl2.items[0].aprsDigi && tl2.items[1].aprsDigi && tl2.items[1].toneHz == null, "sattxlist tone and APRS")
    val ss = Control.parse("""{"e":"sat","ok":true,"n":"ISS","id":25544,"i":0,"geo":false,"tx":2,"tle":0.4,"fav":true,"cat":4,"tot":120,"nfav":3,"stamp":"2026-11-09T03:17Z","pos":"GPS"}""") as ControlEvent.Sat
    check(ss.state.favorite && ss.state.category == 4 && ss.state.total == 120 && ss.state.favorites == 3 && ss.state.stamp.startsWith("2026"), "sat event favorites")
    val inf = Control.parse("""{"e":"info","v":1,"fw":"5.3.5","board":"T-TWR","caps":{"satMax":900000,"satFav":true,"satFm":true}}""") as ControlEvent.Info
    check(inf.info.satMaxBytes == 900_000 && inf.info.hasSatFav && inf.info.hasSatFm, "info satellite caps")
    val inf2 = Control.parse("""{"e":"info","v":1,"fw":"5.3.4","board":"x","caps":{}}""") as ControlEvent.Info
    check(inf2.info.satMaxBytes == Control.SAT_DATA_MAX && !inf2.info.hasSatFav, "info without satellite caps")
}

private fun hex(b: ByteArray) = b.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }

private fun testUbx() {
    eq(hex(Ubx.frame(0x0A, 0x04, ByteArray(0))), "b5620a0400000e34", "UBX checksum (MON-VER poll)")
    eq(hex(Ubx.iniTimeUtc(1793968496789L)), "b5621340180010000080ea070b060c223800402f072f02000000000000000a0d", "MGA-INI-TIME_UTC")
    eq(hex(Ubx.iniPosLlh(37.1915, -3.9367, 512.4, 25.0)), "b5621340140001000000f8f82a16a84ea7fd28c80000c4090000ef4e", "MGA-INI-POS_LLH")
    val two = Ubx.iniTimeUtc(1793968496789L) + Ubx.iniPosLlh(37.1915, -3.9367, 512.4, 25.0)
    eq(Ubx.countFrames(two), 2, "UBX stream counted")
    val why = StringBuilder()
    eq(Ubx.countFrames("<html>Unauthorized</html>".toByteArray(), why), null, "error page refused"); check(why.startsWith("not u-blox data"), "error page reason")
    eq(Ubx.countFrames(two.copyOf(two.size - 1)), null, "truncated stream refused")
    val bad = two.copyOf(); bad[10] = (bad[10] + 1).toByte()
    eq(Ubx.countFrames(bad), null, "bad checksum refused"); eq(Ubx.countFrames(ByteArray(0)), null, "empty refused")
    val big = ByteArray(1000) { it.toByte() }
    val lines = Ubx.aidLines(big)
    check(lines.first().contains("\"c\":\"gpsaid\"") && lines.first().contains("\"s\":\"begin\"") && lines.first().contains("\"n\":1000"), "gpsaid begin")
    check(lines.last().contains("\"s\":\"end\""), "gpsaid end"); eq(lines.size, 2 + 8, "gpsaid pieces")
    val back = java.io.ByteArrayOutputStream()
    for (l in lines) { val o = Json.parseObject(l.trim())!!; if (o.str("s") == "c") { val d = o.str("d")!!; check(d.length <= 180, "gpsaid piece fits the tracker"); back.write(java.util.Base64.getDecoder().decode(d)) } }
    check(back.toByteArray().contentEquals(big), "gpsaid pieces rebuild the data")
    check(lines.all { it.toByteArray().size <= Control.MAX_LINE_SAFE + 1 }, "gpsaid lines fit the tracker")
    check(Control.parse("{\"e\":\"info\",\"v\":1,\"fw\":\"5.3.5\",\"board\":\"x\",\"caps\":{\"gpsaid\":true}}").let { it is ControlEvent.Info && it.info.hasGpsAid }, "caps.gpsaid")
}

private fun testRotator() {
    var lastSet: Pair<Double, Double>? = null
    val rot = object : RotCtl.Rot {
        override fun setPos(azDeg: Double, elDeg: Double): Boolean { lastSet = azDeg to elDeg; return true }
        override fun getPos() = 123.5 to 45.25
        override fun park() = true
        override fun info() = "APRS LoRa+ app"
    }
    val s = RotCtl.Session(rot)
    val out = StringBuilder()
    check(!s.feed("P 370.5 95\n", out), "rotctld P"); eq(out.toString(), "RPRT 0\n", "rotctld P reply"); eq(lastSet, 10.5 to 90.0, "rotctld P wraps az and caps el")
    out.setLength(0); s.feed("p\r\n", out); eq(out.toString(), "123.500000\n45.250000\n", "rotctld p")
    out.setLength(0); s.feed("\\set_pos -90 30\n", out); eq(lastSet, 270.0 to 30.0, "rotctld \\set_pos"); eq(out.toString(), "RPRT 0\n", "rotctld long form reply")
    out.setLength(0); s.feed("P abc 3\n", out); eq(out.toString(), "RPRT -1\n", "rotctld bad number")
    out.setLength(0); s.feed("\\dump_state\n", out); eq(out.toString(), "1\n2\nmin_az=0.000000\nmax_az=360.000000\nmin_el=0.000000\nmax_el=90.000000\nsouth_zero=0\nrot_type=AzEl\ndone\n", "rotctld dump_state (Hamlib 4 protocol 1)")
    out.setLength(0); s.feed("_\nX\n", out); eq(out.toString(), "APRS LoRa+ app\nRPRT -4\n", "rotctld info and unknown")
    out.setLength(0); s.feed("P 10 ", out); eq(out.toString(), "", "rotctld waits for the end of line"); s.feed("20\n", out); eq(lastSet, 10.0 to 20.0, "rotctld split line")
    out.setLength(0); check(s.feed("q\n", out), "rotctld quit closes")
    eq(RotCtl.setPosLine(-10.0, 95.0), "P 350.0 90.0\n", "rotctld client line"); eq(RotCtl.parseReply("RPRT -1"), -1, "rotctld reply"); eq(RotCtl.parseReply("12.0"), null, "not a reply")
    eq(RotCtl.angleDiff(350.0, 10.0), 20.0, "angle diff wraps"); eq(RotCtl.angleDiff(10.0, 350.0), -20.0, "angle diff negative")
    check(RotCtl.moved(null, null, 0.0, 0.0, 1.0), "first position always sent"); check(!RotCtl.moved(359.8, 10.0, 0.3, 10.2, 1.0), "small move across north not sent")
    check(RotCtl.moved(359.0, 10.0, 0.5, 10.0, 1.0), "move across north sent")

    val flat = PhoneMath.tilt(0.0, 0.0, 9.81)!!; check(abs(flat.first) < 1e-9 && abs(flat.second) < 1e-9, "flat phone: no tilt")
    val up = PhoneMath.tilt(0.0, 9.81 * Math.sin(Math.toRadians(10.0)), 9.81 * Math.cos(Math.toRadians(10.0)))!!
    check(abs(up.first - 10.0) < 1e-6 && abs(up.second) < 1e-6, "front up = pitch +10")
    val right = PhoneMath.tilt(-9.81 * Math.sin(Math.toRadians(5.0)), 0.0, 9.81 * Math.cos(Math.toRadians(5.0)))!!
    check(abs(right.second - 5.0) < 1e-6 && abs(right.first) < 1e-6, "right side down = roll +5")
    eq(PhoneMath.tilt(0.0, 9.81, 0.0), null, "phone standing up: no tilt")
    check(abs(PhoneMath.baroAltitude(1013.25)) < 1e-9, "baro altitude at QNH"); check(abs(PhoneMath.baroAltitude(899.0) - 1000.0) < 15.0, "baro altitude 1000 m")
    check(abs(PhoneMath.qnh(1013.25 * Math.pow(1.0 - 1000.0 / 44330.0, 5.255), 1000.0) - 1013.25) < 0.01, "QNH back")


    check(Control.parse("{\"e\":\"rot\",\"az\":10,\"el\":5,\"aaz\":9.5,\"ael\":4}").let { it is ControlEvent.Rotor && it.pointing.actualAzDeg == 9.5 && it.pointing.actualElDeg == 4.0 }, "rot actual position")
    check(Control.parse("{\"e\":\"info\",\"caps\":{\"rotor\":true}}").let { it is ControlEvent.Info && it.info.hasRotor }, "caps.rotor")
}

private fun testRotorProto() {
    // Commands as the rotor box expects them (firmware/lib/RotorCore/src/rc_api.h of APRS-LoRa-Plus-Rotor)
    eq(RotorProto.auth("123456"), "{\"c\":\"auth\",\"pin\":\"123456\"}\n", "rotor auth")
    eq(RotorProto.pos(123.456, 45.0), "{\"c\":\"pos\",\"az\":123.5,\"el\":45}\n", "rotor pos")
    eq(RotorProto.pos(10.0, null), "{\"c\":\"pos\",\"az\":10}\n", "rotor pos az only")
    eq(RotorProto.move(RotorProto.Move.CCW, 30), "{\"c\":\"move\",\"d\":\"ccw\",\"s\":30}\n", "rotor move")
    eq(RotorProto.subscribe(500), "{\"c\":\"sub\",\"ms\":500}\n", "rotor sub")
    // Events
    val info = RotorProto.parse("{\"e\":\"info\",\"api\":1,\"lim\":{\"minAz\":0,\"maxAz\":450,\"minEl\":0,\"maxEl\":180},\"hasEl\":true,\"pin\":true," +
        "\"dev\":\"APRS LoRa+ Rotor\",\"ver\":\"0.1.0\",\"board\":\"esp32s3\",\"chip\":\"ESP32-S3\",\"name\":\"APRS-Rotor\",\"drv\":\"direct (relays)\"," +
        "\"call\":\"EA7JRS-12\",\"caps\":{\"wifi\":true,\"ble\":true,\"spp\":false,\"lora\":false,\"cal\":true,\"move\":true},\"ip\":\"192.168.1.50\",\"rotctld\":4533,\"up\":60}")
    check(info is RotorProto.Event.InfoEv && info.info.limits.maxAz == 450.0 && info.info.canCalibrate && info.info.pinRequired && info.info.rotctldPort == 4533, "rotor info event")
    val pos = RotorProto.parse("{\"e\":\"pos\",\"az\":123.4,\"el\":45,\"st\":22,\"mv\":true,\"f\":\"OK\",\"maz\":483.4,\"taz\":125,\"tel\":45,\"aprs\":\"EA7JRS-9: P 125 45 -> GO\"}")
    check(pos is RotorProto.Event.Pos && pos.pos.azDeg == 123.4 && pos.pos.elDeg == 45.0 && pos.pos.mechAzDeg == 483.4 && pos.pos.targetAzDeg == 125.0 && pos.pos.ok, "rotor pos event")
    if (pos is RotorProto.Event.Pos) eq(RotorProto.statusText(pos.pos.status), "moving · CW", "rotor status text")
    eq(RotorProto.parse("{\"e\":\"err\",\"c\":\"pos\",\"m\":\"auth required\"}"), RotorProto.Event.Err("pos", "auth required"), "rotor err event")
    eq(RotorProto.parse("{\"e\":\"ok\",\"c\":\"cfgset\",\"i\":2,\"done\":true}"), RotorProto.Event.Ok("cfgset", 2, true), "rotor ok event")
    check(RotorProto.parse("not json") == null && RotorProto.parse("{\"e\":\"pos\"}") == null, "rotor bad events")
    // Wind protection: internet weather to the box, state in the pos event
    eq(RotorProto.wx(NetWeather.Data("OWM", windMs = 20.04, gustMs = 28.41, windDeg = 250, timeS = 1000), 1150),
        "{\"c\":\"wx\",\"ws\":20,\"wg\":28.4,\"wd\":250,\"age\":150,\"src\":\"OWM\"}\n", "rotor wx")
    eq(RotorProto.wx(NetWeather.Data("IQAir", windMs = 3.0), 5), "{\"c\":\"wx\",\"ws\":3,\"age\":0,\"src\":\"IQAir\"}\n", "rotor wx no gust")
    check(RotorProto.wx(NetWeather.Data("OWM", tempC = 20.0), 5) == null, "rotor wx without wind")
    val wp = RotorProto.parse("{\"e\":\"pos\",\"az\":10,\"st\":0,\"mv\":false,\"f\":\"WIND\",\"wind\":{\"on\":true,\"park\":15,\"lk\":true,\"ws\":20,\"wg\":28.4,\"wd\":250,\"pk\":28.4,\"age\":150,\"src\":\"OWM\",\"hold\":870}}")
    check(wp is RotorProto.Event.Pos && wp.pos.wind?.locked == true && wp.pos.wind?.gustMs == 28.4 && wp.pos.wind?.holdS == 870L && !wp.pos.ok, "rotor pos wind")
    val wi = RotorProto.parse("{\"e\":\"info\",\"lim\":{},\"caps\":{\"wind\":true}}")
    check(wi is RotorProto.Event.InfoEv && wi.info.wind, "rotor info wind")
    // Lines split across BLE notifications
    val la = RotorProto.LineAssembler()
    eq(la.feed("{\"e\":\"ok\",".toByteArray()), emptyList<String>(), "rotor line part 1")
    eq(la.feed("\"c\":\"stop\"}\n{\"e\"".toByteArray()), listOf("{\"e\":\"ok\",\"c\":\"stop\"}"), "rotor line part 2")
    // Configuration: split for cfgset and joined again from cfg events
    val cfg = "{\"name\":\"" + "x".repeat(400) + "\"}"
    val lines = RotorProto.configSet(cfg)
    eq(lines.size, 3, "rotor cfgset pieces")
    val ca = RotorProto.ConfigAssembler()
    var whole: String? = null
    for (l in lines) {
        val o = Json.parseObject(l.trim())!!
        check(l.length < 244, "rotor cfgset piece fits a BLE write")
        whole = ca.add(RotorProto.Event.ConfigPiece(o.int("i")!!, o.int("n")!!, o.str("d")!!)) ?: whole
    }
    eq(whole, cfg, "rotor config round trip")
    eq(ca.add(RotorProto.Event.ConfigPiece(1, 3, "x")), null, "rotor config out of order")
    // Follow the tracker: only real changes
    check(RotorProto.worthSending(null, null, 10.0, 0.0, 2.0), "rotor first pointing")
    check(!RotorProto.worthSending(359.5, 10.0, 0.5, 10.5, 2.0), "rotor small change across north")
    check(RotorProto.worthSending(359.0, 10.0, 2.0, 10.0, 2.0), "rotor 3 degrees across north")
    check(RotorProto.worthSending(100.0, 10.0, 100.0, 13.0, 2.0), "rotor elevation change")
    // CAT of the radio on the rotor box (rc_api.h: cat, catset, cattrack, event "cat")
    eq(RotorProto.catFreq(145825000L), "{\"c\":\"catset\",\"f\":145825000}\n", "rotor catset f")
    eq(RotorProto.catVfo("swap"), "{\"c\":\"catset\",\"vfo\":\"swap\"}\n", "rotor catset vfo")
    eq(RotorProto.catPtt(false), "{\"c\":\"catset\",\"ptt\":false}\n", "rotor catset ptt")
    eq(RotorProto.catTrack("SO-50", 436795000L, 145850000L, -3.21456), "{\"c\":\"cattrack\",\"name\":\"SO-50\",\"dl\":436795000,\"ul\":145850000,\"rr\":-3.215}\n", "rotor cattrack")
    val cat = RotorProto.parse("{\"e\":\"cat\",\"rig\":\"IC-9700 · CI-V A2 · 19200\",\"l\":true,\"f\":436800000,\"m\":\"PKTUSB\",\"tf\":145850000,\"sp\":true,\"v\":\"A\"," +
        "\"ptt\":false,\"sm\":11,\"pa\":true,\"tr\":{\"en\":true,\"on\":true,\"n\":\"SO-50\",\"dop\":-4210}}")
    check(cat is RotorProto.Event.Cat && cat.state.freqHz == 436800000L && cat.state.txFreqHz == 145850000L && cat.state.split && cat.state.pttAllowed &&
        cat.state.tracking?.dopplerHz == -4210L && cat.state.sText == "S9+20", "rotor cat event")
    val cat2 = RotorProto.parse("{\"e\":\"cat\",\"rig\":\"RSPdx-R2 · SDRconnect\",\"l\":false,\"f\":7074000,\"m\":\"USB\",\"sp\":false,\"v\":\"A\",\"ptt\":false,\"sm\":-1,\"pa\":false}")
    check(cat2 is RotorProto.Event.Cat && cat2.state.txFreqHz == null && cat2.state.tracking == null && cat2.state.sText == "—", "rotor cat event receiver")
    eq(RotorProto.freq(145825000L), "145.825.000", "rotor cat freq text")
    eq(RotorProto.freq(10489550000L), "10489.550.000", "rotor cat freq QO-100")
    eq(RotorProto.modeName("PKTUSB"), "DIG-U", "rotor cat mode name")
    eq(RotorProto.catTone(67.0), "{\"c\":\"catset\",\"tone\":67}\n", "rotor cat tone")
    eq(listOf("Mode V/U FM - Voice, 67 Hz CTCSS", "FM uplink PL 74.4", "Uplink 145.850 MHz, tone 67.0Hz", "9600bps GMSK", "FM 1200 Hz AFSK", "CTCSS: 88,5").map { RotorProto.satTone(it) },
        listOf(67.0, 74.4, 67.0, null, null, 88.5), "rotor cat satellite tone")
    val catTn = RotorProto.parse("{\"e\":\"cat\",\"f\":145850000,\"tn\":67}")
    check(catTn is RotorProto.Event.Cat && catTn.state.toneHz == 67.0, "rotor cat tone event")
    eq(listOf("FM", "AFSK", "USB", "CW", "GMSK", "BPSK", "LoRa", "FMN").map { RotorProto.satMode(it) },
        listOf("FM", "PKTFM", "USB", "CW", "PKTUSB", "PKTUSB", null, "FM"), "rotor cat satellite modes")
    val infoCat = RotorProto.parse("{\"e\":\"info\",\"lim\":{},\"caps\":{\"cat\":true},\"rigctld\":4532,\"cat\":true}")
    check(infoCat is RotorProto.Event.InfoEv && infoCat.info.cat && infoCat.info.rigctldPort == 4532, "rotor info cat")
}

private fun testRotorFollow() {
    // Same cases as tools/rotor_follow/test_rotor_follow.cpp of the tracker firmware
    val l = RotorFollow.locatorToLatLon("im76hr")!!
    near(l.first, 36.729167, 1e-5, "locator 6 lat"); near(l.second, -5.375, 1e-5, "locator 6 lon")
    val l8 = RotorFollow.locatorToLatLon("IM76HR45")!!
    near(l8.first, 36.73125, 1e-4, "locator 8 lat"); near(l8.second, -5.37917, 1e-4, "locator 8 lon")
    check(RotorFollow.locatorToLatLon("EA7JRS") == null && RotorFollow.locatorToLatLon("ZZ76") == null, "not locators")
    eq(RotorFollow.parseCwBeacon("VVV VVV DE ED7YAA IM76HR "), RotorFollow.Beacon("ED7YAA", "IM76HR", l.first, l.second), "CW beacon with DE")
    eq(RotorFollow.parseCwBeacon("OH2B KP20 OH2B KP20")?.call, "OH2B", "CW beacon without DE")
    eq(RotorFollow.parseCwBeacon("BEACON IO91WM G3XXX")?.call, "G3XXX", "CW beacon call after the locator")
    eq(RotorFollow.parseCwBeacon("DE ED7YAA IM76HR   DE EA1AAA IN73XL")?.call, "EA1AAA", "last CW beacon wins")
    check(RotorFollow.parseCwBeacon("CQ CQ DE EA7JRS K") == null, "no locator, no beacon")
    check(RotorFollow.callMatches("EA7JRS-9", "") && RotorFollow.callMatches("EA7JRS-9", "ea7jrs") && RotorFollow.callMatches("EA7ABC", "EA7*"), "call filter yes")
    check(!RotorFollow.callMatches("EA7JRS-7", "EA7JRS-9") && !RotorFollow.callMatches("EA7JRSX", "EA7JRS"), "call filter no")
    // Moving station: 60 km/h north for 60 s = 1 km
    val p = RotorFollow.predict(37.0, -6.0, 60.0, 0.0, 60.0, 300.0)!!
    near(p.first, 37.008993, 1e-5, "predict north"); near(p.second, -6.0, 1e-9, "predict north lon")
    near(RotorFollow.predict(37.0, -6.0, 60.0, 0.0, 3600.0, 300.0)!!.first, 37.0 + 5.0 / 111.195, 1e-4, "predict capped at 300 s")
    check(RotorFollow.predict(37.0, -6.0, 2.0, 0.0, 60.0, 300.0) == null, "stopped station not predicted")
    check(RotorFollow.predict(0.0, 179.99, 100.0, 90.0, 60.0, 300.0)!!.second < -179.9, "predict across the antimeridian")
    // Look angles: due east 10 km, same height -> az 90, slightly below the horizon (curvature)
    val east = RotorFollow.predict(37.0, -6.0, 36.0, 90.0, 1000.0, 1000.0)!!
    val lk = RotorFollow.look(37.0, -6.0, 0.0, east.first, east.second, 0.0)
    near(lk.azDeg, 90.0, 0.1, "look az"); near(lk.km, 10.0, 0.01, "look range"); check(lk.elDeg < 0 && lk.elDeg > -0.1, "look elevation with curvature")
    near(RotorFollow.look(37.0, -6.0, 0.0, 37.0 + 1 / 111.195, -6.0, 1000.0).elDeg, 45.0, 0.1, "look 1 km away 1 km up")
    // Station to aim at from the tracker's heard list (newest first)
    fun hs(c: String, lat: Double?, age: Long, spd: Int? = null, crs: Int? = null, pa: Long? = null) =
        HeardStation(c, "", "", lat, if (lat != null) -6.0 else null, 100, spd, crs, null, null, age, 1, null, null, null, null, null, pa)
    val list = listOf(hs("EA1AAA", null, 5), hs("EA7JRS-9", 37.0, 60, 60, 0, 60), hs("EA7XYZ", 37.5, 120))
    val a = RotorFollow.aimAtStation(list, "", "", 30, true, 300, 0.0)!!
    eq(a.call, "EA7JRS-9", "aim newest with position"); check(a.estimated, "aim estimated"); near(a.lat, 37.008993, 1e-5, "aim: 60 s since its position at 60 km/h")
    eq(RotorFollow.aimAtStation(list, "", "EA7XYZ", 30, true, 300, 0.0)?.call, "EA7XYZ", "aim with filter")
    eq(RotorFollow.aimAtStation(list, "", "EA7XYZ", 1, true, 300, 0.0), null, "aim too old")
    eq(RotorFollow.aimAtStation(list, "ea7xyz", "", 1, true, 300, 0.0)?.estimated, false, "aim chosen station, stopped")
    // Tracker events with the new fields
    val tg = Control.parse("{\"e\":\"targets\",\"sel\":0,\"src\":4,\"ff\":\"EA7*\",\"fm\":20,\"pr\":false,\"pm\":120,\"items\":[]}") as ControlEvent.Targets
    check(tg.source == 4 && tg.filter == "EA7*" && tg.maxAgeMin == 20 && !tg.predict && tg.predictMaxS == 120, "targets with follow settings")
    val hd = Control.parse("{\"e\":\"heard\",\"tot\":1,\"pkts\":1,\"from\":0,\"items\":[{\"c\":\"EA7JRS-9\",\"lat\":37,\"lon\":-6,\"spd\":50,\"crs\":90,\"age\":5,\"pa\":40,\"n\":2}]}") as ControlEvent.Heard
    eq(hd.items.single().posAgeS, 40L, "heard position age")
    eq(Sat.rotorSource(5), "CW beacon", "rotor source name")
    eq(Sat.rotorSource(7), "Moon", "rotor source moon")
    check(RotorFollow.TRACKER_SOURCES.containsAll(listOf(RotorFollow.SRC_SUN, RotorFollow.SRC_MOON)), "tracker sources sun / moon")
}

// Worked examples of "The 1090 MHz Riddle" (J. Sun), and a synthetic 2 MS/s signal for the demodulator
private fun testAdsb() {
    fun h(x: String) = Adsb.unhex(x)!!
    check(Adsb.residual(h("8D406B902015A678D4D220AA4BDA")) == 0 && Adsb.residual(h("8D4840D6202CC371C32CE0576098")) == 0 && Adsb.crc(h("8D406B902015A678D4D220"), 11) == 0xAA4BDA, "ADS-B: parity of good messages")
    val id = Adsb.decode(h("8D4840D6202CC371C32CE0576098"))
    check(id != null && id.df == 17 && id.icao == 0x4840D6 && id.typeCode == 4 && id.callsign == "KLM1023", "ADS-B: identification")
    val ev = Adsb.decode(h("8D40621D58C382D690C8AC2863A7"))!!; val od = Adsb.decode(h("8D40621D58C386435CC412692AD6"))!!
    check(ev.icao == 0x40621D && ev.cprOdd == false && od.cprOdd == true && ev.cprLat == 93000 && ev.cprLon == 51372 && od.cprLat == 74158 && od.cprLon == 50194 &&
        ev.altFt == 38000, "ADS-B: airborne position fields and altitude")
    val g = Adsb.cprGlobal(ev.cprLat, ev.cprLon, od.cprLat, od.cprLon, latestOdd = false)
    check(g != null && Math.abs(g.first - 52.25720) < 1e-4 && Math.abs(g.second - 3.91937) < 1e-4, "ADS-B: position from an even and an odd message")
    val l = Adsb.cprLocal(ev.cprLat, ev.cprLon, false, 52.258, 3.918)
    check(Math.abs(l.first - 52.25720) < 1e-4 && Math.abs(l.second - 3.91937) < 1e-4, "ADS-B: position from one message and a reference")
    check(Adsb.nl(0.0) == 59 && Adsb.nl(52.2572) == 36 && Adsb.nl(87.0) == 2 && Adsb.nl(89.0) == 1, "ADS-B: longitude zones")
    val v = Adsb.decode(h("8D485020994409940838175B284F"))!!
    check(Math.abs(v.gsKt!! - 159.20) < 0.01 && Math.abs(v.trackDeg!! - 182.88) < 0.01 && v.vrFpm == -832, "ADS-B: speed, track and vertical rate")
    val a = Adsb.decode(h("8DA05F219B06B6AF189400CBC33F"))!!
    check(a.gsKt == null && Math.abs(a.trackDeg!! - 243.98) < 0.01 && a.vrFpm == -2304, "ADS-B: airspeed message (heading, vertical rate)")
    // one wrong bit is repaired, two are not; the format bits are never repaired
    val one = h("8D4840D6202CC371C32CE0576098"); one[6] = (one[6].toInt() xor 0x10).toByte()
    val fixed = Adsb.decode(one)
    check(fixed != null && fixed.callsign == "KLM1023" && fixed.fixedBit == 51 && Adsb.hex(one) == "8D4840D6202CC371C32CE0576098", "ADS-B: one wrong bit repaired")
    val two = h("8D4840D6202CC371C32CE0576098"); two[6] = (two[6].toInt() xor 0x18).toByte()
    check(Adsb.decode(two) == null && Adsb.decode(h("8D4840D6202CC371C32CE0576098").also { it[6] = (it[6].toInt() xor 0x10).toByte() }, fix = false) == null, "ADS-B: two wrong bits rejected")
    // address-parity formats only for addresses already heard
    val s = h("5D4840D6000000"); val r = Adsb.crc(s, 4)
    s[4] = (r shr 16).toByte(); s[5] = (r shr 8).toByte(); s[6] = r.toByte()
    check(Adsb.decode(s)?.let { it.df == 11 && it.icao == 0x4840D6 } == true, "ADS-B: all-call reply")
    val alt = h("20001838000000"); val ra = Adsb.crc(alt, 4) xor 0x4840D6       // address mixed with the parity
    alt[4] = (ra shr 16).toByte(); alt[5] = (ra shr 8).toByte(); alt[6] = ra.toByte()
    check(Adsb.decode(alt) == null && Adsb.decode(alt, known = { it == 0x4840D6 })?.let { it.icao == 0x4840D6 && it.altFt == 38000 } == true, "ADS-B: altitude reply of a known aircraft")

    // table: identification + position pair + speed
    val t = Adsb.Table()
    t.onFrame(h("8D40621D58C386435CC412692AD6"), 1000)
    check(t.list(1000).isEmpty(), "ADS-B table: one message is not enough to show an aircraft")
    t.onFrame(h("8D40621D58C382D690C8AC2863A7"), 2000)
    val p = t.list(2000).single()
    check(p.hex == "40621D" && Math.abs(p.lat!! - 52.25720) < 1e-4 && Math.abs(p.lon!! - 3.91937) < 1e-4 && p.altFt == 38000 && p.flightLevel() == "FL380", "ADS-B table: position")
    val t2 = Adsb.Table()
    t2.onFrame(h("8D40621D58C382D690C8AC2863A7"), 1000, 52.0, 4.0); t2.onFrame(h("8D40621D58C382D690C8AC2863A7"), 1500, 52.0, 4.0)
    check(t2.list(2000).single().lat?.let { Math.abs(it - 52.25720) < 1e-4 } == true, "ADS-B table: position from the first message with the receiver position")
    val t3 = Adsb.Table()
    t3.onFrame(h("8D40621D58C382D690C8AC2863A7"), 1000, -30.0, 100.0); t3.onFrame(h("8D40621D58C382D690C8AC2863A7"), 1500, -30.0, 100.0)
    check(t3.list(2000).single().lat?.let { Math.abs(it - -30.0) < 4 } == true, "ADS-B table: one message places the aircraft near the receiver")
    check(t.list(2000 + 301_000L).isEmpty(), "ADS-B table: forgotten after 5 minutes")
    check(t.onSbs("MSG,3,1,1,4CA2D6,1,2026/11/13,10:00:00.000,2026/11/13,10:00:00.000,,37000,,,36.7000,-6.1000,,,0,0,0,0", 5000) &&
        t.onSbs("MSG,1,1,1,4CA2D6,1,2026/11/13,10:00:01.000,2026/11/13,10:00:01.000,RYR12AB ,,,,,,,,,,,", 6000) &&
        t.onSbs("MSG,4,1,1,4CA2D6,1,2026/11/13,10:00:02.000,2026/11/13,10:00:02.000,,,450.0,270.5,,,-64,,,,,", 7000), "ADS-B: BaseStation lines")
    val b = t.list(8000).first { it.icao == 0x4CA2D6 }
    check(b.callsign == "RYR12AB" && b.altFt == 37000 && b.lat == 36.7 && b.gsKt == 450.0 && b.trackDeg == 270.5 && b.vrFpm == -64, "ADS-B: BaseStation fields")
    check(Adsb.Aircraft(1, squawk = 7700).emergency == "EMERGENCY 7700" && Adsb.Aircraft(1, squawk = 1000).emergency == null, "ADS-B: emergency squawk")

    // receiver on the network: Beast, AVR, BaseStation
    val got = ArrayList<String>(); val sbs = ArrayList<String>()
    val long = h("8D4840D6202CC371C32CE0576098")
    val beast = java.io.ByteArrayOutputStream()
    fun esc(x: ByteArray) { for (c in x) { beast.write(c.toInt()); if (c == 0x1A.toByte()) beast.write(0x1A) } }
    beast.write(0x1A); beast.write('3'.code); esc(byteArrayOf(0, 0, 0x1A, 0, 0, 1, 80)); esc(long)
    beast.write(0x1A); beast.write('1'.code); esc(byteArrayOf(0, 0, 0, 0, 0, 2, 80, 0x12, 0x34))
    beast.write(0x1A); beast.write('2'.code); esc(byteArrayOf(0, 0, 0, 0, 0, 3, 80)); esc(s)
    val bp = Adsb.NetParser({ got.add(Adsb.hex(it)) }, { sbs.add(it) })
    val bb = beast.toByteArray(); bp.feed(bb.copyOfRange(0, 11)); bp.feed(bb.copyOfRange(11, bb.size))
    check(bp.format == "Beast" && got == listOf("8D4840D6202CC371C32CE0576098", Adsb.hex(s)), "ADS-B: Beast frames (escapes, split data, Mode A/C skipped)")
    got.clear()
    val ap = Adsb.NetParser({ got.add(Adsb.hex(it)) }, { sbs.add(it) })
    ap.feed("*8D4840D6202CC371C32CE0576098;\n@0123456789AB8D40621D58C382D690C8AC2863A7;\r\n*zz;\n".toByteArray())
    check(ap.format == "AVR" && got == listOf("8D4840D6202CC371C32CE0576098", "8D40621D58C382D690C8AC2863A7"), "ADS-B: raw AVR lines, with and without time")
    val sp = Adsb.NetParser({ got.add("x") }, { sbs.add(it) })
    sp.feed("MSG,3,1,1,4CA2D6,1,,,,,,37000,,,36.7,-6.1,,,0,0,0,0\r\n".toByteArray())
    check(sp.format == "BaseStation" && sbs.size == 1 && sbs[0].startsWith("MSG,3"), "ADS-B: BaseStation detected")

    // demodulator: three messages as 2 MS/s pulses over noise, split across blocks
    val rnd = java.util.Random(7)
    val frames = listOf("8D4840D6202CC371C32CE0576098", "8D40621D58C382D690C8AC2863A7", Adsb.hex(s))
    val n = 6000
    val iq = ByteArray(n * 2)
    val amp = DoubleArray(n)
    var at = 300
    for (f in frames) {
        val m = h(f)
        for (k in intArrayOf(0, 2, 7, 9)) amp[at + k] = 60.0
        for (bit in 0 until m.size * 8) { val one1 = (m[bit / 8].toInt() shr (7 - bit % 8)) and 1 == 1; amp[at + 16 + 2 * bit + (if (one1) 0 else 1)] = 60.0 }
        at += 1500
    }
    for (i in 0 until n) {
        val ph = rnd.nextDouble() * 2 * Math.PI
        iq[2 * i] = Math.round(127.5 + amp[i] * Math.cos(ph) + rnd.nextGaussian() * 4).toInt().coerceIn(0, 255).toByte()
        iq[2 * i + 1] = Math.round(127.5 + amp[i] * Math.sin(ph) + rnd.nextGaussian() * 4).toInt().coerceIn(0, 255).toByte()
    }
    val out = ArrayList<String>()
    val d = Adsb.Demod { out.add(Adsb.hex(it)) }
    var o = 0
    for (len in intArrayOf(1000, 2222, 3000, 5778)) { d.process(iq.copyOfRange(o, o + len)); o += len }
    check(out == frames, "ADS-B demodulator: three messages out of the samples, across blocks (${out.size})")
    val noise = ByteArray(400_000) { (127.5 + rnd.nextGaussian() * 6).toInt().coerceIn(0, 255).toByte() }
    val bad = ArrayList<String>()
    Adsb.Demod { bad.add(Adsb.hex(it)) }.process(noise)
    check(bad.size <= 1, "ADS-B demodulator: (almost) nothing out of noise (${bad.size})")
}

private fun testFlags() {
    check(Flags.emoji("ES") == "\uD83C\uDDEA\uD83C\uDDF8" && Flags.emoji("us") == "\uD83C\uDDFA\uD83C\uDDF8" && Flags.emoji("x") == "", "Flags: two letters to regional indicators")
    check(Flags.emoji("GB-SCT").codePoints().toArray().toList() == listOf(0x1F3F4, 0xE0067, 0xE0062, 0xE0073, 0xE0063, 0xE0074, 0xE007F), "Flags: Scotland as a tag sequence")
    check(Flags.code("EA") == "ES" && Flags.code("EA6") == "ES" && Flags.code("EA8") == "IC" && Flags.code("*TA1") == "TR" && Flags.code("VP8/g") == "GS" &&
        Flags.code("DL") == "DE" && Flags.code("K") == "US" && Flags.code("KH6") == "US" && Flags.code("JA") == "JP" && Flags.code("1A") == null, "Flags: cty.dat prefixes")
    check(Flags.of(Cty.Entity("Spain", 14, 37, "EU", 40.4, -3.7, "EA")) == Flags.emoji("ES") && Flags.of(null) == "" &&
        Flags.of(Cty.Entity("Spratly Islands", 26, 50, "AS", 9.9, 114.4, "1S")) == "", "Flags: of an entity")
}

private fun testRateLimiter() {
    val r = RateLimiter(30)
    var late = 0
    for (i in 0 until 30) if (r.waitMs(1000L + i) != 0L) late++
    check(late == 0 && r.used(2000) == 30, "Rate limiter: the first 30 calls of the minute go at once")
    check(r.waitMs(2000) == 59_000L && r.waitMs(2000) == 59_001L, "Rate limiter: the next ones wait for the minute to pass")
    // whatever is asked, no minute ever holds more than 30 calls
    val q = RateLimiter(30); val at = ArrayList<Long>(); var now = 0L
    for (i in 0 until 500) { at.add(now + q.waitMs(now)); now += 37 }
    var worst = 0
    for (t in at) worst = maxOf(worst, at.count { it >= t && it < t + 60_000L })
    check(worst == 30, "Rate limiter: never more than 30 calls in any minute")
    check(RateLimiter(30).waitMs(5) == 0L && q.waitMs(at.last() + 120_000L) == 0L, "Rate limiter: free again after a quiet minute")
}

private fun testSdr() {
    run {   // SDRconnect audio to the phone: 48 kHz stereo -> 12 kHz mono
        check(Sdr.SdrConnect.audioStream(true) == """{"event_type":"audio_stream_enable","property":"","value":"true"}""", "SDRconnect: audio stream on")
        check(Sdr.SdrConnect.binaryType(byteArrayOf(1, 0, 5)) == Sdr.SdrConnect.BIN_AUDIO && Sdr.SdrConnect.binaryType(byteArrayOf(2, 0)) == Sdr.SdrConnect.BIN_IQ &&
            Sdr.SdrConnect.binaryType(byteArrayOf(1)) == -1, "SDRconnect: kind of a binary message")
        fun tone(hz: Double, n: Int, amp: Double): ByteArray {
            val b = ByteArray(2 + n * 4); b[0] = 1
            for (i in 0 until n) {
                val v = Math.round(amp * 32767 * Math.sin(2 * Math.PI * hz * i / 48000.0)).toInt()
                for (c in 0..1) { b[2 + i * 4 + c * 2] = (v and 0xFF).toByte(); b[3 + i * 4 + c * 2] = ((v shr 8) and 0xFF).toByte() }
            }
            return b
        }
        fun rms(x: FloatArray, from: Int, to: Int): Double { var s = 0.0; for (i in from until to) s += x[i] * x[i]; return Math.sqrt(s / (to - from)) }
        val out = FloatArray(4000)
        val d = Sdr.SdrConnect.AudioDown()
        val n = d.process(tone(1000.0, 9600, 0.5), out)
        check(n == 2400, "SDRconnect audio: one sample out of four")
        check(Math.abs(rms(out, 200, n) - 0.5 / Math.sqrt(2.0)) < 0.01 && Math.abs(d.peak - 0.5f) < 0.01f, "SDRconnect audio: 1 kHz passes at its level")
        var cross = 0; for (i in 201 until n) if (out[i - 1] < 0 && out[i] >= 0) cross++
        check(Math.abs(cross - 183) <= 2, "SDRconnect audio: 1 kHz stays 1 kHz at 12 kHz")
        val d2 = Sdr.SdrConnect.AudioDown()
        val n2 = d2.process(tone(9000.0, 9600, 0.5), out)
        check(rms(out, 200, n2) < 0.01, "SDRconnect audio: 9 kHz is removed before decimating")
        val d3 = Sdr.SdrConnect.AudioDown(); var total = 0
        val whole = tone(1000.0, 9600, 0.5)
        for (part in listOf(whole.copyOfRange(0, 2 + 1001 * 4), byteArrayOf(1, 0) + whole.copyOfRange(2 + 1001 * 4, whole.size))) total += d3.process(part, out)
        check(total == 2400, "SDRconnect audio: state kept between messages")
    }
    eq(Sdr.dopplerRx(437_800_000L, -7.0), 437_810_222L, "Doppler approaching raises the frequency")
    eq(Sdr.dopplerRx(437_800_000L, 0.0), 437_800_000L, "no Doppler")
    eq(Sdr.parseFreq("145,8"), 145_800_000L, "freq in MHz with comma"); eq(Sdr.parseFreq("433775000"), 433_775_000L, "freq in Hz"); eq(Sdr.parseFreq("x"), null, "bad freq")
    eq(Sdr.Rigctl.setFreq(145_800_000L), "F 145800000\n", "rigctl F"); eq(Sdr.Rigctl.setMode("NFM", 12500), "M FM 12500\n", "rigctl M for SDR++")
    eq(Sdr.Rigctl.appMode("FM"), "NFM", "rigctl FM is NFM"); eq(Sdr.Rigctl.reply("RPRT -1"), -1, "rigctl reply")
    eq(Sdr.SdrConnect.setFreq(101_000_000L), "{\"event_type\":\"set_property\",\"property\":\"device_vfo_frequency\",\"device\":\"primary\",\"value\":\"101000000\"}", "SDRconnect set_property")
    val m = Sdr.SdrConnect.parse("{\"event_type\":\"property_changed\",\"property\":\"device_center_frequency\",\"device\":\"primary\",\"value\":\"100000000\"}")!!
    eq(m.type, "property_changed", "SDRconnect event"); eq(m.value, "100000000", "SDRconnect value"); eq(Sdr.SdrConnect.parse("nope"), null, "SDRconnect bad")
    // RFC 6455 example: key dGhlIHNhbXBsZSBub25jZQ== -> s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
    eq(Sdr.WebSocket.accept("dGhlIHNhbXBsZSBub25jZQ=="), "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", "WebSocket accept key")
    check(Sdr.WebSocket.checkResponse("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", "dGhlIHNhbXBsZSBub25jZQ==") == null, "WebSocket handshake ok")
    check(Sdr.WebSocket.checkResponse("HTTP/1.1 404 Not Found", "x") != null, "WebSocket handshake refused")
    // RFC 6455 5.7: masked "Hello" with mask 37 fa 21 3d
    val f = Sdr.WebSocket.frame(1, "Hello".toByteArray(), byteArrayOf(0x37, 0xfa.toByte(), 0x21, 0x3d))
    eq(hex(f), "818537fa213d7f9f4d5158", "WebSocket masked frame")
    val back = Sdr.WebSocket.read(java.io.ByteArrayInputStream(f)); eq(String(back.payload), "Hello", "WebSocket frame read back"); check(back.fin && back.opcode == 1, "WebSocket frame flags")
    val unmasked = Sdr.WebSocket.read(java.io.ByteArrayInputStream(byteArrayOf(0x81.toByte(), 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f))); eq(String(unmasked.payload), "Hello", "WebSocket server frame")
    val big = ByteArray(70000) { 1 }
    val bf = Sdr.WebSocket.frame(2, big, byteArrayOf(1, 2, 3, 4)); eq(bf[1].toInt() and 0x7F, 127, "WebSocket 64-bit length")
    val dropped = Sdr.WebSocket.read(java.io.ByteArrayInputStream(bf), 1000); check(dropped.payload.isEmpty() && dropped.opcode == 2, "WebSocket big frame dropped")
    val mid = Sdr.WebSocket.frame(1, ByteArray(300), byteArrayOf(0, 0, 0, 0)); eq(mid[1].toInt() and 0x7F, 126, "WebSocket 16-bit length"); eq(Sdr.WebSocket.read(java.io.ByteArrayInputStream(mid)).payload.size, 300, "WebSocket 16-bit read")
    val hello = Sdr.SpyServer.hello("APRS")
    eq(hex(hello), "0000000008000000a4060002" + "41505253", "SpyServer hello")
    eq(hex(Sdr.SpyServer.setting(Sdr.SpyServer.SET_IQ_FREQUENCY, 145_800_000)), "02000000080000006500000040bbb008", "SpyServer set frequency")
    val hdr = java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(Sdr.SpyServer.PROTOCOL_VERSION).putInt(0x00010001).putInt(0).putInt(7).putInt(36).array()
    val h = Sdr.SpyServer.header(hdr); eq(h.type, 1, "SpyServer message type"); eq(h.bodySize, 36, "SpyServer body size")
    val syncBody = java.nio.ByteBuffer.allocate(36).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(10).putInt(145_000_000).putInt(145_800_000).array()
    eq(Sdr.SpyServer.sync(syncBody), Sdr.SpyServer.Sync(true, 10, 145_000_000L, 145_800_000L), "SpyServer client sync")
    val infoBody = java.nio.ByteBuffer.allocate(48).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(-1).putInt(10_000_000).putInt(0).putInt(0).putInt(0).putInt(21).putInt(24_000_000).putInt(1_800_000_000).array()
    val di = Sdr.SpyServer.deviceInfo(infoBody)!!; eq(di.serial, 0xFFFFFFFFL, "SpyServer serial unsigned"); eq(di.maxGainIndex, 21, "SpyServer gain steps"); eq(di.name, "Airspy R2 / Mini", "SpyServer device")
}

/** 8-bit IQ at Dsp.INPUT_RATE of [seconds]; [sample] gives the complex baseband (re, im) at time t, amplitude <= 1. */
private fun synthIq(seconds: Double, sample: (Double) -> Pair<Double, Double>): ByteArray {
    val n = (Dsp.INPUT_RATE * seconds).toInt()
    val b = ByteArray(2 * n)
    for (i in 0 until n) {
        val (re, im) = sample(i.toDouble() / Dsp.INPUT_RATE)
        b[2 * i] = (127.5 + 100 * re).toInt().coerceIn(0, 255).toByte(); b[2 * i + 1] = (127.5 + 100 * im).toInt().coerceIn(0, 255).toByte()
    }
    return b
}

/** Runs the receiver over [iq] in blocks and returns the audio after the first 0.05 s (filters settled). */
private fun runRx(rx: Dsp.Receiver, iq: ByteArray): FloatArray {
    val out = ArrayList<Float>()
    val buf = FloatArray(16384)
    var pos = 0
    while (pos < iq.size) {
        val len = minOf(32768, iq.size - pos)
        val m = rx.process(iq.copyOfRange(pos, pos + len), len, buf)
        for (i in 0 until m) out.add(buf[i])
        pos += len
    }
    return out.drop(rx.audioRate / 20).toFloatArray()
}

private fun toneRatio(a: FloatArray, rate: Int, f: Double, other: Double) = Dsp.goertzel(a, a.size, f, rate.toDouble()) / (Dsp.goertzel(a, a.size, other, rate.toDouble()) + 1e-9)

private fun testDsp() {
    val off = -Dsp.IF_OFFSET.toDouble()                      // the wanted signal sits IF_OFFSET below the tuner
    val pi2 = 2 * Math.PI
    // NFM: carrier at the wanted frequency, 1 kHz tone, 3 kHz deviation
    val fm = synthIq(0.3) { t -> val ph = pi2 * off * t + 3.0 * Math.sin(pi2 * 1000 * t); Math.cos(ph) to Math.sin(ph) }
    val rx = Dsp.Receiver(); rx.setOffset(off); rx.setMode("NFM", 0)
    val a = runRx(rx, fm)
    check(toneRatio(a, rx.audioRate, 1000.0, 2300.0) > 50, "NFM demodulates the 1 kHz tone"); check(rx.levelDb > -10, "NFM channel level")
    // a signal 60 kHz away is outside the channel
    val far = synthIq(0.3) { t -> val ph = pi2 * (off + 60_000) * t + 3.0 * Math.sin(pi2 * 1000 * t); Math.cos(ph) to Math.sin(ph) }
    val rxF = Dsp.Receiver(); rxF.setOffset(off); rxF.setMode("NFM", 0); runRx(rxF, far)
    check(rxF.levelDb < -40, "adjacent signal rejected (${"%.0f".format(rxF.levelDb)} dB)")
    rxF.squelchDb = -30.0; val muted = runRx(rxF, far); check(muted.all { it == 0f }, "NFM squelch mutes")
    // AM: carrier with 80 % 700 Hz modulation, 2 kHz off the wanted frequency then retuned with the NCO
    val am = synthIq(0.3) { t -> val env = 0.5 * (1 + 0.8 * Math.sin(pi2 * 700 * t)); val ph = pi2 * (off + 2000) * t; env * Math.cos(ph) to env * Math.sin(ph) }
    val rxA = Dsp.Receiver(); rxA.setOffset(off + 2000); rxA.setMode("AM", 0)
    check(toneRatio(runRx(rxA, am), rxA.audioRate, 700.0, 1700.0) > 50, "AM demodulates the 700 Hz tone (NCO retune)")
    // SSB: a tone 1 kHz above the carrier is heard in USB, not in LSB
    val usbTone = synthIq(0.3) { t -> val ph = pi2 * (off + 1000) * t; 0.5 * Math.cos(ph) to 0.5 * Math.sin(ph) }
    val rxU = Dsp.Receiver(); rxU.setOffset(off); rxU.setMode("USB", 0)
    val u = runRx(rxU, usbTone); check(toneRatio(u, rxU.audioRate, 1000.0, 2000.0) > 50, "USB hears +1 kHz")
    val rxL = Dsp.Receiver(); rxL.setOffset(off); rxL.setMode("LSB", 0)
    val pU = Dsp.goertzel(u, u.size, 1000.0, 12000.0); val l = runRx(rxL, usbTone)
    val pL = Dsp.goertzel(l, l.size, 1000.0, 12000.0)
    check(rxL.levelDb > -20 && pL < pU, "LSB weaker than USB on an upper tone")
    val lsbTone = synthIq(0.3) { t -> val ph = pi2 * (off - 1000) * t; 0.5 * Math.cos(ph) to 0.5 * Math.sin(ph) }
    val rxL2 = Dsp.Receiver(); rxL2.setOffset(off); rxL2.setMode("LSB", 0)
    check(toneRatio(runRx(rxL2, lsbTone), 12000, 1000.0, 2000.0) > 50, "LSB hears -1 kHz")
    // CW: a carrier on the frequency gives the 700 Hz tone
    val carrier = synthIq(0.3) { t -> val ph = pi2 * off * t; 0.5 * Math.cos(ph) to 0.5 * Math.sin(ph) }
    val rxC = Dsp.Receiver(); rxC.setOffset(off); rxC.setMode("CW", 0)
    check(toneRatio(runRx(rxC, carrier), 12000, Dsp.CW_TONE, 1500.0) > 50, "CW carrier gives the 700 Hz tone")
    // WFM: 75 kHz deviation 1 kHz tone, 48 kHz audio
    val wfm = synthIq(0.3) { t -> val ph = pi2 * off * t + 75.0 * Math.sin(pi2 * 1000 * t); Math.cos(ph) to Math.sin(ph) }
    val rxW = Dsp.Receiver(); rxW.setOffset(off); rxW.setMode("WFM", 0); eq(rxW.audioRate, 48000, "WFM audio rate")
    check(toneRatio(runRx(rxW, wfm), 48000, 1000.0, 3300.0) > 50, "WFM demodulates the 1 kHz tone")
    // discriminator tap for digital voice: 48 kHz, a +3 kHz carrier gives about +0.6
    val rxD = Dsp.Receiver(); rxD.setOffset(off); rxD.setMode("NFM", 0)
    var tapN = 0; var tapSum = 0.0
    rxD.discriminatorTap = { x, n -> for (i in 0 until n) { tapSum += x[i] }; tapN += n }
    runRx(rxD, synthIq(0.2) { t -> val ph = pi2 * (off + 3000) * t; 0.5 * Math.cos(ph) to 0.5 * Math.sin(ph) })
    check(tapN in 9000..9700 && Math.abs(tapSum / tapN - 0.6) < 0.05, "discriminator tap at 48 kHz (${tapN}, ${"%.2f".format(tapSum / tapN)})")
    eq(DigitalVoice.flag("DMR"), "-fs", "DV DMR flag"); eq(DigitalVoice.channels("Auto"), 2, "DV auto is two slots"); eq(DigitalVoice.channels("D-STAR"), 1, "DV D-STAR mono")
    eq(DigitalVoice.clean("\u001b[1;32m22:55:46 Sync: -DSTAR VOICE   SRC: ALBERTO\u001b[0m"), "22:55:46 Sync: -DSTAR VOICE   SRC: ALBERTO", "DV log line cleaned")
    // rtl_tcp
    eq(hex(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_FREQ, 145_800_000)), "0108b0bb40", "rtl_tcp set frequency")
    eq(hex(Sdr.RtlTcp.cmd(Sdr.RtlTcp.SET_FREQ_CORRECTION, -3)), "05fffffffd", "rtl_tcp negative ppm")
    eq(Sdr.RtlTcp.greeting(byteArrayOf(0x52, 0x54, 0x4c, 0x30, 0, 0, 0, 5, 0, 0, 0, 29)), Sdr.RtlTcp.Greeting("R820T / R860", 29), "rtl_tcp greeting")
    eq(Sdr.RtlTcp.greeting(ByteArray(12)), null, "rtl_tcp bad greeting")
    eq(Sdr.RtlTcp.driverUri(14423, 960000, 145800000, 1, true), "iqsrc://-a 127.0.0.1 -p 14423 -s 960000 -f 145800000 -P 1 -T 1", "driver intent")
    val w = Dsp.wavHeader(12000, 2400); eq(String(w, 0, 4), "RIFF", "WAV header"); eq(w.size, 44, "WAV header size")
}

private fun testSigId() {
    // ustar header built here: "./data.sqlite", 995328 bytes
    val h = ByteArray(512)
    fun put(o: Int, t: String) { t.toByteArray().copyInto(h, o) }
    put(0, "./data.sqlite"); put(100, "0000666"); put(108, "0000000"); put(116, "0000000"); put(124, "00003630000"); put(136, "00000000000")
    h[156] = '0'.code.toByte(); put(257, "ustar"); put(263, "00")
    var sum = 0; for (i in 0 until 512) sum += if (i in 148 until 156) 32 else (h[i].toInt() and 0xFF)
    put(148, String.format("%06o", sum)); h[154] = 0; h[155] = ' '.code.toByte()
    eq(SigId.tarHeader(h), SigId.TarEntry("./data.sqlite", 995328, '0'), "tar header")
    eq(SigId.findMember(h + ByteArray(1024), "data.sqlite"), 512L to 995328L, "tar member found")
    h[0] = 'X'.code.toByte(); eq(SigId.tarHeader(h), null, "tar header with bad checksum"); eq(SigId.tarHeader(ByteArray(512)), null, "tar end")
    val rel = SigId.parseRelease("{\"sigID_DB\":{\"version\":74,\"url\":\"https://x/v74.tar\",\"sha256_hash\":\"ab\",\"total_bytes\":302172160}}")
    eq(rel, SigId.Release(74, "https://x/v74.tar", "ab", 302172160L), "release info")
    eq(SigId.parseIndex("{\"102\":{\"title\":\"Single Sideband Voice\",\"added_in_db\":65}}"), mapOf("Single Sideband Voice" to "102"), "index")
    val ssb = SigId.Signal(1, "Single Sideband Voice", "u", "**SSB** voice", listOf(3_000_000, 30_000_000), listOf(1900), listOf("SSB"), listOf("AM"), listOf("Amateur Radio"), listOf("Worldwide"))
    val aprs = SigId.Signal(2, "APRS", "u", "Automatic Packet Reporting System", listOf(144_390_000, 144_800_000, 145_825_000), listOf(12_500), listOf("NFM"), listOf("AFSK"), listOf("Amateur Radio", "Digital"), emptyList())
    val radar = SigId.Signal(3, "OTH Radar", "u", "radar", listOf(6_000_000, 22_000_000), listOf(40_000), emptyList(), listOf("FMCW"), listOf("Radar"), emptyList())
    val all = listOf(ssb, aprs, radar)
    eq(SigId.freqDistance(ssb.frequencies, 14_200_000, 0.02), 0.0, "inside a band")
    eq(SigId.freqDistance(aprs.frequencies, 144_801_000, 0.02)?.let { it < 1e-5 }, true, "near a spot frequency")
    eq(SigId.freqDistance(aprs.frequencies, 430_000_000, 0.02), null, "far from every frequency")
    eq(SigId.search(all, SigId.Query(freqHz = 144_800_000)).map { it.id }, listOf(2), "search by frequency")
    eq(SigId.search(all, SigId.Query(freqHz = 14_200_000)).map { it.id }, listOf(3, 1), "search by frequency, bands (same score: by name)")
    eq(SigId.search(all, SigId.Query(freqHz = 14_200_000, bandwidthHz = 2_000)).map { it.id }, listOf(1), "frequency and bandwidth")
    eq(SigId.search(all, SigId.Query(text = "packet")).map { it.id }, listOf(2), "search by text")
    eq(SigId.search(all, SigId.Query(category = "radar")).map { it.id }, listOf(3), "search by category")
    eq(SigId.search(all, SigId.Query(mode = "afsk")).map { it.id }, listOf(2), "search by modulation")
    eq(ssb.freqText, "3 MHz – 30 MHz", "band text"); eq(SigId.hz(12_500), "12.5 kHz", "hz text")
    eq(SigId.plainText("### SUMMARY\nA **bold** [link](http://x) text"), "SUMMARY\nA bold link text", "markdown to text")
    check(SigId.isSqlite("SQLite format 3\u0000xx".toByteArray()), "sqlite header"); check(!SigId.isSqlite("<html>".toByteArray()), "not sqlite")
    eq(SigId.mediaUrl("102", "ogg"), "https://raw.githubusercontent.com/AresValley/Artemis-Database/master/static/102/media/1.ogg", "media url")
}

private fun noiseOf(seed: Long) = java.util.Random(seed)

private fun genPsk(text: String, baud: Double, f: Double, snrAmp: Double, seed: Long = 1): FloatArray {
    val bits = StringBuilder("0".repeat(40))
    for (c in text) bits.append(Modems.varicode(c)).append("00")
    bits.append("1".repeat(40))
    val sps = (Modems.RATE / baud).toInt()
    val out = FloatArray(bits.length * sps + 500)
    val rnd = noiseOf(seed); var sign = 1.0; var n = 0
    for (b in bits) {
        for (k in 0 until sps) {
            val a = if (b == '0') sign * Math.cos(Math.PI * k / sps) else sign
            out[n] = (0.5 * a * Math.cos(2 * Math.PI * f * n / Modems.RATE) + snrAmp * rnd.nextGaussian()).toFloat(); n++
        }
        if (b == '0') sign = -sign
    }
    return out
}

private fun genRtty(text: String, baud: Double, shift: Double, f: Double, noise: Double, seed: Long = 2): FloatArray {
    val levels = ArrayList<Pair<Boolean, Double>>()                    // (mark, bit lengths)
    levels.add(true to 15.0)
    fun ch(code: Int) { levels.add(false to 1.0); for (i in 0 until 5) levels.add(((code shr i) and 1 == 1) to 1.0); levels.add(true to 1.5) }
    var figs = false; ch(0x1F)
    for (c in text) { val (code, f2) = Modems.baudot(c) ?: continue; if (c != ' ' && f2 != figs) { ch(if (f2) 0x1B else 0x1F); figs = f2 }; ch(code); if (c == ' ') figs = false }
    levels.add(true to 10.0)
    val total = levels.sumOf { it.second } * Modems.RATE / baud
    val out = FloatArray(total.toInt() + 10); val rnd = noiseOf(seed)
    var ph = 0.0; var n = 0
    for ((mark, len) in levels) {
        val ns = (len * Modems.RATE / baud).toInt()
        val fr = f + (if (mark) shift else -shift) / 2
        for (k in 0 until ns) { ph += 2 * Math.PI * fr / Modems.RATE; out[n++] = (0.5 * Math.sin(ph) + noise * rnd.nextGaussian()).toFloat() }
    }
    return out.copyOf(n)
}

private fun genCw(text: String, wpm: Int, f: Double, noise: Double, seed: Long = 3): FloatArray {
    val dit = 1.2 / wpm * Modems.RATE
    val keys = ArrayList<Pair<Boolean, Double>>()                       // (on, dits)
    keys.add(false to 10.0)
    for (w in text.split(' ')) {
        for (c in w) { for (e in Morse.pattern(c)!!) { keys.add(true to (if (e == '.') 1.0 else 3.0)); keys.add(false to 1.0) }; keys.add(false to 2.0) }
        keys.add(false to 4.0)
    }
    keys.add(false to 20.0)
    val out = FloatArray((keys.sumOf { it.second } * dit).toInt() + 10); val rnd = noiseOf(seed); var n = 0; var env = 0.0
    for ((on, d) in keys) for (k in 0 until (d * dit).toInt()) {
        env += ((if (on) 1.0 else 0.0) - env) * 0.03                      // ~4 ms edges
        out[n] = (0.5 * env * Math.sin(2 * Math.PI * f * n / Modems.RATE) + noise * rnd.nextGaussian()).toFloat(); n++
    }
    return out.copyOf(n)
}

private fun decode(m: Modems.Modem, x: FloatArray, block: Int = 512): String {
    val sb = StringBuilder(); m.onText = { sb.append(it) }
    var p = 0; while (p < x.size) { val n = minOf(block, x.size - p); m.process(x.copyOfRange(p, p + n), n); p += n }
    return sb.toString()
}

private fun testModems() {
    eq(Modems.varicode('e'), "11", "varicode e"); eq(Modems.varicode('E'), "1110111", "varicode E"); eq(Modems.varicode(' '), "1", "varicode space")
    val msg = "CQ CQ DE EA7JRS EA7JRS K"
    for (baud in listOf(31.25, 62.5, 125.0)) {
        val psk = Modems.Psk(1003.0, baud)                                 // 3 Hz off: AFC pulls it in
        val got = decode(psk, genPsk(msg, baud, 1000.0, 0.15))
        check(got.contains(msg), "BPSK${baud.toInt()} decodes (got '$got')")
    }
    val noisy = decode(Modems.Psk(1500.0), genPsk(msg, 31.25, 1500.0, 0.6, 7))
    check(noisy.contains("EA7JRS"), "BPSK31 in noise (got '$noisy')")
    val tx = "RYRY THE QUICK BROWN FOX 0123456789 EA7JRS"
    for ((b, s) in listOf(45.45 to 170.0, 50.0 to 450.0, 75.0 to 170.0)) {
        val got = decode(Modems.Rtty(1500.0, b, s), genRtty(tx, b, s, 1500.0, 0.2))
        check(got.contains(tx), "RTTY $b/$s decodes (got '$got')")
    }
    val rev = decode(Modems.Rtty(1500.0, 45.45, 170.0, reverse = true), genRtty("ABC", 45.45, -170.0, 1500.0, 0.05))
    check(rev.contains("ABC"), "RTTY reverse (got '$rev')")
    val cw = decode(Modems.Cw(700.0, 20), genCw("VVV CQ DE EA7JRS TEST", 20, 700.0, 0.1))
    check(cw.replace(" ", "").contains("CQDEEA7JRSTEST"), "CW decodes after the first letters (got '$cw')")
    val cw30 = decode(Modems.Cw(650.0, 18), genCw("PARIS PARIS", 30, 650.0, 0.15))
    check(cw30.replace(" ", "").contains("PARIS"), "CW adapts to 30 wpm (got '$cw30')")
    val sp = Modems.Spectrum(1024); val tone = FloatArray(1024) { Math.sin(2 * Math.PI * 1000 * it / 8000.0).toFloat() }
    sp.push(tone, 1024); val db = sp.db(); val peak = db.indices.maxByOrNull { db[it] }!!
    eq(peak, 128, "spectrum peak at 1000 Hz (bin 128)")
    val rs = Modems.Resampler12to8(); val x12 = FloatArray(12000) { Math.sin(2 * Math.PI * 1000 * it / 12000.0).toFloat() }
    val y = FloatArray(9000); val ny = rs.process(x12, 12000, y)
    eq(ny, 8000, "12 -> 8 kHz count"); check(Dsp.goertzel(y.copyOfRange(500, 8000), 7500, 1000.0, 8000.0) > 100 * Dsp.goertzel(y.copyOfRange(500, 8000), 7500, 1700.0, 8000.0), "12 -> 8 kHz keeps the tone")
    eq(Modems.create("RTTY 45/170", 1500.0).label, "RTTY 45/170", "mode factory"); eq(Modems.create("BPSK63", 1000.0).label, "BPSK62", "mode factory PSK63 (62.5 Bd)")
    // IQ panadapter spectrum: a complex tone at +rate/8 peaks at the fft-shifted bin centre + size/8
    val iq = Modems.IqSpectrum(1024)
    val cRe = FloatArray(1024) { Math.cos(2 * Math.PI * (1024 / 8) * it / 1024.0).toFloat() }
    val cIm = FloatArray(1024) { Math.sin(2 * Math.PI * (1024 / 8) * it / 1024.0).toFloat() }
    iq.push(cRe, cIm, 1024); check(iq.ready, "iq spectrum filled")
    val idb = iq.db(); val ipeak = idb.indices.maxByOrNull { idb[it] }!!
    eq(ipeak, 1024 / 2 + 1024 / 8, "iq peak at +rate/8 (bin 640, fft-shifted)")
    val cRe2 = FloatArray(1024) { Math.cos(-2 * Math.PI * (1024 / 4) * it / 1024.0).toFloat() }
    val cIm2 = FloatArray(1024) { Math.sin(-2 * Math.PI * (1024 / 4) * it / 1024.0).toFloat() }
    iq.push(cRe2, cIm2, 1024); val idb2 = iq.db(); val ipeak2 = idb2.indices.maxByOrNull { idb2[it] }!!
    eq(ipeak2, 1024 / 2 - 1024 / 4, "iq peak at -rate/4 (bin 256)")
}

private fun testG3ruh() {
    eq(Ax25Frame.create("EA7JRS-7", Tocall.APP, emptyList(), ">x")?.toTnc2(), "EA7JRS-7>APLJRA:>x", "app tocall APLJRA")
    eq(G3ruh.crc16("123456789".toByteArray()), 0x906E, "CRC-16/X.25 check value")
    val frame = Tnc2.parse("EA7JRS-7>APZLRP,WIDE1-1:!3711.49N/00356.20W>APRS LoRa+ FSK test")!!.encode()
    // 1) bits straight into the decoder's line stage: 5 samples a bit, ideal levels
    val line = G3ruh.lineBits(G3ruh.hdlcBits(frame))
    val got = ArrayList<ByteArray>()
    val dec = G3ruh.Decoder(48_000.0) { got.add(it) }
    val sq = FloatArray(line.size * 5) { if (line[it / 5] == 1) 1f else -1f }
    dec.process(sq, sq.size)
    check(got.size == 1 && got[0].contentEquals(frame), "G3RUH line bits decode (${got.size})")
    // 2) end to end: FM at 960 kS/s (±3 kHz, 9600 bd, smoothed), the app receiver in NFM 20 kHz, the discriminator tap
    val off = -Dsp.IF_OFFSET.toDouble()
    val spb = Dsp.INPUT_RATE / 9600.0
    val nrz = DoubleArray((line.size * spb).toInt() + 2000)
    var lvl = -1.0
    for (i in nrz.indices) { val b = (i / spb).toInt(); val target = if (b < line.size && line[b] == 1) 1.0 else -1.0; lvl += (target - lvl) * 0.12; nrz[i] = lvl }
    var ph = 0.0
    val rnd = java.util.Random(5)
    val iq = ByteArray(2 * nrz.size)
    for (i in nrz.indices) {
        ph += 2 * Math.PI * (off + 3000.0 * nrz[i]) / Dsp.INPUT_RATE
        iq[2 * i] = (127.5 + 90 * Math.cos(ph) + 6 * rnd.nextGaussian()).toInt().coerceIn(0, 255).toByte()
        iq[2 * i + 1] = (127.5 + 90 * Math.sin(ph) + 6 * rnd.nextGaussian()).toInt().coerceIn(0, 255).toByte()
    }
    val rx = Dsp.Receiver(); rx.setOffset(off); rx.setMode("NFM", 20_000)
    val got2 = ArrayList<ByteArray>()
    val dec2 = G3ruh.Decoder(48_000.0) { got2.add(it) }
    rx.discriminatorTap = { x, n -> dec2.process(x, n) }
    runRx(rx, iq)
    check(got2.any { it.contentEquals(frame) }, "G3RUH 9600 through the RTL receiver (${got2.size} frames, ${dec2.badFcs} bad FCS)")
    eq(Ax25Frame.decode(got2.firstOrNull() ?: ByteArray(0))?.toTnc2(), "EA7JRS-7>APZLRP,WIDE1-1:!3711.49N/00356.20W>APRS LoRa+ FSK test", "G3RUH frame to TNC2")
}

private fun testReceivers() {
    val b = Receivers.entry("aa:bb:cc:dd:ee:01", " Supreme FSK ")!!
    eq(b, Receivers.Entry(Receivers.Kind.BLE, "AA:BB:CC:DD:EE:01", "Supreme FSK"), "BLE receiver")
    eq(Receivers.entry("192.168.4.1", "")!!.address, "192.168.4.1:8001", "TCP receiver, default KISS port")
    eq(Receivers.entry("tracker.local:9001", "x")!!.hostPort(), "tracker.local" to 9001, "TCP receiver with port")
    eq(Receivers.entry("host:99999", ""), null, "bad port refused"); eq(Receivers.entry("a b", ""), null, "spaces refused")
    val list = listOf(b, Receivers.entry("10.0.0.5:8001", "Roof")!!.copy(enabled = false))
    eq(Receivers.decode(Receivers.encode(list)), list, "receivers saved and read back")
    eq(b.key, "ble:AA:BB:CC:DD:EE:01", "receiver key")
    eq(Control.parse("{\"e\":\"cwrx\",\"on\":true,\"t\":\"E\",\"dbm\":-104,\"snr\":15,\"wpm\":18}"), ControlEvent.CwRx(true, "E", -104.0, 15.0, 18), "cwrx event")
    eq(Control.parse("{\"e\":\"cwrx\",\"on\":false,\"dbm\":-150,\"snr\":0,\"wpm\":0}"), ControlEvent.CwRx(false, null, -150.0, 0.0, 0), "cwrx end")
}

private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

private fun testSpots() {
    eq(Spots.locator(37.38, -5.98), "IM77aj", "locator Seville"); eq(Spots.locator(40.35, -74.66, 4), "FN20", "locator 4")
    eq(Spots.locator(40.35, -74.66), "FN20qi", "locator 6"); eq(Spots.stationCall("ea7jrs-7"), "EA7JRS", "call without SSID")
    eq(Spots.rfHz(14_074_000, "USB", 1500.0), 14_075_500L, "USB tone"); eq(Spots.rfHz(7_030_000, "CW", 900.0), 7_030_200L, "CW tone")
    eq(Spots.rfHz(7_074_000, "LSB", 1000.0), 7_073_000L, "LSB tone"); eq(Spots.band(14_074_000), "20m", "band"); eq(Spots.band(15_000_000), null, "no band")
    eq(Spots.deCallAndGrid("CQ EA7JRS IM77"), "EA7JRS" to "IM77", "CQ sender"); eq(Spots.deCallAndGrid("CQ DX EA7JRS IM77"), "EA7JRS" to "IM77", "CQ DX sender")
    eq(Spots.deCallAndGrid("CQ POTA K1ABC FN42"), "K1ABC" to "FN42", "directional CQ"); eq(Spots.deCallAndGrid("EA7JRS K1JT FN20"), "K1JT" to "FN20", "reply sender")
    eq(Spots.deCallAndGrid("K1JT EA7JRS R FN20")?.second, "FN20", "R grid"); eq(Spots.deCallAndGrid("K1JT <PJ4/K1ABC> -12")?.first, "PJ4/K1ABC", "hashed sender")
    eq(Spots.deCallAndGrid("TNX 73 GL"), null, "free text")
    val d = Wsjt.Decode(1_700_000_010_000, -12.0, 0.3, 1291, "CQ R7IW LN35")
    eq(Spots.fromWsjt(d, Wsjt.Mode.FT8, 14_074_000, "EA7JRS-7", "IM77"), Spots.Spot("R7IW", "LN35", 14_075_291, "FT8", -12, 1_700_000_010_000), "FT8 spot")
    eq(Spots.fromWsjt(d.copy(message = "EA7JRS R7IW -05"), Wsjt.Mode.FT8, 14_074_000, "EA7JRS", "IM77"), null, "report without locator: no spot")
    eq(Spots.fromWsjt(d.copy(message = "CQ EA7JRS IM77"), Wsjt.Mode.FT8, 14_074_000, "EA7JRS", "IM77aj"), null, "no self spot")
    eq(Spots.fromWsjt(d, Wsjt.Mode.FT8, null, "EA7JRS", "IM77"), null, "no spot without frequency")
    eq(Spots.wsprFields("EA7JRS IM76 23"), Triple("EA7JRS", "IM76", 23), "WSPR type 1"); eq(Spots.wsprFields("PJ4/K1ABC 37"), Triple("PJ4/K1ABC", "", 37), "WSPR type 2")
    eq(Spots.wsprFields("<EA7JRS> IM76AB"), Triple("EA7JRS", "IM76AB", null), "FST4W type 3"); eq(Spots.wsprFields("<...> IM76AB"), null, "unknown hash")
    val w = Wsjt.Decode(1_762_473_720_000, -21.0, 0.3, 1493, "K1JT FN20 30", 14_097_093, -1)       // 2025-11-07 00:02 UTC
    val body = Spots.WsprNet.spot(w, Wsjt.Mode.WSPR, "EA7JRS-7", "IM77aj", 14_095_600, "ALP 0.1.0")!!
    eq(body, "function=wspr&date=251107&time=0002&sig=-21&dt=0.3&drift=-1&tqrg=14.097093&tcall=K1JT&tgrid=FN20&dbm=30&version=ALP+0.1.0&rcall=EA7JRS&rgrid=IM77aj&rqrg=14.095600&mode=2", "WSPRnet spot")
    check(Spots.WsprNet.spot(w.copy(message = "<EA7JRS> IM76AB"), Wsjt.Mode.FST4W_300, "EA7JRS", "IM77", 14_095_600, "x")!!.contains("tcall=EA7JRS&tgrid=IM76AB&dbm=&"), "FST4W type 3 to WSPRnet")
    eq(Spots.WsprNet.modeCode(Wsjt.Mode.FST4W_120), "3", "WSPRnet FST4W-120"); eq(Spots.WsprNet.modeCode(Wsjt.Mode.FST4W_1800), "30", "WSPRnet FST4W-1800")
    check(Spots.WsprNet.status(Wsjt.Mode.WSPR, "EA7JRS", "IM77", 14_095_600, "x").startsWith("function=wsprstat&rcall=EA7JRS"), "WSPRnet status")
    check(Spots.WsprNet.accepted("1 out of 1 spot(s) added"), "WSPRnet reply")
    // IPFIX
    val rx = Spots.Receiver("EA7JRS", "IM77aj", "APRS LoRa+ 0.1.0", "Dipole")
    val sp = Spots.Spot("K1JT", "FN20", 14_075_291, "FT8", -12, 1_700_000_000_000)
    val rec = Spots.Ipfix.record(sp)
    eq(rec.size, 1 + 4 + 5 + 1 + 1 + 3 + 1 + 4 + 1 + 4, "IPFIX record size"); eq(rec[5].toInt(), 0, "frequency high byte")
    eq(((rec[6].toLong() and 0xff) shl 24) or ((rec[7].toLong() and 0xff) shl 16) or ((rec[8].toLong() and 0xff) shl 8) or (rec[9].toLong() and 0xff), 14_075_291L, "frequency")
    eq(rec[10].toInt(), -12, "SNR byte")
    val pk = Spots.Ipfix.packets(rx, listOf(sp), true, 0, 0x12345678, 1_700_000_100)
    eq(pk.size, 1, "one datagram"); val p0 = pk[0].first
    eq(u16(p0, 0), 10, "IPFIX version"); eq(u16(p0, 2), p0.size, "IPFIX length"); eq(p0.size % 4, 0, "IPFIX padded")
    var off = 16; val ids = ArrayList<Int>()
    while (off < p0.size) { ids.add(u16(p0, off)); val l = u16(p0, off + 2); check(l % 4 == 0 && l > 0, "IPFIX set length"); off += l }
    eq(off, p0.size, "IPFIX sets fill the message"); eq(ids, listOf(2, 3, 0x50e2, 0x50e3), "IPFIX sets")
    val many = (1..60).map { sp.copy(call = "K${it}AB") }
    val pks = Spots.Ipfix.packets(rx, many, false, 100, 1, 1)
    check(pks.size > 1 && pks.all { it.first.size <= Spots.Ipfix.MAX_UDP_PAYLOAD }, "IPFIX split by size"); eq(pks.sumOf { it.second }, 60, "IPFIX all spots")
    eq(((pks[1].first[8].toLong() and 0xff) shl 24) or ((pks[1].first[9].toLong() and 0xff) shl 16) or ((pks[1].first[10].toLong() and 0xff) shl 8) or (pks[1].first[11].toLong() and 0xff),
        100L + pks[0].second, "IPFIX sequence counts spots")
    val q = Spots.PskQueue(rx, 7)
    check(q.add(sp, 0), "first spot queued"); check(!q.add(sp.copy(snr = -3), 60_000), "same call, band and mode: once per hour")
    check(q.add(sp.copy(mode = "FT4"), 60_000), "other mode"); check(!q.add(sp.copy(call = "EA7JRS"), 0), "not ourselves")
    eq(q.take(300_000).size, 1, "datagram with descriptors"); eq(q.size, 0, "queue emptied"); eq(q.take(600_000).size, 1, "descriptors again (3 times)")
    q.take(900_000); eq(q.take(1_200_000).size, 0, "nothing to send")
    // WSJT-X UDP
    val hb = Spots.WsjtxUdp.heartbeat("ALP", "0.1.0")
    eq(hb.toList().take(16).map { it.toInt() and 0xff }, listOf(0xad, 0xbc, 0xcb, 0xda, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 3), "UDP header and type")
    eq(hb.size, 12 + 4 + 3 + 4 + 4 + 5 + 4, "heartbeat size")
    val dec = Spots.WsjtxUdp.forDecode("ALP", d, Wsjt.Mode.FT8, 14_074_000)!!
    eq(dec.size, 12 + 7 + 1 + 4 + 4 + 8 + 4 + 5 + 16 + 2, "decode size"); eq(dec[11].toInt(), 2, "decode type")
    eq(((dec[20].toLong() and 0xff) shl 24) or ((dec[21].toLong() and 0xff) shl 16) or ((dec[22].toLong() and 0xff) shl 8) or (dec[23].toLong() and 0xff), 80_010_000L, "QTime ms of day")
    eq(Spots.WsjtxUdp.forDecode("ALP", w, Wsjt.Mode.WSPR, 14_095_600)!![11].toInt(), 10, "WSPRDecode type")
    eq(Spots.WsjtxUdp.marker(Wsjt.Mode.FST4W_120), "`", "FST4W marker"); eq(Spots.WsjtxUdp.status("ALP", 14_074_000, Wsjt.Mode.FT8, "EA7JRS", "IM77", true)[11].toInt(), 1, "status type")
    // CW
    val cw = Spots.CwSpotter("EA7JRS")
    eq(cw.feed("CQ CQ DE K1JT K1JT K", 0), listOf(Spots.CwHit("K1JT", "CQ")), "CW CQ call"); eq(cw.feed(" CQ DE K1JT K1JT K ", 1000), emptyList<Spots.CwHit>(), "CW given once")
    eq(cw.feed("VVV VVV DE DB0ABC DB0ABC ", 2000), listOf(Spots.CwHit("DB0ABC", "BEACON")), "CW beacon"); eq(cw.feed("CQ DE EA7JRS EA7JRS ", 3000), emptyList<Spots.CwHit>(), "CW not ourselves")
    eq(cw.feed("5NN TU K1ABC ", 4000), emptyList<Spots.CwHit>(), "CW call once: nothing"); eq(cw.feed("CQ TEST DL1ABC ", 5000) + cw.feed("TEST DL1ABC ", 9000), listOf(Spots.CwHit("DL1ABC", "CQ")), "CW contest CQ")
    eq(Spots.skimmerLine("EA7JRS-7", 14_025_100, "K1JT", 15, 22, "CQ", 1_762_473_720_000), "DX de EA7JRS-#:   14025.1  K1JT         CW    15 dB  22 WPM  CQ      0002Z", "skimmer line")
}

private fun testLogbook() {
    val a = Adif.parse("Exported by qFT8\n<ADIF_VER:5>3.1.0 <PROGRAMID:4>qFT8 <EOH>\n<call:5>K1ABC <qso_date:8>20261107 <time_on:6>065500 <band:3>20m <freq:9>14.075291 <mode:3>FT8 <rst_sent:3>-10 <rst_rcvd:3>-12 <gridsquare:4>FN42 <eor>\n" +
        "<CALL:6>DL1ABC <QSO_DATE:8>20261107 <TIME_ON:4>0710 <FREQ:6>14.025 <MODE:2>CW <EOR>")
    eq(a.header["PROGRAMID"], "qFT8", "ADIF header"); eq(a.records.size, 2, "ADIF records"); eq(a.records[0]["CALL"], "K1ABC", "ADIF field upper case")
    eq(Adif.parse("<CALL:4>EA1A<QSO_DATE:8>20260101<EOR>").records[0]["QSO_DATE"], "20260101", "ADIF without header")
    eq(Adif.record(mapOf("CALL" to "EA7JRS", "NAME" to "")), "<CALL:6>EA7JRS <EOR>", "ADIF record"); eq(Adif.band(7_025_000), "40m", "ADIF band")
    eq(Adif.band(432_100_000), "70cm", "ADIF band UHF"); eq(Adif.band(15_000_000), null, "outside bands")
    val q = Qso.create("1", "dl1abc", 1_762_498_500_000, 7_025_000, "CW", "", "599", "579", "EA7JRS-7", "IM77aj", name = "Hans")
    eq(q.date, "20251107", "QSO date"); eq(q.time, "065500", "QSO time"); eq(q.band, "40m", "QSO band"); eq(q["FREQ"], "7.025000", "QSO freq")
    eq(q["STATION_CALLSIGN"], "EA7JRS", "station call"); check(!q.export().containsKey("APP_ALP_ID"), "internal fields not exported")
    val lb = Logbook(listOf(q))
    var n = 0
    eq(lb.import("<CALL:6>DL1ABC <QSO_DATE:8>20251107 <TIME_ON:4>0656 <FREQ:5>7.025 <MODE:2>CW <GRIDSQUARE:6>JO62qm <EOR> <CALL:5>K1ABC <QSO_DATE:8>20251107 <TIME_ON:4>0700 <BAND:3>20m <MODE:4>MFSK <SUBMODE:3>FT4 <EOR>") { "n${++n}" }, 1 to 1, "import: one new, one merged")
    eq(lb.qsos.first { it.call == "DL1ABC" }["GRIDSQUARE"], "JO62qm", "merged field"); eq(lb.qsos.first { it.call == "K1ABC" }.id, "n1", "new id")
    eq(lb.confirm("<CALL:6>DL1ABC <QSO_DATE:8>20251107 <TIME_ON:4>0720 <BAND:3>40M <MODE:2>CW <QSL_RCVD:1>Y <QSLRDATE:8>20251110 <EOR>", Logbook.LOTW_CONFIRMED), 1, "LoTW confirmation")
    check(lb.qsos.first { it.call == "DL1ABC" }.lotwConfirmed, "LoTW confirmed flag")
    eq(lb.confirm("<CALL:6>DL1ABC <QSO_DATE:8>20251107 <TIME_ON:4>0900 <BAND:3>40M <MODE:2>CW <QSL_RCVD:1>Y <EOR>", Logbook.LOTW_CONFIRMED), 0, "too far in time")
    eq(lb.confirm("<CALL:5>K1ABC <QSO_DATE:8>20251107 <TIME_ON:4>0700 <BAND:3>20m <MODE:3>FT4 <EOR>", Logbook.EQSL_CONFIRMED), 1, "FT4 as MFSK/FT4 or FT4")
    eq(Logbook.load(lb.adif("APRS LoRa+", "0.1", true)).qsos.size, 2, "log saved and loaded")
    // QRZ
    check(QrzLog.insertBody("ABCD-1234", q).startsWith("KEY=ABCD-1234&ACTION=INSERT&ADIF=%3CCALL%3A6%3EDL1ABC"), "QRZ insert body")
    eq(QrzLog.insertResult("RESULT=OK&LOGIDS=130877825&COUNT=1"), true to "Log id 130877825", "QRZ OK")
    eq(QrzLog.insertResult("RESULT=FAIL&REASON=Unable to add QSO to database: duplicate&EXTENDED=").first, true, "QRZ duplicate = already there")
    eq(QrzLog.insertResult("RESULT=AUTH").first, false, "QRZ auth")
    eq(QrzLog.parse("RESULT=OK&COUNT=1&ADIF=&lt;call:5&gt;K1ABC &lt;app_qrzlog_status:1&gt;C &lt;eor&gt;")["ADIF"], "<call:5>K1ABC <app_qrzlog_status:1>C <eor>", "QRZ fetch ADIF")
    check(QrzLog.fetchBody("K", "2026-11-01").endsWith("OPTION=MODSINCE%3A2026-11-01%3BSTATUS%3ACONFIRMED%3BTYPE%3AADIF"), "QRZ fetch body")
    // eQSL
    val eu = Eqsl.uploadUrl("EA7JRS", "pa ss", "Home", q)
    check(eu.startsWith("https://www.eqsl.cc/qslcard/importADIF.cfm?ADIFData=APRSLoRaPlus%20%3CADIF_VER%3A4%3E1.00"), "eQSL upload URL")
    check(eu.contains("%3CEQSL_PSWD%3A5%3Epa%20ss") && eu.contains("%3CTIME_ON%3A4%3E0655") && eu.contains("APP_EQSL_QTH_NICKNAME%3A4%3EHome"), "eQSL fields")
    eq(Eqsl.uploadResult("Result: 1 out of 1 records added"), Eqsl.Result.ADDED, "eQSL added"); eq(Eqsl.uploadResult("Bad record: Duplicate"), Eqsl.Result.DUPLICATE, "eQSL duplicate")
    eq(Eqsl.inboxLink("Your ADIF log file has been built <a href=\"downloadedfiles/abc123.adi\">.ADI file</a>"), "https://www.eqsl.cc/qslcard/downloadedfiles/abc123.adi", "eQSL inbox link")
    eq(Eqsl.inboxError("You have no log entries"), null, "eQSL empty inbox")
    // LoTW
    eq(Lotw.tqslMode("SSB", "USB"), "SSB", "TQSL SSB"); eq(Lotw.tqslMode("MFSK", "FT4"), "FT4", "TQSL FT4"); eq(Lotw.tqslMode("FT8", ""), "FT8", "TQSL FT8")
    eq(Lotw.tqslMode("PSK", "BPSK31"), "PSK31", "TQSL PSK31"); eq(Lotw.tqslMode("MFSK", "JS8"), "DATA", "TQSL JS8")
    val st = Lotw.Station("EA7JRS", 281, "IM77aj", "14", "37")
    eq(Lotw.stationSigndata(st), "14IM77AJ37", "station signdata (CQZ, GRIDSQUARE, ITUZ)")
    val c = Lotw.contact(q)!!
    eq(c, Lotw.Contact("DL1ABC", "40M", "CW", "7.025000", "2025-11-07", "06:55:00Z", "", ""), "LoTW contact")
    eq(Lotw.contactSigndata("14IM77AJ37", c), "14IM77AJ3740MDL1ABC7.025000CW2025-11-0706:55:00Z", "contact signdata")
    val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
    val (tq8, ids) = Lotw.sign(listOf(q, q.with("BAND" to "", "FREQ" to "")), st, kp.private, byteArrayOf(1, 2, 3), "APRS LoRa+ test")
    eq(ids, listOf("1"), "contacts without band left out")
    val text = java.util.zip.GZIPInputStream(tq8.inputStream()).readBytes().toString(Charsets.US_ASCII)
    check(text.contains("<Rec_Type:5>tCERT\n<CERT_UID:1>1\n<CERTIFICATE:5>AQID\n<eor>"), "tq8 certificate")
    check(text.contains("<CALL:6>EA7JRS\n<DXCC:3>281\n<GRIDSQUARE:6>IM77AJ\n<CQZ:2>14\n<ITUZ:2>37\n"), "tq8 station")
    val sig = Regex("<SIGN_LOTW_V2.0:\\d+:6>([A-Za-z0-9+/=]+)<SIGNDATA:\\d+>(\\S+)\n").find(text)!!
    eq(sig.groupValues[2], "14IM77AJ3740MDL1ABC7.025000CW2025-11-0706:55:00Z", "signed data in tq8")
    check(java.security.Signature.getInstance("SHA1withRSA").run { initVerify(kp.public); update(sig.groupValues[2].toByteArray()); verify(java.util.Base64.getDecoder().decode(sig.groupValues[1])) }, "signature verifies")
    eq(Lotw.uploadResult("<html><!-- .UPL. accepted --><!-- .UPLMESSAGE. Your log has been received --></html>"), true to "Your log has been received", "LoTW accepted")
    eq(Lotw.uploadResult("<!-- .UPL.  rejected --><!-- .UPLMESSAGE. Bad signature -->").first, false, "LoTW rejected")
    check(Lotw.reportUrl("ea7jrs", "p&w", "2026-01-01", "EA7JRS").contains("password=p%26w&qso_query=1&qso_qsl=yes&qso_qslsince=2026-01-01"), "LoTW report URL")
    eq(Lotw.dnField("CN=Manuel,1.3.6.1.4.1.12348.1.4=#130332383" + "1,1.3.6.1.4.1.12348.1.1=#1306454137" + "4a5253", Lotw.OID_DXCC), "281", "DXCC in certificate")
    eq(Lotw.dnField("1.3.6.1.4.1.12348.1.1=#13064541374a5253,CN=X", Lotw.OID_CALL), "EA7JRS", "call in certificate")
    // WSJT-X logged
    val ql = Spots.WsjtxUdp.qsoLogged("ALP", 1_762_498_560_000, 1_762_498_500_000, "DL1ABC", "", 7_025_000, "CW", "599", "579", "", "", "Hans", "EA7JRS", "IM77")
    eq(ql[11].toInt(), 5, "QSO logged type")
    eq(java.nio.ByteBuffer.wrap(ql, 19, 8).long, 2_460_987L, "QDateTime Julian day (2025-11-07)")
    eq(Spots.WsjtxUdp.loggedAdif("ALP", "<EOH>")[11].toInt(), 12, "logged ADIF type")
}

private fun testWsjt() {
    eq(Wsjt.slotStart(30_100, 15_000), 30_000L, "FT8 slot"); eq(Wsjt.slotStart(37_400, 7_500), 30_000L, "FT4 slot"); eq(Wsjt.slotStart(37_600, 7_500), 37_500L, "FT4 second slot")
    val d = Wsjt.parse("000000 +13.0 +1.60 1291 ~  CQ R7IW LN35", 30_000)!!
    eq(d, Wsjt.Decode(30_000, 13.0, 1.6, 1291, "CQ R7IW LN35"), "decode line"); eq(d.calls, listOf("R7IW"), "calls in a message")
    eq(Wsjt.parse("Max magnitude: -15.6 dB", 0), null, "other output ignored"); eq(Wsjt.parse("000000 +1.0 +0.10  500 ~  Error [3] while unpacking!", 0), null, "unpack error ignored")
    eq(Wsjt.Decode(0, 0.0, 0.0, 0, "EA7JRS <PJ4/K1ABC> -12").calls, listOf("EA7JRS", "PJ4/K1ABC"), "calls with hash and prefix")
    val slots = ArrayList<Pair<Long, Int>>()
    val rec = Wsjt.SlotRecorder(Wsjt.Mode.FT4) { st, x -> slots.add(st to x.size) }
    var t = 37_550L                                              // joins 50 ms into a slot
    val block = ShortArray(1200)                                 // 100 ms
    repeat(200) { rec.feed(block, block.size, t + 100); t += 100 }
    check(slots.size >= 2 && slots.all { it.second == 90_000 && it.first % 7_500 == 0L }, "FT4 slots handed out whole (${slots.map { it.first }})")
    check(slots.first().first == 37_500L, "first slot is the one joined at its start")
    val w = Wsjt.wav(ShortArray(4) { 1000 }); eq(w.size, 44 + 8, "slot WAV size")
    eq(Wsjt.parseWspr("WSPR +18 +0.0 432.301537 +0 EA7JRS IM76 23", 0, 432_300_000), Wsjt.Decode(0, 18.0, 0.0, 1537, "EA7JRS IM76 23", 432_301_537, 0), "WSPR line")
    eq(Wsjt.parseWspr("WSPR -21 +0.3 0.001493 -1 K1JT FN20 30", 0, 0)?.audioHz, 1493, "WSPR line without dial")
    eq(Wsjt.parse(Wsjt.Mode.WSPR, "Max", 0, 0), null, "WSPR other output ignored")
    eq(Wsjt.arguments(Wsjt.Mode.WSPR, "/x/libwsprdec.so", "/c/s.wav", 432_300_000), listOf("/x/libwsprdec.so", "/c/s.wav", "432300000"), "WSPR command")
    eq(Wsjt.arguments(Wsjt.Mode.FT4, "/x/libft8dec.so", "/c/s.wav", null), listOf("/x/libft8dec.so", "-ft4", "/c/s.wav"), "FT4 command")
    eq(Wsjt.arguments(Wsjt.Mode.FST4W_300, "/x/libfst4wdec.so", "/c/s.wav", 14_095_600, 1500, 150, "/f/fst4w.txt"),
        listOf("/x/libfst4wdec.so", "/c/s.wav", "14095600", "300", "1500", "150", "/f/fst4w.txt"), "FST4W command")
    eq(Wsjt.parse(Wsjt.Mode.FST4W_120, "FST4W -26 +0.8 14.097078 +0 <EA7JRS> IM76AB", 0, 14_095_600),
        Wsjt.Decode(0, -26.0, 0.8, 1478, "<EA7JRS> IM76AB", 14_097_078, 0), "FST4W line")
    eq(Wsjt.parse(Wsjt.Mode.FST4W_120, "FST4W -33 +0.0 0.001499 +0 EA7JRS IM76 30", 0, null)?.calls, listOf("EA7JRS"), "FST4W calls")
    eq(Wsjt.Mode.entries.count { it.isFst4w }, 4, "FST4W periods"); eq(Wsjt.slotStart(1_799_999, Wsjt.Mode.FST4W_1800.periodMs), 0L, "FST4W-1800 slot")
}

private fun testNetWeather() {
    val owm = """{"coord":{"lon":-3.94,"lat":37.19},"weather":[{"id":802,"main":"Clouds","description":"scattered clouds","icon":"03d"}],"base":"stations","main":{"temp":18.42,"feels_like":17.9,"pressure":1018,"humidity":56,"sea_level":1018,"grnd_level":955},"visibility":10000,"wind":{"speed":4.12,"deg":250,"gust":7.2},"clouds":{"all":40},"dt":1793970000,"sys":{"country":"ES"},"timezone":3600,"id":2517117,"name":"Granada","cod":200}"""
    val w = NetWeather.parseOwmWeather(owm)
    check(w != null && w.tempC == 18.42 && w.humidity == 56.0 && w.pressureHpa == 1018.0 && w.windDeg == 250 && w.gustMs == 7.2 && w.clouds == 40 && w.place == "Granada" && w.description == "scattered clouds", "OWM weather")
    eq(NetWeather.parseOwmWeather("""{"cod":401,"message":"Invalid API key"}"""), null, "OWM bad key")
    eq(NetWeather.parseOwmWeather("not json"), null, "OWM garbage")
    val air = """{"coord":{"lon":-3.94,"lat":37.19},"list":[{"main":{"aqi":2},"components":{"co":201.94,"no":0.0,"no2":0.77,"o3":68.66,"so2":0.64,"pm2_5":7.5,"pm10":9.1,"nh3":0.12},"dt":1793970000}]}"""
    val wa = NetWeather.parseOwmAir(air, w)
    check(wa != null && wa.aqiOwm == 2 && wa.pm25 == 7.5 && wa.pm10 == 9.1 && wa.tempC == 18.42, "OWM air pollution")
    val iq = """{"status":"success","data":{"city":"Granada","state":"Andalusia","country":"Spain","location":{"type":"Point","coordinates":[-3.6,37.18]},"current":{"pollution":{"ts":"2026-11-09T13:00:00.000Z","aqius":42,"mainus":"p2","aqicn":15,"maincn":"p2"},"weather":{"ts":"2026-11-09T14:00:00.000Z","ic":"03d","hu":55,"pr":1017,"tp":19,"wd":240,"ws":3.6}}}}"""
    val i1 = NetWeather.parseIqair(iq, null)
    check(i1 != null && i1.source == "IQAir" && i1.aqiUs == 42 && i1.mainPollutant == "PM2.5" && i1.tempC == 19.0 && i1.windMs == 3.6 && i1.timeS == 1794232800L, "IQAir alone")
    val i2 = NetWeather.parseIqair(iq, wa)
    check(i2 != null && i2.source == "OWM+IQAir" && i2.aqiUs == 42 && i2.tempC == 18.42 && i2.aqiOwm == 2, "IQAir added to OWM")
    eq(NetWeather.parseIqair("""{"status":"fail","data":{"message":"incorrect_api_key"}}""", null), null, "IQAir bad key")
    val cmd = NetWeather.command(i2!!, 1793970600L)
    check(cmd.startsWith("{\"c\":\"wxnet\",\"src\":\"OWM+IQAir\",\"pl\":\"Granada\",\"t\":18.4,\"h\":56,\"p\":1018") && cmd.contains("\"aqus\":42") && cmd.contains("\"age\":600") && cmd.contains("\"mp\":\"PM2.5\""), "wxnet command: " + cmd)
    check(NetWeather.owmWeatherUrl(37.19151, -3.93670, "k y").endsWith("lat=37.1915&lon=-3.9367&units=metric&appid=k+y"), "OWM url")
    check(NetWeather.iqairUrl(37.0, -3.0, "abc").startsWith("https://api.airvisual.com/v2/nearest_city?lat=37.0000&lon=-3.0000&key=abc"), "IQAir url")
    eq(NetWeather.usAqiName(42), "Good", "US AQI band"); eq(NetWeather.owmAqiName(5), "Very poor", "OWM AQI name")
    check(NetWeather.summary(i2).contains("US AQI 42 (Good)"), "net weather summary")
    eq(Control.parse("""{"e":"netreq","what":"wx"}"""), ControlEvent.NetRequest("wx"), "netreq event")
    // AIS
    val ae = Control.parse("""{"e":"ais","m":351759000,"cl":"A","n":"EVER DIADEM","c":"3FOF8","t":70,"st":0,"lat":36.5,"lon":-6.3,"pa":12,"sog":10.2,"cog":185.0,"hdg":184,"age":5,"nm":2.3,"brg":210,"cpa":0.4,"tcpa":8.5,"dng":true,"ds":"NEW YORK","len":295,"bm":32,"dr":12.2,"eta":"05-15 14:00","imo":9134270,"ch":"A","msgs":7}""") as ControlEvent.Ais
    val av = ae.vessel
    check(av.mmsi == 351759000L && av.title == "EVER DIADEM" && av.lengthM == 295 && av.danger && av.cpaNm == 0.4 && av.destination == "NEW YORK" && av.imo == 9134270L, "ais event")
    eq(Ais.shipTypeName(av.shipType), "Cargo", "AIS ship type"); eq(Ais.statusName(5), "Moored", "AIS status")
    val al = Control.parse("""{"e":"aislist","tot":2,"from":0,"msgs":120,"src":"SA868 A","ch":"A","items":[{"m":351759000,"cl":"A","sog":9.0,"age":2},{"m":970123456,"cl":"T","age":1}]}""") as ControlEvent.AisList
    check(al.total == 2 && al.items.size == 2 && al.items[1].title == "AIS-SART" && al.source == "SA868 A", "aislist event")
    val mg = Ais.merge(av, al.items[0])
    check(mg.name == "EVER DIADEM" && mg.sogKn == 9.0 && mg.lengthM == 295, "AIS merge keeps static data")
    eq(Ais.settings(channel = 2, cpaNm = 0.5, alarm = true), "{\"c\":\"aisset\",\"ch\":2,\"cpa\":5,\"alarm\":true}\n", "aisset command")
    eq(TrackerMode.name(TrackerMode.AIS), "AIS receiver", "AIS mode name")
}

private fun inRing(ring: List<Pair<Double, Double>>, lat: Double, lon: Double): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val (yi, xi) = ring[i]; val (yj, xj) = ring[j]
        if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

private fun testMapData() {
    // WMS tile box (EPSG:3857) and address
    val b0 = Wms.bounds3857(0, 0, 0)
    near(b0[0], -20037508.34, 0.01, "WMS z0 minX"); near(b0[3], 20037508.34, 0.01, "WMS z0 maxY")
    val b = Wms.bounds3857(1, 1, 0)
    near(b[0], 0.0, 0.01, "WMS z1 x1 minX"); near(b[1], 0.0, 0.01, "WMS z1 y0 minY")
    val u = Wms.tileUrl("https://ideihm.covam.es/wms/enc", "ENC,Luces", 1, 1, 0)
    check(u.startsWith("https://ideihm.covam.es/wms/enc?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=ENC%2CLuces&STYLES=&CRS=EPSG:3857&BBOX=0.00,0.00,20037508.34,20037508.34"), "WMS GetMap address")
    check(u.endsWith("FORMAT=image%2Fpng&TRANSPARENT=TRUE"), "WMS format")
    // layers from GetCapabilities: the named root, or the leaves (style names are not layers)
    val capRoot = "<WMS_Capabilities><Service><Name>WMS</Name></Service><Capability><Layer><Name>enc</Name><Title>ENC</Title>" +
        "<Layer><Name>a</Name></Layer><Layer><Name>b</Name></Layer></Layer></Capability></WMS_Capabilities>"
    eq(Wms.layers(capRoot), "enc", "WMS named root layer")
    val capLeaves = "<WMS_Capabilities><Capability><Layer><Title>IHM</Title><Layer><Name>Luces</Name><Style><Name>default</Name></Style></Layer>" +
        "<Layer><Title>Group</Title><Layer><Name>Boyas</Name></Layer><Layer><Name>Faros</Name></Layer></Layer></Layer></Capability></WMS_Capabilities>"
    eq(Wms.layers(capLeaves), "Luces,Boyas,Faros", "WMS leaf layers")
    check(Wms.layers("<html>error</html>") == null, "WMS without capabilities")

    // day / night line
    val june = java.time.Instant.parse("2026-06-21T12:00:00Z").toEpochMilli()
    val (dec, lon) = Terminator.subsolar(june)
    near(dec, 23.44, 0.1, "subsolar latitude at the June solstice"); near(lon, 0.4, 1.0, "subsolar longitude at noon UTC")
    val midnight = java.time.Instant.parse("2026-06-21T00:00:00Z").toEpochMilli()
    check(Terminator.isNight(midnight, 40.4, -3.7) && !Terminator.isNight(june, 40.4, -3.7), "night in Madrid at 00 UTC, day at 12 UTC")
    val ring = Terminator.nightPolygon(midnight)
    check(inRing(ring, 40.4, -3.7), "Madrid inside the night polygon at midnight"); check(!inRing(ring, 35.7, 139.7), "Tokyo outside the night polygon at 00 UTC")
    check(!inRing(Terminator.nightPolygon(june), 40.4, -3.7), "Madrid outside the night polygon at noon")
    val nov = java.time.Instant.parse("2026-11-13T16:30:00Z").toEpochMilli()      // afternoon in Spain, night in Japan, day in New York
    val rn = Terminator.nightPolygon(nov)
    check(!inRing(rn, 36.5, -6.3) && inRing(rn, 35.7, 139.7) && !inRing(rn, 40.7, -74.0) && inRing(rn, 80.0, 0.0) && !inRing(rn, -80.0, 0.0), "night polygon in November")
    check((1 until rn.size).all { Math.abs(rn[it].second - rn[it - 1].second) <= 2.01 } && Math.abs(rn.last().second - rn.first().second) < 0.01, "night polygon: no long steps in longitude")
    // earthquake magnitude
    eq(OfficialAlert("q", AlertSrc.USGS, 0, "M4.1 Earthquake", "Spain").magnitude(), 4.1, "quake magnitude")
    check(OfficialAlert("a", AlertSrc.AEMET, 2, "Wind", "Murcia").magnitude() == null, "no magnitude for weather alerts")
}

private fun testCtyFiles() {
    // Win-Test format (CTY_WT_MOD.DAT): "#" comment lines before records and inside the prefix list, overrides <lat/lon>
    val wt = "#\n# RELEASE 2026.01.31.00\n# based on CTY-3607\n#\n# ADIF 246\n" +
        "Sov Mil Order of Malta:   15:  28:  EU:   41.90:   -12.43:    -1.0:  1A:\n    1A;\n# ADIF 260\n" +
        "Monaco:                   14:  27:  EU:   43.73:    -7.40:    -1.0:  3A:\n    3A;\n# ADIF 4\n" +
        "Agalega & St. Brandon:    39:  53:  AF:  -10.45:   -56.67:    -4.0:  3B6:\n# Agalega Islands\n    3B6<-10.42/-56.58>,\n# St. Brandon Islands\n    3B7<-16.58/-59.62>;\n"
    val t = Cty.parse(wt)
    eq(t.entities, 3, "Win-Test file: entities with comments"); eq(t.lookup("3A2MW")?.name, "Monaco", "Win-Test file: Monaco")
    near(t.lookup("3B7SP")!!.lat, -16.58, 1e-9, "Win-Test file: St. Brandon override"); check(t.lookup("3B7SP")!!.lon > 59.0, "Win-Test file: east longitude")
    eq(Cty.parse(wt.replace("\n", "\r\n")).entities, 3, "Win-Test file with CRLF")
    eq(Cty.version("    CF,CG,VE,=VE2EM/M,=VER20250115,\n"), "2025-01-15", "cty.dat: version")
    eq(Cty.version(wt), null, "cty.dat: no version")
    // zip of country-files.com: cty.dat inside (any folder, any case)
    val bos = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(bos).use { z ->
        z.putNextEntry(java.util.zip.ZipEntry("cty-3611/readme.txt")); z.write("hi".toByteArray()); z.closeEntry()
        z.putNextEntry(java.util.zip.ZipEntry("cty-3611/cty.dat")); z.write(wt.toByteArray(Charsets.ISO_8859_1)); z.closeEntry()
    }
    eq(Cty.textOf(bos.toByteArray())?.let { Cty.parse(it).entities }, 3, "cty.dat inside a zip")
    eq(Cty.textOf("Monaco: 14: 27: EU: 43.73: -7.40: -1.0: 3A:\n 3A;".toByteArray()), "Monaco: 14: 27: EU: 43.73: -7.40: -1.0: 3A:\n 3A;", "plain file text")
}

private fun testRepeaterBookConnect() {
    // RepeaterBook Connect content provider row (column names of the RepeaterBookConnect demo): RX = output, TX = input
    val r = RepeaterBook.fromConnect(mapOf("Call" to "ED7ZAB", "RX" to 145.6375, "TX" to 145.0375, "CTCSS" to 88.5, "DCS" to 0L,
        "Location" to "Sevilla", "Province" to "Andalucía", "Country" to "Spain", "Lat" to 37.38, "Lng" to -5.97, "ServiceTxt" to "Open"))
    eq(r.callsign, "ED7ZAB", "Connect callsign"); near(r.freqMhz!!, 145.6375, 1e-9, "Connect output"); near(r.inputMhz!!, 145.0375, 1e-9, "Connect input")
    eq(r.tone, "88.5", "Connect CTCSS"); eq(r.region, "Andalucía", "Connect region"); check(r.hasPosition, "Connect position"); eq(r.freqHz, 145_637_500L, "Connect Hz")
    eq(RepeaterBook.fromConnect(mapOf("Call" to "X", "CTCSS" to 0.0, "DCS" to 23L)).tone, "D023", "Connect DCS")
    check(!RepeaterBook.fromConnect(mapOf("Call" to "X", "Lat" to 0.0, "Lng" to 0.0)).hasPosition, "Connect without position")
}

private fun testAprsWx() {
    // APRS 1.0.1, chapter 12: complete weather report with an uncompressed position
    val w = Aprs.parse("!4903.50N/07201.75W_220/004g005t077r000p000P000h50b09900wRSW") as AprsPacket.Position
    val x = w.wx!!
    eq(x.windDirDeg, 220, "wx wind direction"); near(x.windKmh!!, 6.437, 0.01, "wx wind mph → km/h"); near(x.gustKmh!!, 8.047, 0.01, "wx gust")
    near(x.tempC!!, 25.0, 0.01, "wx °F → °C"); near(x.rain1hMm!!, 0.0, 1e-9, "wx rain 1 h"); eq(x.humidityPct, 50, "wx humidity"); near(x.pressureHpa!!, 990.0, 0.01, "wx pressure")
    eq(w.comment, "wRSW", "wx rest of the comment"); check(w.courseDeg == null && w.speedKmh == null, "the wind is not the course / speed of the station")
    // with a timestamp, negative temperature, humidity 00 = 100 %, rain, luminosity over 1000
    val w2 = (Aprs.parse("@092345z4903.50N/07201.75W_090/000g000t-07r012p034P056h00b10132l123 Meteo Sevilla") as AprsPacket.Position).wx!!
    near(w2.tempC!!, -21.667, 0.01, "wx negative °F"); eq(w2.humidityPct, 100, "wx h00 = 100 %"); near(w2.rain1hMm!!, 3.048, 0.001, "wx rain 1 h mm")
    near(w2.rain24hMm!!, 8.636, 0.001, "wx rain 24 h"); near(w2.rainMidnightMm!!, 14.224, 0.001, "wx rain since midnight"); eq(w2.luminosityWm2, 1123, "wx luminosity l")
    eq(w2.wind(), "calm", "wx calm"); near(w2.pressureHpa!!, 1013.2, 0.01, "wx pressure 2")
    // missing values ("..." or spaces)
    val w3 = (Aprs.parse("=3725.50N/00559.00W_.../...g...t068h  b.....") as AprsPacket.Position).wx!!
    check(w3.windDirDeg == null && w3.windKmh == null && w3.humidityPct == null && w3.pressureHpa == null, "wx missing values"); near(w3.tempC!!, 20.0, 0.01, "wx temp alone")
    // positionless report
    val r = Aprs.parse("_10090556c220s004g005t077r000p000P000h50b09900wRSW") as AprsPacket.Weather
    eq(r.wx.windDirDeg, 220, "positionless wind direction"); near(r.wx.windKmh!!, 6.437, 0.01, "positionless wind speed"); eq(r.comment, "wRSW", "positionless rest")
    // compressed position with the wind in the course / speed bytes (APRS 1.0.1 example "/5L!!<*e7" = 49°30'N 72°45'W)
    val c = Aprs.parse("=/5L!!<*e7_7P[g005t077r000p000P000h50b09900wRSW") as AprsPacket.Position
    near(c.lat, 49.5, 1e-4, "compressed wx lat"); near(c.lon, -72.75, 1e-4, "compressed wx lon")
    eq(c.wx!!.windDirDeg, 88, "compressed wx wind direction"); near(c.wx!!.windKmh!!, 36.24 * 1.852, 0.2, "compressed wx wind speed"); near(c.wx!!.tempC!!, 25.0, 0.01, "compressed wx temp")
    check(c.courseDeg == null, "compressed wx: no course")
    // compressed car: course / speed of the station
    val car = Aprs.parse("=/5L!!<*e7>7P[") as AprsPacket.Position
    eq(car.courseDeg, 88, "compressed course"); near(car.speedKmh!!, 36.24 * 1.852, 0.2, "compressed speed")
    // the weather symbol with a plain comment: no weather, no course
    val plain = Aprs.parse("!3725.50N/00559.00W_Estacion meteo de Paterna") as AprsPacket.Position
    check(plain.wx == null && plain.comment == "Estacion meteo de Paterna", "wx symbol with a plain comment")
    // uncompressed car: course / speed / altitude now in the position
    val mv = Aprs.parse("!4903.50N/07201.75W>088/036/A=001234 hi") as AprsPacket.Position
    eq(mv.courseDeg, 88, "uncompressed course"); near(mv.speedKmh!!, 66.672, 0.01, "uncompressed speed"); near(mv.altitudeM!!, 376.12, 0.01, "uncompressed altitude")
    eq(x.summary(), "25.0 °C · SW 6 km/h · 50 % · 990 hPa", "wx summary"); eq(x.mapLabel(), "25° 6 km/h", "wx map label")

    // Mic-E: 37°11.49'N 3°56.20'W, 36 kn, course 88, 690 m, "Off duty", Kenwood-free status "Hola"
    val m = Aprs.parse("`yT0oXt>/\";L}Hola", "SWQQTY") as AprsPacket.Position
    near(m.lat, 37.1915, 1e-5, "Mic-E lat"); near(m.lon, -3.936667, 1e-5, "Mic-E lon")
    near(m.speedKmh!!, 66.672, 0.01, "Mic-E speed"); eq(m.courseDeg, 88, "Mic-E course"); near(m.altitudeM!!, 690.0, 1e-9, "Mic-E altitude")
    eq(m.symbolCode, '>', "Mic-E symbol"); eq(m.symbolTable, '/', "Mic-E table"); eq(m.comment, "Hola", "Mic-E text"); eq(m.micE, "Off duty", "Mic-E status")
    val emg = Aprs.parse("`yT0oXt>/]Help=", "371QTY") as AprsPacket.Position
    eq(emg.micE, Aprs.MICE_EMERGENCY, "Mic-E emergency"); check(emg.isEmergency, "Mic-E emergency flag"); eq(emg.comment, "Help", "Kenwood marks removed")
    eq((Aprs.parse("`yT0oXt>/", "SW1QTY") as AprsPacket.Position).micE, "En route", "Mic-E bits 110")
    eq((Aprs.parse("`yT0oXt>/", "AW1QTY") as AprsPacket.Position).micE, "Custom-1", "Mic-E custom")
    check(Aprs.parse("`yT0oXt>/", "N0CALL") is AprsPacket.Other, "Mic-E with an invalid destination")
    check(Aprs.parse("`yT0oXt>/") is AprsPacket.Other, "Mic-E without destination")

    // objects and items
    val o = Aprs.parse(";LEADER   *092345z4903.50N/07201.75W>088/036") as AprsPacket.Position
    eq(o.objectName, "LEADER", "object name"); check(!o.killed, "live object"); eq(o.courseDeg, 88, "object course")
    check((Aprs.parse(";LEADER   _092345z4903.50N/07201.75W>") as AprsPacket.Position).killed, "killed object")
    val wo = Aprs.parse(";WX-PTN   *111111z3725.50N/00559.00W_090/010g015t050") as AprsPacket.Position
    eq(wo.objectName, "WX-PTN", "weather object"); near(wo.wx!!.tempC!!, 10.0, 0.01, "weather object temp")
    val it = Aprs.parse(")AID #2!4903.50N/07201.75WA") as AprsPacket.Position
    eq(it.objectName, "AID #2", "item name"); check(!it.killed, "live item")
    check((Aprs.parse(")AID #2_4903.50N/07201.75WA") as AprsPacket.Position).killed, "killed item")

    // table: weather, objects, third party, Mic-E
    val st = StationTable()
    st.onFrame(Tnc2.parse("EA7URS-13>APRS:!3725.50N/00559.00W_220/004g005t077h50b10132Davis")!!, 100)
    st.onFrame(Tnc2.parse("EA7URS-13>APRS:>Weather station")!!, 200)
    val ws = st.get("EA7URS-13")!!
    check(ws.wx != null && ws.wxMs == 100L, "station keeps the weather"); eq(ws.comment, "Davis", "station comment without the weather")
    st.onFrame(Tnc2.parse("EA7URS-13>APRS:_11101800c180s010g020t050")!!, 300)
    eq(st.get("EA7URS-13")!!.wx!!.windDirDeg, 180, "positionless report updates the station"); eq(st.get("EA7URS-13")!!.wxMs, 300L, "report time")
    check(st.get("EA7URS-13")!!.lat != null, "positionless report keeps the position")
    st.onFrame(Tnc2.parse("EA7JRS-10>APRS:;LEADER   *092345z4903.50N/07201.75W>088/036")!!, 400)
    eq(st.get("LEADER")?.owner, "EA7JRS-10", "object owner")
    st.onFrame(Tnc2.parse("EA7JRS-10>APRS:;LEADER   _092345z4903.50N/07201.75W>")!!, 500)
    check(st.get("LEADER") == null, "killed object leaves the table")
    st.onFrame(Tnc2.parse("EA7JRS-10>APRS,WIDE1-1:}EA1ABC>APRS,TCPIP,EA7JRS-10*:!4000.00N/00300.00W-via IS")!!, 600)
    check(st.get("EA1ABC")?.lat != null, "third party: the station inside"); eq(st.get("EA1ABC")?.comment, "via IS", "third party comment")
    st.onFrame(Tnc2.parse("EA7XYZ-9>SWQQTY,WIDE1-1:`yT0oXt>/\";L}Hola")!!, 700)
    eq(st.get("EA7XYZ-9")?.courseDeg, 88, "Mic-E in the table"); eq(st.get("EA7XYZ-9")?.micE, "Off duty", "Mic-E status in the table")
    eq(st.weather(37.4, -5.98).map { it.call }, listOf("EA7URS-13"), "weather stations")
    val tp = Aprs.unwrapThirdParty(Tnc2.parse("EA7JRS-10>APRS:}EA1ABC>APRS,TCPIP*::EA7JRS-7 :hola{12")!!)
    eq(tp.source.text(), "EA1ABC", "third party source"); check((Aprs.parse(tp.infoText) as AprsPacket.Message).addressee == "EA7JRS-7", "third party message")
}

private fun testOfficialAlerts() {
    val O = OfficialAlerts
    eq(O.parseIso("2026-11-04T23:00:00+00:00"), O.unixFromUtc(2026, 11, 4, 23), "iso utc")
    eq(O.parseIso("2026-11-04T16:50:00-06:00"), O.unixFromUtc(2026, 11, 4, 22, 50), "iso -06:00")
    eq(O.parseIso("2026-11-05 00:00:00.000"), O.unixFromUtc(2026, 11, 5), "iso with space")
    val ma = "<entry><cap:geocode><valueName>EMMA_ID</valueName><value>DE103</value></cap:geocode>" +
        "<cap:areaDesc>Kreis Aurich - Küste</cap:areaDesc><cap:event>heavy thunderstorms with gale- or storm-force gusts</cap:event>" +
        "<cap:sent>2026-11-12T16:09:00+00:00</cap:sent><cap:expires>2026-11-12T20:00:00+00:00</cap:expires>" +
        "<cap:onset>2026-11-12T16:30:00+00:00</cap:onset><cap:severity>Moderate</cap:severity><cap:message_type>Alert</cap:message_type>" +
        "<cap:status>Actual</cap:status><cap:identifier>2.49.0.0.276.0.DWD.PVW.1794499740000.4aff.MUL</cap:identifier>" +
        "<title>Orange Thunderstorm Warning issued for Germany - Kreis Aurich - Küste</title></entry>"
    val a = O.meteoAlarmEntry(ma)
    check(a != null && a.level == 2 && a.area == "Kreis Aurich - Küste" && a.expires == O.unixFromUtc(2026, 11, 12, 20), "MeteoAlarm entry (orange title, Moderate severity)")
    eq(a?.id, "2.49.0.0.276.0.DWD.PVW.1794499740000.4aff.MUL/DE103", "MeteoAlarm id"); eq(O.asciiFold("Küste Almería"), "Kuste Almeria", "ascii fold")
    eq(O.meteoAlarmFeedName("es"), "spain", "MeteoAlarm feed")
    val it = "<item><title>Aviso. Nivel amarillo. Lluvias. Poniente y Almería Capital</title>" +
        "<description>Aviso de precipitación acumulada en una hora de nivel amarillo de 18:00 04-11-2026 CET (UTC+1) a 07:59 05-11-2026 CET (UTC+1).</description>" +
        "<guid isPermaLink=\"false\">Z_CAP_C_LEMM_20261104171224_AFAZ610403PRP10507.xml</guid></item>"
    val r = O.aemetRssItem(it)
    check(r != null && r.level == 1 && r.event == "Rain" && r.area == "Poniente y Almería Capital" && r.text == "precipitación acumulada en una hora", "AEMET RSS item")
    check(r?.onset == O.unixFromUtc(2026, 11, 4, 17) && r.expires == O.unixFromUtc(2026, 11, 5, 6, 59), "AEMET RSS times")
    check(O.aemetRssItem("<item><title>Estado completo de avisos para España</title></item>") == null, "AEMET bundle item")
    check(O.zoneMatch("Poniente y Almería Capital", "huelva, almeria") && !O.zoneMatch("Costa granadina", "Huelva") && O.zoneMatch("x", " "), "zone match")
    check(O.levelFromColour("Nivel rojo") == 3 && O.levelFromColour("reduced visibility") == -1, "colour words")
    val cap = "<alert xmlns=\"urn:oasis:names:tc:emergency:cap:1.2\"><identifier>2.49.0.0.724.0.ES.1</identifier><sent>2026-11-03T10:27:59-00:00</sent><status>Actual</status>" +
        "<info><language>es-ES</language><event>Aviso de lluvias de nivel amarillo</event><severity>Moderate</severity><onset>2026-11-04T11:00:00-00:00</onset>" +
        "<expires>2026-11-04T19:59:59-00:00</expires><parameter><valueName>AEMET-Meteoalerta nivel</valueName><value>amarillo</value></parameter>" +
        "<area><areaDesc>Norte de Gran Canaria</areaDesc><polygon>28.05,-15.74 28.09,-15.71 28.16,-15.7 28.17,-15.63 28.15,-15.61 28.14,-15.54 28.13,-15.43 28.06,-15.42 28.01,-15.51 28.05,-15.74</polygon></area></info>" +
        "<info><language>en-GB</language><event>Yellow warning for rain</event><severity>Moderate</severity><onset>2026-11-04T11:00:00-00:00</onset>" +
        "<expires>2026-11-04T19:59:59-00:00</expires><description>Accumulated precipitation in one hour: 15 mm.</description>" +
        "<parameter><valueName>AEMET-Meteoalerta nivel</valueName><value>amarillo</value></parameter>" +
        "<area><areaDesc>Norte de Gran Canaria</areaDesc><polygon>28.05,-15.74 28.09,-15.71 28.16,-15.7 28.17,-15.63 28.15,-15.61 28.14,-15.54 28.13,-15.43 28.06,-15.42 28.01,-15.51 28.05,-15.74</polygon></area></info></alert>"
    val c = O.capParse(cap)
    check(c.size == 1 && c[0].first.event == "Yellow warning for rain" && c[0].first.level == 1 && c[0].second.size == 10, "AEMET CAP")
    check(O.pointInPolygon(28.10, -15.60, c[0].second) && !O.pointInPolygon(28.0, -15.3, c[0].second), "point in polygon")
    val nws = "{\"type\":\"FeatureCollection\",\"features\":[{\"id\":\"x\",\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[-93.14,30.38],[-93.03,30.31],[-93.08,30.25],[-93.21,30.19],[-93.29,30.3],[-93.14,30.38]]]}," +
        "\"properties\":{\"id\":\"urn:oid:2.49.0.1.840.0.4ecd.001.1\",\"areaDesc\":\"Calcasieu, LA\",\"sent\":\"2026-11-04T16:50:00-06:00\",\"onset\":\"2026-11-04T16:50:00-06:00\"," +
        "\"expires\":\"2026-11-05T05:00:00-06:00\",\"ends\":\"2026-11-07T06:00:00-06:00\",\"status\":\"Actual\",\"messageType\":\"Alert\",\"severity\":\"Severe\",\"event\":\"Flood Warning\"," +
        "\"headline\":\"Flood Warning issued\",\"instruction\":\"Turn around, don't drown when encountering flooded roads.\"}}]}"
    val n = O.nws(nws)
    check(n.size == 1 && n[0].level == 2 && n[0].expires == O.unixFromUtc(2026, 11, 7, 12) && kotlin.math.abs((n[0].lat ?: 0.0) - 30.3) < 0.1, "NWS GeoJSON")
    val tsu = "<entry><title>BONIN ISLANDS  JAPAN REGION</title><updated>2026-10-25T02:27:01Z</updated><geo:lat>28.</geo:lat><geo:long>139.9</geo:long>" +
        "<summary type=\"xhtml\"><div xmlns=\"http://www.w3.org/1999/xhtml\"><strong>Category:</strong> Information<br/><strong>Bulletin Issue Time: </strong> 2026.10.25 02:27:01 UTC <br/>" +
        "<strong>Preliminary Magnitude: </strong>7.0(Mwp)<br/><strong>Lat/Lon: </strong>28. / 139.9<br/><strong>Affected Region: </strong>BONIN ISLANDS  JAPAN REGION<br/></div></summary>" +
        "<id>urn:uuid:ba64bc82</id></entry>"
    val ts = O.tsunamiEntry(tsu)
    check(ts != null && ts.level == 0 && ts.event == "Tsunami Information" && ts.text == "M7.0(Mwp) BONIN ISLANDS JAPAN REGION" && ts.sent == O.unixFromUtc(2026, 10, 25, 2, 27, 1), "tsunami entry")
    eq(O.tsunamiEntry(tsu.replace("Information", "Warning"))?.level, 3, "tsunami warning")
    val q = O.usgs("{\"features\":[{\"type\":\"Feature\",\"properties\":{\"mag\":5,\"place\":\"south of the Fiji Islands\",\"time\":1793827465643,\"type\":\"earthquake\"}," +
        "\"geometry\":{\"type\":\"Point\",\"coordinates\":[179.8078,-25.3174,508.132]},\"id\":\"us7000tw43\"}]}") +
        O.emsc("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"id\":\"20261104_0000300\",\"properties\":{\"time\":\"2026-11-04T21:24:25.643Z\",\"flynn_region\":\"SOUTH OF FIJI ISLANDS\"," +
            "\"lat\":-25.3174,\"lon\":179.8078,\"depth\":508.1,\"mag\":5.0,\"unid\":\"20261104_0000300\"}},{\"type\":\"Feature\",\"properties\":{\"time\":\"2026-11-04T16:14:39.4Z\"," +
            "\"flynn_region\":\"STRAIT OF GIBRALTAR\",\"lat\":36.0,\"lon\":-5.5,\"depth\":10,\"mag\":4.6,\"unid\":\"b\"}}]}")
    check(q.size == 3 && O.quakeSame(q[0], q[1]) && !O.quakeSame(q[0], q[2]), "quakes USGS / EMSC")
    val ql = O.quakeLevels(q, 36.5, -6.0)
    check(ql.size == 2 && ql[0].src == AlertSrc.EMSC && ql[0].level == 0 && ql[1].level == 1 && ql[1].text.startsWith("Depth 10 km, ") && ql[1].text.endsWith(" km SE"), "quake levels")
    // ShakeNet: FDSN-WS 1.2 text (sample written from the standard; the real answer could not be read from the session), same as the plotter core
    val sk = O.fdsnText("#EventID|Time|Latitude|Longitude|Depth/km|Author|Catalog|Contributor|ContributorID|MagType|Magnitude|MagAuthor|EventLocationName|EventType\n" +
        "rs2026wabc|2026-11-04T16:14:52.100|36.04|-5.47|12.0|RaspberryShake||RaspberryShake|rs2026wabc|ML|4.4|RaspberryShake|Strait of Gibraltar|earthquake\n" +
        "rs2026wabd|2026-11-04T10:00:00|37.1|-3.9|1|RaspberryShake||||ML|2.1||Granada quarry|quarry blast\r\n", AlertSrc.SHAKE)
    check(sk.size == 1 && sk[0].id == "shake/rs2026wabc" && sk[0].onset == O.unixFromUtc(2026, 11, 4, 16, 14, 52) && sk[0].area == "Strait of Gibraltar" &&
        sk[0].text == "Depth 12 km" && O.quakeMag(sk[0]) == 4.4 && AlertSrc.SHAKE.quake, "ShakeNet FDSN text")
    check(O.quakeLevels(sk + q[2], 36.5, -6.0).let { it.size == 1 && it[0].src == AlertSrc.EMSC }, "EMSC wins over ShakeNet")
    check(O.shakeUrl(36.5, -6.0, O.unixFromUtc(2026, 11, 12, 20, 5, 9)).let { it.contains("quakelink.raspberryshake.org/fdsnws/event/1/query?format=text") && it.endsWith("&starttime=2026-11-12T20:05:09") }, "ShakeNet URL")
    eq(O.fdsnText("rs1|2026-11-04T16:14:52|36.0|-5.5|10|||||ML|3.9||Gibraltar|earthquake", AlertSrc.SHAKE).size, 1, "FDSN text without a header")
    check(O.quakeLevel(5.4, 186.0) == 2 && O.quakeLevel(7.1, 9842.0) == 0 && O.quakeLevel(7.6, 2500.0) == 2 && O.quakeLevel(6.2, 250.0) == 3, "quake level rule")
    var w = O.swpcScales("{\"0\":{\"R\":{\"Scale\":\"1\"},\"S\":{\"Scale\":\"0\"},\"G\":{\"Scale\":\"3\"}},\"1\":{\"G\":{\"Scale\":\"2\"}},\"2\":{\"G\":{\"Scale\":\"1\"}},\"3\":{\"G\":{\"Scale\":null}}}", SpaceWx())!!
    check(w.g == 3 && w.r == 1 && w.s == 0 && w.gMax == 2, "SWPC scales")
    w = O.swpcKp("[[\"time_tag\",\"Kp\",\"a_running\",\"station_count\"],[\"2026-11-11 21:00:00.000\",\"2.33\",\"9\",\"8\"],[\"2026-11-12 00:00:00.000\",\"5.00\",\"48\",\"8\"]]", w)!!
    check(w.kp.size == 2 && w.kpNow == 5.0 && w.kpTime == O.unixFromUtc(2026, 11, 12) && O.kpToG(5.0) == 1, "SWPC Kp rows")
    check(O.swpcKp("[{\"time_tag\":\"2026-11-12T03:00:00\",\"Kp\":6.67},{\"time_tag\":\"2026-11-12T00:00:00\",\"Kp\":5.0}]", w)?.kpNow == 6.67, "SWPC Kp objects")
    check(O.xrayToR(9.9e-6) == 0 && O.xrayToR(1e-5) == 1 && O.xrayToR(5e-5) == 2 && O.xrayToR(1e-4) == 3 && O.xrayToR(1e-3) == 4 && O.xrayToR(2e-3) == 5 &&
        O.xrayToR(HfProp.xrayFlux("X1.0")) == 3 && O.xrayToR(HfProp.xrayFlux("M5.2")) == 2, "X-ray R scale")
    check(O.noaaScaleRgb(0) == 0 && O.noaaScaleRgb(1) == 0xFFFF00 && O.noaaScaleRgb(2) == 0xFFC800 && O.noaaScaleRgb(3) == 0xFF9600 &&
        O.noaaScaleRgb(4) == 0xFF0000 && O.noaaScaleRgb(5) == 0xC80000 && O.noaaScaleRgb(9) == 0xC80000 && O.kpToG(8.67) == 4 && O.kpToG(9.0) == 5, "NOAA scale colours")
    w = O.swpcSummary("{\"Flux\":\"182\"}", w)!!; w = O.swpcSummary("{\"Bt\":\"14\",\"Bz\":\"-12\"}", w)!!
    eq(O.swpcSsn("""[{"Obsdate":"2026-11-17T00:00:00","swpc_ssn":131},{"Obsdate":"2026-11-18T00:00:00","swpc_ssn":118}]"""), 118.0, "daily SSN: the last one")
    eq(O.swpcSsn("""[{"time-tag":"2026-09","ssn":121.4,"smoothed_ssn":-1},{"time-tag":"2026-10","ssn":null}]"""), 121.4, "monthly SSN: the last with a value")
    eq(O.swpcSsn("<html>"), null, "SSN: not JSON")
    val daily = ":Product: Daily Solar Data            DSD.txt\n#  Last 30 Days Daily Solar Data\n#\n#                         Sunspot       Stanford GOES15\n" +
        "#           Radio  SESC     Area          Solar  X-Ray  ------ Flares ------\n" +
        "  2026 11 17  148    131     560      2    -999   B6.1   5  0  0  0  0  0  0\n  2026 11 18  152    118     610      1    -999   C1.0   3  1  0  0  0  0  0\n"
    eq(O.swpcDailyIndices(daily), 152.0 to 118.0, "daily solar indices: flux and SSN of the last day")
    eq(O.swpcDailyIndices("# nothing"), null, "daily solar indices: no data")
    // Kp forecast, A index, Dst and the N0NBH backup (2026-11-23)
    eq(O.swpcKp("[[\"time_tag\",\"Kp\",\"a_running\",\"station_count\"],[\"2026-11-11 21:00:00.000\",\"2.33\",\"9\",\"8\"],[\"2026-11-12 00:00:00.000\",\"5.00\",\"48\",\"8\"]]", SpaceWx())?.aIndex, 48.0, "SWPC Kp: running A index")
    eq(O.swpcKp("[{\"time_tag\":\"2026-11-12T03:00:00\",\"Kp\":6.67}]", SpaceWx(aIndex = 7.0))?.aIndex, 7.0, "SWPC Kp without A keeps the one it had")
    val kf0 = O.parseIso("2026-11-23 12:00:00")
    val kfJson = """[["time_tag","kp","observed","noaa_scale"],["2026-11-23 06:00:00","2.00","observed",null],["2026-11-23 09:00:00","3.00","estimated",null],["2026-11-23 12:00:00","4.33","predicted",null],["2026-11-24 03:00:00","6.00","predicted","G2"],["2026-11-25 00:00:00","3.67","predicted",null]]"""
    val kf = O.swpcKpForecast(kfJson, kf0)
    eq(kf.size, 3, "Kp forecast: only the periods still to come, no observations")
    eq(kf.first().kp, 4.33, "Kp forecast: the period in course comes first")
    eq(O.kpForecastMax(kf, kf0, kf0 + 86400)?.kp, 6.0, "Kp forecast: highest of the next 24 h")
    eq(O.kpForecastMax(kf, kf0, kf0 + 86400)?.g, 2, "Kp forecast: Kp 6 is G2")
    eq(O.kpForecastMax(kf, kf0 + 10 * 86400, kf0 + 11 * 86400), null, "Kp forecast: nothing that far")
    eq(O.swpcKpForecast("nonsense", kf0).size, 0, "Kp forecast: bad text")
    val dstW = O.swpcDst("""[{"time_tag":"2026-11-23T10:00:00","dst":-12},{"time_tag":"2026-11-23T11:00:00","dst":-63}]""", SpaceWx())!!
    eq(dstW.dst, -63.0, "Dst: newest hourly value (objects)")
    eq(dstW.dstTime, O.parseIso("2026-11-23 11:00:00"), "Dst: its time")
    eq(O.swpcDst("""[["time_tag","dst"],["2026-11-23 10:00:00","-12"],["2026-11-23 11:00:00","-130"]]""", SpaceWx())?.dst, -130.0, "Dst: rows with a header")
    eq(O.swpcDst("[]", SpaceWx()), null, "Dst: empty")
    eq(listOf(-10.0, -35.0, -63.0, -130.0, -300.0).map { O.dstLevel(it) }, listOf(0, 1, 2, 3, 4), "Dst storm levels")
    eq(O.dstName(-63.0), "moderate storm", "Dst name")
    val n0 = """<?xml version="1.0"?><solar><solardata><source url="http://www.hamqsl.com/solar.html">N0NBH</source><updated> 23 Nov 2026 1012 GMT</updated><solarflux>148</solarflux><aindex>12</aindex><kindex>3</kindex><xray>C2.4</xray><sunspots>97</sunspots><solarwind>412.5</solarwind><magneticfield> -3.1</magneticfield></solardata></solar>"""
    val nb = O.n0nbhFill(n0, SpaceWx(g = 0, kp = listOf(2.0), xray = "B9.1"))!!
    eq(listOf(nb.sfi, nb.ssn, nb.aIndex, nb.bz, nb.windKms), listOf(148.0, 97.0, 12.0, -3.1, 412.5), "N0NBH fills what is missing")
    eq(nb.xray, "B9.1", "N0NBH never replaces what SWPC gave")
    eq(nb.kp, listOf(2.0), "N0NBH keeps the SWPC Kp")
    eq(nb.backup, "SFI SSN A Bz Wind", "N0NBH: what came from the backup")
    eq(O.n0nbhFill(n0, nb), null, "N0NBH: nothing missing, nothing done")
    eq(O.n0nbhFill("<html>busy</html>", SpaceWx()), null, "N0NBH: not his feed")
    check(O.needsBackup(SpaceWx(sfi = 150.0)) && !O.needsBackup(nb.copy(xray = "C1.0")), "needsBackup")
    val hpTxt = """# Hp30 and ap30, GFZ, CC BY 4.0
#YYY MM DD hh.h hh._m      days    days_m   Hp30 ap30 D
2026 11 23  9.5  9.75 34660.39583 34660.40625  2.667   12 0
2026 11 23 10.0 10.25 34660.41667 34660.42708  9.333  300 0
2026 11 23 10.5 10.75 34660.43750 34660.44792 10.667  550 0
2026 11 23 11.0 11.25 34660.45833 34660.46875 -1.000   -1 0
"""
    val hpW = O.gfzHp30(hpTxt, SpaceWx())!!
    eq(hpW.hp30, listOf(2.667, 9.333, 10.667), "GFZ Hp30: the values that exist, oldest first (it goes above 9)")
    eq(hpW.hp30Time, O.parseIso("2026-11-23 10:30:00"), "GFZ Hp30: start of the newest half hour")
    eq(O.gfzHp30(hpTxt, SpaceWx(), keep = 2)?.hp30?.size, 2, "GFZ Hp30: only the last ones")
    eq(O.gfzHp30("# nothing yet\n2026 11 23 0.0 0.25 1 1 -1.000 -1 0", SpaceWx()), null, "GFZ Hp30: no data")
    val hpNow = O.parseIso("2026-11-23 11:10:00")
    eq(O.geomagNow(hpW.copy(kp = listOf(4.0)), hpNow), 10.667 to "Hp30", "geomagnetic index now: the fresh Hp30 before the Kp")
    eq(O.geomagNow(hpW.copy(kp = listOf(4.0)), hpNow + 3 * 3600), 4.0 to "Kp", "geomagnetic index now: Hp30 too old, the Kp")
    eq(O.geomagNow(SpaceWx(), hpNow), null, "geomagnetic index now: nothing")
    val hpA = O.hp30Alert(hpW, hpNow)!!
    eq(listOf(hpA.level, hpA.event, hpA.area, O.alertG(hpA)), listOf<Any>(3, "Geomagnetic storm G5 (Hp30 10.7)", "G5 Geomagnetic storm", 5), "Hp30 storm alert")
    check(hpA.activeAt(hpNow) && !hpA.activeAt(hpNow + 4 * 3600) && hpA.id.startsWith("gfz/hp30/G5/"), "Hp30 storm alert: id and 3 h of life")
    eq(O.hp30Alert(hpW.copy(hp30 = listOf(4.9)), hpNow), null, "Hp30 below 5: no alert")
    eq(O.hp30Alert(hpW, hpNow + 3 * 3600), null, "Hp30 too old: no alert")
    eq(O.spaceCommand(hpW)["hp"], 10.7, "spacewx command carries the Hp30")
    eq(O.spaceCommand(hpW)["ht"], hpW.hp30Time, "spacewx command carries its time")
    check(!O.spaceCommand(SpaceWx(g = 0)).containsKey("hp"), "spacewx command without Hp30")
    val swFull = SpaceWx(g = 5, r = 5, s = 5, gMax = 5, kp = List(8) { 8.67 }, kpTime = 1795000000, sfi = 250.0, bz = -25.4, bt = 31.2, windKms = 1050.0, xray = "X12.5", ssn = 250.0, hp30 = listOf(11.33), hp30Time = 1795000000)
    check(Control.command("spacewx", *O.spaceCommand(swFull).toList().toTypedArray()).length <= 250, "spacewx command with everything fits the tracker line (256)")
    val rw = O.rtswWind("""[{"time_tag":"2026-11-19T21:39:00","active":false,"source":"ACE","proton_speed":470.0,"proton_density":4.0},
        {"time_tag":"2026-11-19T21:40:00","active":true,"source":"DSCOVR","proton_speed":487.3,"proton_density":5.4,"proton_temperature":110200},
        {"time_tag":"2026-11-19T21:40:00","active":false,"source":"ACE","proton_speed":480.0,"proton_density":5.0}]""", SpaceWx())!!
    near(rw.windKms!!, 487.3, 1e-9, "RTSW: speed of the active spacecraft"); near(rw.density!!, 5.4, 1e-9, "RTSW: density"); near(rw.tempK!!, 110200.0, 1e-9, "RTSW: temperature")
    val rm = O.rtswMag("""[["time_tag","active","bt","bx_gsm","by_gsm","bz_gsm"],["2026-11-19 21:40:00.000",true,6.1,1.0,2.0,-4.2]]""", rw)!!
    near(rm.bz!!, -4.2, 1e-9, "RTSW: Bz GSM from rows with a header"); near(rm.bt!!, 6.1, 1e-9, "RTSW: Bt")
    near(O.windPressureNpa(rm)!!, 2.1449, 1e-3, "solar wind dynamic pressure")
    // HF: the same values as the tracker's tools/test_forecast.cpp (formulas of sat-aprs)
    val hn = HfProp.estimate(150.0, -20.0, 0.0); near(hn.muf, 12.1 * 0.85, 1e-6, "HF night MUF"); near(hn.luf, 1.925 * 0.7, 1e-6, "HF night LUF")
    val hd = HfProp.estimate(150.0, 90.0, 3.1e-6); near(hd.muf, 33.7, 1e-6, "HF day MUF"); near(hd.luf, 1.925 + 3.2 + 1.5, 1e-6, "HF day LUF with C3.1")
    eq(HfProp.state(28.5, hd, 2.0), HfProp.State.OPEN, "HF 10 m open"); eq(HfProp.state(3.65, hd, 2.0), HfProp.State.ABSORBED, "HF 80 m absorbed")
    eq(HfProp.state(14.15, hn, 2.0), HfProp.State.SHUT, "HF 20 m shut at night"); eq(HfProp.state(7.1, hd, 5.0), HfProp.State.POOR, "HF storm")
    near(HfProp.estimate(150.0, -20.0, 3.1e-6).luf, 1.925 * 0.7, 1e-6, "HF no X-ray absorption at night")
    near(HfProp.estimate(150.0, 30.0, 3.1e-6).luf, 1.925 + 0.5 * 3.2 + 1.5 * Math.pow(0.5, 0.75), 1e-6, "HF X-ray D-RAP scaling")
    near(HfProp.xrayFlux("M2.4"), 2.4e-5, 1e-12, "X-ray class to flux"); eq(HfProp.bestDx(hd, 2.0), "10m", "HF best DX band")
    eq(O.spaceCommand(SpaceWx(ssn = 118.4))["sn"], 118L, "spacewx command carries the SSN")
    check(w.sfi == 182.0 && w.bz == -12.0 && w.bt == 14.0, "SWPC summaries")
    eq(O.swpcXray("[{\"flux\":1.2e-6,\"energy\":\"0.1-0.8nm\"},{\"flux\":2.4e-5,\"energy\":\"0.1-0.8nm\"},{\"flux\":1e-7,\"energy\":\"0.05-0.4nm\"}]", w)?.xray, "M2.4", "X-ray class")
    check(O.xrayClass(3.1e-4) == "X3.1" && O.xrayClass(5e-8) == "A5.0", "X-ray classes")
    val now = O.unixFromUtc(2026, 8, 27, 9)
    val sa = O.swpcAlerts("[{\"issue_datetime\":\"2026-08-27 06:15:00.000\",\"message\":\"Space Weather Message Code: ALTK07\\r\\nSerial Number: 123\\r\\n\\r\\nALERT: Geomagnetic K-index of 7\\r\\nNOAA Scale: G3 - Strong\\r\\n\"}," +
        "{\"issue_datetime\":\"2026-08-27 05:00:00.000\",\"message\":\"Space Weather Message Code: WATA20\\r\\nSerial Number: 99\\r\\n\\r\\nWATCH: Geomagnetic Storm Category G1 Predicted\\r\\n\"}," +
        "{\"issue_datetime\":\"2026-08-27 04:00:00.000\",\"message\":\"Space Weather Message Code: WARK05\\r\\nSerial Number: 50\\r\\n\\r\\nWARNING: Geomagnetic K-index of 5 expected\\r\\nValid To: 2026 Aug 27 1200 UTC\\r\\nNOAA Scale: G1 - Minor\\r\\n\"}," +
        "{\"issue_datetime\":\"2026-08-27 08:00:00.000\",\"message\":\"Space Weather Message Code: WARK05\\r\\nSerial Number: 51\\r\\n\\r\\nCANCEL WARNING: Geomagnetic K-index of 5 expected\\r\\nCancel Serial Number: 50\\r\\n\"}," +
        "{\"issue_datetime\":\"2026-08-20 04:00:00.000\",\"message\":\"Space Weather Message Code: ALTK04\\r\\nSerial Number: 7\\r\\n\\r\\nALERT: Geomagnetic K-index of 4\\r\\n\"}]", now)
    check(sa.size == 2 && sa[0].level == 2 && sa[0].area == "G3 Geomagnetic storm" && sa[0].text == "Alert" && sa[1].level == 1 && sa[1].text == "Watch", "SWPC alerts")
    val cmd = O.command(a!!)
    check(Json.encode(cmd).length < 230 && (cmd["ev"] as String).length <= 32 && cmd["ar"] == "Kreis Aurich - Kuste", "tracker alert command")
    check(O.fromEvent(Json.parseObject("{\"e\":\"alert\",\"id\":\"same/1\",\"s\":\"same\",\"lv\":3,\"ev\":\"Tornado Warning\",\"ar\":\"048201\",\"on\":1,\"ex\":2}")!!)?.src == AlertSrc.SAME, "alert event")
}

private fun testSame() {
    val m = Same.parse("ZCZC-WXR-TOR-048201-048157+0030-3171845-KHGX/NWS-")
    check(m != null && m.org == "WXR" && m.event == "TOR" && m.fips.size == 2 && m.durationMin == 30 && m.julianDay == 317 && m.sender == "KHGX/NWS", "SAME header")
    check(Same.parse("ZCZC-WXR-TOR-04820+0030-3171845-KHGX/NWS-") == null && Same.headerEnd("ZCZC-WXR-SVR-048201+0100-3171845-KHGX/NWS-xx") == 42, "SAME header end")
    check(Same.eventName("SVR") == "Severe Thunderstorm Warning" && Same.eventLevel("TOR") == 3 && Same.eventLevel("XYW") == 2, "SAME events")
    check(Same.nwrChannel(162.400) == 2 && Same.nwrChannel(162.41) == 0, "NOAA Weather Radio channels")
    val al = Same.toAlert(m!!, OfficialAlerts.unixFromUtc(2026, 11, 13, 19))
    check(al.onset == OfficialAlerts.unixFromUtc(2026, 11, 13, 18, 45) && al.expires == al.onset + 1800 && al.level == 3, "SAME alert")
    val hdr = "ZCZC-WXR-SVR-048201-048157-048339+0045-3171902-KHGX/NWS-"
    for (fs in listOf(12000.0, 48000.0)) {
        val sig = (Same.synth(hdr, fs * 1.005).toList() + Same.synth("NNNN", fs).toList()).toFloatArray()
        var seed = 12345L
        for (i in sig.indices) { seed = (seed * 1103515245L + 12345L) and 0xFFFFFFFFL; sig[i] += (((seed shr 8) and 0xFFFF) / 65535f - 0.5f) * 0.55f }
        val d = SameDecoder(fs)
        val got = ArrayList<String>()
        var i = 0
        while (i < sig.size) { val k = minOf(256, sig.size - i); d.feed(sig, i, k); i += k; while (true) { got.add(d.poll() ?: break) } }
        eq(got, listOf(hdr, "NNNN"), "SAME decoder at ${fs.toInt()} Hz")
    }
}

// METAR, TAF and SIGMET as the aviationweather.gov API sends them (fields of its metar1_0.json schema; invented values;
// the same cases as testAviation of the plotter core)
private fun testAviation() {
    val A = Aviation
    check(A.flightCategory(-1, 10.0) == Aviation.FlightCat.VFR, "flight cat VFR")
    check(A.flightCategory(2500, 10.0) == Aviation.FlightCat.MVFR, "flight cat MVFR by ceiling")
    check(A.flightCategory(5000, 2.0) == Aviation.FlightCat.IFR, "flight cat IFR by visibility")
    check(A.flightCategory(300, 10.0) == Aviation.FlightCat.LIFR, "flight cat LIFR")
    check(A.flightCategory(-1, -1.0) == Aviation.FlightCat.UNKNOWN, "flight cat unknown")
    val js = """[
 {"icaoId":"LERT","receiptTime":"2026-11-13 08:32:11","obsTime":1794558600,"reportTime":"2026-11-13 08:30:00","temp":17,"dewp":11,
  "wdir":100,"wspd":22,"wgst":32,"visib":"6+","altim":1016,"wxString":null,"vertVis":null,"metarType":"METAR",
  "rawOb":"METAR LERT 130830Z 10022G32KT 9999 FEW030 17/11 Q1016","lat":36.645,"lon":-6.349,"elev":26,"name":"Rota Naval Station, AN, ES",
  "clouds":[{"cover":"FEW","base":3000}]},
 {"icaoId":"GMTN","obsTime":1794558600,"temp":14,"dewp":14,"wdir":"VRB","wspd":3,"wgst":null,"visib":0.5,"altim":1017,"wxString":"FG",
  "vertVis":200,"rawOb":"METAR GMTN 130830Z VRB03KT 0800 FG VV002 14/14 Q1017","lat":35.594,"lon":-5.32,"name":"Tetouan","clouds":[]},
 {"icaoId":"LPFR","obsTime":1794558600,"temp":15,"dewp":14,"wdir":320,"wspd":6,"visib":"1.55","altim":1018,"fltCat":"IFR",
  "rawOb":"METAR LPFR 130830Z 32006KT 2500 BR BKN007 15/14 Q1018","lat":37.014,"lon":-7.966,"clouds":[{"cover":"BKN","base":700}]},
 {"icaoId":"XXXX","name":"sin posicion"}
]"""
    val ms = A.parseMetars(js) ?: emptyList()
    check(ms.size == 3, "METAR: 3 with position")
    if (ms.size == 3) {
        val r = ms[0]
        check(r.icao == "LERT" && r.wdir == 100 && r.wspdKt == 22 && r.wgstKt == 32 && r.visibPlus && r.cat == Aviation.FlightCat.VFR, "METAR LERT fields")
        check(r.qnhHpa == 1016.0 && r.obsTime == 1794558600L && r.ceilingFt < 0, "METAR LERT QNH, time, no ceiling")
        check(A.windText(r) == "Wind 100° 22 kn, gusts 32 kn", "METAR wind text: " + A.windText(r))
        check(A.skyText(r) == "Visibility 10 km or more · Clouds: few at 3000 ft", "METAR sky text: " + A.skyText(r))
        check(A.tempText(r) == "Temperature 17 °C · Dew point 11 °C · QNH 1016 hPa", "METAR temp text: " + A.tempText(r))
        val t = ms[1]
        check(t.wdir == -2 && t.ceilingFt == 200 && t.cat == Aviation.FlightCat.LIFR && t.wgstKt < 0, "METAR GMTN fog, VV002")
        check(A.windText(t) == "Wind variable 3 kn", "METAR variable wind")
        check(A.skyText(t) == "Visibility 800 m · FG · Clouds: vertical visibility 200 ft", "METAR fog sky: " + A.skyText(t))
        check(ms[2].cat == Aviation.FlightCat.IFR && ms[2].ceilingFt == 700, "METAR fltCat from the API")
    }
    check(A.parseMetars("{\"error\":1}") == null, "METAR: not an array")
    val ts = A.parseTafs("""[{"icaoId":"LERT","issueTime":"2026-11-13T05:00:00Z","rawTAF":"TAF LERT 130500Z 1306/1406 09020G30KT 9999 FEW030"}]""") ?: emptyList()
    check(ts.size == 1 && ts[0].raw.startsWith("TAF LERT") && ts[0].issueTime == 1794546000L, "TAF")
    val gj = """{"type":"FeatureCollection","features":[
 {"type":"Feature","properties":{"icaoId":"LPMG","firId":"LPPO","firName":"SANTA MARIA OCEANIC","seriesId":"2","hazard":"CONV",
  "qualifier":"EMBD","validTimeFrom":1794556800,"validTimeTo":"2026-11-13T12:00:00Z","base":10000,"top":35000,"dir":"NE","spd":15,
  "rawSigmet":"LPPO SIGMET 2 VALID 130800/131200 LPMG- EMBD TS"},
  "geometry":{"type":"Polygon","coordinates":[[[-9.2,36.4],[-8.3,36.9],[-7.4,36.3],[-7.9,35.5],[-9.0,35.5],[-9.2,36.4]]]}},
 {"type":"Feature","properties":{"firId":"X"},"geometry":{"type":"Point","coordinates":[0,0]}}]}"""
    val ss = A.parseSigmets(gj) ?: emptyList()
    check(ss.size == 1, "SIGMET: one with hazard")
    if (ss.size == 1) {
        val g = ss[0]
        check(g.id == "LPPO 2" && g.hazard == "CONV" && g.baseFt == 10000 && g.topFt == 35000 && g.spdKt == 15, "SIGMET fields")
        check(g.points.size == 6 && g.points[0].first == 36.4 && g.points[0].second == -9.2, "SIGMET GeoJSON lon, lat order")
        check(g.from == 1794556800L && g.to == 1794571200L, "SIGMET validity (number and ISO)")
        check(A.sigmetDistanceNm(g, 36.0, -8.4) == 0.0 && A.sigmetDistanceNm(g, 36.53, -6.29) > 40, "SIGMET distance")
        check(A.hazardName(g.hazard) == "Thunderstorms", "SIGMET hazard name")
    }
    check(A.sigmetUrl(A.sigmetRegion(36.5, -6.3)) == "https://aviationweather.gov/api/data/isigmet?format=geojson&loc=eur", "SIGMET url")
    check(A.metarUrl(35.0, -8.0, 38.0, -4.0) == "https://aviationweather.gov/api/data/metar?bbox=35.00,-8.00,38.00,-4.00&format=json", "METAR url")
}

// Layers with the ideas and feeds of OpenHamClock: samples with the shape of each feed (values invented)
private fun testHamLayers() {
    val H = HamLayers
    val ov = H.parseOvation("""{"Observation Time":"2026-11-13T08:00:00Z","Forecast Time":"2026-11-13T08:40:00Z","Data Format":"[Longitude, Latitude, Aurora]",
        "coordinates":[[0,65,12],[359,66,40],[200,-70,2],[10,60,1]]}""")
    check(ov != null && ov.cells.size == 2 && ov.forecastTime.startsWith("2026-11-13T08:40"), "OVATION: cells of 3 % or more")
    check(ov != null && ov.cells[1].second == -1.0 && ov.cells[1].third == 40, "OVATION: longitude 359 -> -1")
    val drap = H.parseDrap("""# Product: Global D-Region Absorption Prediction
# Product Valid At : 2026-11-13 08:10 UTC
#
              -178  -174  -170  -166  -162  -158  -154  -150  -146  -142  -138  -134
         ---------------------------------------------------------------------------
   89.0 |   0.0   0.0   0.0   0.0   0.0   0.0   0.0   0.0   0.0   0.0   0.0   0.0
   87.0 |   1.5   2.0  12.5   0.0   0.0   0.0   0.0   0.0   0.0   0.0   0.0   0.0
""")
    check(drap != null && drap.lats == listOf(89.0, 87.0) && drap.lons.size == 12 && drap.validAt == "2026-11-13 08:10 UTC", "D-RAP grid")
    check(drap != null && drap.maxFreq == 12.5 && drap.freqs[1][1] == 2.0, "D-RAP values")
    val nhc = H.parseNhc("""{"activeStorms":[{"id":"al142026","binNumber":"AT4","name":"Example","classification":"HU","intensity":"75","pressure":"984",
        "latitude":"28.5N","longitude":"38.0W","latitudeNumeric":28.5,"longitudeNumeric":-38.0,"movementDir":45,"movementSpeed":18,"lastUpdate":"2026-11-13T06:00:00.000Z",
        "publicAdvisory":{"advNum":"14","url":"https://www.nhc.noaa.gov/text/refresh/MIATCPAT4+shtml/130851.shtml"},
        "forecastTrack":{"kmzFile":"https://www.nhc.noaa.gov/storm_graphics/api/AL142026_014adv_TRACK.kmz"},
        "trackCone":{"kmzFile":"https://www.nhc.noaa.gov/storm_graphics/api/AL142026_014adv_CONE.kmz"}}]}""")
    check(nhc != null && nhc.size == 1 && nhc[0].windKt == 75 && nhc[0].category == 1 && nhc[0].label == "Hurricane", "NHC storm")
    check(nhc != null && nhc[0].trackKmz!!.endsWith("_TRACK.kmz") && nhc[0].pressureMb == 984 && nhc[0].lon == -38.0, "NHC KMZ links")
    val kml = """<kml><Document><Placemark><name>Track</name><LineString><coordinates>-38.0,28.5,0 -34.0,30.4,0 -29.5,32.6,0</coordinates></LineString></Placemark>
<Placemark><description><![CDATA[<b>Advisory Information</b> Valid at: 5:00 AM AST November 13 Location: 28.5 N Maximum Wind: 75 knots]]></description><Point><coordinates>-38.0,28.5,0</coordinates></Point></Placemark>
<Placemark><description><![CDATA[24 hr Forecast Valid at: 2:00 AM AST November 14 Location: 32.6 N Maximum Wind: 60 knots]]></description><Point><coordinates>-29.5,32.6,0</coordinates></Point></Placemark>
<Placemark><Polygon><outerBoundaryIs><LinearRing><coordinates>-38,28 -30,33 -29,32 -38,28</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark></Document></kml>"""
    val (line, fc) = H.nhcTrack(kml)
    check(line.size == 3 && line[1] == (30.4 to -34.0), "NHC track line (lat, lon)")
    check(fc.size == 2 && fc[0].hour == 0 && fc[1].hour == 24 && fc[1].windKt == 60 && fc[1].validAt.startsWith("2:00 AM"), "NHC forecast points")
    check(H.nhcCone(kml).size == 4, "NHC cone ring")
    val now = 1794558600_000L                                         // 2026-11-13 08:30 UTC
    check(H.ibpSlot(Date0 + 0) == 0 && H.ibpSlot(Date0 + 35_000) == 3 && H.ibpSlot(Date0 + 180_000) == 0, "IBP slot")
    val ibp = H.ibpNow(Date0 + 5_000)
    check(ibp[0].second.call == "4U1UN" && ibp[1].second.call == "YV5B" && ibp[4].second.call == "CS3B", "IBP: 20 m 4U1UN, 17 m YV5B, 10 m CS3B at slot 0")
    check(H.ibpNow(Date0 + 15_000)[1].second.call == "4U1UN" && H.ibpSecondsLeft(Date0 + 13_000) == 7, "IBP: 4U1UN on 17 m in slot 1")
    check(H.ibpSecondsUntil(Date0 + 5_000, 0, H.IBP_BANDS[0]) == 0 && H.ibpSecondsUntil(Date0 + 5_000, 0, H.IBP_BANDS[1]) == 5, "IBP: 4U1UN on 17 m in 5 s")
    check(H.ibpSecondsUntil(Date0 + 5_000, 14, H.IBP_BANDS[0]) == 135 && H.ibpSecondsUntil(Date0 + 5_000, 14, H.IBP_BANDS[4]) == 0, "IBP: CS3B on 10 m now, on 20 m in 135 s")
    val cs3b = H.BEACONS[14]
    check(cs3b.grid == "IM12jt" && abs(cs3b.lat - 32.81) < 0.05 && abs(cs3b.lon - (-17.21)) < 0.05, "IBP: CS3B position from IM12jt")
    val gc = H.greatCircle(36.53, -6.29, 35.68, 139.69)
    check(gc.size == 1 && abs(gc[0].first().first - 36.53) < 1e-9 && abs(gc[0].last().first - 35.68) < 1e-6 && gc[0].maxOf { it.first } > 60, "great circle to Tokyo goes north")
    val gcl = H.greatCircle(36.53, -6.29, 35.68, 139.69, longPath = true)
    check(gcl.size == 2 && abs(gcl.last().last().second - 139.69) < 1e-6, "long path crosses the antimeridian")
    val gb = H.gridBounds("IM76hr")!!
    check(abs(gb[0] - 36.7083) < 1e-3 && abs(gb[1] - (-5.4167)) < 1e-3 && H.gridBounds("JJ00") != null && H.gridBounds("ZZ99") == null, "grid bounds")
    check(H.gridStep(4.0).first == 20.0 && H.gridStep(8.0).first == 2.0, "grid step by zoom")
    val ev = H.parseEonet("""{"events":[{"id":"EONET_1","title":"Wildfire in Huelva","categories":[{"id":"wildfires","title":"Wildfires"}],
        "geometry":[{"magnitudeValue":1200,"magnitudeUnit":"acres","date":"2026-11-12T10:00:00Z","type":"Point","coordinates":[-6.9,37.3]}]},
        {"id":"EONET_2","title":"Flood","categories":[{"title":"Floods"}],"geometry":[{"date":"2026-11-11T00:00:00Z","type":"Polygon","coordinates":[[[0,0],[2,0],[2,2],[0,2]]]}]}]}""")
    check(ev != null && ev.size == 2 && ev[0].lat == 37.3 && ev[0].magnitude == "1200 acres" && ev[1].lat == 1.0 && ev[1].category == "Floods", "EONET events")
    val bl = H.parseSondehub("""{"EA7XYZ-11":{"2026-11-13T08:00:00.000Z":{"lat":36.5,"lon":-6.2,"alt":9000,"datetime":"2026-11-13T08:00:00.000Z"},
        "2026-11-13T08:10:00.000Z":{"lat":36.6,"lon":-6.0,"alt":11000,"datetime":"2026-11-13T08:10:00.000Z","comment":"pico"}}}""")
    check(bl != null && bl.size == 1 && bl[0].lat == 36.6 && bl[0].altM == 11000.0 && bl[0].track.size == 2 && bl[0].comment == "pico", "SondeHub balloon")
    val nd = H.parseNdbc("""#STN       LAT      LON  YYYY MM DD hh mm WDIR WSPD   GST WVHT  DPD APD MWD   PRES  PTDY  ATMP  WTMP  DEWP  VIS   TIDE
#text      deg      deg   yr mo day hr mn degT  m/s   m/s   m   sec sec degT   hPa   hPa  degC  degC  degC  nmi     ft
41047   27.520  -71.530  2026 11 13 08 00  80   7.0   9.0  2.1  10  MM  MM 1018.5    MM  24.5  26.8    MM   MM     MM
""")
    check(nd != null && nd.size == 1 && nd[0].windDeg == 80 && nd[0].waveM == 2.1 && nd[0].pressureHpa == 1018.5 && nd[0].timeMs == 1794556800_000L, "NDBC buoy")
    val ws = H.parseWsprLive("""{"meta":[],"data":[{"time":"2026-11-13 08:20:00","band":14,"rx_sign":"G4XYZ","rx_loc":"IO91wm","snr":-18,"frequency":14097042}],"rows":1}""")
    check(ws != null && ws.size == 1 && ws[0].band == "20m" && ws[0].snr == -18 && ws[0].timeMs == 1794558000_000L, "wspr.live spots")
    check(H.wsprLiveUrl("ea7jrs").contains("tx_sign+%3D+%27EA7JRS%27"), "wspr.live query")
    val ps = H.parsePskReporter("""<receptionReports><receptionReport receiverCallsign="DL1ABC" receiverLocator="JO62qm" senderCallsign="EA7JRS" frequency="14074500" flowStartSeconds="1794558000" mode="FT8" sNR="-12" /></receptionReports>""")
    check(ps.size == 1 && ps[0].rxCall == "DL1ABC" && ps[0].mode == "FT8" && ps[0].band == "20m" && ps[0].snr == -12, "PSK Reporter receptions")
    val z = H.parseZones("""{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"itu_zone_name":"!:KL:**Alaska, west of 141º W","itu_zone_number":1,"itu_zone_name_loc":[60,-160]},
        "geometry":{"type":"Polygon","coordinates":[[[-170,50],[-141,50],[-141,72],[-170,72],[-170,50]]]}}]}""", itu = true)
    check(z != null && z.size == 1 && z[0].number == 1 && z[0].name == "Alaska, west of 141º W" && z[0].rings[0].size == 5 && z[0].labelLat == 60.0, "ITU zones")
    check(H.drapColor(0.5, 200) == 0 && (H.auroraColor(50, 255) ushr 24) > 150, "colour scales")
}
private const val Date0 = 1794528000_000L                             // 2026-11-13 00:00 UTC (slot 0)

// Reverse Beacon Network spots through a DX cluster
// Same cases as testCatCiv / testCatYaesu / testCatKenwood / testCatYaesu5 / testCatTone of the rotor box (test_core.cpp)
private fun testCat() {
    fun hex(s: String) = s.map { String.format("%02X", it.code) }.joinToString(" ")
    fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    fun feed(e: Cat.Engine, s: String) = e.feed(Cat.bytes(s))
    val ok = b(0xFE, 0xFE, 0xE0, 0xA2, 0xFB, 0xFD)

    run {   // Icom CI-V
        val e = Cat.CivEngine(0xA2)
        var out = e.tick(0)
        check(hex(out) == "FE FE A2 E0 03 FD", "CAT CI-V: frequency query")
        e.feed(Cat.bytes(out))                                         // echo of the bus: ignored
        check(!e.state.link, "CAT CI-V: the echo is not an answer")
        e.feed(b(0xFE, 0xFE, 0xE0, 0xA2, 0x03, 0x00, 0x50, 0x82, 0x45, 0x01, 0xFD))
        check(e.state.link && e.state.freq == 145_825_000L, "CAT CI-V: frequency")
        check(hex(e.tick(200)) == "FE FE A2 E0 04 FD", "CAT CI-V: mode query")
        e.feed(b(0xFE, 0xFE, 0xE0, 0xA2, 0x04, 0x05, 0x01, 0xFD)); check(e.state.mode == "FM", "CAT CI-V: FM")
        check(hex(e.tick(400)) == "FE FE A2 E0 1C 00 FD", "CAT CI-V: PTT query")
        e.feed(b(0xFE, 0xFE, 0xE0, 0xA2, 0x1C, 0x00, 0x01, 0xFD)); check(e.state.ptt, "CAT CI-V: PTT")
        check(hex(e.tick(600)) == "FE FE A2 E0 15 02 FD", "CAT CI-V: S-meter query")
        e.feed(b(0xFE, 0xFE, 0xE0, 0xA2, 0x15, 0x02, 0x01, 0x20, 0xFD)); check(e.state.smeter == 9, "CAT CI-V: S9")
        check(hex(e.tick(800)) == "FE FE A2 E0 1A 06 FD", "CAT CI-V: data mode query")
        e.feed(b(0xFE, 0xFE, 0xE0, 0xA2, 0x1A, 0x06, 0x01, 0x01, 0xFD)); check(e.state.mode == "PKTFM", "CAT CI-V: data FM")
        check(hex(e.tick(1000)) == "FE FE A2 E0 25 01 FD", "CAT CI-V: other VFO query")
        e.feed(b(0xFE, 0xFE, 0xE0, 0xA2, 0xFA, 0xFD))                 // NG: no command 0x25, not asked again
        check(hex(e.tick(1200)) == "FE FE A2 E0 03 FD", "CAT CI-V: round without 0x25")
        e.feed(b(0xFE, 0xFE, 0xE0, 0xA2, 0x03, 0x00, 0x00, 0x07, 0x14, 0x00, 0xFD)); check(e.state.freq == 14_070_000L, "CAT CI-V: 14.070")
        check(e.setFreq(145_800_000L) && hex(e.tick(1400)) == "FE FE A2 E0 05 00 00 80 45 01 FD", "CAT CI-V: set frequency")
        e.feed(ok)
        check(e.setMode("DIG") && hex(e.tick(1500)) == "FE FE A2 E0 06 01 FD", "CAT CI-V: set mode")
        e.feed(ok)
        check(hex(e.tick(1600)) == "FE FE A2 E0 1A 06 01 01 FD", "CAT CI-V: set data mode")
        e.feed(ok)
        check(!e.setPtt(true), "CAT CI-V: PTT not allowed")
        e.allowPtt = true
        check(e.setPtt(true) && hex(e.tick(1700)) == "FE FE A2 E0 1C 00 01 FD", "CAT CI-V: PTT on")
        e.feed(ok)
        check(e.setTxFreq(435_100_000L) && hex(e.tick(1800)) == "FE FE A2 E0 25 01 00 00 10 35 04 FD", "CAT CI-V: other VFO")
        e.feed(b(0xFE, 0xFE, 0x00, 0xA2, 0x00, 0x00, 0x25, 0x83, 0x45, 0x01, 0xFD))      // transceive
        check(e.state.freq == 145_832_500L, "CAT CI-V: transceive")
        val m = Cat.CivEngine()
        var t = 0L; while (t < 4000) { m.tick(t); t += 50 }
        check(!m.state.link, "CAT CI-V: no answer, no link")
        check(Cat.CivEngine.unbcd(intArrayOf(0, 0, 0, 0, 0x10), 0, 5) == 1_000_000_000L, "CAT CI-V: BCD")
    }
    run {   // Yaesu text (FT-991A, FT-891)
        val e = Cat.YaesuEngine()
        check(e.tick(0) == "FA;", "CAT Yaesu: FA")
        feed(e, "FA145825000;"); check(e.state.link && e.state.freq == 145_825_000L, "CAT Yaesu: frequency")
        check(e.tick(200) == "MD0;", "CAT Yaesu: MD0"); feed(e, "MD04;"); check(e.state.mode == "FM", "CAT Yaesu: FM")
        check(e.tick(400) == "TX;", "CAT Yaesu: TX"); feed(e, "TX0;"); check(!e.state.ptt, "CAT Yaesu: receiving")
        check(e.tick(600) == "SM0;", "CAT Yaesu: SM0"); feed(e, "SM0120;"); check(e.state.smeter == 9, "CAT Yaesu: S9")
        check(e.tick(800) == "FB;", "CAT Yaesu: FB"); feed(e, "FB435100000;"); check(e.state.txFreq == 435_100_000L, "CAT Yaesu: VFO B")
        check(e.setFreq(7_074_000) && e.tick(1000) == "FA007074000;", "CAT Yaesu: set frequency")
        check(e.setMode("PKTUSB") && e.tick(1100) == "MD0C;", "CAT Yaesu: set mode")
        check(!e.setMode("XYZ"), "CAT Yaesu: unknown mode")
        feed(e, "?;")
        e.allowPtt = true; e.setPtt(true); check(e.tick(1200) == "TX1;", "CAT Yaesu: PTT")
        feed(e, "MD0C;"); check(e.state.mode == "PKTUSB", "CAT Yaesu: DATA-USB")
    }
    run {   // Kenwood
        val e = Cat.KenwoodEngine()
        check(e.tick(0) == "IF;", "CAT Kenwood: IF")
        feed(e, "IF00014074000     +00000000012001000000;")
        check(e.state.link && e.state.freq == 14_074_000L && e.state.ptt && e.state.mode == "USB" && e.state.vfo == 'A' && e.state.split, "CAT Kenwood: IF answer")
        check(e.tick(200) == "SM0;", "CAT Kenwood: SM0"); feed(e, "SM00015;"); check(e.state.smeter == 9, "CAT Kenwood: S9")
        check(e.tick(400) == "FB;", "CAT Kenwood: FB"); feed(e, "FB00014076000;")
        check(e.state.txFreq == 14_076_000L && e.state.freq == 14_074_000L, "CAT Kenwood: VFO B")
        check(e.tick(600) == "DA;", "CAT Kenwood: DA"); feed(e, "DA1;"); check(e.state.mode == "PKTUSB", "CAT Kenwood: data")
        check(e.setFreq(145_800_000) && e.tick(800) == "FA00145800000;", "CAT Kenwood: set frequency")
        check(e.setMode("CW") && e.tick(900) == "MD3;" && e.tick(950) == "DA0;", "CAT Kenwood: set mode")
        check(e.toggleVfo() && e.tick(1000) == "FR1;FT1;" && e.state.vfo == 'B', "CAT Kenwood: VFO B")
        feed(e, "SM0030;"); check(e.state.smeter == 15, "CAT Kenwood: S9+60")
    }
    run {   // Yaesu 5 bytes (FT-817 / 818)
        val e = Cat.Yaesu5Engine()
        check(hex(e.tick(0)) == "00 00 00 00 03", "CAT FT-817: frequency query")
        e.feed(b(0x14, 0x52, 0x50, 0x00, 0x01))
        check(e.state.link && e.state.freq == 145_250_000L && e.state.mode == "USB", "CAT FT-817: frequency and mode")
        check(hex(e.tick(200)) == "00 00 00 00 E7", "CAT FT-817: RX status"); e.feed(b(0x09)); check(e.state.smeter == 9, "CAT FT-817: S9")
        check(hex(e.tick(400)) == "00 00 00 00 F7", "CAT FT-817: TX status"); e.feed(b(0x7F)); check(e.state.ptt, "CAT FT-817: transmitting")
        check(e.setFreq(145_825_000) && hex(e.tick(600)) == "14 58 25 00 01", "CAT FT-817: set frequency")
        check(e.setMode("FM") && hex(e.tick(700)) == "08 00 00 00 07", "CAT FT-817: set mode")
        check(!e.setTxFreq(435_000_000), "CAT FT-817: no command for the other VFO")
        e.allowPtt = true; e.setPtt(false); check(hex(e.tick(800)) == "00 00 00 00 88", "CAT FT-817: PTT off")
    }
    // CTCSS
    check(Cat.toneIndex(Cat.CTCSS50, 50, 670) == 0 && Cat.toneIndex(Cat.CTCSS50, 50, 744) == 3 && Cat.toneIndex(Cat.CTCSS50, 50, 1598) == 26 &&
        Cat.toneIndex(Cat.CTCSS50, 50, 2541) == 49, "CAT: Yaesu tone table")
    check(Cat.toneIndex(Cat.CTCSS42, 41, 1622) == 26 && Cat.toneIndex(Cat.CTCSS42, 41, 1598) == -1 && Cat.toneIndex(Cat.CTCSS42, 41, 671) == 0, "CAT: Kenwood tone table")
    run {
        val e = Cat.CivEngine(0xA2)
        check(e.setTone(670) && e.state.tone == 670 && hex(e.tick(0)) == "FE FE A2 E0 1B 00 00 06 70 FD", "CAT CI-V: tone")
        e.feed(ok); check(hex(e.tick(100)) == "FE FE A2 E0 16 42 01 FD", "CAT CI-V: tone on")
        e.feed(ok); check(e.setTone(0) && hex(e.tick(200)) == "FE FE A2 E0 16 42 00 FD" && !e.setTone(3000), "CAT CI-V: tone off")
    }
    run {
        val e = Cat.YaesuEngine()
        check(e.setTone(744) && e.tick(0) == "CN00003;CT02;", "CAT Yaesu: tone")
        check(e.setTone(0) && e.tick(100) == "CT00;" && !e.setTone(1500), "CAT Yaesu: tone off")
    }
    run {
        val e = Cat.KenwoodEngine()
        check(e.setTone(670) && e.tick(0) == "TN01;TO1;", "CAT Kenwood: tone (TS-2000)")
        e.toneBase = 0; check(e.setTone(885) && e.tick(100) == "TN08;TO1;", "CAT Kenwood: tone (TS-590)")
        check(e.setTone(0) && e.tick(200) == "TO0;", "CAT Kenwood: tone off")
    }
    run {
        val e = Cat.Yaesu5Engine()
        check(e.setTone(885) && hex(e.tick(0)) == "08 85 08 85 0B" && hex(e.tick(100)) == "4A 00 00 00 0A", "CAT FT-817: tone")
        check(e.setTone(0) && hex(e.tick(200)) == "8A 00 00 00 0A", "CAT FT-817: tone off")
    }
    // Doppler: ISS at 437.800 MHz approaching at 7 km/s is heard 10.2 kHz high; the uplink goes the other way
    check(Cat.dopplerRx(437_800_000L, -7.0) == 437_810_222L && Cat.dopplerTx(145_990_000L, -7.0) == 145_986_591L, "CAT: Doppler")
    run {
        val e = Cat.YaesuEngine(); val tr = Cat.Tracker()
        tr.update("ISS", 437_800_000L, 145_990_000L, -7.0, 0)
        tr.step(e, 0); check(e.tick(0) == "FA;", "CAT tracker: waits for the link")
        feed(e, "FA437800000;")
        tr.step(e, 600)
        check(e.tick(700) == "FA437810222;" && e.tick(800) == "FB145986591;" && tr.doppler == 10_222L, "CAT tracker: downlink and uplink corrected")
        tr.update("ISS", 437_800_000L, 145_990_000L, -6.9999, 1000); tr.step(e, 1300)
        check(e.tick(1400).let { it == "MD0;" || it == "FA;" || it == "TX;" || it == "SM0;" || it == "FB;" }, "CAT tracker: no retune below the step")
        tr.step(e, 20_000); check(!tr.active, "CAT tracker: stops without data")
    }
    check(Cat.MODELS.first { it.name.startsWith("FT-818") }.proto == Cat.Proto.YAESU5 && Cat.MODELS.first { it.name.startsWith("FT-991") }.proto == Cat.Proto.YAESU &&
        Cat.MODELS.first { it.name.startsWith("FT-891") }.stopBits == 2, "CAT: Yaesu models")
}

private fun testRbn() {
    val now = 1794558600_000L
    val a = DxCluster.parse("DX de DL8LAS-#:   14025.0  EA7JRS       CW    12 dB  22 WPM  CQ      0830Z", "EA1XYZ", now)!!
    val h = Rbn.of(a)
    check(Spots.band(144_050_000) == "2m" && Spots.band(432_100_000) == "70cm" && Spots.band(1_296_200_000) == "23cm" &&
        Spots.band(10_368_100_000) == "3cm" && Spots.band(70_200_000) == "4m" && Spots.band(146_500_000) == "2m" && Spots.band(60_000_000) == null,
        "Spots: VHF / UHF / SHF bands")
    val v = Rbn.of(DxCluster.parse("DX de OH6BG-#:   144050.0  SM6FMB       CW    9 dB  20 WPM  CQ      0830Z", "EA1XYZ", now)!!)
    check(v?.band == "2m", "RBN 2 m spot")
    val u = Rbn.of(DxCluster.parse("DX de OH6BG-#:   432050.0  SM6FMB       CW    12 dB  20 WPM  CQ      0830Z", "EA1XYZ", now)!!)!!
    check(u.band == "70cm" && Rbn.freqText(u.freqKhz) == "432.050 MHz" && Rbn.freqText(14025.0) == "14025.0 kHz", "RBN UHF frequency in MHz")
    check(Rbn.alertText(u) == "OH6BG hears SM6FMB on 432.050 MHz · CW · CQ · 12 dB · 20 WPM · 0830Z", "RBN alert text")
    val t0 = 1794558600_000L - (1794558600_000L % 86_400_000L) + (8 * 60 + 32) * 60_000L     // 08:32 UTC
    check(Rbn.utcAgeMin("0830", t0) == 2 && Rbn.utcAgeMin("2359", t0 - (8 * 60 + 32) * 60_000L + 60_000L) == 2, "RBN spot age")
    val al = Rbn.Alerter()
    check(al.check(u, Rbn.AlertRange.UHF, false, "EA7JRS", t0), "RBN alert: UHF spot rings")
    check(!al.check(u, Rbn.AlertRange.UHF, false, "EA7JRS", t0 + 60_000), "RBN alert: same station and band once per 30 min")
    check(!al.check(v!!, Rbn.AlertRange.UHF, false, "EA7JRS", t0), "RBN alert: 2 m is not UHF")
    check(Rbn.Alerter().check(v, Rbn.AlertRange.VHF, false, "EA7JRS", t0), "RBN alert: 2 m rings with VHF+")
    check(!Rbn.Alerter().check(u, Rbn.AlertRange.UHF, true, "EA7JRS", t0), "RBN alert: only me")
    check(!Rbn.Alerter().check(u, Rbn.AlertRange.UHF, false, "", t0 + 60 * 60_000), "RBN alert: old spot from the node history")
    check(!Rbn.Alerter().check(u, Rbn.AlertRange.OFF, false, "", t0), "RBN alert: off")
    check(h != null && h.skimmer == "DL8LAS-#" && h.snrDb == 12 && h.speed == 22 && h.speedUnit == "WPM" && h.kind == Rbn.Kind.CQ && h.band == "20m", "RBN CW CQ spot")
    val b = Rbn.of(DxCluster.parse("DX de EA5WU-#:    14100.0  4X6TU        CW    25 dB  22 WPM  NCDXF B 0830Z", "", now)!!)
    check(b != null && b.kind == Rbn.Kind.NCDXF, "RBN NCDXF beacon")
    val c = Rbn.of(DxCluster.parse("DX de OH6BG-#:    28222.0  OH2B         CW    -3 dB  15 WPM  BEACON  0831Z", "", now)!!)
    check(c != null && c.kind == Rbn.Kind.BEACON && c.snrDb == -3, "RBN beacon, negative SNR")
    val d = Rbn.of(DxCluster.parse("DX de K1TTT-#:    14085.0  EA7JRS       RTTY  8 dB  45 BPS CQ      0832Z", "", now)!!)
    check(d != null && d.speed == 45 && d.speedUnit == "BPS", "RBN RTTY speed")
    check(Rbn.of(DxCluster.parse("DX de EA7DEF:     18075.0  3B8ABC       TNX QSO                        1830Z", "", now)!!) == null, "a person's spot is not RBN")
    check(Rbn.position(null, "G4XYZ-#", "IO91wm")?.let { abs(it.first - 51.5) < 0.1 } == true && Rbn.position(null, "G4XYZ-#", null) == null, "RBN position from locator")
}

private fun testLrFhss() {
    // reference bits from an independent encoder (pyLR-FHSS), used here only as test vectors
    fun s(b: IntArray) = b.joinToString("")
    val h1 = LrFhss.Header(11, 3, true, true, 0, 0, 2)
    val h2 = LrFhss.Header(20, 0, false, true, 2, 77, 0)
    val b1 = LrFhss.headerBits(h1); val b2 = LrFhss.headerBits(h2)
    eq(b1.size, 114, "LR-FHSS: header burst of 114 bits")
    eq(s(b1.copyOfRange(2, 42)), "1010010000100101010000101010001110001000", "LR-FHSS: header, first half")
    eq(s(b1.copyOfRange(74, 114)), "0100000101101001000101110101000110000101", "LR-FHSS: header, second half")
    eq(s(b2.copyOfRange(2, 42)) + s(b2.copyOfRange(74, 114)), "01111001000001100110101101110011101111100111101010001001110100100000000011011010", "LR-FHSS: second header")
    eq(s(b1.copyOfRange(42, 74)), "00010010101011010001000000011011", "LR-FHSS: sync word 12 AD 10 1B")
    fun soft(b: IntArray) = FloatArray(b.size) { if (b[it] == 1) 1f else -1f }
    eq(LrFhss.decodeHeader(soft(b1)), h1, "LR-FHSS: header decoded")
    eq(LrFhss.decodeHeader(soft(b2)), h2, "LR-FHSS: second header decoded")
    val rnd = java.util.Random(7)
    val noisy = soft(b1); for (i in intArrayOf(5, 17, 30, 80, 95, 110)) noisy[i] = -noisy[i] * 0.6f
    eq(LrFhss.decodeHeader(noisy), h1, "LR-FHSS: header with 6 wrong bits")
    LrFhss.decodeHeader(FloatArray(114) { rnd.nextFloat() - 0.5f })
    val msg = "PB1 EA7JRS IM67NG 14".toByteArray()
    val ref = arrayOf("101000101110110101100110010010111101010000010010011101000011001001111010110001001111110001111110011001000101011101110001000100101111011101000110000100100100101111000010001110100000111000001110001111111001011011000100111",
        "100001000110100001001001001101010010000011101101011000110010011111001010000110101010000100010010111100001000001010000110010001111111001111011110110110111011010101110000101001001011001111101110101110111101101011000110001000111111110100000001000100010001100110011111100110011",
        "1001001001101011011001010111110010110100100110110000101111010011101100110010011100101101111111110010100100010110011000010000001010010010111110101111000011111101100010111000011001000101100010100100110111100010101011001101111001001011000001110101100101110001010110010111111111101101000011110100111000010001100110010000001011010100000110100000001000110010000101011101",
        "101100000101101100010001010000010111111000001111011000100100101101110110000101101110111111111000110101000001010010110010110100110010011111101101011111100011101110000110101001011000000000000001011110110001001000111010100100100100000111110010111100000101101101001111000011011101011111101111100001100110101011100100001001101101001101100001010000101001001110111001100000100101000001010100111110001101111111001111101101110100110111000101101011000011011100110000011011111101110101011110001010010101010001100110000011001110110001011111110100110110101100")
    for (cr in 0..3) {
        val blocks = LrFhss.payloadBlocks(msg, cr)
        val air = blocks.joinToString("") { s(it.copyOfRange(2, it.size)) }
        eq(air, ref[cr], "LR-FHSS: payload bits, CR ${LrFhss.CR_NAMES[cr]}")
        val h = LrFhss.Header(msg.size, cr, true, true, 0, 0, 0)
        eq(air.length, h.codedBits, "LR-FHSS: coded bits, CR $cr")
        eq(blocks.size, h.fragments, "LR-FHSS: fragments, CR $cr")
        eq(blocks.last().size, h.fragmentSymbols(blocks.size - 1), "LR-FHSS: last fragment, CR $cr")
        val sb = FloatArray(air.length) { if (air[it] == '1') 1f else -1f }
        eq(LrFhss.decodePayload(sb, h)?.let { String(it) }, "PB1 EA7JRS IM67NG 14", "LR-FHSS: payload decoded, CR $cr")
        if (cr >= 2) {
            val bad = sb.copyOf(); for (i in 0 until bad.size step 13) bad[i] = -bad[i]
            eq(LrFhss.decodePayload(bad, h)?.let { String(it) }, "PB1 EA7JRS IM67NG 14", "LR-FHSS: payload with 1 wrong bit in 13, CR $cr")
            val lost = sb.copyOf(); for (i in 48 until 96) lost[i] = 0f
            eq(LrFhss.decodePayload(lost, h)?.let { String(it) }, "PB1 EA7JRS IM67NG 14", "LR-FHSS: payload with a fragment lost, CR $cr")
        }
        val wrong = sb.copyOf(); for (i in 0 until wrong.size step 2) wrong[i] = -wrong[i]
        check(LrFhss.decodePayload(wrong, h) == null, "LR-FHSS: garbage fails the CRC, CR $cr")
    }
    eq(LrFhss.whiten(LrFhss.whiten(intArrayOf(1, 2, 0x80, 0xFF, 0x55)), undo = true).toList(), listOf(1, 2, 0x80, 0xFF, 0x55), "LR-FHSS: whitening undone")
    fun pll(a: DoubleArray?) = a?.map { Math.round(it / LrFhss.PLL_STEP_HZ).toInt() }
    eq(pll(LrFhss.hopOffsetsHz(true, 0, 0, 3, 11)), listOf(10240, -14080, -6144, -2048, 6144, 14336, 2048, -18432, 18432, -10240, 10240, -14336, -6144, -2048), "LR-FHSS: hop sequence 0, 39 kHz, narrow grid")
    eq(pll(LrFhss.hopOffsetsHz(true, 0, 5, 3, 4)), listOf(-2048, 14592, -14336, 10240, -6144, 2048, -18432), "LR-FHSS: hop sequence 5")
    check(LrFhss.hopOffsetsHz(false, 2, 77, 2, 5) == null, "LR-FHSS: no sequence for that grid")
    eq(LrFhss.burstSymbols(h1, 3, 0), 115, "LR-FHSS: first burst one symbol longer")
    eq(LrFhss.parseBeacon(msg), LrFhss.Beacon("EA7JRS", "IM67NG", 14), "LR-FHSS: beacon text")
    eq(LrFhss.parseBeacon("PB1 EA7JRS-7 IM67NG55 -3".toByteArray()), LrFhss.Beacon("EA7JRS-7", "IM67NG55", -3), "LR-FHSS: beacon with SSID and 8 characters")
    check(LrFhss.parseBeacon("PB1 EA7JRS ZZ99 14".toByteArray()) == null && LrFhss.parseBeacon("hello world".toByteArray()) == null && LrFhss.parseBeacon("PB1 EA7JRS IM67 x".toByteArray()) == null, "LR-FHSS: not a beacon")

    // the radio side: a whole packet, off frequency and in noise, through the receiver
    fun receive(cr: Int, centre: Double, sigma: Float, seed: Long, spoil: Boolean = false): List<LrFhss.Packet> {
        val got = ArrayList<LrFhss.Packet>()
        val rx = LrFhss.Receiver { got.add(it) }
        val iq = LrFhss.modulate(msg, cr, true, 0, 0, 3, centre)!!
        val r = java.util.Random(seed)
        if (spoil) for (i in (4000 + (3 * 114 + 100 + 4) * 128) * 2 until (4000 + (3 * 114 + 150) * 128) * 2) iq[i] = 0f      // most of the third fragment gone
        for (i in iq.indices) iq[i] += sigma * r.nextGaussian().toFloat()
        rx.feed(iq); rx.feed(FloatArray(4000) { sigma * r.nextGaussian().toFloat() })
        return got
    }
    val clean = receive(3, 1234.5, 0.3f, 1)
    eq(clean.size, 1, "LR-FHSS receiver: one packet, once")
    eq(clean.firstOrNull()?.beacon, LrFhss.Beacon("EA7JRS", "IM67NG", 14), "LR-FHSS receiver: the beacon")
    near(clean.firstOrNull()?.centreHz ?: 0.0, 1234.5, 3.0, "LR-FHSS receiver: carrier found")
    eq(receive(0, -3210.0, 0.5f, 2).firstOrNull()?.text, "PB1 EA7JRS IM67NG 14", "LR-FHSS receiver: CR 5/6, -3.2 kHz")
    eq(receive(3, 777.0, 3.5f, 3).firstOrNull()?.text, "PB1 EA7JRS IM67NG 14", "LR-FHSS receiver: 14 dB under the noise of the 62.5 kHz")
    eq(receive(3, -150.0, 1.0f, 4, spoil = true).firstOrNull()?.text, "PB1 EA7JRS IM67NG 14", "LR-FHSS receiver: a fragment lost")
    run {
        val got = ArrayList<LrFhss.Packet>(); val rx = LrFhss.Receiver { got.add(it) }
        val r = java.util.Random(5); rx.feed(FloatArray(62500 * 2 * 4) { r.nextGaussian().toFloat() })
        eq(got.size, 0, "LR-FHSS receiver: nothing out of noise")
    }
    // resampler: a tone 20 kHz above a centre that sits 250 kHz up in a 1 MS/s input comes out at 30 kHz
    run {
        val o = ArrayList<Float>()
        val rs = LrFhss.Resampler(1_000_000.0, 250_000.0) { i, q -> o.add(i); o.add(q) }
        for (n in 0 until 200_000) { val p = 2 * Math.PI * 270_000.0 * n / 1e6; rs.feed(Math.cos(p).toFloat(), Math.sin(p).toFloat()) }
        near(o.size / 2.0, 12500.0, 20.0, "LR-FHSS resampler: 1 MS/s to 62.5 kS/s")
        var sr = 0.0; var si = 0.0; var pw = 0.0
        for (k in 1000 until 6000) { val a = o[2 * k].toDouble(); val b = o[2 * k + 1].toDouble(); val a0 = o[2 * k - 2].toDouble(); val b0 = o[2 * k - 1].toDouble(); sr += a * a0 + b * b0; si += b * a0 - a * b0; pw += a * a + b * b }
        near(Math.atan2(si, sr) / (2 * Math.PI) * 62500, 20000.0, 1.0, "LR-FHSS resampler: tone where it should be")
        near(pw / 5000, 1.0, 0.05, "LR-FHSS resampler: gain 1 in the band")
        // and a packet through it, from 250 kS/s made by repeating samples
        val got = ArrayList<LrFhss.Packet>(); val rx = LrFhss.Receiver { got.add(it) }
        val rs2 = LrFhss.Resampler(250_000.0) { i, q -> rx.feed(i, q) }
        val iq = LrFhss.modulate(msg, 3, true, 0, 0, 3, 500.0)!!
        for (k in 0 until iq.size / 2) repeat(4) { rs2.feed(iq[2 * k], iq[2 * k + 1]) }
        repeat(40_000) { rs2.feed(0f, 0f) }
        eq(got.firstOrNull()?.text, "PB1 EA7JRS IM67NG 14", "LR-FHSS: packet through the resampler")
    }
}

private fun testBeacons() {
    val now = 1_800_000_000_000L
    val log = Beacons.Log()
    val h = Beacons.fromLrFhss(LrFhss.Beacon("EA7JRS", "IM67NG", 14), 434_000_000L, 9, now, "PB1 EA7JRS IM67NG 14")
    eq(Beacons.spotCommand(h), "DX 434000.0 EA7JRS LR-FHSS IM67NG 14dBm 9dB", "Beacons: spot of an LR-FHSS beacon")
    check(h.position != null && Math.abs(h.position!!.first - 37.27) < 0.05 && h.band == "70cm", "Beacons: position from the locator, band")
    check(log.due(h, now), "Beacons: new station is due")
    log.add(h); log.spotted(h.key, now)
    check(!log.due(h, now + 10 * 60_000L) && log.due(h, now + 31 * 60_000L), "Beacons: one spot per station, mode and band every 30 min")
    val w = Beacons.fromWsjt(Wsjt.Decode(now, -21.4, 0.3, 1500, "EA7JRS IM67 23", rfHz = 14_097_100L), Wsjt.Mode.WSPR, 14_095_600L)
    eq(w?.let { listOf(it.call, it.mode, it.locator, it.powerDbm, it.snrDb, it.freqHz) }, listOf<Any?>("EA7JRS", "WSPR", "IM67", 23, -21, 14_097_100L), "Beacons: WSPR")
    val f = Beacons.fromWsjt(Wsjt.Decode(now, -5.0, 0.1, 1200, "CQ EA7JRS IM67"), Wsjt.Mode.FT8, 14_074_000L)
    eq(f?.let { listOf(it.call, it.mode, it.locator, it.freqHz) }, listOf<Any?>("EA7JRS", "FT8", "IM67", 14_075_200L), "Beacons: FT8 CQ")
    check(Beacons.fromWsjt(Wsjt.Decode(now, -5.0, 0.1, 1200, "CQ EA7JRS IM67"), Wsjt.Mode.FT8, null) == null, "Beacons: no dial, no entry")
    log.add(f!!); log.add(f.copy(locator = "", timeMs = now + 15_000))
    eq(log.list().map { it.call + " " + it.mode + " " + it.locator + " " + it.count }, listOf("EA7JRS FT8 IM67 2", "EA7JRS LR-FHSS IM67NG 1"), "Beacons: newest first, locator kept, counted")
    eq(Beacons.spotComment(Beacons.fromCw("ed7yaa", "BEACON", "im76hr", 144_450_000L, 12, now)), "CW IM76HR 12dB BEACON", "Beacons: CW comment")
    val fsk = Beacons.fromAprsFsk("ea7jrs-7", 37.27, -6.94, 432_500_000L, " tracker ", now)
    eq(Beacons.spotCommand(fsk), "DX 432500.0 EA7JRS-7 APRS FSK IM67MG", "Beacons: APRS FSK spot with the locator of the position")
    check(Beacons.isTrackerMode(fsk.mode) && !Beacons.isTrackerMode("FT8"), "Beacons: tracker modes")
    val m17 = DigitalVoice.M17Log()
    eq(m17.feed(" CAN: 0 DST: BROADCAST SRC: EA7JRS", now), DigitalVoice.M17Heard("EA7JRS"), "M17 log: station")
    check(m17.feed(" CAN: 0 DST: BROADCAST SRC: EA7JRS", now + 5000) == null && m17.feed(" CAN: 0 DST: BROADCAST SRC: EA7JRS", now + 61_000) != null, "M17 log: same station once a minute")
    eq(m17.feed(" GPS: (37.270000, -6.940000); Radius: 1.0; Speed: 0.0", now), DigitalVoice.M17Heard("EA7JRS", 37.27, -6.94), "M17 log: position")
    check(m17.feed(" CAN: 3 DST: BROADCAST SRC:  UNKNOWN FFFFFFFFFFFF", now) == null && m17.feed(" GPS: (37.27, -6.94);", now) == null && m17.feed(" TGT=91 SRC=2141234", now) == null, "M17 log: unknown source, other protocols")
    val mh = Beacons.fromM17("ea7jrs", 37.27, -6.94, 433_475_000L, now)
    eq(Beacons.spotCommand(mh), "DX 433475.0 EA7JRS M17 IM67MG", "Beacons: M17 spot")
    eq(Beacons.fromM17("EA7JRS", null, null, 433_475_000L, now).position, null, "Beacons: M17 without position")
    // IQ WAV
    fun wav(fmt: Int, bits: Int, rate: Int, extra: ByteArray = ByteArray(0)): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44 + extra.size + 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(0).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(fmt.toShort()).putShort(2).putInt(rate).putInt(rate * 2 * bits / 8).putShort((2 * bits / 8).toShort()).putShort(bits.toShort())
        b.put(extra).put("data".toByteArray()).putInt(16)
        return b.array()
    }
    val w16 = wav(1, 16, 125000)
    val info = IqWav.parse(w16)
    eq(info?.let { listOf(it.sampleRate, it.format, it.bits, it.dataOffset, it.frameBytes) }, listOf(125000, 1, 16, 44, 4), "IqWav: 16-bit header")
    w16[44] = 0; w16[45] = 0x40; w16[46] = 0; w16[47] = 0xC0.toByte()
    val fl = FloatArray(2); IqWav.toFloat(info!!, w16, 44, 1, fl)
    check(fl[0] == 0.5f && fl[1] == -0.5f, "IqWav: 16-bit samples")
    val aux = java.nio.ByteBuffer.allocate(8 + 36).order(java.nio.ByteOrder.LITTLE_ENDIAN).put("auxi".toByteArray()).putInt(36).put(ByteArray(32)).putInt(434_000_000).array()
    eq(IqWav.parse(wav(3, 32, 62500, aux))?.let { listOf<Any?>(it.format, it.dataOffset, it.centreHz) }, listOf<Any?>(3, 88, 434_000_000L), "IqWav: float file with the centre frequency")
    check(IqWav.parse(ByteArray(64)) == null, "IqWav: not a WAV")
    eq(IqWav.centreFromName("SDRconnect_IQ_20261114_102737_434017780HZ.wav"), 434_017_780L, "IqWav: centre in SDRconnect's file name")
    eq(IqWav.centreFromName("SDRSharp_20261114_092737Z_434000kHz_IQ.wav"), 434_000_000L, "IqWav: centre in kHz")
    eq(IqWav.centreFromName("recording.wav"), null, "IqWav: no frequency in the name")
    eq(IqWav.plan(62500, 434_017_780L, 434_000_000L), 0.0 to 434_017_780L, "IqWav: narrow recording off centre is read as it is")
    eq(IqWav.plan(250_000, 434_050_000L, 434_000_000L), -50_000.0 to 434_000_000L, "IqWav: wide recording is shifted to the beacon")
    eq(IqWav.plan(62500, null, 434_000_000L), 0.0 to 434_000_000L, "IqWav: unknown centre")
}

/** Lo que trae la rama wx-plotter y no estaba en main: base de nubes, tendencia de la presion, unidades. */
private fun testWxExtras() {
    near(WxCalc.cloudBaseM(20.0, 12.0)!!, 1000.0, 0.5, "cloud base")
    eq(WxCalc.cloudBaseM(10.0, 12.0), null, "no cloud base when dew > temperature")
    val tr = WxCalc.PressureTrend()
    eq(tr.delta3h(0), null, "no readings")
    for (i in 0..36) tr.add(i * 300L, 1015.0 - i * 0.05)
    near(tr.delta3h(36 * 300L)!!, -1.8, 0.05, "3 h falling 1.8 hPa")
    eq(WxCalc.trendName(-1.8), "falling", "trend name"); eq(WxCalc.trendName(0.4), "steady", "steady"); eq(WxCalc.trendName(4.0), "rising fast", "fast")
    eq(tr.delta3h(36 * 300L + 4000), null, "the last reading is old")
    val t2 = WxCalc.PressureTrend(); t2.add(0, 1010.0); t2.add(1800, 1011.0)
    eq(t2.delta3h(1800), null, "less than an hour")
    t2.add(3600, 1012.0); near(t2.delta3h(3600)!!, 6.0, 0.01, "2 hPa in 1 h = 6 in 3 h")
    eq(WxCalc.speedFactor(2).second, "mph", "imperial speed"); near(WxCalc.tempShown(100.0, 2), 212.0, 1e-9, "Fahrenheit")
    near(WxCalc.distFactor(1).first * 1.852, 1.0, 1e-12, "nautical mile")
    val wxu = AprsWx(windDirDeg = 225, windKmh = 18.52, tempC = 20.0)
    eq(wxu.wind(), "SW 19 km/h", "wind, metric"); eq(wxu.wind(1), "SW 10 kn", "wind, nautical"); eq(wxu.mapLabel(2), "68° 12 mph", "map label, imperial")
    check(wxu.summary(2).startsWith("68.0 °F"), "summary, imperial: ${wxu.summary(2)}")
    val tbl = StationTable()
    fun wxFrame(hpa: Int) = Ax25Frame(Ax25Address("APRS", 0), Ax25Address("EA7WX", 13), emptyList(), "!3723.00N/00559.00W_090/005g010t068h50b%05d".format(hpa * 10).toByteArray())
    var st: Station? = null
    for (i in 0..12) st = tbl.onFrame(wxFrame(1015 - i / 6), i * 600_000L)
    check(st?.wxPressD3h != null && st!!.wxPressD3h!! < -0.5, "pressure trend in the station: ${st?.wxPressD3h}")
    eq(AlertKind.AIS_COLLISION.title, "COLLISION RISK", "collision title as in the plotter and the Display")
}

fun main() {
    testNetWeather();
    testKiss(); testAx25(); testAprs(); testMessaging(); testIGate(); testGeoStations(); testJson(); testControl(); testSat(); testJsonEdit(); testDapnet(); testNmea(); testCallsignCheck(); testCw(); testFirmware(); testUbx(); testRotator(); testRotorProto(); testRotorFollow(); testSdr(); testDsp(); testSigId(); testModems(); testG3ruh(); testReceivers(); testWsjt(); testSpots(); testLogbook(); testRadioUi(); testDxCluster(); testGnssSky(); testStatusRadio(); testSun(); testRtlUsb(); testHeardSymbol(); testLights(); testAlerts(); testWxRadar(); testAprsWx(); testWxExtras(); testFormulas(); testRepeaterBookConnect(); testCtyFiles(); testMapData(); testOfficialAlerts(); testSame(); testAviation(); testHamLayers(); testRbn(); testCat(); testRateLimiter(); testAdsb(); testFlags(); testLrFhss(); testBeacons(); testVessel(); testMtCheckApp(); testAisRx(); testCelnav(); testLatitude(); testCurrent(); testBearingFix(); testRadiotel(); testDeviation(); testNavtex(); testNavaids(); testDsc(); testBeacon406(); testCarText(); testEphem()
    println("$checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}

private fun testRadioUi() {
    // S-meter, IARU R.1: S9 = -93 dBm from 30 MHz, -73 dBm below; 6 dB per S unit
    eq(SMeter.fromDbm(-93.0, 433_775_000).label, "S9", "S9 at UHF")
    eq(SMeter.fromDbm(-99.0, 433_775_000).label, "S8", "S8 at UHF")
    eq(SMeter.fromDbm(-94.0, 433_775_000).label, "S8", "just below S9 is S8")
    eq(SMeter.fromDbm(-141.0, 433_775_000).label, "S1", "S1 at UHF")
    eq(SMeter.fromDbm(-150.0, 433_775_000).label, "S0", "below S1")
    eq(SMeter.fromDbm(-73.0, 433_775_000).label, "S9+20", "S9+20 at UHF")
    eq(SMeter.fromDbm(-73.0, 14_025_000).label, "S9", "S9 at HF")
    eq(SMeter.fromDbm(-121.0, 7_074_000).label, "S1", "S1 at HF")
    eq(SMeter.fromDbm(-60.0, 433_775_000).segments, 13, "+33 dB lights 4 over segments")
    eq(SMeter.fromDbm(-30.0, 433_775_000).segments, 15, "bar full at +63 dB")
    eq(SMeter.fromDbm(-80.0, 433_775_000).segments, 11, "+13 dB lights 2 over segments")
    eq(SMeter.fromCat(7)?.label, "S7", "CAT S7")
    eq(SMeter.fromCat(11)?.label, "S9+20", "CAT 11 = S9+20")
    eq(SMeter.fromCat(-1), null, "CAT unknown")
    // relative (uncalibrated) S-meter in dBFS: S9 at −40 dBFS, 6 dB/S; does not pin to the top on a quiet band
    eq(SMeter.fromDbfs(-40.0).label, "S9", "dBFS S9 at −40")
    eq(SMeter.fromDbfs(-46.0).label, "S8", "dBFS S8")
    eq(SMeter.fromDbfs(-70.0).label, "S4", "dBFS quiet band is not full scale")
    eq(SMeter.fromDbfs(-20.0).label, "S9+20", "dBFS strong signal")
    eq(SMeter.fromDbfs(0.0).label, "S9+40", "dBFS full scale = overload")
    eq(SMeter.fromDbfs(-120.0).label, "S0", "dBFS noise floor")
    eq(SMeter.fromDbfs(-40.0).dbm, null, "dBFS reading carries no dBm")

    // aprs.fi sprites: 16 columns, '!' first
    eq(AprsSymbols.of('/', '>'), AprsSymbols.Symbol(AprsSymbols.Cell(0, 13, 1), null), "car /> in the primary sheet")
    eq(AprsSymbols.of('\\', '!')?.base, AprsSymbols.Cell(1, 0, 0), "emergency in the alternate sheet")
    eq(AprsSymbols.of("L&"), AprsSymbols.Symbol(AprsSymbols.Cell(1, 5, 0), AprsSymbols.Cell(2, 11, 2)), "LoRa iGate: & with overlay L")
    eq(AprsSymbols.of("/_")?.base, AprsSymbols.Cell(0, 14, 3), "weather station")
    eq(AprsSymbols.of("/"), null, "too short")
    eq(AprsSymbols.describe('L', '&'), "LoRa iGate", "LoRa iGate name")
    eq(AprsSymbols.describe('/', '['), "Human / jogger", "jogger name")

    val x = AprsExtra.parse("088/036/A=001234 QRV 433.775")
    eq(x.courseDeg, 88, "course"); near(x.speedKmh ?: 0.0, 66.672, 0.01, "speed km/h"); near(x.altitudeM ?: 0.0, 376.1, 0.1, "altitude m")
    eq(AprsExtra.cleanComment("088/036/A=001234 QRV 433.775"), "QRV 433.775", "clean comment")
    eq(AprsExtra.parse("LoRa tracker").courseDeg, null, "no course")
}

// GMSK BT 0.4 at 48 kHz from the NRZI levels (Gaussian-shaped NRZ = FM discriminator output), plus optional noise.
private fun aisGmsk(lv: IntArray, fs: Float, noise: Float, seed: Long): FloatArray {
    val baud = AisRx.BAUD
    val sps = fs / baud; val bt = 0.4f
    val sigma = (kotlin.math.sqrt(kotlin.math.ln(2.0)) / (2.0 * Math.PI * bt) * sps).toFloat()
    val half = (3 * sigma).toInt().coerceAtLeast(1)
    val g = FloatArray(2 * half + 1); var gs = 0f
    for (i in -half..half) { g[i + half] = kotlin.math.exp(-0.5 * i * i / (sigma * sigma)).toFloat(); gs += g[i + half] }
    for (i in g.indices) g[i] /= gs
    val lead = (fs * 0.004f).toInt()
    val n = (lv.size * sps).toInt() + 2 * lead
    val nrz = FloatArray(n)
    for (i in 0 until n) {
        val sym = ((i - lead) / sps).toInt()
        nrz[i] = if (sym in lv.indices) (if (lv[sym] == 1) 1f else -1f) else 0f
    }
    val out = FloatArray(n)
    for (i in 0 until n) { var s = 0f; for (k in g.indices) { val j = i + k - half; if (j in 0 until n) s += g[k] * nrz[j] }; out[i] = s }
    val rng = java.util.Random(seed)
    for (i in out.indices) out[i] += noise * rng.nextGaussian().toFloat()
    return out
}

private fun aisUnarmor(payload: String): Pair<ByteArray, Int> {
    val bits = ArrayList<Int>()
    for (ch in payload) { var v = ch.code - 48; if (v > 40) v -= 8; for (b in 5 downTo 0) bits.add((v ushr b) and 1) }
    val bytes = ByteArray((bits.size + 7) / 8)
    for (i in bits.indices) if (bits[i] == 1) { val by = i ushr 3; bytes[by] = (bytes[by].toInt() or (0x80 ushr (i and 7))).toByte() }
    return bytes to bits.size
}

private fun testAisRx() {
    // CRC and NMEA against a real AIS type-1 sentence.
    val payload = "15NG6V0P01G?cFhE`R2IU?wn28R>"
    val (bytes, nbits) = aisUnarmor(payload)
    eq(nbits, 168, "type 1 is 168 bits"); eq(bytes.size, 21, "21 bytes")
    val nmea = AisRx.toNmea(bytes, nbits, 'B')
    eq(nmea.trim(), "!AIVDM,1,1,,B,$payload,0*05", "toNmea reproduces the sentence")

    // Local decode of the type-1 message.
    val dv = AisRx.decodeVessel(bytes, 21, 'B', 1L)!!
    eq(dv.cls, "A", "type 1 is class A"); check(dv.mmsi in 1..999_999_999L, "mmsi decoded")
    check(dv.lat != null && dv.lon != null, "type 1 has a position")
    eq(AisRx.decodeVessel(ByteArray(2), 2, 'A', 1L), null, "too short -> null")

    // CPA: head-on, own north at 10 kn, target 1 nm ahead southbound at 10 kn -> CPA ~0 at ~3 min.
    val (cpaNm, tcpa) = AisRx.cpa(0.0, 0.0, 10.0, 0.0, 1.0 / 60.0, 0.0, 10.0, 180.0)
    near(cpaNm, 0.0, 0.01, "head-on CPA ~0"); near(tcpa, 3.0, 0.1, "head-on TCPA ~3 min")
    // Stationary own and target: CPA = current range.
    val (cpaStat, tStat) = AisRx.cpa(0.0, 0.0, 0.0, 0.0, 2.0 / 60.0, 0.0, 0.0, 0.0)
    near(cpaStat, 2.0, 0.01, "stationary CPA = range"); near(tStat, 0.0, 1e-9, "stationary TCPA 0")

    // Demod round trip: frameBits -> GMSK at 48 kHz -> Demod recovers the same 21 bytes.
    val lv = AisRx.frameBits(bytes, 21)
    var got: ByteArray? = null; var gotN = 0
    val sink = AisRx.FrameSink { d, n -> got = d.copyOf(n); gotN = n }
    for (noise in listOf(0.0f, 0.05f, 0.10f)) {
        val d = AisRx.Demod(48_000f); got = null
        for (v in aisGmsk(lv, 48_000f, noise, 7)) d.feed(v, sink)
        check(got != null && gotN == 21 && got!!.copyOf(21).contentEquals(bytes), "AIS demod round trip, noise $noise")
    }
    // Decoded frame turns back into the same sentence.
    val back = AisRx.toNmea(got!!, gotN * 8, 'B')
    eq(back.trim(), "!AIVDM,1,1,,B,$payload,0*05", "round-trip sentence")
    // Noise only: no frames.
    val d2 = AisRx.Demod(48_000f); var spurious = 0
    val sink2 = AisRx.FrameSink { _, _ -> spurious++ }
    val noiseOnly = aisGmsk(IntArray(0), 48_000f, 0.3f, 5)
    for (v in noiseOnly) d2.feed(v, sink2)
    for (i in 0 until 48_000) d2.feed((java.util.Random(11).nextGaussian() * 0.3).toFloat(), sink2)
    eq(spurious, 0, "no frames from noise")

    // Full IQ chain: synthesize 960 kS/s IQ for channel B (162.025 MHz), tuner at 162.000 + IF_OFFSET, and decode it.
    val fsIq = Dsp.INPUT_RATE.toFloat()
    val sps = fsIq / AisRx.BAUD
    val bt = 0.4f
    val sigma = (kotlin.math.sqrt(kotlin.math.ln(2.0)) / (2.0 * Math.PI * bt) * sps).toFloat()
    val half = (3 * sigma).toInt().coerceAtLeast(1)
    val g = FloatArray(2 * half + 1); var gs = 0f
    for (i in -half..half) { g[i + half] = kotlin.math.exp(-0.5 * i * i / (sigma * sigma)).toFloat(); gs += g[i + half] }
    for (i in g.indices) g[i] /= gs
    val lead = (fsIq * 0.003f).toInt()
    val ns = (lv.size * sps).toInt() + 2 * lead
    val nrz = FloatArray(ns)
    for (i in 0 until ns) { val sym = ((i - lead) / sps).toInt(); nrz[i] = if (sym in lv.indices) (if (lv[sym] == 1) 1f else -1f) else 0f }
    val shaped = FloatArray(ns)
    for (i in 0 until ns) { var s = 0f; for (k in g.indices) { val j = i + k - half; if (j in 0 until ns) s += g[k] * nrz[j] }; shaped[i] = s }
    val tunerHz = AisRx.CENTER_HZ + Dsp.IF_OFFSET
    val carrier = (162_025_000L - tunerHz).toDouble()            // channel B in the dongle baseband
    val dev = 2400.0                                             // GMSK h=0.5 at 9600 bd
    val iq = ByteArray(2 * ns)
    var phase = 0.0
    for (i in 0 until ns) {
        phase += 2.0 * Math.PI * (carrier + dev * shaped[i]) / fsIq
        iq[2 * i] = (127.5 + 110.0 * kotlin.math.cos(phase)).roundToInt().coerceIn(0, 255).toByte()
        iq[2 * i + 1] = (127.5 + 110.0 * kotlin.math.sin(phase)).roundToInt().coerceIn(0, 255).toByte()
    }
    var sentence: String? = null
    val rxr = AisRx.Receiver({ sentence = it })
    rxr.center(tunerHz)
    // feed in blocks
    var off = 0
    while (off < iq.size) { val take = minOf(32_768, iq.size - off); rxr.process(iq.copyOfRange(off, off + take), take); off += take }
    check(sentence != null && sentence!!.contains(",B,") && sentence!!.contains(payload), "AIS IQ chain decodes channel B: ${sentence?.trim()}")
}

private fun testVessel() {
    // type table
    eq(Vessel.TYPES.size, 17, "17 vessel types")
    eq(Vessel.type(0).classB, true, "sailing yacht is class B"); eq(Vessel.type(0).cls, 'C', "type 0 CS")
    eq(Vessel.type(1).cls, 'O', "power boat SO"); eq(Vessel.type(2).classB, false, "cargo is class A")
    eq(Vessel.type(99).id, 0, "out of range -> first"); eq(Vessel.className('C'), "Class B (CS)", "class name CS")

    // base callsign
    eq(Vessel.baseCall("ea7jrs-5"), "EA7JRS", "base call strips SSID, upper"); eq(Vessel.baseCall("AB"), "", "too short")
    eq(Vessel.baseCall("WORD12"), "WORD12", "6 chars ok"); eq(Vessel.validMmsi(224123456L), true, "valid MMSI")
    eq(Vessel.validMmsi(12345L), false, "short MMSI")

    // parse the event
    val o = Json.parseObject("{\"e\":\"vessel\",\"b\":true,\"ok\":true,\"i\":3,\"act\":true,\"call\":\"EA7JRS-5\",\"mmsi\":224123456,\"n\":\"SEA BREEZE\",\"mc\":\"EBXY\",\"mcok\":1,\"mcu\":false,\"t\":0,\"cls\":\"C\",\"st\":36,\"len\":12,\"beam\":4,\"dr\":1.8}")!!
    val st = Vessel.parse(o)
    eq(st.classBTransponder, true, "b"); eq(st.hasProfile, true, "ok"); eq(st.index, 3, "i"); eq(st.active, true, "act")
    eq(st.callsign, "EA7JRS-5", "call"); eq(st.mmsi, 224123456L, "mmsi"); eq(st.name, "SEA BREEZE", "name")
    eq(st.maritimeCall, "EBXY", "mc"); eq(st.maritimeVerified, 1, "mcok"); eq(st.shipType, 36, "st")
    eq(st.lengthM, 12, "len"); eq(st.beamM, 4, "beam"); near(st.draughtM, 1.8, 1e-9, "dr"); eq(st.maritimeToSend, "EBXY", "mc to send")
    eq(Vessel.parse(mapOf("call" to "EA7JRS")).maritimeToSend, "EA7JRS", "empty mc -> base call")

    // set command
    val cmd = Vessel.setCommand("SEA BREEZE", 224123456L, "EA7JRS-5", "EBXY", 0, 12, 4, 1.8, mcok = 1, unverified = false)
    val p = Json.parseObject(cmd)!!
    eq(p["c"], "vessel", "command name"); eq((p["mmsi"] as Number).toLong(), 224123456L, "mmsi field")
    eq(p["call"], "EA7JRS", "call stripped"); eq(p["mc"], "EBXY", "mc field"); eq((p["mcok"] as Number).toInt(), 1, "mcok sent")
    eq(p.containsKey("mcok"), true, "mcok present when 1")
    eq(Json.parseObject(Vessel.setCommand("X", 0, "EA7JRS", "", 1, 0, 0, 0.0))!!.containsKey("mcok"), false, "mcok absent when 0")
}

private fun testMtCheckApp() {
    eq(MtCheck.validKey("0123456789abcdef0123456789ABCDEF01234567"), true, "40 hex key ok")
    eq(MtCheck.validKey("123"), false, "short key"); eq(MtCheck.validKey("0123456789abcdef0123456789ABCDEF0123456g"), false, "bad char")
    eq(MtCheck.searchUrl("abc", 224123456L), "https://services.marinetraffic.com/api/shipsearch/abc?mmsi=224123456&protocol=jsono", "search url")
    eq(MtCheck.webUrl(224123456L), "https://www.marinetraffic.com/es/ais/details/ships/mmsi:224123456", "MarineTraffic page by MMSI")
    eq(MtCheck.webUrl(224123456L, 715183L), "https://www.marinetraffic.com/es/ais/details/ships/shipid:715183", "MarineTraffic page by shipid")
    eq(MtCheck.shipIdFrom("https://www.marinetraffic.com/es/ais/details/ships/shipid:715183/mmsi:224123456/imo:0/vessel:SEA_BREEZE"), 715183L, "shipid from link")
    check(MtCheck.shipIdFrom(" 715183 ") == 715183L && MtCheck.shipIdFrom("") == 0L && MtCheck.shipIdFrom("mmsi:224123456") == 0L && MtCheck.shipIdFrom(null) == 0L, "shipid from text")
    val two = "[{\"SHIPNAME\":\"SEA BREEZE\",\"MMSI\":\"224123456\",\"IMO\":\"0\",\"CALLSIGN\":\"EBXY\"},{\"SHIPNAME\":\"X\",\"MMSI\":\"224123456\",\"CALLSIGN\":\"EA1234\"}]"
    eq(MtCheck.parse(200, two, "ebxy"), MtCheck.Result.MATCH to "EBXY", "match first")
    eq(MtCheck.parse(200, two, "EA1234"), MtCheck.Result.MATCH to "EA1234", "match second")
    eq(MtCheck.parse(200, two, "EBZZ").first, MtCheck.Result.MISMATCH, "mismatch")
    eq(MtCheck.parse(200, "[]", "EBXY").first, MtCheck.Result.NOT_FOUND, "empty not found")
    eq(MtCheck.parse(200, "[{\"MMSI\":\"224123456\",\"CALLSIGN\":\"\"}]", "EBXY").first, MtCheck.Result.NOT_FOUND, "blank call not found")
    eq(MtCheck.parse(200, "{\"errors\":[{\"code\":\"5\",\"detail\":\"INVALID API KEY\"}]}", "EBXY").first, MtCheck.Result.BAD_KEY, "bad key error")
    eq(MtCheck.parse(200, "{\"errors\":[{\"code\":\"2\",\"detail\":\"ABOVE SERVICE CALL RATE LIMIT\"}]}", "EBXY").first, MtCheck.Result.LIMIT, "rate limit")
    eq(MtCheck.parse(200, "{\"errors\":[{\"code\":\"7\",\"detail\":\"NO DATA\"}]}", "EBXY").first, MtCheck.Result.NOT_FOUND, "no data")
    eq(MtCheck.parse(429, "", "EBXY").first, MtCheck.Result.LIMIT, "429 limit")
    eq(MtCheck.parse(401, "", "EBXY").first, MtCheck.Result.BAD_KEY, "401 bad key")
    eq(MtCheck.parse(200, "{\"DATA\":[{\"CALLSIGN\":\"EBXY\"}]}", "EBXY").first, MtCheck.Result.MATCH, "DATA array")
    eq(MtCheck.pairKey(224123456L, "eb-xy"), "224123456/EBXY", "pair key")
    eq(MtCheck.verified(1, "224123456/EBXY", 224123456L, "EBXY"), true, "verified MT")
    eq(MtCheck.verified(2, "224123456/EBXY", 224123456L, "ebxy"), true, "verified MARS lowercase")
    eq(MtCheck.verified(0, "224123456/EBXY", 224123456L, "EBXY"), false, "none not verified")
    eq(MtCheck.verified(2, "224123456/EBXY", 224123457L, "EBXY"), false, "other MMSI not verified")
    eq(MtCheck.verified(2, "224123456/EBXY", 224123456L, "EBXZ"), false, "other call not verified")
    eq(MtCheck.sourceName(2), "ITU MARS", "source name MARS"); eq(MtCheck.sourceName(0), "", "source name none")
    eq(MtCheck.resultText(MtCheck.Result.NOT_FOUND).contains("MARS"), true, "not found text points to MARS")
}

private fun testDxCluster() {
    val a = DxCluster.parse("DX de EA7DEF:     18075.0  3B8ABC       TNX QSO                        1830Z", "EA7JRS-7", 1L)!!
    eq(a.spotter, "EA7DEF", "spotter"); eq(a.dx, "3B8ABC", "dx"); near(a.freqKhz, 18075.0, 1e-9, "freq"); eq(a.utc, "1830", "utc")
    eq(a.mode, "CW", "18075 CW by band plan"); eq(a.origin, DxCluster.Origin.HUMAN, "human spot"); eq(a.band, "17m", "17m")
    val b = DxCluster.parse("DX de EA5ABC-#:  14025.0  JA1ABC       CW    22 dB  28 WPM  CQ      1832Z", "EA7JRS", 1L)!!
    eq(b.origin, DxCluster.Origin.RBN_CW, "RBN CW"); eq(b.comment, "CW    22 dB  28 WPM  CQ", "comment kept")
    val c = DxCluster.parse("DX de W3LPL-#:    7074.0  EA7JRS       FT8   -12 dB  CQ             1500Z FN20", "EA7JRS", 1L)!!
    eq(c.origin, DxCluster.Origin.RBN_DIG, "RBN digital"); eq(c.mode, "FT8", "FT8 word"); eq(c.spotterLocator, "FN20", "spotter grid")
    val d = DxCluster.parse("DX de EA7JRS-#:  28020.0  VK2ABC       CW 14 dB 24 WPM CQ   1819Z", "EA7JRS-7", 1L)!!
    eq(d.origin, DxCluster.Origin.MINE, "own skimmer")
    val sh = DxCluster.parse("  14025.0  JA1ABC       9-Nov-2026 1832Z CW 22 dB 28 WPM CQ          <EA5ABC-#>", "EA7JRS", 1L)
    eq(sh?.dx, "JA1ABC", "sh/dx line"); eq(sh?.spotter, "EA5ABC-#", "sh/dx spotter"); eq(sh?.utc, "1832", "sh/dx time"); eq(sh?.origin, DxCluster.Origin.RBN_CW, "sh/dx RBN")
    eq(DxCluster.parse("EA4URE-2 de EA7JRS 9-Nov-2026 1845Z dxspider >", "EA7JRS", 1L), null, "prompt is not a spot")
    eq(DxCluster.modeOf(21074.0, "-12 dB"), "FT8", "FT8 frequency")
    eq(DxCluster.modeOf(14080.0, ""), "FT4", "FT4 frequency")
    eq(DxCluster.modeOf(7144.0, "park 59"), "SSB", "phone segment")
    eq(DxCluster.modeOf(144300.0, "IL18 tropo 59"), "SSB", "2 m SSB")
    eq(DxCluster.modeOf(14200.0, "usb"), "SSB", "USB word")
    eq(DxCluster.groupOf("FT8"), DxCluster.Group.DIGITAL, "FT8 digital")
    eq(DxCluster.tags("POTA EA-0123"), listOf("POTA"), "POTA")
    eq(DxCluster.tags("IOTA EU-012 QRV"), listOf("IOTA"), "IOTA")
    eq(DxCluster.tags("EAFF-0123"), listOf("WWFF"), "WWFF")
    eq(DxCluster.tags("EA7/SE-012 summit"), listOf("SOTA"), "SOTA")
    eq(DxCluster.tags("DME 41091 Sevilla"), listOf("DME"), "DME")
    eq(DxCluster.rigMode(a), "CW", "rig CW")
    val ssb = DxCluster.parse("DX de EA3JKL:      7144.0  9A1ABC       park 59                        1827Z", "", 1L)!!
    eq(DxCluster.rigMode(ssb), "LSB", "LSB below 10 MHz")
    check(DxCluster.Filter(band = "40m", group = DxCluster.Group.PHONE).passes(ssb), "filter 40 m phone")
    check(!DxCluster.Filter(band = "HF", origins = setOf(DxCluster.Origin.RBN_CW)).passes(ssb), "origin filter")
    check(DxCluster.isLoginPrompt("Please enter your call: "), "login prompt")
    check(DxCluster.isLoginPrompt("login: "), "login prompt 2")
    eq(DxCluster.spotCommand(14025.0, "ja1abc", "CQ 599"), "DX 14025.0 JA1ABC CQ 599", "spot command")
    eq(DxCluster.formatKhz(144300.0), "144300.0", "freq text")

    val cty = Cty.parse("""Spain:                    14:  37:  EU:   40.32:     3.43:    -1.0:  EA:
    AM,AN,AO,EA,EB,EC,ED,EE,EF,EG,EH;
Canary Islands:           33:  36:  AF:   28.32:    15.85:     0.0:  EA8:
    AM8,AN8,AO8,EA8,EB8,EC8,ED8,EE8,EF8,EG8,EH8,=EA4XYZ/P;
Japan:                    25:  45:  AS:   36.40:  -138.38:    -9.0:  JA:
    7J,7K,JA,JE,JR,
    JA6(24)[45]<32.0/-131.0>;
Mauritius:                39:  53:  AF:  -20.35:   -57.50:    -4.0:  3B8:
    3B8;
""")
    eq(cty.entities, 4, "4 entities")
    eq(cty.lookup("EA7JRS")?.name, "Spain", "Spain")
    eq(cty.lookup("EA8ABC")?.name, "Canary Islands", "longest prefix wins")
    eq(cty.lookup("EA4XYZ/P")?.name, "Canary Islands", "exact call")
    eq(cty.lookup("EA8/DL1ABC")?.name, "Canary Islands", "portable prefix")
    eq(cty.lookup("JA1ABC")?.name, "Japan", "Japan")
    eq(cty.lookup("JA6ABC")?.cqz, 24, "zone override")
    near(cty.lookup("JA6ABC")?.lon ?: 0.0, 131.0, 1e-9, "position override, east positive")
    near(cty.lookup("3B8ABC")?.lon ?: 0.0, 57.5, 1e-9, "east-positive longitude")
    eq(Cty.homeCall("DL1ABC/P"), "DL1ABC", "suffix /P")
    eq(cty.lookup("ZZ9ZZZ"), null, "unknown prefix")
    val p = Cty.path(cty, "JA1ABC", 37.39, -5.99)!!
    check(p.shortDeg in 25..35, "JA short path near 30 deg (${p.shortDeg})"); eq(p.longDeg, (p.shortDeg + 180) % 360, "long path opposite")
    check(p.shortKm in 10500..11500, "JA distance (${p.shortKm})")
    eq(Cty.path(cty, "JA1ABC", null, null), null, "no own position")
}

private fun testGnssSky() {
    val ev = Control.parse("""{"e":"sky","pd":2.0,"hd":1.0,"vd":1.6,"used":8,"s":[["G",5,45,310,38,1],["E",16,55,330,42,1],["C",13,-1,-1,-1,0],["R",73,15,100,25,0]]}""")
    val sky = (ev as ControlEvent.Sky).sky
    eq(sky.inView, 4, "4 satellites in view"); eq(sky.used, 8, "used from GGA"); near(sky.pdop ?: 0.0, 2.0, 1e-9, "PDOP")
    eq(sky.sats[2].elevationDeg, null, "unknown elevation"); eq(sky.sats[2].snr, null, "no signal")
    eq(GnssSky.label(sky.sats[1]), "e16", "Galileo label"); eq(GnssSky.label(sky.sats[3]), "r9", "GLONASS slot from NMEA 73")
    eq(GnssSky.label(sky.sats[0]), "5", "GPS label")
    eq(GnssSky.fromAndroid(6), 'E', "Android Galileo"); eq(GnssSky.fromAndroid(5), 'C', "Android BeiDou")
    val p = GnssSky.plot(0, 90); near(p.first, 1.0, 1e-9, "east on the horizon: right"); near(p.second, 0.0, 1e-9, "east: middle")
    val z = GnssSky.plot(90, 200); near(z.first, 0.0, 1e-9, "zenith in the centre")
    val up = GnssSky.plot(0, 335, 335.0); near(up.second, -1.0, 1e-9, "heading up: satellite ahead at the top")
    eq(GnssSky.strength(38), 2, "strong"); eq(GnssSky.strength(22), 1, "medium"); eq(GnssSky.strength(null), -1, "none")
    eq(GnssSky.sorted(sky.sats).map { it.system }, listOf('G', 'E', 'C', 'R'), "bars order")
    val d = GnssSky.gsaDops(Nmea.sentence("GNGSA,A,3,05,15,23,24,,,,,,,,,2.0,1.0,1.6,1").trim())
    eq(d, Triple(2.0, 1.0, 1.6), "DOP from GSA")
    eq(GnssSky.dopText(sky, null), "2.0/1.0/1.6", "DOP text")
    eq(GnssSky.dopText(SkyView(emptyList()), 0.9), "0.9", "only HDOP")
}

private fun testStatusRadio() {
    val st = (Control.parse("""{"e":"status","mode":2,"profile":0,"lora":0,"digi":false,"smart":true,"sos":false,"up":60,"bleKiss":true,"f":432050000,"mod":"CW"}""") as ControlEvent.Status).status
    eq(st.freqHz, 432_050_000L, "radio frequency of the mode"); eq(st.modulation, "CW", "modulation")
    val old = (Control.parse("""{"e":"status","mode":2,"profile":0,"lora":0,"up":60}""") as ControlEvent.Status).status
    eq(old.freqHz, null, "older firmware: no frequency"); eq(TrackerMode.modulation(old.mode), "CW", "fallback modulation")
    eq(TrackerMode.modulation(TrackerMode.APRS_LORA, sa868 = true), "AFSK", "T-TWR APRS")
}

private fun testSun() {
    val utc = { y: Int, mo: Int, d: Int, h: Int, mi: Int -> java.time.ZonedDateTime.of(y, mo, d, h, mi, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
    // Seville 37.39 N 5.99 W: noon of the June solstice ~ 76°, of the December one ~ 29°
    near(Sun.elevationDeg(37.39, -5.99, utc(2026, 6, 21, 12, 24)), 76.0, 0.6, "sun elevation June noon")
    near(Sun.elevationDeg(37.39, -5.99, utc(2026, 12, 21, 12, 24)), 29.2, 0.6, "sun elevation December noon")
    check(!Sun.isDay(37.39, -5.99, utc(2026, 11, 10, 4, 58)), "night before dawn in Seville")
    check(Sun.isDay(37.39, -5.99, utc(2026, 11, 10, 12, 0)), "day at noon")
    // sunrise in Seville on 2026-11-10 is ~08:10 CET (07:10 UTC)
    check(!Sun.isDay(37.39, -5.99, utc(2026, 11, 10, 6, 55)), "before sunrise")
    check(Sun.isDay(37.39, -5.99, utc(2026, 11, 10, 7, 25)), "after sunrise")
}

private fun testRtlUsb() {
    check(RtlUsb.find(0x0bda, 0x2838) != null, "RTL-SDR Blog dongle known")
    eq(RtlUsb.find(0x1a86, 0x7523), null, "CH340 is not an RTL-SDR")
    eq(RtlUsb.id(0x0bda, 0x2838), "0bda:2838", "USB id text")
    eq(RtlUsb.KNOWN.map { it.vid to it.pid }.toSet().size, RtlUsb.KNOWN.size, "no repeated ids")
}

private fun testAlerts() {
    eq(Alerts.textKind("EMERGENCY need help", true), AlertKind.APRS_MESSAGE, "emergency message")
    eq(Alerts.textKind("sos at km 12", false), AlertKind.APRS_BEACON, "sos beacon, any case")
    eq(Alerts.textKind("SOSTENIDO", true), null, "SOS only as a word")
    eq(Alerts.textKind("Man overboard!", true), AlertKind.MOB, "MOB")
    eq(Alerts.textKind("normal text", true), null, "no alarm")
    val l = Control.parse("""{"e":"led","msg":false,"unread":0,"emg":true,"ec":"EA7ZZZ-9","et":"Mic-E: Emergency!","ek":"emergency","em":false,"eq":3,"eo":true,"fl":true}""") as ControlEvent.Led
    val a = Alerts.fromLed(l, 1000)!!
    eq(a.kind, AlertKind.ROAD, "led beacon of a -9: road emergency"); eq(a.key, "aprs:EA7ZZZ-9", "led key")
    val ais = Alerts.fromLed(l.copy(kind = "ais_sos", emgCall = "970123456"), 1000)!!
    eq(ais.kind, AlertKind.AIS_DISTRESS, "led AIS"); eq(ais.mmsi, 970123456L, "led AIS mmsi"); eq(ais.key, "ais:970123456", "led AIS key")
    eq(Alerts.fromLed(l.copy(seq = 0), 1000), null, "old firmware: no alert from led")
    val v = AisVessel(970123456, "T", "", "", 0, 14, 36.5, -6.2, 5, 0.8, 245.0, null, 5, 3.2, 212, null, null, false, text = "SART ACTIVE")
    eq(Alerts.fromAis(v, 0)?.kind, AlertKind.AIS_DISTRESS, "SART")
    eq(Alerts.fromAis(v.copy(text = "SART TEST"), 0), null, "SART test")
    eq(Alerts.fromAis(v.copy(mmsi = 224123450, status = 0, text = "", danger = true), 0)?.kind, AlertKind.AIS_COLLISION, "collision")
    val q = Alerts.Queue()
    eq(q.offer(a, 1000), true, "new alert sounds")
    eq(q.offer(a.copy(fromTracker = false), 2000), false, "same alert from the app: no second sound")
    eq(q.items.size, 1, "one alert")
    q.close(a.key, 3000)
    eq(q.offer(a, 4000), false, "closed: quiet for 10 min")
    eq(q.offer(a, 3000 + Alerts.QUIET_MS + 1), true, "after 10 min it opens again")
    val cpa = Alerts.fromAis(v.copy(mmsi = 224123450, status = 0, text = "", danger = true), 0)!!
    q.offer(cpa, 0); eq(q.offer(cpa.copy(kind = AlertKind.AIS_DISTRESS), 1), true, "collision then distress sounds again")
    val e = Control.parse("""{"e":"eai","sos":2,"sent":3,"left":581,"by":"EA7URM-9","fm":false,"md":true,"mdLeft":27,"mdMin":15}""") as ControlEvent.Eai
    eq(e.left, 581, "eai left"); eq(e.by, "EA7URM-9", "eai by"); eq(e.manDownLeft, 27, "man down left")
    eq(Alerts.fromEai(e, "EA7JRS-7", 0).map { it.kind }, listOf(AlertKind.EAI, AlertKind.MAN_DOWN), "eai alerts")
    eq(Alerts.formatLeft(581), "9:41", "countdown")
    val own = Alerts.fromEai(e, "EA7JRS-7", 0)[0]
    val q2 = Alerts.Queue(); q2.offer(own, 0); q2.close("eai", 1)
    eq(q2.offer(own, 5000), false, "closed EAI stays closed while it lasts")
    q2.end("eai"); eq(q2.offer(own, Alerts.QUIET_MS * 3), true, "next EAI opens again")
}

private fun testWxRadar() {
    eq(WxRadar.aemetData("""{"descripcion":"exito","estado":200,"datos":"https://opendata.aemet.es/opendata/sh/abc"}""").getOrNull(), "https://opendata.aemet.es/opendata/sh/abc", "aemet data url")
    eq(WxRadar.aemetData("""{"descripcion":"API key invalido","estado":401}""").exceptionOrNull()?.message, "AEMET: API key invalido", "aemet error")
}

private fun testLights() {
    val rx = Control.parse("""{"e":"rf","s":"rx","rssi":-97,"snr":7.5}""") as ControlEvent.Rf
    eq(rx.state, "rx", "rf rx"); eq(rx.rssi, -97, "rf rssi"); eq(rx.snr, 7.5, "rf snr")
    eq((Control.parse("""{"e":"rf","s":"tx"}""") as ControlEvent.Rf).state, "tx", "rf tx")
    val l = Control.parse("""{"e":"led","msg":true,"unread":2,"emg":true,"ec":"EA7ZZZ-9","et":"SOS 9.6 km","fl":true}""") as ControlEvent.Led
    eq(l.msg && l.emg && l.flash, true, "led flags"); eq(l.unread, 2, "led unread"); eq(l.emgCall, "EA7ZZZ-9", "led call"); eq(l.emgText, "SOS 9.6 km", "led text")
    val o = Control.parse("""{"e":"led","msg":false,"unread":0,"emg":false,"fl":false}""") as ControlEvent.Led
    eq(o.emg || o.msg || o.flash, false, "led off"); eq(o.emgCall, "", "led no call")
}

private fun testHeardSymbol() {
    // the tracker sends code then table; the app keeps table then code
    eq(heardSymbol("&L"), "L&", "LoRa iGate from the tracker")
    eq(heardSymbol(">/"), "/>", "car from the tracker")
    eq(AprsSymbols.describe('L', '&'), "LoRa iGate", "not the lighthouse")
    val h = (Control.parse("""{"e":"heard","tot":1,"pkts":1,"from":0,"items":[{"c":"EA7XYZ-9","sym":">/","age":5}]}""") as ControlEvent.Heard).items[0]
    eq(h.symbol, "/>", "heard event symbol in APRS order")
    eq(AprsSymbols.of(h.symbol)?.base, AprsSymbols.Cell(0, 13, 1), "car icon")
}

// Formulas shared with the plotter (lib/plotter_core): same reference values (pygeomag, PyEphem, NWS)
private fun testFormulas() {
    val w = Wmm.DEFAULT
    near(w.field(36.53, -6.29, 0.0, 2026.86)!!.decDeg, -0.0318, 0.002, "WMM declination Rota")
    near(w.field(36.53, -6.29, 0.0, 2026.86)!!.incDeg, 49.8584, 0.002, "WMM inclination Rota")
    near(w.field(89.0, -121.0, 28.0, 2025.0)!!.decDeg, -99.7746, 0.01, "WMM declination north pole area")
    near(w.field(-80.0, 240.0, 48.0, 2027.5)!!.decDeg, 68.2187, 0.01, "WMM declination Antarctica")
    near(w.field(0.0, 0.0, 0.0, 2026.0)!!.decDeg, -3.8929, 0.002, "WMM declination 0/0")
    check(w.field(91.0, 0.0, 0.0, 2026.0) == null && !w.expired(2029.99) && w.expired(2030.0), "WMM range and validity")
    near(Wmm.decimalYear(1767225600000L), 2026.0, 1e-9, "decimal year")
    val cof = buildString {
        append("    2030.0  WMM-2030  12/01/2029\n")
        for (n in 1..12) for (m in 0..n) append("$n $m ${if (n == 1 && m == 0) "-30000" else "0"} 0 0 0\n")
        append("999999\n")
    }
    val w2 = Wmm.load(cof)
    check(w2 != null && w2.epoch == 2030.0 && w2.name == "WMM-2030" && w2.validUntil == 2035.0, "WMM.COF loaded")
    near(w2!!.declination(0.0, 0.0, 2030.0), 0.0, 1e-6, "axial dipole: no declination")
    check(Wmm.load("2030.0 X\n1 0 1 0 0 0\n") == null, "WMM.COF too short")
    val t = 1794490200000L                                            // 2026-11-12 13:30 UTC, Rota
    val sun = Astro.sunPosition(t, 36.53, -6.29)
    near(sun.altDeg, 32.434, 0.02, "sun altitude"); near(sun.azDeg, 202.895, 0.02, "sun azimuth")
    val moon = Astro.moonPosition(t, 36.53, -6.29)
    near(moon.altDeg, 22.708, 0.03, "moon altitude"); near(moon.azDeg, 162.424, 0.03, "moon azimuth")
    check(moon.distKm in 400000.0..410000.0, "moon distance")
    val m92 = Astro.moonPosition(703112400000L, 36.53, -6.29)        // 1992-04-12 21:00 UTC
    near(m92.altDeg, 62.016, 0.03, "moon 1992 altitude"); near(m92.azDeg, 187.298, 0.03, "moon 1992 azimuth")
    val mt = Astro.moonTimes(1794441600000L, 36.53, -6.29)            // 2026-11-12 00:00 UTC
    near(mt.riseMin ?: -1.0, 606.3, 3.0, "moonrise"); near(mt.setMin ?: -1.0, 1165.1, 3.0, "moonset")
    val st = Astro.sunTimes(2026, 11, 12, 36.53, -6.29)
    check(st.riseMin != null && st.setMin != null && st.riseMin!! in 400.0..440.0 && st.setMin!! in 1020.0..1060.0, "sunrise / sunset")
    check(Astro.sunTimes(2026, 6, 21, 80.0, 0.0).riseMin == null, "polar day")
    run {   // iterated rise / set (plotter core): at that minute the precise sun is at the asked altitude, any latitude
        var bad = 0; var n = 0
        for (la in doubleArrayOf(0.0, 36.53, 43.5, 52.0, 60.0, 66.0)) for (h in doubleArrayOf(-0.833, -6.0, -12.0)) for (mo in 1..12) {
            val r = Astro.sunTimes(2026, mo, 15, la, -6.28, h)
            if (r.riseMin == null || r.setMin == null) continue
            val day = java.time.LocalDate.of(2026, mo, 15).toEpochDay() * 86_400_000L
            val a1 = Astro.sunPosition(day + (r.riseMin!! * 60_000).toLong(), la, -6.28).altDeg
            val a2 = Astro.sunPosition(day + (r.setMin!! * 60_000).toLong(), la, -6.28).altDeg
            n++
            if (abs(a1 - h) > 0.003 || abs(a2 - h) > 0.003) bad++
        }
        check(n > 180 && bad == 0, "sunrise / sunset iterated")
        val noonDay = java.time.LocalDate.of(2026, 6, 15).toEpochDay() * 86_400_000L
        near(Astro.sunPosition(noonDay + (Astro.solarNoonMin(2026, 6, 15, -6.28) * 60_000).toLong(), 52.0, -6.28).azDeg, 180.0, 0.02, "solar noon due south")
    }
    check(Astro.moonPhase8(0.2) == 0 && Astro.moonPhase8(7.4) == 2 && Astro.moonPhase8(14.8) == 4 && Astro.moonPhase8(22.1) == 6, "moon phase")
    eq(Astro.moonPhaseName(4), "Full moon", "moon phase name")
    near(Astro.refractionDeg(0.0), 0.48, 0.03, "refraction at the horizon")
    eq(Astro.hhmm(606.3, 60), "11:06", "hh:mm with offset"); eq(Astro.hhmm(null), "--:--", "hh:mm unknown")
    near(WxCalc.dewPointC(20.0, 50.0) ?: 0.0, 9.26, 0.05, "dew point"); check(WxCalc.dewPointC(20.0, 0.0) == null, "dew point no humidity")
    near(WxCalc.windChillC(-10.0, 30.0), -19.5, 0.1, "wind chill"); eq(WxCalc.windChillC(15.0, 30.0), 15.0, "no wind chill when warm")
    near(WxCalc.heatIndexC(32.0, 70.0), 40.4, 0.3, "heat index")
    eq(WxCalc.feelsLikeC(18.0, 50.0, 20.0), 18.0, "feels like = air")
    near(WxCalc.seaLevelPressureHpa(1000.0, 110.0, 15.0), 1013.1, 0.3, "sea level pressure")
    eq(listOf(0.0, 0.5, 3.4, 10.0, 38.9, 70.0).map { WxCalc.beaufort(it) }, listOf(0, 1, 1, 3, 8, 12), "Beaufort")
    eq(WxCalc.beaufortMs(20.0), 8, "Beaufort from m/s"); eq(WxCalc.beaufortName(8), "Gale", "Beaufort name")
    // Rotor box: track, trk, bf, sky, hdg.dec
    eq(RotorProto.track(RotorProto.SkyTarget.MOON), "{\"c\":\"track\",\"t\":\"moon\"}\n", "rotor track")
    val p = RotorProto.parse("{\"e\":\"pos\",\"az\":162.4,\"el\":22.7,\"st\":0,\"mv\":false,\"f\":\"OK\"," +
        "\"wind\":{\"on\":true,\"park\":15,\"lk\":false,\"ws\":8,\"pk\":8,\"bf\":5,\"age\":3}," +
        "\"trk\":{\"t\":\"moon\",\"az\":162.4,\"el\":22.7,\"below\":false},\"hdg\":{\"on\":false,\"dec\":0.9}}")
    check(p is RotorProto.Event.Pos && p.pos.track?.target == "moon" && p.pos.track?.below == false && p.pos.wind?.beaufort == 5 &&
        p.pos.declinationDeg == 0.9, "rotor pos with trk / bf / dec")
    val i = RotorProto.parse("{\"e\":\"info\",\"api\":1,\"sky\":true,\"caps\":{\"sky\":true}}")
    check(i is RotorProto.Event.InfoEv && i.info.sky, "rotor info sky")
    // Raspberry Pi with the stepper motor HAT and the GPS HAT
    val stp = RotorProto.parse("{\"e\":\"info\",\"drv\":\"stepper\",\"caps\":{\"cal\":true,\"gps\":true},\"gps\":{\"link\":true,\"fix\":3,\"sats\":9,\"seen\":14,\"pps\":true,\"lat\":36.72,\"lon\":-4.42,\"alt\":12.5}}")
    check(stp is RotorProto.Event.InfoEv && stp.info.stepper && stp.info.canCalibrate && stp.info.gps?.fix == 3 && stp.info.gps?.pps == true &&
        stp.info.gps?.lat == 36.72 && stp.info.gps?.altM == 12.5, "rotor info stepper + gps")
    val rs = RotorProto.parse("{\"e\":\"raw\",\"link\":true,\"known\":false,\"homing\":true,\"azSteps\":24000,\"elSteps\":-5,\"fault\":\"HOME AZ\"}")
    check(rs is RotorProto.Event.Raw && rs.known == false && rs.homing && rs.azSteps == 24000L && rs.elSteps == -5L && rs.fault == "HOME AZ", "rotor raw stepper")
    eq(RotorProto.home(), "{\"c\":\"home\"}\n", "rotor home")
}

// Celestial navigation: same reference values as the plotter core (a 2026 nautical almanac, 0.1', and PyEphem)
private fun testLatitude() {
    // same cases as testLatitude of the plotter
    near(Celnav.latitudeFromAltitude(90 - (36.5 + 18.3), -18.3, 0.0, 36.0)!!, 36.5, 1e-9, "lat: meridian, N")
    near(Celnav.latitudeFromAltitude(90 - (12.0 - 5.0), -5.0, 0.0, -10.0)!!, -12.0, 1e-9, "lat: meridian, S")
    val aa = Celnav.bodyAltAz(30.0, 10.0, 41.2, -20.0)
    near(Celnav.latitudeFromAltitude(aa.alt, 10.0, 10.0, 40.0)!!, 41.2, 1e-9, "lat: off the meridian")
    check(Celnav.latitudeFromAltitude(80.0, -60.0, 90.0, 0.0) == null, "lat: impossible altitude")
    val pol = Celnav.findStar("Polaris")!!
    val jd = 2461360.75
    val p = Celnav.starShaDec(pol, jd)
    val gha = (Celnav.ariesGha(jd) + p.sha) % 360.0
    val pa = Celnav.bodyAltAz(gha, p.dec, 36.53, -6.28)
    near(Celnav.latitudeFromAltitude(pa.alt, p.dec, gha - 6.28, 35.0)!!, 36.53, 1e-9, "lat: Polaris")
    check(abs(pa.alt - 36.53) < 1.0, "lat: Polaris within a degree")
    val sunGha = { j: Double -> Celnav.sunGhaDec(j).gha }
    val jdNoon = 2461360.0
    val tp = Celnav.meridianPassageJd(jdNoon, -6.28, sunGha)
    // the Sun's local hour angle is 0 at the passage, and it is near 12:25 UTC (about 12 h - 15 min + 25 min)
    near(Math.IEEEremainder(sunGha(tp) - 6.28, 360.0), 0.0, 1e-4, "lat: Sun meridian passage, LHA 0")
    check(abs((tp - (jdNoon - 0.5)) * 1440.0 - (12 * 60 - 15.3 + 25.1)) < 1.0, "lat: Sun meridian passage time")
    val vegaGha = { j: Double -> val v = Celnav.starShaDec(Celnav.findStar("Vega")!!, j); (Celnav.ariesGha(j) + v.sha) % 360.0 }
    val tv = Celnav.meridianPassageJd(jdNoon, -6.28, vegaGha)
    near(Math.IEEEremainder(vegaGha(tv) - 6.28, 360.0), 0.0, 1e-4, "lat: Vega meridian passage")
    check(abs(tv - jdNoon) <= 0.51, "lat: nearest passage")
}

private fun testRadiotel() {
    // same cases as testRadiotel of the plotter
    val R = Radiotel
    check(R.natureCount(Radiotel.Kind.MAYDAY) == 11, "radiotel: 11 distress natures")
    check(R.natureFromDsc(100) == 0 && R.natureFromDsc(110) == 10 && R.natureFromDsc(111) == -1, "radiotel: DSC natures")
    check(R.natureName(Radiotel.Kind.MAYDAY, R.natureFromDsc(105), true) == "SINKING", "radiotel: 105 sinking")
    check(R.natureName(Radiotel.Kind.MAYDAY, R.natureFromDsc(110), false) == "HOMBRE AL AGUA", "radiotel: 110 MOB")
    check(R.phonetic("EA7jrs", false) == "ECHO ALFA SIETE JULIETT ROMEO SIERRA", "radiotel: phonetic ES")
    check(R.phonetic("EB-9", true) == "ECHO BRAVO NINE", "radiotel: phonetic EN")
    check(R.digits("224 1", true) == "TWO TWO FOUR ONE", "radiotel: digits")
    check(R.position(36.53, -6.28333, false) == "36 GRADOS 31,8 MINUTOS NORTE, 006 GRADOS 17,0 MINUTOS OESTE", "radiotel: position ES")
    var i = Radiotel.Input(name = "Mar Azul", callsign = "EA1234", mmsi = "224123456", lat = 36.53, lon = -6.28333, utcMin = 14 * 60 + 5,
        nature = 1, persons = 3, description = "velero de 12 m, casco blanco")
    var m = R.message(i)
    check(m.startsWith("MAYDAY, MAYDAY, MAYDAY\n"), "radiotel: MAYDAY call")
    check("AQUÍ MAR AZUL, MAR AZUL, MAR AZUL\n" in m, "radiotel: name three times")
    check("DISTINTIVO ECHO ALFA UNO DOS TRES CUATRO, MMSI DOS DOS CUATRO" in m, "radiotel: identity")
    check("A LAS 1405 UTC" in m && "VÍA DE AGUA\n" in m && "3 PERSONAS A BORDO\n" in m, "radiotel: time, nature, persons")
    check("VELERO DE 12 M, CASCO BLANCO\n" in m && m.endsWith("CAMBIO\n"), "radiotel: description and over")
    i = i.copy(english = true, kind = Radiotel.Kind.PAN_PAN, nature = 2, persons = 1)
    m = R.message(i)
    check(m.startsWith("PAN PAN PAN PAN PAN PAN\nALL STATIONS ALL STATIONS ALL STATIONS\nTHIS IS MAR AZUL MAR AZUL MAR AZUL\n"), "radiotel: PAN PAN")
    check("I REQUIRE MEDICAL ADVICE\n1 PERSON ON BOARD\n" in m && m.endsWith("OVER\n"), "radiotel: PAN PAN body")
    i = i.copy(kind = Radiotel.Kind.SECURITE, nature = 1, extra = "container awash")
    m = R.message(i)
    check(m.startsWith("SÉCURITÉ SÉCURITÉ") && "DRIFTING OBJECT\nCONTAINER AWASH\nIN POSITION 36 DEGREES" in m, "radiotel: SECURITE")
    check("PERSON" !in m && m.endsWith("OUT\n"), "radiotel: SECURITE ends OUT")
    i = i.copy(kind = Radiotel.Kind.MAYDAY_RELAY, english = false, nature = 5, extra = "", relayMmsi = "224999888", relayLat = 36.1, relayLon = -6.5, relayPersons = 4)
    m = R.message(i)
    check(m.startsWith("MAYDAY RELAY, MAYDAY RELAY, MAYDAY RELAY\nA TODAS LAS ESTACIONES"), "radiotel: relay call")
    check("DE BUQUE DESCONOCIDO, MMSI DOS DOS CUATRO NUEVE" in m, "radiotel: relay vessel")
    check("36 GRADOS 06,0 MINUTOS NORTE, 006 GRADOS 30,0 MINUTOS OESTE\nHUNDIMIENTO\n4 PERSONAS A BORDO\n" in m && "3 PERSONAS" !in m, "radiotel: relay body")
    m = R.message(Radiotel.Input())
    check("[NOMBRE DEL BARCO]" in m && "[SITUACIÓN]" in m && "DISTINTIVO" !in m && "PERSONAS" !in m, "radiotel: gaps")
    check(R.TYPE_ES.size == Vessel.TYPES.size, "radiotel: Spanish ship types")
}

private fun testBearingFix() {
    // same cases as testBearingFix of the plotter
    val f1 = 36.50 to -6.30; val f2 = 36.45 to -6.10
    val boat = 36.40 to -6.20
    fun brg(a: Pair<Double, Double>, b: Pair<Double, Double>) = Current.rhumbBearing(a.first, a.second, b.first, b.second)
    fun nm(aLat: Double, aLon: Double, b: Pair<Double, Double>) = Current.rhumbDistanceNm(aLat, aLon, b.first, b.second)
    var f = Current.fixFromBearings(f1.first, f1.second, brg(boat, f1), f2.first, f2.second, brg(boat, f2), 0.0, 0.0)!!
    check(nm(f.lat, f.lon, boat) < 0.02, "bearings: simultaneous fix")
    near(f.dist2Nm, nm(boat.first, boat.second, f2), 0.02, "bearings: distance to mark 2")
    val p1 = 36.40 to -6.40
    val p2 = Current.rhumbDestination(p1.first, p1.second, 90.0, 3.0)
    f = Current.fixFromBearings(f1.first, f1.second, brg(p1, f1), f1.first, f1.second, brg(p2, f1), 90.0, 3.0)!!
    check(nm(f.lat, f.lon, p2) < 0.02, "bearings: running fix on one light")
    check(nm(f.firstLat, f.firstLon, p1) < 0.02, "bearings: position at the first bearing")
    check(f.cutDeg > 10, "bearings: cut")
    f = Current.fixFromBearings(f1.first, f1.second, brg(p1, f1), f2.first, f2.second, brg(p2, f2), 90.0, 3.0)!!
    check(nm(f.lat, f.lon, p2) < 0.02, "bearings: running fix on two lights")
    check(Current.fixFromBearings(f1.first, f1.second, 0.0, f2.first, f2.second, 5.0, 0.0, 0.0) == null, "bearings: nearly parallel")
    near(Current.parseCoord("36.53", true)!!, 36.53, 1e-9, "coord: decimal")
    near(Current.parseCoord("-6,28", false)!!, -6.28, 1e-9, "coord: comma")
    near(Current.parseCoord("36 31.8N", true)!!, 36.53, 1e-9, "coord: deg min N")
    near(Current.parseCoord("36°31,8'N", true)!!, 36.53, 1e-9, "coord: degree sign")
    near(Current.parseCoord("006 16.8 W", false)!!, -6.28, 1e-9, "coord: W")
    near(Current.parseCoord("6 16 48 O", false)!!, -6.28, 1e-9, "coord: O and seconds")
    check(Current.parseCoord("36 31.8E", true) == null, "coord: E on a latitude")
    check(Current.parseCoord("95", true) == null, "coord: out of range")
    check(Current.parseCoord("36 61", true) == null, "coord: minutes over 60")
    check(Current.fixFromBearings(f1.first, f1.second, (brg(boat, f1) + 180) % 360, f2.first, f2.second, brg(boat, f2), 0.0, 0.0) == null, "bearings: wrong side")
}

private fun testCurrent() {
    // same cases as testCurrent of the plotter
    var d = Current.rhumbDestination(36.0, -6.0, 45.0, 100.0)
    near(Current.rhumbBearing(36.0, -6.0, d.first, d.second), 45.0, 1e-6, "current: rhumb bearing back")
    near(Current.rhumbDistanceNm(36.0, -6.0, d.first, d.second), 100.0, 1e-6, "current: rhumb distance back")
    d = Current.rhumbDestination(36.0, -6.0, 90.0, 60.0)
    near(d.first, 36.0, 1e-9, "current: due east keeps the latitude")
    near(Current.rhumbDistanceNm(36.0, -6.0, d.first, d.second), 60.0, 1e-6, "current: due east distance")
    var c = Current.Vec(180.0, 2.0)
    val a = Math.toDegrees(kotlin.math.asin(1.0 / 3))
    var r = Current.courseToSteer(90.0, 6.0, 0.0, c)!!
    near(r.first, 90 - a, 1e-9, "current: course to steer"); near(r.second, kotlin.math.sqrt(32.0), 1e-9, "current: SOG")
    var g = Current.groundTrack(r.first, 6.0, 0.0, c)
    near(g.first, 90.0, 1e-9, "current: ground track"); near(g.second, kotlin.math.sqrt(32.0), 1e-9, "current: ground speed")
    r = Current.courseToSteer(90.0, 6.0, 5.0, c)!!
    near(r.first, 90 - a - 5, 1e-9, "current: with leeway")
    near(Current.groundTrack(r.first, 6.0, 5.0, c).first, 90.0, 1e-9, "current: ground track with leeway")
    check(Current.courseToSteer(90.0, 6.0, 0.0, Current.Vec(180.0, 7.0)) == null, "current: crosses faster")
    check(Current.courseToSteer(90.0, 6.0, 0.0, Current.Vec(270.0, 8.0)) == null, "current: stops the boat")
    r = Current.courseToSteer(90.0, 6.0, 0.0, Current.Vec(270.0, 2.0))!!
    near(r.first, 90.0, 1e-9, "current: head current"); near(r.second, 4.0, 1e-9, "current: head current SOG")
    c = Current.Vec(135.0, 1.5)
    g = Current.groundTrack(10.0, 5.0, 0.0, c)
    val m = Current.fromVectors(10.0, 5.0, 0.0, g.first, g.second)
    near(m.setDeg, 135.0, 1e-9, "current: measured set"); near(m.driftKn, 1.5, 1e-9, "current: measured drift")
    near(Current.fromVectors(10.0, 5.0, 0.0, 10.0, 5.0).driftKn, 0.0, 1e-9, "current: none")
    val e = Current.rhumbDestination(36.0, -6.0, 90.0, 12.0)
    val fix = Current.rhumbDestination(e.first, e.second, 200.0, 2.0)
    val dr = Current.fromDr(36.0, -6.0, 90.0, 6.0, 0.0, 2.0, fix.first, fix.second)!!
    near(Current.rhumbDistanceNm(dr.drLat, dr.drLon, e.first, e.second), 0.0, 1e-9, "current: DR position")
    near(dr.current.setDeg, 200.0, 1e-6, "current: DR set"); near(dr.current.driftKn, 1.0, 1e-6, "current: DR drift")
    check(Current.fromDr(36.0, -6.0, 90.0, 6.0, 0.0, 0.0, fix.first, fix.second) == null, "current: no time")
}

private fun testCelnav() {
    fun dm(d: Int, m: Double) = d + m / 60.0
    fun jd(y: Int, mo: Int, d: Int, h: Int): Double {
        val c = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")); c.clear(); c.set(y, mo - 1, d, h, 0, 0)
        return Celnav.julianDay(c.timeInMillis)
    }
    // Moon against JPL DE421 and planets against PyEphem (same values as the plotter core)
    run {
        val j = jd(2026, 11, 15, 12)
        val m = Celnav.moonGhaDec(j)
        near(m.gha, 290.5971, 0.005, "celnav moon GHA"); near(m.dec, -21.8661, 0.005, "celnav moon dec"); near(m.dist, 403893.0, 60.0, "celnav moon distance")
        val ref = listOf(Triple(33.8976, -10.1333, 0.3331), Triple(86.1122, 14.8131, 1.3117), Triple(86.2542, 13.6086, 5.2753), Triple(225.7374, 0.9423, 8.6920))
        for ((i, p) in Celnav.Planet.values().withIndex()) {
            val b = Celnav.planetGhaDec(p, j)
            near(b.gha, ref[i].first, 0.002, "celnav ${p.label} GHA"); near(b.dec, ref[i].second, 0.002, "celnav ${p.label} dec"); near(b.dist, ref[i].third, 0.0005, "celnav ${p.label} distance")
        }
        val j2 = jd(2035, 1, 9, 0)
        val m2 = Celnav.moonGhaDec(j2)
        near(m2.gha, 186.2497, 0.005, "celnav moon GHA 2035"); near(m2.dec, -18.3677, 0.005, "celnav moon dec 2035")
        val s2 = Celnav.planetGhaDec(Celnav.Planet.SATURN, j2)
        near(s2.gha, 343.0114, 0.002, "celnav Saturn GHA 2035"); near(s2.dec, 19.8376, 0.002, "celnav Saturn dec 2035")
        near(Celnav.moonHpArcmin(384400.0), 57.04, 0.02, "celnav moon HP"); near(Celnav.planetHpArcmin(0.3331), 0.44, 0.01, "celnav Venus HP")
    }
    val tol = 0.1 / 60
    var s = Celnav.sunGhaDec(jd(2026, 1, 1, 0))
    near(s.gha, dm(179, 10.0), tol, "celnav sun GHA 1 Jan 0h"); near(s.dec, -dm(23, 1.0), tol, "celnav sun dec 1 Jan 0h")
    s = Celnav.sunGhaDec(jd(2026, 6, 16, 9))
    near(s.gha, dm(314, 49.6), tol, "celnav sun GHA 16 Jun 9h"); near(s.dec, dm(23, 21.2), tol, "celnav sun dec 16 Jun 9h")
    near(Celnav.ariesGha(jd(2026, 6, 16, 0)), dm(264, 16.8), tol, "celnav Aries 0h")
    near(Celnav.ariesGha(jd(2026, 6, 16, 23)), dm(250, 13.5), tol, "celnav Aries 23h")
    near(Celnav.sunSemiDiameterArcmin(jd(2026, 6, 16, 12)), 15.75, 0.02, "celnav semidiameter")
    val alm = listOf(Triple("Acamar", dm(315, 11.2), -dm(40, 11.7)), Triple("Acrux", dm(172, 58.6), -dm(63, 15.1)),
        Triple("Capella", dm(280, 20.6), dm(46, 1.4)), Triple("Deneb", dm(49, 24.6), dm(45, 22.3)),
        Triple("Regulus", dm(207, 33.3), dm(11, 50.3)), Triple("Rigel", dm(281, 3.1), -dm(8, 10.3)))
    for ((n, sha, dec) in alm) {
        val p = Celnav.starShaDec(Celnav.findStar(n)!!, jd(2026, 6, 16, 12))
        near(p.sha, sha, 0.11 / 60, "celnav $n SHA"); near(p.dec, dec, 0.11 / 60, "celnav $n dec")
    }
    val si = Celnav.starShaDec(Celnav.findStar("sirius")!!, jd(2030, 7, 4, 12))
    near(si.sha, 258.3789, 0.0005, "celnav Sirius 2030 SHA"); near(si.dec, -16.7609, 0.0005, "celnav Sirius 2030 dec")
    check(Celnav.STARS.size == 157 && Celnav.NAV_STARS == 58 && Celnav.STARS[57].name == "Polaris" && Celnav.findStar("Caph") != null && Celnav.findStar("nada") == null, "celnav catalogue")
    run {
        // same sky as the plotter test: 15 Jun 2026 21:30 UTC at 36.5 N 6.3 W (nautical twilight off Cadiz)
        val sky = Celnav.visibleBodies(jd(2026, 6, 15, 21) + 0.5 / 24.0, 36.5, -6.3)
        check(sky.size == 54 && sky.zipWithNext().all { it.first.az <= it.second.az } && sky.all { it.alt in 10.0..80.0 } && sky.none { it.kind == 0 }, "celnav visible bodies")
        val t = Celnav.bestStarTriple(sky)
        check(t != null && listOf(t.first, t.second, t.third).map { sky[it].name }.toSet() == setOf("Vega", "Arcturus", "Merak"), "celnav best triple")
    }
    near(Celnav.dipArcmin(11.08), 5.9, 0.05, "celnav dip"); near(Celnav.refractionArcmin(15.0 + 1 / 60.0), 3.6, 0.06, "celnav refraction")
    near(Celnav.observedAltitude(35 + 20.0 / 60, 1.5, 3.0, Celnav.Limb.LOWER, 15.9, 0.15), 35 + 30.1 / 60, 0.1 / 60, "celnav Ho lower limb")
    // three stars from 36 30 N 006 20 W with the assumed position 8' south and 6' west: the fix comes back
    val t = jd(2026, 6, 16, 21); val tLat = 36.5; val tLon = -6.3333; val eLat = tLat - 8 / 60.0; val eLon = tLon - 6 / 60.0
    val sights = listOf("Arcturus", "Vega", "Spica").map { n ->
        val p = Celnav.starShaDec(Celnav.findStar(n)!!, t)
        val gha = (Celnav.ariesGha(t) + p.sha) % 360.0
        Celnav.reduceSight(eLat, eLon, gha, p.dec, Celnav.bodyAltAz(gha, p.dec, tLat, tLon).alt)
    }
    val fix = Celnav.fixFromSights(eLat, eLon, sights)
    check(fix != null, "celnav fix")
    near(fix!!.first, tLat, 0.3 / 60, "celnav fix lat"); near(fix.second, tLon, 0.4 / 60, "celnav fix lon")
    check(Celnav.fixFromSights(eLat, eLon, listOf(sights[0], sights[0])) == null, "celnav parallel lines")
    // running fix: a Vega line taken 6 nm to the west is advanced to the present position and crossed with Spica
    val pLon = tLon - 6 / (60.0 * Math.cos(Math.toRadians(tLat)))
    val vega = Celnav.starShaDec(Celnav.findStar("Vega")!!, t); val vGha = (Celnav.ariesGha(t) + vega.sha) % 360.0
    val old = Celnav.reduceSight(tLat, pLon, vGha, vega.dec, Celnav.bodyAltAz(vGha, vega.dec, tLat, pLon).alt)
    val adv = Celnav.referTo(Celnav.advanceSight(old, tLat, pLon, tLat, tLon), eLat, eLon)
    val run = Celnav.fixFromSights(eLat, eLon, listOf(adv, sights[2]))
    check(run != null, "celnav running fix")
    near(run!!.first, tLat, 0.4 / 60, "celnav running fix lat"); near(run.second, tLon, 0.5 / 60, "celnav running fix lon")
}

private fun testDeviation() {
    val d = Deviation
    near(d.totalCorrection(135.4, 131.2), 4.2, 1e-9, "Ct")
    near(d.deviationFromCt(4.2, -1.5), 5.7, 1e-9, "deviation from Ct")
    near(d.totalCorrection(2.0, 358.0), 4.0, 1e-9, "Ct across north")
    near(d.totalCorrection(358.0, 2.0), -4.0, 1e-9, "Ct across north, west")
    near(d.deviationFromCt(-3.0, 2.0), -5.0, 1e-9, "deviation west")
    // Polaris from 40 N: within 1.5 degrees of north at any time
    val pol = Celnav.STARS.first { it.name == "Polaris" }
    var ok = true
    for (h in 0 until 24 step 3) {
        val jd = Celnav.julianDay(java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 10, 15, h, 0) }.timeInMillis)
        val sd = Celnav.starShaDec(pol, jd)
        val aa = Celnav.bodyAltAz((Celnav.ariesGha(jd) + sd.sha) % 360.0, sd.dec, 40.0, -5.0)
        if (kotlin.math.abs(d.angleDiff(aa.az, 0.0)) > 1.5 || kotlin.math.abs(aa.alt - 40.0) > 1.5) ok = false
    }
    check(ok, "Polaris azimuth near north")
    val A = 1.0; val B = 2.0; val C = 3.0; val D = -0.5; val E = 0.7
    fun curve(h: Double): Double { val r = Math.toRadians(h); return A + B * kotlin.math.sin(r) + C * kotlin.math.cos(r) + D * kotlin.math.sin(2 * r) + E * kotlin.math.cos(2 * r) }
    val k = Deviation.Card()
    for (h in 0 until 360 step 45) k.add(h.toDouble(), curve(h.toDouble()))
    check(k.points.size == 8 && k.terms == 5 && k.rmsDeg < 1e-6, "card: eight headings, five terms")
    near(k.coef[0], A, 1e-6, "A"); near(k.coef[1], B, 1e-6, "B"); near(k.coef[2], C, 1e-6, "C"); near(k.coef[3], D, 1e-6, "D"); near(k.coef[4], E, 1e-6, "E")
    near(k.at(100.0), curve(100.0), 1e-6, "card at 100")
    val ra = k.compassFromMagnetic(100.0)
    near(ra + k.at(ra), 100.0, 1e-3, "compass heading for a magnetic one")
    k.add(46.0, curve(46.0) + 1.0)
    check(k.points.size == 8 && k.rmsDeg > 0.1, "same heading replaces")
    val k2 = Deviation.Card(); k2.decode(k.encode())
    check(k2.points.size == 8 && kotlin.math.abs(k2.at(200.0) - k.at(200.0)) < 0.1, "card encode / decode")
    val t3 = Deviation.Card(); t3.add(0.0, 4.0); t3.add(120.0, 1.0); t3.add(240.0, -2.0)
    check(t3.terms == 3, "three headings: A B C"); near(t3.at(0.0), 4.0, 1e-6, "three at 0"); near(t3.at(240.0), -2.0, 1e-6, "three at 240")
    val tc = Deviation.Card(); for ((h, v) in listOf(10.0 to 2.0, 16.0 to 3.0, 22.0 to 2.5, 28.0 to 2.5, 34.0 to 2.0)) tc.add(h, v)
    check(tc.terms == 1, "clustered headings: mean only"); near(tc.at(200.0), 2.4, 1e-6, "clustered mean")
    val t1 = Deviation.Card(); t1.add(90.0, -3.0)
    check(t1.terms == 1, "one observation"); near(t1.at(270.0), -3.0, 1e-9, "one observation everywhere")
    t1.remove(0); check(t1.points.isEmpty() && t1.terms == 0 && t1.at(0.0) == 0.0, "removed")
    val full = Deviation.Card(); for (i in 0 until 30) full.add(i * 12.0, 1.0)
    check(full.points.size == Deviation.Card.MAX && kotlin.math.abs(full.points[0].compassHdg - 72.0) < 1e-6, "full card drops the oldest")
}

private fun testNavtex() {
    // CCIR 476: 35 valid codes (four marks), letters and figures both ways
    check((0 until 128).count { Navtex.validCode(it) } == 35, "ccir476 valid codes")
    check(Navtex.codeToChar(0x47, false) == 'A' && Navtex.codeToChar(0x47, true) == '-', "ccir476 A / -")
    val codes = Navtex.textToCodes("ZCZC GA05\r\nGALE 8 AT 36-07.5N 005-21.2W\r\nNNNN\r\n")
    val back = StringBuilder(); var figs = false
    for (c in codes) when (c) { Navtex.CODE_LTRS -> figs = false; Navtex.CODE_FIGS -> figs = true; else -> back.append(Navtex.codeToChar(c, figs)) }
    check(back.toString() == "ZCZC GA05\r\nGALE 8 AT 36-07.5N 005-21.2W\r\nNNNN\r\n", "ccir476 round trip")
    // interleave: the repeat of each character comes 5 slots (35 bits) after its first copy
    val st = Navtex.interleave(intArrayOf(0x47, 0x4b), phasingPairs = 2, tailPairs = 1)
    check(st[0] == Navtex.CODE_REP && st[1] == Navtex.CODE_ALPHA && st[4] == 0x47 && st[9] == 0x47 && st[6] == 0x4b && st[11] == 0x4b, "sitor-b interleave")
    // a whole message through the modem, clean and with noise and an offset the AFC must pull in
    fun run(text: String, snrNoise: Double, offsetHz: Double, seed: Long, reverse: Boolean = false): List<Navtex.Message> {
        val audio = Navtex.modulate(Navtex.interleave(Navtex.textToCodes(text), 60, 30), centerHz = 1000.0 + offsetHz)
        if (reverse) { /* LSB: swap mark and space by mirroring around 1000 Hz is not needed; the decoder flag is tested separately */ }
        val r = java.util.Random(seed)
        for (i in audio.indices) audio[i] = (audio[i] + snrNoise * r.nextGaussian()).toFloat()
        val got = ArrayList<Navtex.Message>()
        val d = Navtex.Decoder(1000.0); d.onMessage = { got.add(it) }
        var i = 0
        while (i < audio.size) { val n = minOf(400, audio.size - i); d.process(audio.copyOfRange(i, i + n), n); i += n }
        return got
    }
    val text = "ZCZC GA05\r\nTARIFA NAVTEX NAV WARNING 123/26\r\nWRECK IN 36-07.5N 005-21.2W. VESSELS KEEP CLEAR 1 MILE.\r\nNNNN\r\n"
    val m1 = run(text, 0.0, 0.0, 1)
    check(m1.size == 1 && m1[0].station == 'G' && m1[0].subject == 'A' && m1[0].number == 5 && !m1[0].lostHeader && !m1[0].lostTrailer, "navtex clean: header ${m1.map { it.id }}")
    check(m1.firstOrNull()?.text == "TARIFA NAVTEX NAV WARNING 123/26\nWRECK IN 36-07.5N 005-21.2W. VESSELS KEEP CLEAR 1 MILE.", "navtex clean: text '${m1.firstOrNull()?.text}'")
    val m2 = run(text, 0.5, 40.0, 7)                     // noise of about the signal level in 4 kHz, and 40 Hz off
    check(m2.size == 1 && m2[0].id == "GA05" && m2[0].text.contains("36-07.5N 005-21.2W"), "navtex noisy and 40 Hz off: ${m2.map { it.id + ' ' + it.text.take(30) }}")
    // subjects, slots, stations, positions
    check(Navtex.subjectName('D').startsWith("Search") && Navtex.firstSlotMin('X') == 230 && Navtex.firstSlotMin('A') == 0 && Navtex.firstSlotMin('Y') == null, "navtex slots")
    val t0 = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 10, 15, 12, 0) }.timeInMillis
    check(Navtex.nextSlotMs('G', t0) == t0 + 60 * 60_000L, "navtex next slot G from 12:00 is 13:00")
    check(Navtex.nextSlotMs('A', t0) == t0, "navtex slot A at 12:00 in progress")
    val csv = "Spain;ESP;518.0;G;EAC;Tarifa;36 01 N;05 35 W;II;EE\nSpain;ESP;490.0;T;EAC;Tarifa;36 01 N;05 35 W;II;SS\nTurkey;TUR;518.0;G;XXX;Somewhere;41 00 N;29 00 E;III;EE\nbad line"
    val stations = Navtex.parseStations(csv)
    check(stations.size == 3 && kotlin.math.abs(stations[0].lon + (5 + 35 / 60.0)) < 1e-9, "navtex stations csv")
    check(Navtex.findStation(stations, 'G', 518.0, 36.5, -6.0)?.name == "Tarifa" && Navtex.findStation(stations, 'G', 518.0, 41.0, 28.0)?.name == "Somewhere", "navtex station nearest")
    val pos = Navtex.positions("A 36-07.5N 005-21.2W B 3607.5N 00521.2W C 42 11,718N 009 59,778E D 2026 10-20 NM 36-70.0N 004-10.0W")
    val nrxMsg = Navtex.Message('G', 'A', 5, "TARIFA NAVTEX\nWRECK IN 36-07.5N 005-21.2W, KEEP CLEAR\n" + "X".repeat(150), false, false, t0 + 300_000L)
    val nrx = Navtex.nrx(nrxMsg, 518.0)
    check(nrx.size >= 6 && nrx.all { it.length <= 82 && it.startsWith("\$CRNRX,GA05,") } && nrx[0].startsWith("\$CRNRX,GA05,1,${nrx.size},518,15,11,2026,120500,0.0,TARIFA NAVTEX*"),
        "navtex nrx: $nrx")
    check(nrx.joinToString("") { it.substring(it.indexOf(",0.0,") + 5, it.indexOf('*')) }.contains("005-21.2W^2C KEEP CLEAR"), "navtex nrx escapes the comma")
    check(nrx.all { val b = it.substring(1, it.indexOf('*')); it.endsWith(String.format("*%02X", Nmea.checksum(b))) }, "navtex nrx checksums")
    check(pos.size == 3 && kotlin.math.abs(pos[1].first - (36 + 7.5 / 60)) < 1e-9 && kotlin.math.abs(pos[1].second + (5 + 21.2 / 60)) < 1e-9, "navtex positions: $pos")
}

private fun testNavaids() {
    val list = Navaids.parse("# comment\nVJF;Vejer;VOR-DME;117800;36.23400;-5.97600;1270;ES\nSVL;Sevilla;VOR-DME;116700;37.42400;-5.93900;100;ES\nNDB;Bad;NDB;300;36.0;-6.0;;ES\nshort;line")
    check(list.size == 2 && list[0].hz == 117_800_000L, "navaids parse (VOR band only)")
    val near = Navaids.nearest(list, 36.53, -6.29, altM = 5.0, antennaM = 10.0)
    check(near.size == 2 && near[0].vor.ident == "VJF" && near[0].km in 40.0..50.0 && near[0].bearing in 130.0..150.0 && near[0].inRange, "navaids nearest: $near")
    check(near[0].elevationDeg > 0 && near[1].elevationDeg < near[0].elevationDeg, "navaids elevation")
    near(Navaids.ppm(-117.8, 117.8e6), 1.0, 1e-9, "ppm: carrier 117.8 Hz low at 117.8 MHz = +1 ppm")
    // carrier 1234.5 Hz off with AM and noise, 48 kS/s, 3 frames
    val m = Navaids.PpmMeter()
    val r = java.util.Random(3)
    val n = 16_384 * 3
    val xr = FloatArray(n); val xi = FloatArray(n)
    for (i in 0 until n) {
        val t = i / 48_000.0
        val a = 1.0 + 0.3 * kotlin.math.sin(2 * Math.PI * 30 * t) + 0.3 * kotlin.math.sin(2 * Math.PI * 9960 * t)
        val ph = 2 * Math.PI * 1234.5 * t
        xr[i] = (0.2 * a * kotlin.math.cos(ph) + 0.3 * r.nextGaussian()).toFloat(); xi[i] = (0.2 * a * kotlin.math.sin(ph) + 0.3 * r.nextGaussian()).toFloat()
    }
    m.feed(xr, xi, n)
    val res = m.result()
    check(res != null && kotlin.math.abs(res.offsetHz - 1234.5) < 1.0 && res.snrDb > 20 && res.frames == 3, "ppm meter: $res")
}

private fun testDsc() {
    // the 10-unit symbols: phasing words as SDRangel lists them (125 = 1011111001, 111 = 1111011001, 106 = 0101011011)
    eq(Dsc.codeword(125), 0b1011111001, "dsc codeword 125"); eq(Dsc.codeword(111), 0b1111011001, "dsc codeword 111")
    eq(Dsc.codeword(106), 0b0101011011, "dsc codeword 106"); eq(Dsc.codeword(110), 0b0111011010, "dsc codeword 110")
    check((0 until 128).all { Dsc.symbolOf(Dsc.codeword(it)) == it }, "dsc symbols round trip")
    check(Dsc.symbolOf(Dsc.codeword(42) xor 0b0001000000) == -1, "dsc one bit error detected")
    // a distress alert: fields, position, time, ECC, NMEA
    val alert = Dsc.distressAlert("224123450", 105, 36.1167, -5.35, "1423")
    val a = Dsc.parse(alert)
    check(a.valid && a.eccOk && a.format == Dsc.FMT_DISTRESS && a.selfId == "224123450" && a.nature == 105 && a.time == "1423" && a.subsequent == 100, "dsc alert fields: ${a.summary()}")
    near(a.lat ?: 0.0, 36.1167, 0.01, "dsc alert lat"); near(a.lon ?: 0.0, -5.35, 0.01, "dsc alert lon")
    eq(a.positionDigits, "1360700521", "dsc alert position digits"); eq(a.distressShip, "224123450", "dsc distress ship")
    eq(Dsc.nmea(a).substringBefore('*'), "\$CDDSC,12,2241234500,12,05,00,1360700521,1423,,,S,", "dsc nmea alert")
    check(Dsc.nmea(a).substringAfter('*') == String.format("%02X", Nmea.checksum(Dsc.nmea(a).drop(1).substringBefore('*'))), "dsc nmea checksum")
    val bad = alert.copyOf().also { it[5] = it[5] xor 1 }
    check(!Dsc.parse(bad).valid && !Dsc.parse(bad).eccOk, "dsc ECC catches a wrong symbol")
    // relay from a coast station, acknowledgement to all ships, individual call with a channel
    val relay = Dsc.parse(Dsc.distressRelay("002241026", "224123450", 101, 28.1, -15.4, null))
    check(relay.valid && relay.format == Dsc.FMT_ALL_SHIPS && relay.category == Dsc.CAT_DISTRESS && relay.tc1 == Dsc.TC_DISTRESS_RELAY &&
        relay.distressId == "224123450" && relay.nature == 101 && relay.time == null && relay.lat != null && Dsc.mmsiKind(relay.selfId) == "coast station" && Dsc.mid(relay.selfId) == 224,
        "dsc relay: ${relay.summary()}")
    eq(Dsc.nmea(relay).substringBefore('*'), "\$CDDSC,16,0022410260,12,12,00,1280601524,,2241234500,01,S,", "dsc nmea relay")
    val ind = Dsc.parse(Dsc.call("224000111", "224999990", Dsc.CAT_ROUTINE, 72))
    check(ind.valid && ind.format == Dsc.FMT_INDIVIDUAL && ind.address == "224999990" && ind.rxChannel == "72" && ind.eos == Dsc.EOS_REQ && !ind.distress, "dsc individual: ${ind.summary()}")
    // through the air: VHF at 8 kHz with noise and a level difference of the two tones (FM pre-emphasis), and MF / HF
    fun air(msg: IntArray, vhf: Boolean, noise: Double, seed: Long, flip: Int = -1): List<Dsc.Call> {
        val bits = Dsc.transmission(msg, if (vhf) 20 else 200)
        if (flip >= 0) for (k in 0 until 4) bits[flip + k] = 1 - bits[flip + k]        // a burst of errors in one copy
        val audio = if (vhf) Dsc.modulate(bits, 8000, Dsc.VHF_BAUD, Dsc.VHF_F1, Dsc.VHF_F0) else Dsc.modulate(bits, 8000, Dsc.HF_BAUD, 1700.0 - 85, 1700.0 + 85)
        if (vhf) { var prev = 0f; for (i in audio.indices) { val x = audio[i]; audio[i] = x - 0.6f * prev; prev = x } }   // pre-emphasis: 2100 Hz louder
        val r = java.util.Random(seed)
        for (i in audio.indices) audio[i] = (audio[i] + noise * r.nextGaussian()).toFloat()
        val got = ArrayList<Dsc.Call>()
        val d = Dsc.Decoder(1700.0, vhf); d.onCall = { got.add(it) }
        var i = 0
        while (i < audio.size) { val n = minOf(512, audio.size - i); d.process(audio.copyOfRange(i, i + n), n); i += n }
        return got
    }
    val v1 = air(alert, true, 0.0, 1)
    check(v1.size == 1 && v1[0].symbols.contentEquals(alert), "dsc VHF clean: ${v1.map { it.summary() }}")
    val v2 = air(alert, true, 0.15, 2)
    check(v2.size == 1 && v2[0].symbols.contentEquals(alert), "dsc VHF noisy: ${v2.map { it.summary() }}")
    val v3 = air(alert, true, 0.0, 3, flip = 20 + 10 * 20 + 3)          // errors in one copy of a message symbol: time diversity repairs it
    check(v3.size == 1 && v3[0].symbols.contentEquals(alert) && v3[0].errors > 0, "dsc VHF time diversity: ${v3.map { it.summary() + " e" + it.errors }}")
    val h1 = air(alert, false, 0.5, 4)
    check(h1.size == 1 && h1[0].symbols.contentEquals(alert), "dsc HF noisy: ${h1.map { it.summary() }}")
}

private fun testBeacon406() {
    // BCH-1 generator: the product of the three minimal polynomials of M.633 gives x^21+x^18+x^17+x^15+x^14+x^12+x^11+x^8+x^7+x^6+x^5+x+1
    eq(Beacon406.BCH1, 0b1001101101100111100011L, "406 BCH-1 generator")
    // long standard-location EPIRB (MMSI) of Spain off Tarifa
    val b = Beacon406.standardMmsi(224, 123456, 36.1167, -5.35)
    val m = Beacon406.parse(b)
    check(m.valid && m.long && m.bch1Ok && m.bch2Ok == true && !m.userProtocol && m.country == 224 && m.beaconType == "EPIRB" && m.id.contains("123456") && m.distress,
        "406 standard location: ${m.summary()}")
    near(m.lat ?: 0.0, 36.1167, 0.0012, "406 lat"); near(m.lon ?: 0.0, -5.35, 0.0012, "406 lon")
    check(m.hexId.length == 15 && m.hex.length == 30 && m.positionSource == "internal" && m.homing121 == true, "406 hex id ${m.hexId} / ${m.hex}")
    // the 15-hex id does not depend on the position
    check(Beacon406.parse(Beacon406.standardMmsi(224, 123456, -12.5, 140.25)).hexId == m.hexId, "406 hex id without position")
    val bad = b.copyOf().also { it[60] = 1 - it[60] }
    check(!Beacon406.parse(bad).valid, "406 BCH-1 catches an error")
    val bad2 = b.copyOf().also { it[120] = 1 - it[120] }
    check(Beacon406.parse(bad2).bch2Ok == false, "406 BCH-2 catches an error")
    val st = Beacon406.parse(Beacon406.standardMmsi(224, 123456, null, null, selfTest = true))
    check(st.valid && st.selfTest && !st.distress && st.lat == null, "406 self-test without position: ${st.summary()}")
    // maritime user protocol, short (no position) and long (user-location)
    val us = Beacon406.parse(Beacon406.userMaritime(224, "123456", null, null, long = false))
    check(us.valid && !us.long && us.userProtocol && us.id == "123456" && us.hex.length == 22 && us.lat == null, "406 user short: ${us.summary()}")
    val ul = Beacon406.parse(Beacon406.userMaritime(224, "123456", 28.1, -15.4, long = true))
    check(ul.valid && ul.long && ul.lat != null && abs(ul.lat!! - 28.1) < 0.07 && abs(ul.lon!! + 15.4) < 0.07, "406 user location: ${ul.summary()}")
    // through the receiver: 48 kS/s, the carrier 4 kHz off the tuned frequency, noise
    fun air(bits: IntArray, offset: Double, sigma: Double, seed: Long): List<Beacon406.Message> {
        val (re, im) = Beacon406.modulate(bits, 48_000, offset, 1.0, lead = 1.0)
        val r = java.util.Random(seed)
        for (i in re.indices) { re[i] = (re[i] + sigma * r.nextGaussian()).toFloat(); im[i] = (im[i] + sigma * r.nextGaussian()).toFloat() }
        val got = ArrayList<Beacon406.Message>()
        val rx = Beacon406.Receiver(); rx.onMessage = { got.add(it) }
        var i = 0
        while (i < re.size) { val n = minOf(2048, re.size - i); rx.feed(re.copyOfRange(i, i + n), im.copyOfRange(i, i + n), n); i += n }
        return got
    }
    val r1 = air(b, 4_000.0, 0.3, 1)
    check(r1.size == 1 && r1[0].valid && r1[0].bits.contentEquals(b) && abs(r1[0].offsetHz - 4000) < 30, "406 receiver: ${r1.map { it.summary() + " " + it.offsetHz }}")
    val r2 = air(Beacon406.userMaritime(224, "123456", null, null, long = false), -9_000.0, 1.0, 2)
    check(r2.size == 1 && r2[0].valid && !r2[0].long, "406 receiver short, noisy: ${r2.map { it.summary() }}")
}

private fun testCarText() {
    eq(CarText.direction(44.0), "north-east", "car direction 44°")
    eq(CarText.direction(350.0, words = false), "N", "car direction 350° short")
    eq(CarText.direction(-90.0), "west", "car direction -90°")
    eq(CarText.where(8.26, "km", 225.0), "8.3 km south-west", "car where under 10")
    eq(CarText.where(12.4, "km", 45.0, words = false), "12 km NE", "car where shown")
    val t0 = 1_000_000L
    fun m(id: Long, out: Boolean, st: MessageStatus, at: Long, tries: Int = 1, no: String? = "1", text: String = "x") =
        ChatMessage(id, "EA7XYZ-9", text, out, if (out) no else null, st, at, tries)
    eq(CarText.status(m(1, true, MessageStatus.ACKED, t0)), "confirmed", "car status acked")
    eq(CarText.status(m(1, true, MessageStatus.ACKED, t0, no = null)), "sent", "car status without ack wanted")
    eq(CarText.status(m(1, true, MessageStatus.PENDING, t0, tries = 2)), "retry 2/3", "car status retry")
    eq(CarText.status(m(1, true, MessageStatus.PENDING, t0)), "sent, waiting for the ack", "car status first try")
    eq(CarText.status(m(1, true, MessageStatus.FAILED, t0, tries = 3)), "not confirmed", "car status failed")
    eq(CarText.status(m(1, false, MessageStatus.RECEIVED, t0)), null, "car status received")
    eq(CarText.quote("Where are you?"), "Where are you?", "car quote short")
    eq(CarText.quote("Meet you at the repeater site on the hill after lunch"), "Meet you at the repeater site on…", "car quote cut at a space")
    val ask = m(1, true, MessageStatus.ACKED, t0, text = "Where are you?")
    val ans = m(2, false, MessageStatus.RECEIVED, t0 + 5 * 60_000, text = "On my way")
    eq(CarText.answers(listOf(ask, ans), ans)?.id, 1L, "car answers the last sent")
    eq(CarText.spoken("On my way", ask), "Re “Where are you?”: On my way", "car spoken reply")
    eq(CarText.answers(listOf(ask, ans.copy(timeMs = t0 + 40 * 60_000)), ans.copy(timeMs = t0 + 40 * 60_000)), null, "car answers: too late")
    val other = m(3, false, MessageStatus.RECEIVED, t0 + 6 * 60_000, text = "QRV 145.500")
    eq(CarText.answers(listOf(ask, ans, other), other), null, "car answers: something came in between")
    check(CarText.signature(listOf(ask)) != CarText.signature(listOf(ask.copy(status = MessageStatus.PENDING))), "car signature sees a status change")
    check(CarText.tellFavourite(t0, 0) && !CarText.tellFavourite(t0 + 60_000, t0) && CarText.tellFavourite(t0 + CarText.FAVOURITE_QUIET_MS, t0), "car favourite quiet time")
    // road emergency (V-27): emergency beacon of a -9 station, or "V-27" in the comment; not a message, not another SSID
    eq(Alerts.fromAprs("EA7ABC-9", "EMERGENCY SOS", false, t0)?.kind, AlertKind.ROAD, "V-27 from a -9 beacon")
    eq(Alerts.fromAprs("EA7ABC-7", "EMERGENCY SOS", false, t0)?.kind, AlertKind.APRS_BEACON, "-7 beacon stays an emergency beacon")
    eq(Alerts.fromAprs("EA7ABC", "SOS V-27 A-4 km 512", false, t0)?.kind, AlertKind.ROAD, "V-27 in the comment")
    eq(Alerts.fromAprs("EA7ABC-9", "SOS please call", true, t0)?.kind, AlertKind.APRS_MESSAGE, "a message from a -9 stays a message")
    eq(Alerts.fromAprs("EA7ABC-9", "MOB", false, t0)?.kind, AlertKind.MOB, "MOB from a -9 stays MOB")
    eq(Alerts.ssid("EA7ABC-9"), 9, "ssid -9"); eq(Alerts.ssid("EA7ABC"), 0, "ssid none"); eq(Alerts.ssid("OBJ-X"), -1, "ssid odd")
    eq(CarText.trendArrow(-1.4), "↓", "car trend falling"); eq(CarText.trendArrow(0.3), "→", "car trend steady"); eq(CarText.trendArrow(null), "", "car trend none")
}

// Sun, Moon and Earth (Ephem): the same reference values as testEphem of the plotter core (Meeus 49.a and 50.a, the phases, seasons
// and eclipses of 2026 and 2027 of the almanacs, the EME path loss on 432 MHz)
private fun testEphem() {
    fun jdOf(y: Int, m: Int, d: Int, h: Int, min: Double): Double {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")); c.clear(); c.set(y, m - 1, d, h, 0, 0)
        return Celnav.julianDay(c.timeInMillis) + min / 1440.0
    }
    val minute = 1.0 / 1440.0
    val jd = jdOf(2026, 11, 23, 6, 0.0)
    near(Ephem.nextMoonPhase(jd, 2), jdOf(2026, 11, 24, 14, 53.0), 2 * minute, "full moon 24-11-2026 14:53")
    near(Ephem.nextMoonPhase(jd, 0), jdOf(2026, 12, 9, 0, 52.0), 2 * minute, "new moon 9-12-2026 00:52")
    near(Ephem.nextMoonPhase(jdOf(1977, 2, 10, 0, 0.0), 0), jdOf(1977, 2, 18, 3, 36.9), 2 * minute, "Meeus 49.a new moon")
    check(Ephem.nextMoonPhase(Ephem.nextMoonPhase(jd, 2), 2) - Ephem.nextMoonPhase(jd, 2) > 29.2, "at the full moon: the next one")
    var p = Ephem.moonPhaseTrue(jd)
    check(p.waxing && p.phase8 == 4, "almost full, waxing")
    near(p.illum, 0.971, 0.004, "lit fraction"); near(p.ageDays, 13.96, 0.03, "true age")
    p = Ephem.moonPhaseTrue(Ephem.nextMoonPhase(jd, 0) + 0.01)
    near(p.ageDays, 0.01, 0.002, "age just after new"); near(p.illum, 0.0, 0.002, "dark at new")
    p = Ephem.moonPhaseTrue(Ephem.nextMoonPhase(jd, 3))
    check(!p.waxing && p.phase8 == 6, "last quarter"); near(p.illum, 0.5, 0.01, "half lit at the quarter")
    var a = Ephem.nextMoonApsis(jdOf(1988, 10, 1, 0, 0.0), false)
    near(a.first, jdOf(1988, 10, 7, 20, 29.0), 5 * minute, "Meeus 50.a apogee"); near(a.second, 405948.0, 60.0, "Meeus 50.a distance")
    a = Ephem.nextMoonApsis(jd, true)
    near(a.first, jdOf(2026, 11, 25, 21, 4.0), 10 * minute, "perigee 25-11-2026"); check(a.second > 356000 && a.second < 370500, "perigee distance")
    near(Ephem.moonAngularDiameterArcmin(384400.0), 31.08, 0.02, "moon diameter")
    var e = Ephem.nextEclipse(jdOf(2026, 1, 1, 0, 0.0), true)!!
    eq(e.kind, Ephem.EclipseKind.LUNAR_TOTAL, "total lunar 3-3-2026"); near(e.jd, jdOf(2026, 3, 3, 11, 33.0), 10 * minute, "its time"); near(e.magnitude, 1.15, 0.02, "its magnitude")
    e = Ephem.nextEclipse(jdOf(2026, 1, 1, 0, 0.0), false)!!
    eq(e.kind, Ephem.EclipseKind.SOLAR_ANNULAR, "annular 17-2-2026"); near(e.jd, jdOf(2026, 2, 17, 12, 12.0), 15 * minute, "its time")
    e = Ephem.nextEclipse(jdOf(2026, 3, 1, 0, 0.0), false)!!
    eq(e.kind, Ephem.EclipseKind.SOLAR_TOTAL, "total solar 12-8-2026"); near(e.jd, jdOf(2026, 8, 12, 17, 46.0), 15 * minute, "its time"); near(e.magnitude, 0.898, 0.01, "its gamma")
    e = Ephem.nextEclipse(jdOf(2026, 3, 10, 0, 0.0), true)!!
    eq(e.kind, Ephem.EclipseKind.LUNAR_PARTIAL, "partial lunar 28-8-2026"); near(e.magnitude, 0.93, 0.02, "its magnitude")
    eq(Ephem.nextEclipse(jd, true)!!.kind, Ephem.EclipseKind.LUNAR_PENUMBRAL, "penumbral 20-2-2027")
    eq(Ephem.nextEclipse(jd, false)!!.kind, Ephem.EclipseKind.SOLAR_ANNULAR, "annular 6-2-2027")
    eq(Ephem.eclipseAtSyzygy(Ephem.nextMoonPhase(jd, 2), true).kind, Ephem.EclipseKind.NONE, "no eclipse on 24-11-2026")
    val tFull = Ephem.tideForceIndex(Ephem.nextMoonPhase(jd, 2)); val tQuarter = Ephem.tideForceIndex(Ephem.nextMoonPhase(jd, 3))
    check(tFull > 105 && tFull < 125, "spring tide near perigee"); check(tQuarter > 28 && tQuarter < 50, "neap tide")
    val m = Ephem.emeInfo(jd, 36.53, -6.29, 432e6)
    check(m.rangeKm > 350000 && m.rangeKm < 407000, "EME range")
    near(m.pathLossDb, 261.64 + 40 * kotlin.math.log10(m.rangeKm / 384400.0), 0.02, "EME path loss on 432 MHz")
    near(m.echoDelayS, 2 * m.rangeKm / 299792.458, 1e-9, "EME echo delay")
    check(abs(m.dopplerHz) < 1300 && m.dopplerHz != 0.0, "EME Doppler")
    near(Ephem.emeInfo(jd, 36.53, -6.29, 144e6).dopplerHz, m.dopplerHz / 3, 0.01, "Doppler scales with the frequency")
    var ap = Ephem.nextEarthApsis(jd, true)
    near(ap.first, jdOf(2027, 1, 3, 12, 0.0), 1.5, "perihelion"); near(ap.second, 0.98330, 0.0001, "perihelion distance")
    ap = Ephem.nextEarthApsis(jd, false)
    near(ap.first, jdOf(2027, 7, 5, 6, 0.0), 1.5, "aphelion"); near(ap.second, 1.01670, 0.0001, "aphelion distance")
    var se = Ephem.nextSeason(jd)
    near(se.first, jdOf(2026, 12, 21, 20, 50.0), 2 * minute, "December solstice 2026"); eq(se.second, 3, "season 3")
    se = Ephem.nextSeason(jdOf(2026, 1, 1, 0, 0.0))
    near(se.first, jdOf(2026, 3, 20, 14, 46.0), 2 * minute, "March equinox 2026"); eq(se.second, 0, "season 0")
    near(Ephem.sunDistanceAu(jd), 0.9875, 0.0003, "sun distance"); near(Ephem.sunAngularDiameterArcmin(jd), 32.39, 0.02, "sun diameter")
    near(Ephem.solarIrradianceToa(jd), 1361.0 / (0.98755 * 0.98755), 0.5, "irradiance"); near(Ephem.carringtonRotation(jd), 2318.13, 0.02, "Carrington rotation")
    near(Ephem.clearSkyUvIndex(90.0, 1.0), 12.5, 1e-9, "UV index overhead")
    check(Ephem.clearSkyUvIndex(-5.0, 1.0) == 0.0 && Ephem.clearSkyUvIndex(30.0, 1.0) < 2.5 && Ephem.clearSkyUvIndex(60.0, 1.0, 250.0) > Ephem.clearSkyUvIndex(60.0, 1.0, 350.0), "UV index shape")
    near(Ephem.horizonKm(100.0), 35.7, 0.05, "horizon"); near(Ephem.radioHorizonKm(100.0), 41.2, 0.05, "radio horizon")
    near(Ephem.radioRangeKm(10.0, 100.0), 13.03 + 41.2, 0.1, "radio range"); near(Ephem.opticalRangeNm(4.0, 36.0), 2.03 * 8, 1e-9, "range of a light")
    check(Ephem.horizonKm(0.0) == 0.0 && Ephem.radioHorizonKm(-3.0) == 0.0, "no height, no horizon")
    near(Ephem.normalGravity(0.0, 0.0), 9.780325, 1e-6, "gravity at the equator"); near(Ephem.normalGravity(90.0, 0.0), 9.832185, 1e-6, "gravity at the pole")
    near(Ephem.earthRadiusAtM(0.0), 6378137.0, 0.5, "equatorial radius"); near(Ephem.earthRadiusAtM(90.0), 6356752.3, 0.5, "polar radius")
    eq(Ephem.jdToMs(Celnav.julianDay(1794441600000L)), 1794441600000L, "julian day and back")
}
