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

    // full sensors event
    val fs = (Control.parse("""{"e":"sensors","batt":4.1,"battPct":87,"gas":120.3,"iaq":42,"iaqLabel":"Good","wxSensor":"BME680","accel":[0.01,0.02,1.0],"gyro":[0,0,0],"flat":true,"charging":true,"vbus":5.1,"pmuTemp":31.5,"battMa":-120,"heap":150000}""") as ControlEvent.SensorData).sensors
    eq(fs.batteryPct, 87, "battPct"); eq(fs.accelG?.size, 3, "accel"); eq(fs.charging, true, "charging"); eq(fs.iaqLabel, "Good", "iaq label"); eq(fs.humidityPct, null, "absent is null"); eq(fs.freeHeap, 150000L, "heap")

    // tracker messages, heard stations, screen, actions, config pieces
    val tm = Control.parse("""{"e":"msgs","tot":2,"unread":1,"from":0,"items":[{"i":0,"d":"R","ep":1700000000,"st":0,"c":"EA1AAA","t":"hola"},{"i":1,"d":"T","ep":1,"st":4,"ack":12,"c":"EA1BBB","t":"adiós"}]}""") as ControlEvent.Messages
    eq(tm.items.size, 2, "msgs items"); check(tm.items[0].incoming && !tm.items[1].incoming, "msg direction"); eq(tm.items[1].state, 4, "msg state"); eq(tm.items[1].ackId, 12, "msg ack")
    val mc = Control.parse("""{"e":"msg","tot":3,"unread":2,"i":0,"d":"R","ep":5,"st":0,"c":"EA1CCC","t":"nuevo"}""") as ControlEvent.MessageChanged
    eq(mc.newest?.call, "EA1CCC", "msg change newest"); eq(mc.unread, 2, "msg change unread")
    eq((Control.parse("""{"e":"msg","tot":0,"unread":0}""") as ControlEvent.MessageChanged).newest, null, "msg change without messages")
    val hd = Control.parse("""{"e":"heard","tot":1,"pkts":9,"from":0,"items":[{"c":"EA1WX","sym":"/_","k":"WX","lat":40.1,"lon":-3.2,"alt":600,"age":30,"n":4,"dev":"X","wx":{"t":21,"h":50}}]}""") as ControlEvent.Heard
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
    out.setLength(0); s.feed("\\dump_state\n", out); check(out.startsWith("1\n0\n0.000000\n360.000000"), "rotctld dump_state")
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

    val json = """{"count":2,"results":[{"State ID":"","Rptr ID":"","Frequency":"145.6500","Input Freq":"145.0500","PL":"","Nearest City":"Granada","Region":null,"Country":"Spain","Lat":"37.1951","Long":"-3.4194","Callsign":"ED7ZAB","Use":"OPEN","Operational Status":"On-air"},
        {"Frequency":438.9,"Input Freq":431.3,"PL":"77.0","Nearest City":"X","Country":"Spain","Lat":"","Long":"","Callsign":"ED7YAA","Use":"OPEN","Operational Status":"Off-air"}]}"""
    val reps = RepeaterBook.parse(json).getOrThrow()
    eq(reps.size, 2, "RepeaterBook results"); eq(reps[0].callsign, "ED7ZAB", "RepeaterBook callsign"); eq(reps[0].freqHz, 145_650_000L, "RepeaterBook frequency")
    check(reps[0].hasPosition && abs(reps[0].lon!! + 3.4194) < 1e-9, "RepeaterBook position"); check(!reps[1].hasPosition, "RepeaterBook without position")
    eq(reps[0].targetName, "ED7ZAB 145.650", "RepeaterBook target name"); eq(reps[1].tone, "77.0", "RepeaterBook tone")
    eq(RepeaterBook.parse("{\"status\":\"error\",\"message\":\"Invalid token\"}").exceptionOrNull()?.message, "Invalid token", "RepeaterBook error")
    check(RepeaterBook.parse("<html>").isFailure, "RepeaterBook not JSON")
    eq(RepeaterBook.url(false, callsign = "ed7zab"), "https://www.repeaterbook.com/api/exportROW.php?callsign=ED7ZAB", "RepeaterBook url")
    eq(RepeaterBook.url(false, city = "La Línea", country = "Spain"), "https://www.repeaterbook.com/api/exportROW.php?city=La+L%C3%ADnea&country=Spain", "RepeaterBook url encoded")

    check(Control.parse("{\"e\":\"rot\",\"az\":10,\"el\":5,\"aaz\":9.5,\"ael\":4}").let { it is ControlEvent.Rotor && it.pointing.actualAzDeg == 9.5 && it.pointing.actualElDeg == 4.0 }, "rot actual position")
    check(Control.parse("{\"e\":\"info\",\"caps\":{\"rotor\":true}}").let { it is ControlEvent.Info && it.info.hasRotor }, "caps.rotor")
}

private fun testSdr() {
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
}

private fun testG3ruh() {
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

fun main() {
    testKiss(); testAx25(); testAprs(); testMessaging(); testIGate(); testGeoStations(); testJson(); testControl(); testSat(); testJsonEdit(); testDapnet(); testNmea(); testCallsignCheck(); testCw(); testFirmware(); testUbx(); testRotator(); testSdr(); testDsp(); testSigId(); testModems(); testG3ruh(); testReceivers(); testWsjt(); testSpots(); testLogbook()
    println("$checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
