/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.abs

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
}

private fun testIGate() {
    eq(IGate.passcode("N0CALL"), 13023, "APRS-IS passcode of N0CALL")
    eq(IGate.passcode("n0call-7"), 13023, "passcode ignores the SSID and the case")
    var t = 0L
    val g = IGate("EA7JRS-7", { t })
    val f = Tnc2.parse("EA1ABC>APRS,WIDE1-1*:!4903.50N/07201.75W-x")!!
    eq(g.toIsLine(f), "EA1ABC>APRS,WIDE1-1*,qAR,EA7JRS-7:!4903.50N/07201.75W-x", "IS line")
    eq(g.toIsLine(f), null, "duplicate within 30 s")
    t += 31_000
    check(g.toIsLine(f) != null, "gated again after the window")
    eq(g.toIsLine(Tnc2.parse("EA1ABC>APRS,TCPIP*:!x")!!), null, "TCPIP path")
    eq(g.toIsLine(Tnc2.parse("EA1ABC>APRS,NOGATE:!x")!!), null, "NOGATE")
    eq(g.toIsLine(Tnc2.parse("EA1ABC>APRS,RFONLY:!x")!!), null, "RFONLY")
    eq(g.toIsLine(Tnc2.parse("EA1ABC>APRS:}EA2XYZ>APRS:!x")!!), null, "third party")
    eq(g.loginLine("r/37/-4/100"), "user EA7JRS-7 pass 21434 vers APRSLoRaPlusAndroid 0.1 filter r/37/-4/100", "login line")
}

private fun testGeoStations() {
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

    // lines split across notifications, two in one, UTF-8 split in the middle of a character
    val la = LineAssembler()
    val whole = "{\"e\":\"page\",\"t\":\"señal\"}\n{\"e\":\"ok\",\"c\":\"x\"}\r\n".toByteArray(Charsets.UTF_8)
    val out = ArrayList<String>()
    for (k in whole.indices step 5) out += la.feed(whole.copyOfRange(k, minOf(whole.size, k + 5)))
    eq(out, listOf("{\"e\":\"page\",\"t\":\"señal\"}", "{\"e\":\"ok\",\"c\":\"x\"}"), "line assembler")
    eq((Control.parse(out[0]) as ControlEvent.NewPage).page.text, "señal", "UTF-8 across chunks")
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
    val fw = Control.parse("""{"e":"fw","state":"available","progress":0,"message":"New version available","latest":"5.3.3","current":"5.3.2","size":1}""")
    eq((fw as ControlEvent.Firmware).status.latest, "5.3.3", "tracker WiFi update status")
}

fun main() {
    testKiss(); testAx25(); testAprs(); testMessaging(); testIGate(); testGeoStations(); testJson(); testControl(); testDapnet(); testNmea(); testCallsignCheck(); testCw(); testFirmware()
    println("$checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
