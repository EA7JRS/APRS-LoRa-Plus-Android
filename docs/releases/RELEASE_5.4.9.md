# APRS LoRa+ for Android 5.4.9

Version of the **Android app** (it has its own numbering; the tracker is at 5.4.8).

**Stations**
- **Last packet explained**: the line as it came (in colour), every field in words and the way it travelled: sender,
  digipeaters, iGate and your tracker with dBm and SNR.
- **Telemetry** (`T#` with the PARM / UNIT / EQNS / BITS the station announces) and **status** (`>`) on the station page.
- Speed and course worked out from two positions ("~") for trackers that send altitude instead; weather stations no longer
  show a speed or course of 0 (that field is the wind).
- Stations, trails and conversations are **kept between sessions** (stations for 24 h).

**Map**
- **Trail** of the stations that move (last 3 h), the **path of the last frame** of the chosen station (station →
  digipeater or iGate → your tracker) and moving **cars turned to their course**.

**Messages**
- Bulletins and group messages (BLN, NWS, ALL, CQ…) are shown and never acknowledged; 5 tries spread over LoRa-friendly
  waits (30 s, 60 s, 2 min, 5 min, 15 min).

**Tracker**
- **Position beacons from the phone** through the tracker (Connect): SmartBeaconing or a fixed interval, symbol and comment.
- **Configuration check** in Config, with the rules of the tracker's WebUI and safe fixes.
- **Remote**: ◀ ▶ and *Back* act as the device button (needs the 5.4.8 firmware rebuilt on 2026-11-23 or later).

**Space weather, NAVTEX and Wefax**
- GFZ **Hp30** (half-hourly) in the HF estimate and the geomagnetic storm alerts; Kp forecast, A index and Dst; N0NBH as a
  backup source.
- NAVTEX messages from a Navtex receiver on the network, with no manual deletion (IMO MSC.508(105)); Wefax charts, schedule
  with reliability and an HF circuit prediction.

**Not tested on a phone yet**: it builds in the CI and the core passes about 2100 checks on the PC.
