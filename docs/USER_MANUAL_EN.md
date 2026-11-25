# APRS LoRa+ for Android — user manual

Copyright (C) 2026 Manuel - EA7JRS. GPL-3.0-or-later. Version **5.5.0** (the whole APRS LoRa+ ecosystem is unified at this number again). Earlier it matched the tracker, the rotor box, the
Display and the plotter).

The app is the companion of the **APRS LoRa+** tracker: it shows everything the tracker hears and measures, and controls
every function of it. It also works on its own as a radio tool (SDR receivers, digital modes, DX cluster, log, maps, alerts)
and drives the **APRS LoRa+ Rotor** box. The app's screens are in English.

Quick guide: [QUICK_GUIDE_EN.md](QUICK_GUIDE_EN.md). Spanish: [MANUAL_ES.md](MANUAL_ES.md). BLE protocol of the tracker:
[BLE_CONTROL.md](BLE_CONTROL.md). Which app screen covers each tracker function: [APP_COVERAGE.md](APP_COVERAGE.md).

**Status**: builds in the CI (debug APK and Play AAB as artefacts) and is in Google Play internal testing; the pure Kotlin
core passes its checks. Most functions **have not been tried yet on a phone** with real hardware.

## 1. First start

1. **Welcome screen.** Type your callsign and your DMR ID / CCS7 (7 digits) and press *Validate and enter*: the app checks
   in RadioID.net that the ID belongs to that callsign. The result is kept and checked again every 90 days.
   *Listen only* enters without validating: the app then only listens (no APRS frames, iGate, DAPNET, spots or uploads,
   and no tracker orders other than queries).
2. **Permissions.** Bluetooth (to reach the tracker), location (position for satellites, weather and alerts),
   notifications (messages and alerts). The rest are asked when a function needs them (microphone, USB).
3. **Link the tracker**: side menu > *Connect & settings* (section 2).

## 2. Linking

- **Tracker over BLE.** *Connect* scans; pick your tracker. The app uses the KISS service for APRS and the control service for
  everything else. The control service needs pairing with the tracker's PIN (WebUI > Bluetooth > *Control PIN*, default
  `123456`): Android asks for it the first time. After changing the PIN on the tracker, press *Forget pairing* and link again.
- **Tracker callsign** (Connect): if it is the same as yours, the tracker sends the ACKs and the app does not.
- **Extra receivers**: more trackers as receivers only (BLE KISS or KISS over TCP, port 8001), shown with their name in the log.
- **Rotor box** (Rotor tool): BLE or Wi-Fi (TCP 4534) with the box PIN.
- **Radio (CAT)**: a transceiver plugged into the phone over USB (section 9).

The tracker takes one control device at a time: while the app is linked, the Display or the plotter waits.

## 3. The radio-style interface

The main screen works like a mobile radio: two bands, touch keys and the **[F]** key.

- **Band A (green)**: the tracker: mode, frequency, profile, S-meter of the last station heard, TX / RX / MSG / EMG lights.
- **Band B (cyan)**: the radio under CAT (on the phone or in the rotor box) or the SDR.
- **BEACON / iGATE / ROTOR** tiles and the last stations heard.
- **[F]**: three pages of keys. *APRS*: S.LIST, MSG, BEACON, BCN, GPS, LOG, IGATE, DAPNET, WX, MAP, DISP, HEARD.
  *RADIO*: DX, RBN, IBP, SAT, ROTOR, CAT, SDR, MODES, CWKEY, BEACONS, AIS, ADSB. *SYSTEM*: REMOTE, CONFIG, FIRMWARE,
  CONNECT, SENSORS, SIGID, RXPLUS, ABOUT, ALERTS, SPOTS, ASTRO.
- **Touch keys**: hold a key of the [F] menu to put it on one of the four keys next to [F].
- **Side menu** (☰): every screen and tool, the theme (light, dark, system, auto = light from sunrise to sunset),
  units (metric, nautical, imperial) and the emergency alert options (sound, vibration, torch, full-screen).
- **DISP**: compass, GPS sky (tracker or phone: satellites by constellation, SNR bars, errors), altitude and clock with
  sun, twilight and moon (true phase), and an **almanac**: next Moon phase, perigee or apogee, equilibrium tide (a guide,
  not a port prediction), next lunar and solar eclipses, next equinox or solstice, the Sun's distance and clear-sky UV
  index, and the moonbounce (EME) path loss, echo delay and Doppler on 432 MHz.

