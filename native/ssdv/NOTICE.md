# SSDV decoder: third-party software

`libssdvdec.so` is a separate program built by `build.sh` from fsphil's **ssdv**
(https://github.com/bristol-seds/ssdv, commit `b7128b1`, a mirror of the classic upstream before the cm256/CBEC rework),
Copyright (C) Philip Heron, **GNU General Public License v3 or later**. This is the 256-byte, RS(255,223) UKHAS SSDV
format that the APRS LoRa+ tracker transmits (via RadioLib's `SSDVClient`), so the two interoperate.

Only `ssdv.c`, `ssdv.h`, `rs8.c` and `rs8.h` are used. The program entry point `ssdv_dec.c` is part of APRS LoRa+
(Copyright (C) 2026 Manuel - EA7JRS, GPL-3.0-or-later): it reads 256-byte SSDV packets from a file and writes the
reconstructed baseline JPEG.

No image or sample data is included in this repository.
