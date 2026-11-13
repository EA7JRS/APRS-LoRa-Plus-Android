/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

/**
 * Flag of a DXCC entity as an emoji (the phone's own emoji font draws it: no images in the app). The entity comes from
 * cty.dat ([Cty.Entity.prefix], its main prefix); the table gives the ISO 3166 code whose flag stands for it. Entities
 * without a country of their own take the flag of the country they belong to (Balearic Islands: Spain; Hawaii: United
 * States); those with no flag at all (Sovereign Order of Malta, Spratly, Scarborough Reef) have none.
 */
object Flags {
    private val TABLE: Map<String, String> = HashMap<String, String>().also { m ->
        val t = """
            3A:MC 3B6:MU 3B8:MU 3B9:MU 3C:GQ 3C0:GQ 3D2:FJ 3D2/C:FJ 3D2/R:FJ 3DA:SZ 3V:TN 3W:VN 3X:GN 3Y/B:BV 3Y/P:NO
            4J:AZ 4L:GE 4O:ME 4S:LK 4U1I:UN 4U1U:UN 4U1V:UN 4W:TL 4X:IL 5A:LY 5B:CY 5H:TZ 5N:NG 5R:MG 5T:MR 5U:NE 5V:TG 5W:WS
            5X:UG 5Z:KE 6W:SN 6Y:JM 7O:YE 7P:LS 7Q:MW 7X:DZ 8P:BB 8Q:MV 8R:GY 9A:HR 9G:GH 9H:MT 9J:ZM 9K:KW 9L:SL 9M2:MY
            9M6:MY 9N:NP 9Q:CD 9U:BI 9V:SG 9X:RW 9Y:TT A2:BW A3:TO A4:OM A5:BT A6:AE A7:QA A9:BH AP:PK BV:TW BV9P:TW BY:CN
            C2:NR C3:AD C5:GM C6:BS C9:MZ CE:CL CE0X:CL CE0Y:CL CE0Z:CL CE9:AQ CM:CU CN:MA CP:BO CT:PT CT3:PT CU:PT CX:UY
            CY0:CA CY9:CA D2:AO D4:CV D6:KM DL:DE DU:PH E3:ER E4:PS E5/N:CK E5/S:CK E6:NU E7:BA EA:ES EA6:ES EA8:IC EA9:EA
            EI:IE EK:AM EL:LR EP:IR ER:MD ES:EE ET:ET EU:BY EX:KG EY:TJ EZ:TM F:FR FG:GP FH:YT FJ:BL FK:NC FK/C:NC FM:MQ
            FO:PF FO/A:PF FO/C:CP FO/M:PF FP:PM FR:RE FS:MF FT/G:TF FT/J:TF FT/T:TF FT/W:TF FT/X:TF FT/Z:TF FW:WF FY:GF
            G:GB-ENG GD:IM GI:GB GJ:JE GM:GB-SCT GM/S:GB-SCT GU:GG GW:GB-WLS H4:SB H40:SB HA:HU HB:CH HB0:LI HC:EC HC8:EC
            HH:HT HI:DO HK:CO HK0/A:CO HK0/M:CO HL:KR HP:PA HR:HN HS:TH HV:VA HZ:SA I:IT IS:IT IT9:IT IG9:IT J2:DJ J3:GD
            J5:GW J6:LC J7:DM J8:VC JA:JP JD/M:JP JD/O:JP JT:MN JW:SJ JW/B:SJ JX:SJ JY:JO K:US KG4:US KH0:MP KH1:UM KH2:GU
            KH3:UM KH4:UM KH5:UM KH6:US KH7K:US KH8:AS KH8/S:AS KH9:UM KL:US KP1:UM KP2:VI KP4:PR KP5:PR LA:NO LU:AR LX:LU
            LY:LT LZ:BG OA:PE OD:LB OE:AT OH:FI OH0:AX OJ0:AX OK:CZ OM:SK ON:BE OX:GL OY:FO OZ:DK P2:PG P4:AW P5:KP PA:NL
            PJ2:CW PJ4:BQ PJ5:BQ PJ7:SX PY:BR PY0F:BR PY0S:BR PY0T:BR PZ:SR R1FJ:RU S0:EH S2:BD S5:SI S7:SC S9:ST SM:SE
            SP:PL ST:SD SU:EG SV:GR SV/A:GR SV5:GR SV9:GR T2:TV T30:KI T31:KI T32:KI T33:KI T5:SO T7:SM T8:PW TA:TR TA1:TR
            TF:IS TG:GT TI:CR TI9:CR TJ:CM TK:FR TL:CF TN:CG TR:GA TT:TD TU:CI TY:BJ TZ:ML UA:RU UA2:RU UA9:RU UK:UZ UN:KZ
            UR:UA V2:AG V3:BZ V4:KN V5:NA V6:FM V7:MH V8:BN VE:CA VK:AU VK0H:HM VK0M:AU VK9C:CC VK9L:AU VK9M:AU VK9N:NF
            VK9W:AU VK9X:CX VP2E:AI VP2M:MS VP2V:VG VP5:TC VP6:PN VP6/D:PN VP8:FK VP8/G:GS VP8/H:AQ VP8/O:AQ VP8/S:GS
            VP9:BM VQ9:IO VR:HK VU:IN VU4:IN VU7:IN XE:MX XF4:MX XT:BF XU:KH XW:LA XX9:MO XZ:MM YA:AF YB:ID YI:IQ YJ:VU
            YK:SY YL:LV YN:NI YO:RO YS:SV YU:RS YV:VE YV0:VE Z2:ZW Z3:MK Z6:XK Z8:SS ZA:AL ZB:GI ZC4:GB ZD7:SH ZD8:AC
            ZD9:TA ZF:KY ZK3:TK ZL:NZ ZL7:NZ ZL8:NZ ZL9:NZ ZP:PY ZS:ZA ZS8:ZA
        """
        for (e in t.trim().split(Regex("\\s+"))) m[e.substringBefore(':')] = e.substringAfter(':')
    }

    /** ISO code of the flag for a cty.dat main prefix ("EA8", "*TA1", "VP8/g"): "ES", "GB-SCT"..., or null. */
    fun code(ctyPrefix: String): String? = TABLE[ctyPrefix.trim().removePrefix("*").uppercase()]

    /** The emoji of an ISO 3166 code: two letters ("ES"), or England / Scotland / Wales as "GB-ENG" / "GB-SCT" / "GB-WLS". */
    fun emoji(code: String): String {
        val c = code.uppercase()
        if (c.length == 2 && c.all { it in 'A'..'Z' }) return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
        if (c.length == 6 && c.startsWith("GB-")) {                      // black flag + tag letters + cancel tag
            val sb = StringBuilder(String(Character.toChars(0x1F3F4)))
            for (ch in c.replace("-", "").lowercase()) sb.append(String(Character.toChars(0xE0000 + ch.code)))
            return sb.append(String(Character.toChars(0xE007F))).toString()
        }
        return ""
    }

    /** Flag of an entity of cty.dat, "" if it has none. */
    fun of(entity: Cty.Entity?): String = entity?.let { code(it.prefix) }?.let { emoji(it) } ?: ""
}