## 4. APRS

- **S.LIST / Station**: stations heard (from the tracker and APRS-IS), with distance, bearing, symbol, course, speed,
  altitude, weather and Mic-E status. The card has a compass (north up / heading up), **GO TO** (Google Maps route),
  MESSAGE and **ROTOR**. Objects and items, third-party frames and deleted objects are handled.
- **MSG**: conversations (RX / TX / PAGER), state of each message (sent, acknowledged, failed, rejected), three retries,
  duplicates removed, *TxCLR* to cancel the pending ones. Messages arrive as notifications with reply and mark-as-read,
  which is what Android Auto reads aloud.
- **IGATE**: mobile iGate, radio → APRS-IS only (`qAO`, receive-only). Each frame goes out exactly as heard, with no spaces or bytes removed; no duplicate filtering (the servers do it), and nothing heard while the link is down is kept to send later. One connection only, to `rotate.aprs2.net` by default. Before each frame it sends, when known, the RSSI, SNR, modem, bit rate and frequency of the reception on an `#is2-meta` line (servers without IS2 take it for a comment).
- **WX**: APRS weather stations with wind and Beaufort, dew point, feels-like, cloud base and pressure trend (3 h), sorted
  by distance, time, temperature or wind.
- **HEARD**: the stations heard by the tracker itself and its message log; send a message from the tracker. *QSY* for
  stations that announce a frequency (T-TWR).
- **DAPNET**: pages received by the tracker in DAPNET mode, and pages sent from the phone through the DAPNET API (account
  needed; up to 80 ASCII characters).
- **NMEA**: TCP server (port 10110) with the tracker's sentences for other apps (OpenCPN, Navionics, the plotter), APRS
  stations as AIS targets, and the tracker GNSS as the phone location (mock location).

## 5. Map

MAP: OpenStreetMap, IGN Base, PNOA orthophoto, MTN, dark, Esri (with your ArcGIS key) and OpenSeaMap, with stations, AIS
ships, aircraft, your position and the rotor beam. Overlays (each with its own transparency): official alerts,
earthquakes, day / night line, Meteosat clouds, OpenWeather maps (your key; limited to 30 tiles / min), AEMET radar and
lightning, METAR / TAF and SIGMET, aurora, D-RAP, tropical cyclones, Maidenhead grid, CQ / ITU zones, great circle, IBP
beacons, worked locators, WSPR and PSK Reporter "who hears me", balloons (SondeHub), wildfires, floods, buoys, city lights
and the beacons heard by the SDR. Touching a mark opens its card.

## 6. Tracker control

| Tool | What it does |
|---|---|
| **Tracker** | Radio mode (APRS LoRa / FSK, Morse, WSPR, FT8, FT4, FST4W, LR-FHSS, DAPNET, SAT, M17, FM, AIS; the tracker restarts), beacon and LoRa profiles, beacon now, digipeater, CW messages, CW listening, FM radio of the T-TWR, broadcast radio |
| **Sensors** | Battery, temperature, humidity, pressure, QNH, barometric altitude, gas / IAQ, IMU, magnetometer, PMU; phone sensors (tilt, compass with WMM2025 declination, barometer); A-GPS for the tracker's u-blox; internet weather sent to the tracker |
| **Satellites** | Current pass, list with groups and favourites, receiver with Doppler, transmitters, transmit with confirmation, rotor and fixed targets (also *Repeaters near me* with RepeaterBook Connect), upload of the satellite list, rotctld server (port 4533) for Look4Sat / GPredict |
| **Remote** | Mirror of the tracker screen and every menu option |
| **Config** | The tracker's whole configuration as a tree: read, edit, store; save it to a file and load it back |
| **Firmware** | Installed and published versions, official or custom firmware, update over Bluetooth (boards with a second slot) and the web installer |

## 7. Rotor box (Rotor tool)

