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
    eq(i.radio, "lora", "radio missing = lora"); eq(i.sa868, null, "no SA868 block")
    // LilyGO T-TWR Plus (radio FM SA868, APRS AFSK 1200)
    val ttwr = (Control.parse("""{"e":"info","v":1,"fw":"5.3.5","board":"lilygo_t_twr_plus","caps":{"radio":"sa868","modes":true},"sa868":{"rev":21,"fw":"NiceRF","band":"VHF","ok":true},"mode":0,"modes":[{"m":0,"n":"APRS AFSK 1200"}]}""") as ControlEvent.Info).info
    eq(ttwr.radio, "sa868", "T-TWR radio"); eq(ttwr.sa868, Sa868Info(21, "NiceRF", "VHF", true), "T-TWR SA868 info")
    eq(ttwr.sa868?.text, "SA868 VHF, Rev2.1, NiceRF firmware", "T-TWR SA868 text"); eq(ttwr.modes[0].name, "APRS AFSK 1200", "T-TWR mode name")
    eq(ttwr.hasM17, false, "no m17 cap = false")
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

fun main() {
    testNetWeather();
    testKiss(); testAx25(); testAprs(); testMessaging(); testIGate(); testGeoStations(); testJson(); testControl(); testSat(); testJsonEdit(); testDapnet(); testNmea(); testCallsignCheck(); testCw(); testFirmware(); testUbx(); testRotator(); testRotorProto(); testRotorFollow(); testSdr(); testDsp(); testSigId(); testModems(); testG3ruh(); testReceivers(); testWsjt(); testSpots(); testLogbook(); testRadioUi(); testDxCluster(); testGnssSky(); testStatusRadio(); testSun(); testRtlUsb(); testHeardSymbol()
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
    eq(DxCluster.formatKhz(144300.0), "144 300.0", "freq text")

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

private fun testHeardSymbol() {
    // the tracker sends code then table; the app keeps table then code
    eq(heardSymbol("&L"), "L&", "LoRa iGate from the tracker")
    eq(heardSymbol(">/"), "/>", "car from the tracker")
    eq(AprsSymbols.describe('L', '&'), "LoRa iGate", "not the lighthouse")
    val h = (Control.parse("""{"e":"heard","tot":1,"pkts":1,"from":0,"items":[{"c":"EA7XYZ-9","sym":">/","age":5}]}""") as ControlEvent.Heard).items[0]
    eq(h.symbol, "/>", "heard event symbol in APRS order")
    eq(AprsSymbols.of(h.symbol)?.base, AprsSymbols.Cell(0, 13, 1), "car icon")
}
