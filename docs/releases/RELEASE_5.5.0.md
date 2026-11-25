# APRS LoRa+ for Android 5.5.0

The whole APRS LoRa+ ecosystem (tracker, app, rotor, Display, plotter, NMEA 2000 and OBD-II) is unified at **5.5.0**;
the app rejoins the common version number.

**HAB telemetry (balloons)**
- Decodes UKHAS telemetry (`$$CALL,counter,HH:MM:SS,lat,lon,alt,…*CRC`, CRC16-CCITT) from the RTTY decoder; a **Balloons**
  tool with the list, GO TO and ROTOR.

**CW skimmer**
- Multi-channel CW decoder (a *skimmer*) in the Digimodes tab: several CW signals at once, each spotted with its audio
  frequency, callsign, SNR and WPM.

**SSDV image and POCSAG control** (needs the 5.5.0 tracker firmware)
- **SSDV** section in the Tracker tab (SSDV mode): status and progress, **upload a JPEG from the phone** over BLE,
  transmit / stop. The image source and radio parameters stay in the Config tab (group `ssdv`).
- **POCSAG** section: send a page to a RIC (numeric or alphanumeric). Both transmit, so they need a validated callsign.
- New `TrackerMode` values SSDV (13) and RTTY (14); `caps.ssdv` / `caps.rtty` / `caps.pocsag` from the tracker.

**Not tested on a phone yet**: it builds in the CI (debug APK and Google Play bundle) and the core passes about 2100
checks on the PC.
