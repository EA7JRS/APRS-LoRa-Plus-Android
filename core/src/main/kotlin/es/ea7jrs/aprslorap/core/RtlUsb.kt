/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import java.util.Locale

/** USB identifiers of the RTL2832U dongles (the list of librtlsdr, `known_devices`), to tell the user what Android sees. */
object RtlUsb {
    data class Known(val vid: Int, val pid: Int, val name: String)

    val KNOWN = listOf(
        Known(0x0bda, 0x2832, "Generic RTL2832U"),
        Known(0x0bda, 0x2838, "RTL2832U (RTL-SDR Blog V3 / V4, most dongles)"),
        Known(0x0413, 0x6680, "DigitalNow Quad DVB-T PCI-E"),
        Known(0x0413, 0x6f0f, "Leadtek WinFast DTV Dongle mini D"),
        Known(0x0458, 0x707f, "Genius TVGo DVB-T03"),
        Known(0x0ccd, 0x00a9, "Terratec Cinergy T Stick Black"),
        Known(0x0ccd, 0x00b3, "Terratec NOXON DAB/DAB+"),
        Known(0x0ccd, 0x00b4, "Terratec Deutschlandradio DAB Stick"),
        Known(0x0ccd, 0x00b5, "Terratec NOXON DAB Stick - Radio Energy"),
        Known(0x0ccd, 0x00b7, "Terratec Media Broadcast DAB Stick"),
        Known(0x0ccd, 0x00b8, "Terratec BR DAB Stick"),
        Known(0x0ccd, 0x00b9, "Terratec WDR DAB Stick"),
        Known(0x0ccd, 0x00c0, "Terratec MuellerVerlag DAB Stick"),
        Known(0x0ccd, 0x00c6, "Terratec Fraunhofer DAB Stick"),
        Known(0x0ccd, 0x00d3, "Terratec Cinergy T Stick RC"),
        Known(0x0ccd, 0x00d7, "Terratec T Stick PLUS"),
        Known(0x0ccd, 0x00e0, "Terratec NOXON DAB/DAB+ (rev 2)"),
        Known(0x1554, 0x5020, "PixelView PV-DT235U(RN)"),
        Known(0x15f4, 0x0131, "Astrometa DVB-T/DVB-T2"),
        Known(0x15f4, 0x0133, "HanfTek DAB+FM+DVB-T"),
        Known(0x185b, 0x0620, "Compro Videomate U620F"),
        Known(0x185b, 0x0650, "Compro Videomate U650F"),
        Known(0x185b, 0x0680, "Compro Videomate U680F"),
        Known(0x1b80, 0xd393, "GIGABYTE GT-U7300"),
        Known(0x1b80, 0xd394, "DIKOM USB-DVBT HD"),
        Known(0x1b80, 0xd395, "Peak 102569AGPK"),
        Known(0x1b80, 0xd397, "KWorld KW-UB450-T USB DVB-T Pico TV"),
        Known(0x1b80, 0xd398, "Zaapa ZT-MINDVBZP"),
        Known(0x1b80, 0xd39d, "SVEON STV20 DVB-T USB & FM"),
        Known(0x1b80, 0xd3a4, "Twintech UT-40"),
        Known(0x1b80, 0xd3a8, "ASUS U3100MINI_PLUS_V2"),
        Known(0x1b80, 0xd3af, "SVEON STV27 DVB-T USB & FM"),
        Known(0x1b80, 0xd3b0, "SVEON STV21 DVB-T USB & FM"),
        Known(0x1d19, 0x1101, "Dexatek DK DVB-T Dongle (Logilink VG0002A)"),
        Known(0x1d19, 0x1102, "Dexatek DK DVB-T Dongle (MSI DigiVox mini II V3.0)"),
        Known(0x1d19, 0x1103, "Dexatek Technology Ltd. DK 5217 DVB-T Dongle"),
        Known(0x1d19, 0x1104, "MSI DigiVox Micro HD"),
        Known(0x1f4d, 0xa803, "Sweex DVB-T USB"),
        Known(0x1f4d, 0xb803, "GTek T803"),
        Known(0x1f4d, 0xc803, "Lifeview LV5TDeluxe"),
        Known(0x1f4d, 0xd286, "MyGica TD312"),
        Known(0x1f4d, 0xd803, "PROlectrix DV107669"),
    )

    fun find(vid: Int, pid: Int): Known? = KNOWN.firstOrNull { it.vid == vid && it.pid == pid }

    fun id(vid: Int, pid: Int): String = String.format(Locale.US, "%04x:%04x", vid, pid)
}