Dial in the sat-aprs style (blue = position, amber = target, green = tracker heading when it moves faster than 3 km/h, red =
stops), go to, move while held, STOP, park, clear fault, elevation, calibration of direct control and the whole box
configuration. **Follow**: the tracker's aim, an APRS station heard by the tracker (with movement estimate) or a CW beacon
with a locator; *Track sun / moon*. **Radio (CAT)** of the box with S-meter, split, tone and the satellite Doppler.
**Wind**: the internet wind goes to the box, which parks with strong gusts.

## 8. Radio tools

| Tool | What it does |
|---|---|
| **DX** (DX cluster) | Telnet node (EA4URE by default), filters by source (human, RBN CW / digital, mine), band, mode and activity (POTA, SOTA, IOTA, WWFF, DME); country flags; TUNE to the SDR and the CAT; ROTOR short / long path; SPOT; LOG |
| **RBN** | World map of Reverse Beacon Network spots through the cluster, VHF / UHF alerts |
| **IBP** | The 18 NCDXF / IARU beacons, the 5 on the air now with their great circle, heard grid, TUNE and ROTOR |
| **SDR** | SDR++ and gqrx (rigctl), SDRconnect (also its audio), SpyServer, and the app's own RTL-SDR receiver (USB OTG) with WFM / NFM / AM / SSB / CW, a **panadapter** (spectrum + waterfall of the whole band, tap or drag to tune), Doppler, recording; **digital voice** on a **DV** button next to the modes (the flavour DMR, D-STAR, YSF, P25, NXDN, dPMR, M17 is picked below); APRS 9600 (G3RUH); VOR list; 406 MHz beacons. The receiver level is shown in dBFS (relative, uncalibrated), with a manual 40 % gain by default so it does not overload |
| **MODES** | PSK31…250, RTTY, CW, FT8, FT4, WSPR, FST4W, NAVTEX and DSC (VHF channel 70, MF / HF), from the app receiver or the microphone |
| **SPOTS** | PSK Reporter, WSPRnet, WSJT-X UDP (GridTracker, JTAlert…) and a CW Skimmer–style telnet port |
| **LOG** | Logbook (ADIF), import / export, QRZ.com, LoTW (signed in the app with your .p12), eQSL and World Radio League |
| **CAT** | Transceiver over USB (section 9) |
| **CWKEY** | Paddle or straight key on the phone (VBand, Vail, MIDI, USB serial), iambic A / B, to the tracker in MORSE mode |
| **BEACONS** | Beacons heard by the SDR: LR-FHSS, APRS FSK, M17, WSPR, FST4W, FT8, FT4, CW; map and spots to the cluster |
| **AIS** | Ships (T-TWR or AIS module), danger first, alarms, GO TO |
| **ADSB** | Aircraft decoded by the RTL-SDR or from a dump1090 / readsb receiver, with bearing and elevation for the rotor |
| **SIGID** | Signal identification (Artemis database, optional download) |
| **ASTRO** | Celestial navigation (section 10) |

## 9. Radio (CAT) on the phone

Yaesu (text: FT-991 / 991A, FT-891, FT-710, FTDX; 5 bytes: FT-817 / 818 / 857 / 897), Icom CI-V and Kenwood / Elecraft
over a USB serial adapter (the SCU-17 too). Frequency, mode, split, satellite Doppler from the tracker. PTT is off by
default, only while held and with a validated callsign. The radio under CAT (phone first, then the rotor box) is the one
that TUNE of the DX cluster, IBP and spots use.

## 10. Celestial navigation (Sextant)

Sun, Moon, Venus, Mars, Jupiter, Saturn and 157 stars to almanac precision. *Visible*: what is up now with the three stars
that cross best. Corrections (index error, dip, refraction, semi-diameter, parallax), intercept and azimuth, up to four lines
advanced for the run and the fix. Latitude by meridian altitude and by Polaris with the time of meridian passage. Compass
check: total correction from a body, deviation and a deviation card.

*Current and course to steer*: from the course you want to make good, the speed through the water, the leeway (+ to
starboard) and the current it gives the true course to steer, the correction, the speed made good and the compass course
(WMM variation and deviation card). *Fix from two bearings*: a fix from two bearings (simultaneous or a running fix) of two points whose positions you type (or the same one twice), with NOW on each bearing, course and speed made good and Ct.

*Mark departure* and, after steering a steady course and speed for a while, *From DR*:
the current from the difference between the DR and the tracker's GNSS position.

