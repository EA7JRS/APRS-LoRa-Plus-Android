<p align="center"><a href="https://ea7jrs.github.io/APRS-LoRa-Plus/"><img src="docs/img/aprs-lora-plus-banner.jpg" alt="APRS LoRa+ · Tracker · APRS · Map · Alerts" width="100%"></a></p>

# APRS LoRa+ for Android

Android companion for the [APRS LoRa+](https://github.com/EA7JRS/APRS-LoRa-Plus) tracker (ESP32 firmware), and the place where the whole APRS LoRa+ ecosystem is controlled: tracker, [rotor box](https://github.com/EA7JRS/APRS-LoRa-Plus-Rotor), [plotter](https://github.com/EA7JRS/APRS-LoRa-Plus-Plotter) and the radio tools of the phone itself.

**Documentation**: [user manual](docs/USER_MANUAL_EN.md) ([español](docs/MANUAL_ES.md)), [quick guide](docs/QUICK_GUIDE_EN.md) ([español](docs/GUIA_RAPIDA.md)), [tracker BLE protocol](docs/BLE_CONTROL.md), [coverage of the tracker functions](docs/APP_COVERAGE.md), [Google Play notes](docs/GOOGLE_PLAY.md).

Status: **5.4.8** (the Android app now has its own version, ahead of the rest of the ecosystem). The pure Kotlin core is tested on the PC (about 1750 checks); the Android app builds in the CI (debug APK and Google Play bundle) and is in Google Play internal testing. Most features have not been tried yet on a phone with real hardware.

The interface works like a radio: a main screen with band A (the tracker) and band B (the CAT radio or the SDR), soft keys [F] with three pages (APRS / RADIO / SYSTEM), and a side menu with every tool.

## The tracker
- **Link** over Bluetooth LE: KISS service for APRS, and the control service (JSON lines, PIN pairing) for everything else; foreground service, link state in the notification. Extra trackers can be added as receive-only stations (BLE or KISS over WiFi).
- **Control** (`docs/BLE_CONTROL.md`, coverage in `docs/APP_COVERAGE.md`): radio mode (APRS LoRa / FSK, Morse, WSPR, FT8, FT4, FST4W, LR-FHSS, DAPNET, SAT, AIS, FM and M17 on the T-TWR Plus), beacon and LoRa profiles, beacon now, digipeater, CW messages and CW listening, every menu option and a mirror of the tracker's screen (Remote), and the whole configuration file (Config, also saved to and loaded from a file).
- **Sensors and GNSS**: battery, PMU, temperature, humidity, pressure, gas / IAQ, IMU, magnetometer, QNH, altitude; position, speed, satellites with the sky plot, DOP and error; A-GPS to the u-blox receiver; phone sensors (tilt for the rotor mount, compass with WMM declination, barometer).
- **Firmware**: installed and published versions, official or modified build, update over Bluetooth (boards with two slots; the phone downloads, checks size and SHA-256, the tracker checks again), and the web installer.
- **Telegraph key on the phone** (tracker in MORSE mode): keyboard-type adapters (VBand, Vail), MIDI and USB serial; straight or iambic A/B, sidetone, real-time keying or text.

## APRS
- **Stations** list and detail with compass (north-up / heading-up), **GO TO** in Google Maps, rotor pointing; APRS symbols; Mic-E (status, altitude), objects and items, third-party frames.
- **Weather stations** (WX): APRS weather in your units, pressure trend, dew point, wind chill, Beaufort, cloud base.
- **Messages**: write, answer, retries and acknowledgements, sent / acked / failed / rejected; notifications with reply and mark-as-read, so **Android Auto** reads them aloud and answers by voice.
- **Map** (osmdroid): OpenStreetMap, IGN (MTN, PNOA, Base), dark, Esri with your own ArcGIS key, OpenSeaMap; layers with their own transparency: AEMET radar and lightning, Meteosat clouds, OpenWeather maps (your key), official alerts, earthquakes, day / night line, METAR / TAF / SIGMET, aurora, D-RAP, tropical cyclones, Maidenhead grid, CQ / ITU zones, great circle, IBP beacons, worked locators, who hears me (WSPR, PSK Reporter), balloons (SondeHub), fires and floods (EONET), NDBC buoys, aircraft.
- **Mobile iGate** (radio → APRS-IS), **DAPNET** pages received by the tracker and sent through the DAPNET API, **NMEA** server (TCP 10110) with APRS stations as AIS targets for OpenCPN or the plotter, and the tracker's GNSS as the phone location.

## Alerts and safety
- **Emergency window** over any screen: APRS emergency beacon or message, AIS distress and collision risk, own EAI and man overboard; the phone as buzzer (alarm tone, vibration, torch); also in Android Auto. An emergency beacon of a -9 (car) station is a **ROAD EMERGENCY** with the V-27 beacon icon.
- **Android Auto**: messages read aloud with who, where and their state (confirmed, retry n/3, not confirmed), answered by voice; alerts with distance and direction; favourite stations heard; IoT keys (SOS V-27 with confirmation, beacon, I'M OK, quick message, rotor follow, park) and a weather screen (alert, nearby APRS weather stations, nearest METAR).
- **Official alerts**: AEMET (with the alert polygons), MeteoAlarm, NWS, tsunami.gov, USGS / EMSC earthquakes, SWPC space weather; NOAA Weather Radio **SAME** from the tracker or the app's RTL-SDR. Sent on to the tracker.
- **NAVTEX** (Modes): SITOR-B decoder ported from fldigi, stations within reach with their next broadcast, positions in the text, alarm for search and rescue.
- **DSC** (Modes > DSC VHF / HF): ITU-R M.493 decoder for channel 70 and the MF / HF channels, DSC DISTRESS window, `$CDDSC` to the plotter.
- **406 MHz beacons** (SDR): Cospas-Sarsat first-generation EPIRB / ELT / PLB with the app's RTL-SDR: 15-hex ID, protocol, position, DISTRESS BEACON window.

## Radio tools on the phone
- **SDR control**: SDR++ and gqrx (rigctl), SDRconnect (WebSocket API, also its audio), SpyServer; Doppler tracking of the tracker's satellite, recording of passes.
- **RTL-SDR receiver in the app** (USB OTG dongle or any `rtl_tcp`): NFM / WFM / AM / USB / LSB / CW, a **panadapter** (spectrum + colour waterfall of the whole tuned band, tap or drag to tune), fine tuning without retuning, recording; relative dBFS level meter and a manual 40 % gain by default. On top of it: **digital voice** on a **DV** mode button (dsd-fme: DMR, D-STAR, YSF, P25, NXDN, dPMR, M17), **APRS FSK 9600** (G3RUH), **AIS** (both channels), **ADS-B** (1090 MHz, or a dump1090 / readsb on the network), **VOR** list (OurAirports) to aim the rotor and measure the dongle's ppm.
- **Modes**: CW, RTTY, BPSK31–250, NAVTEX, DSC, and **FT8 / FT4 / WSPR / FST4W** with the receiver or the microphone; **spots** to PSK Reporter, WSPRnet, the WSJT-X UDP protocol (GridTracker, JTAlert, N1MM, RBN Aggregator) and a CW Skimmer telnet port.
- **Beacons heard**: the **LR-FHSS** beacon of the tracker (RTL-SDR or an I/Q recording from SDRconnect / SDR++; decoded from a real recording), plus WSPR, FST4W, FT8, FT4, CW, APRS FSK and M17 stations, on the map and as **spots to the DX cluster**.
- **CAT** of a radio plugged into the phone (Yaesu FT-817 / 818 / 857 / 897 / 891 / 991A / 710 / FTDX, Icom CI-V, Kenwood / Elecraft) with Doppler; or of the rotor box.
- **DX cluster** (telnet; EA4URE by default): filters as WebClusterURE, country flags and entities from cty.dat (a copy inside the app, updated from country-files.com), TUNE, short / long path to the rotor, spot, log. **RBN map** with skimmer paths and VHF / UHF alerts; **IBP** map of the 18 NCDXF / IARU beacons with the ones on air now.
- **Log**: ADIF logbook, import from qFT8, upload to QRZ.com, eQSL and LoTW (signed in the app), WRL through the WSJT-X UDP protocol, confirmations.
- **Satellites** (SAT): passes, Doppler, transmitters, the tracker's rotor; satellite file by CelesTrak groups and favourites.
- **Rotor box** (BLE or WiFi): dial, go to, move, park, follow the tracker / an APRS station / a CW beacon / the Sun or the Moon, wind protection with internet weather, calibration, configuration.
- **SigID** (optional Artemis database), **RepeaterBook Connect**, internet weather (OpenWeatherMap, IQAir) to the tracker.

## Navigation
- **Celestial navigation** (Sextant): Sun, Moon, Venus, Mars, Jupiter, Saturn and 157 stars to almanac precision; bodies visible now and the best star triple; altitude corrections, intercept and azimuth, up to four lines advanced for the run and the fix; latitude by meridian altitude and by Polaris; current from dead reckoning and the course to steer with current and leeway; fix from two bearings (simultaneous or running fix); compass check and a deviation card.
- **My vessel** (AIS nautical profile of the tracker or the MAPRS transponder): name, MMSI, maritime call sign, type and size; the call sign is checked with MarineTraffic (your API key, or by hand on its public page opened inside the app: you sign in yourself, the app never reads the page) or the ITU MARS.

## Callsign
The callsign must have a DMR ID / CCS7 in RadioID.net. Until it is validated the app only listens: no APRS frames, iGate, DAPNET, spots, uploads or commands that transmit.

## Layout
- `core/`: pure Kotlin (no Android): KISS, AX.25, APRS, message engine, control protocol, DSP and modems, decoders, formulas (WMM, astronomy, celestial navigation), map data readers. `gradle :core:selfTest` runs its checks.
- `app/`: Android app (Compose UI, BLE, foreground service, notifications, Android Auto messaging).
- `native/`: dsd-fme, ft8_lib, wsprd and the FST4W decoder built as separate programs with the NDK (the CI builds them).
- `docs/`: control protocol (`BLE_CONTROL.md`, copy of the firmware's), coverage table (`APP_COVERAGE.md`), Google Play steps (`GOOGLE_PLAY.md`).

## Build
`gradle :core:selfTest` and `gradle :app:assembleDebug` (Android SDK 36, JDK 17). The GitHub workflow does both, builds the native decoders and uploads the debug APK and the signed Google Play bundle.

The core alone runs anywhere with a JDK and `kotlinc`:
`kotlinc core/src/main/kotlin core/src/test/kotlin -include-runtime -d core-test.jar && java -cp core-test.jar es.ea7jrs.aprslorap.core.SelfTestKt`

## Not done yet
APRS telemetry (`T#`) and status (`>`); saving messages and stations between runs; position beacons from the phone; Android Auto station list; KISS over TCP as the main link; AIS built by the phone for boards without NMEA output; automatic measurement of the IBP beacon dashes; live I/Q from SDRconnect.

License: GPL-3.0-or-later.
