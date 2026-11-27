# APRS LoRa+ for Android 5.5.1

The Android app carries its own version since 5.4.8. 5.5.1 brings a full **SSDV image** workflow and a reorganised side menu.

**SSDV image tool** (OPERATE > *SSDV image*)
- **Send an image** to the tracker to transmit as SSDV packets: **take a photo** with the phone, pick one **from the
  gallery**, or build an **eQSL-style QSL card** (your call sign, the correspondent, UTC date and time, RST, band, mode,
  comment). Choose the size (multiples of 16: QVGA, VGA, QSL landscape / wide, square, postcard) and the quality (0–7);
  the app crops, scales and JPEG-encodes it and uploads it over BLE (`ssdvimg`). Transmitting needs a validated call sign.
- **View, save and share received images**: download the last image the tracker received over BLE (new `ssdvrx` command;
  needs the tracker firmware with the SSDV RX download, branch `ssdv-rx-download`) and show it, with **Save** to the
  gallery and **Share** to WhatsApp, Telegram and so on.

**SSDV reception with the phone's SDR** (SDR tool, *SSDV images (SDR, FSK)*)
- Decodes SSDV images from the SDR audio: a 2-FSK demodulator frames the 256-byte packets and a **native decoder**
  (`libssdvdec.so`, fsphil's ssdv, the classic UKHAS format the tracker emits via RadioLib) reconstructs the JPEG.
  Baud and tone spacing are tunable. Only the FSK variant (an RTL-SDR cannot demodulate SSDV over LoRa).

**Side menu and App settings reorganised**
- New **App settings** screen with **User** (call sign, licence validation, tracker call sign), **TNC** (APRS path),
  Theme, Units, Emergency alerts, the **Signal ID database** download, **Spotting**, the tracker's **Sensors**,
  **Official alerts** and **RepeaterBook** (with Tune to the SDR and Follow to the rotor for a chosen repeater).
- **Compass / GPS** moves under *Radio* in Operate; **Beacons heard** opens from inside the SDR tool; VOR stays an SDR
  section. Operate groups the stations tools (Weather, AIS, ADS-B, HAB) and DAPNET under Messages.

**Not tested on a phone yet**: it builds in the CI (debug APK and Google Play bundle) and the pure Kotlin core passes
about 2185 checks on the PC. The SSDV SDR reception and the QSL card have not been tried with a real signal.