## 11. Alerts

- **Emergency window** over any screen: APRS emergency beacons and messages, Mic-E "Emergency", AIS distress and collision
  risk, the tracker's EAI and man overboard, DSC DISTRESS, 406 MHz DISTRESS BEACON. The phone can sound, vibrate and flash
  the torch; in Android Auto each alert arrives as a conversation (reply, mark as read, go to).
- **Official alerts** (ALERTS): AEMET (polygons), MeteoAlarm, NWS, tsunami.gov, earthquakes (USGS, EMSC, Raspberry Shake ShakeNet),
  SWPC space weather (scales, Kp, solar flux, sunspot number SSN, real-time solar wind with density and pressure, and an HF
  estimate: MUF, LUF and band state, with the sat-aprs formulas; X-ray absorption on the day side only, as SWPC's D-RAP) and NOAA
  Weather Radio SAME (from the tracker or the app's RTL-SDR), every 15 min; sent to the tracker too.

## 12. My vessel (AIS)

Name, MMSI, maritime call sign (checked on the MarineTraffic page or by hand) and dimensions for the APRS LoRa+ MAPRS
transponder or a tracker with the nautical AIS profile.

## 13. Android Auto

Messages and alerts come as conversation notifications: the car reads them aloud and you answer by voice.

## 14. New in 5.4.9

- **Station page:** a *LAST PACKET* card with the line as it came (in colour), every field explained and the way it travelled (sender, digipeaters, iGate, your tracker with dBm and SNR); a *TELEMETRY* card (`T#` reports with the names, units and equations the station announces; bits as lamps) and the `>` status with its age. Speed and course carry "~" when worked out from two positions; weather stations show no speed or course (that field is the wind).
- **Map:** trail of the stations that move (last 3 h), the way of the last frame of the chosen station (station → digipeater or iGate → your tracker) and moving cars drawn turned to their course.
- **Between sessions:** stations (with their trails) and conversations come back when the app opens; stations older than 24 h do not. A message that was waiting for its acknowledgement comes back as not confirmed.
- **Messages:** bulletins and group messages (BLN, NWS, ALL, CQ…) are shown and never acknowledged; 5 tries (30 s, 60 s, 2 min, 5 min, 15 min).
- **Position beacons from the phone** (*Connect > Position beacons from the phone*): the phone's GPS and your callsign, through the tracker; SmartBeaconing (30 min when still, 90 s at 100 km/h, turns) or a fixed interval; symbol and comment. Only with a validated callsign and the tracker connected. If the callsign and SSID are the tracker's, use another SSID: the tracker already beacons with it.
- **Config:** a *Configuration check* with the same rules as the tracker's WebUI (NOCALL, callsigns, path, IARU band plan frequencies, WiFi AP password, control PIN, KISS links), with safe fixes; storing with errors asks first.
- **Remote:** ◀ ▶ and *Back* do what the device button does (needs the rebuilt 5.4.8 firmware or later).
- **Official alerts:** GFZ Hp30 (every half hour) in the HF estimate and the geomagnetic storm alerts, Kp forecast, A index and Dst.
- **NAVTEX and Wefax:** messages from a Navtex receiver on the network (TCP / UDP), no manual deletion (IMO MSC.508(105)); charts, schedule with reliability and an HF circuit prediction from the Wefax program.

## 15. New in 5.5.0

The whole APRS LoRa+ ecosystem is unified again at 5.5.0 (needs the 5.5.0 tracker firmware for what it controls over BLE).

- **HAB telemetry (balloons):** decodes UKHAS telemetry (`$$CALL,counter,time,lat,lon,alt,…*CRC`) from the RTTY decoder; a **Balloons** tool with the list, *GO TO* and *ROTOR*.
- **CW skimmer:** multi-channel CW decoder in the Digimodes tab (several signals at once, each with its audio frequency, callsign, SNR and WPM).
- **SSDV image and POCSAG** (with the 5.5.0 firmware): **SSDV** section in the Tracker tab (SSDV mode): status and progress, **upload a JPEG from the phone** over BLE, transmit / stop; the image source and radio parameters stay in Config (group `ssdv`). **POCSAG** section: send a page to a RIC (numeric or alphanumeric). Transmitting needs a validated callsign.
