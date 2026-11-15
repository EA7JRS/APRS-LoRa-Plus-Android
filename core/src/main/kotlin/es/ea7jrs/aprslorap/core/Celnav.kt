/* Copyright (C) 2026 Manuel - EA7JRS
 *
 * This file is part of APRS LoRa+ for Android.
 * Licensed under the GNU General Public License, version 3 or later.
 */
package es.ea7jrs.aprslorap.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Celestial navigation to nautical-almanac precision (0.1'), ported from lib/plotter_core/src/celnav.cpp of the APRS LoRa+
 * plotter with the same reference values: GHA and declination of the Sun (truncated VSOP87, nutation, aberration), GHA of
 * Aries, SHA and declination of the 57 navigational stars and Polaris (proper motion, precession, nutation, annual
 * aberration; PyEphem / Hipparcos catalogue), the Moon and the four navigational planets, sextant altitude corrections, sight reduction and fix. Tables GENERATED from
 * the plotter core: do not edit them by hand.
 */
object Celnav {
    data class NavStar(val name: String, val mag: Double, val raHours: Double, val decDeg: Double, val pmRaMas: Double, val pmDecMas: Double)
    data class GhaDec(val gha: Double, val dec: Double)
    data class ShaDec(val sha: Double, val dec: Double)
    data class AltAz(val alt: Double, val az: Double)
    /** hc / zn from the assumed position; intercept in nm (+ towards the body); lat / lon of the intercept point. */
    data class Sight(val hc: Double, val zn: Double, val interceptNm: Double, val lat: Double, val lon: Double)
    enum class Limb { CENTER, LOWER, UPPER }

    private const val AS = Math.PI / (180.0 * 3600.0)
    private const val DELTA_T = 69.2

    /** The 57 navigational stars and Polaris (indices 0-57), then 99 more bright almanac stars (HYG Database v4.1, CC BY-SA 4.0). */
    const val NAV_STARS = 58
    val STARS: List<NavStar> = listOf(
        NavStar("Acamar", 2.88, 2.97102074, -40.30467239, -53.53, 25.71),
        NavStar("Achernar", 0.45, 1.62856849, -57.23675744, 88.02, -40.08),
        NavStar("Acrux", 0.77, 12.44330439, -63.09909168, -35.37, -14.73),
        NavStar("Adhara", 1.50, 6.97709679, -28.97208374, 2.63, 2.29),
        NavStar("Aldebaran", 0.87, 4.59867740, 16.50930138, 62.78, -189.36),
        NavStar("Alioth", 1.76, 12.90048595, 55.95982123, 111.74, -8.99),
        NavStar("Alkaid", 1.85, 13.79234379, 49.31326512, -121.23, -15.56),
        NavStar("Alnair", 1.73, 22.13721819, -46.96097539, 127.60, -147.91),
        NavStar("Alnilam", 1.69, 5.60355929, -1.20191983, 1.49, -1.06),
        NavStar("Alphard", 1.99, 9.45978980, -8.65860253, -14.49, 33.25),
        NavStar("Alphecca", 2.22, 15.57813004, 26.71469307, 120.38, -89.44),
        NavStar("Alpheratz", 2.07, 0.13979405, 29.09043197, 135.68, -162.95),
        NavStar("Altair", 0.76, 19.84638864, 8.86832203, 536.82, 385.54),
        NavStar("Ankaa", 2.40, 0.43806972, -42.30598144, 232.76, -353.64),
        NavStar("Antares", 1.06, 16.49012803, -26.43200250, -10.16, -23.21),
        NavStar("Arcturus", -0.05, 14.26102001, 19.18241038, -1093.45, -1999.40),
        NavStar("Atria", 1.91, 16.81108191, -69.02771505, 17.85, -32.92),
        NavStar("Avior", 1.86, 8.37523211, -59.50948307, -25.34, 22.72),
        NavStar("Bellatrix", 1.64, 5.41885085, 6.34970223, -8.75, -13.28),
        NavStar("Betelgeuse", 0.45, 5.91952924, 7.40706274, 27.33, 10.86),
        NavStar("Canopus", -0.62, 6.39919718, -52.69566045, 19.99, 23.67),
        NavStar("Capella", 0.08, 5.27815528, 45.99799106, 75.52, -427.13),
        NavStar("Deneb", 1.25, 20.69053187, 45.28033800, 1.56, 1.55),
        NavStar("Denebola", 2.14, 11.81766043, 14.57206038, -499.02, -113.78),
        NavStar("Diphda", 2.04, 0.72649196, -17.98660457, 232.79, 32.71),
        NavStar("Dubhe", 1.81, 11.06213019, 61.75103324, -136.46, -35.25),
        NavStar("Elnath", 1.65, 5.43819816, 28.60745000, 23.28, -174.22),
        NavStar("Eltanin", 2.24, 17.94343608, 51.48889500, -8.52, -23.05),
        NavStar("Enif", 2.38, 21.73643281, 9.87501126, 30.02, 1.38),
        NavStar("Fomalhaut", 1.17, 22.96084626, -29.62223601, 329.22, -164.22),
        NavStar("Gacrux", 1.59, 12.51943314, -57.11321175, 27.94, -264.33),
        NavStar("Gienah", 2.58, 12.26343617, -17.54192948, -159.58, 22.31),
        NavStar("Hadar", 0.61, 14.06372347, -60.37303932, -33.96, -25.06),
        NavStar("Hamal", 2.01, 2.11955753, 23.46242310, 190.73, -145.77),
        NavStar("Kaus Australis", 1.79, 18.40286620, -34.38461611, -39.61, -124.05),
        NavStar("Kochab", 2.07, 14.84509068, 74.15550496, -32.29, 11.91),
        NavStar("Markab", 2.49, 23.07934827, 15.20526441, 61.10, -42.56),
        NavStar("Menkar", 2.54, 3.03799227, 4.08973396, -11.81, -78.76),
        NavStar("Menkent", 2.06, 14.11137457, -36.36995451, -519.29, -517.87),
        NavStar("Miaplacidus", 1.67, 9.21999318, -69.71720776, -157.66, 108.91),
        NavStar("Mirfak", 1.79, 3.40538065, 49.86117958, 24.11, -26.01),
        NavStar("Nunki", 2.05, 18.92109048, -26.29672225, 13.87, -52.65),
        NavStar("Peacock", 1.94, 20.42746051, -56.73509009, 7.71, -86.15),
        NavStar("Pollux", 1.16, 7.75526397, 28.02619865, -625.69, -45.95),
        NavStar("Procyon", 0.40, 7.65503283, 5.22499314, -716.57, -1034.58),
        NavStar("Rasalhague", 2.08, 17.58224183, 12.56003481, 110.08, -222.61),
        NavStar("Regulus", 1.36, 10.13953074, 11.96720709, -249.40, 4.91),
        NavStar("Rigel", 0.18, 5.24229787, -8.20164055, 1.87, -0.56),
        NavStar("Rigil Kentaurus", -0.01, 14.66013779, -60.83397588, -3678.19, 481.84),
        NavStar("Sabik", 2.43, 17.17296871, -15.72491023, 41.16, 97.65),
        NavStar("Schedar", 2.24, 0.67512237, 56.53733107, 50.36, -32.17),
        NavStar("Shaula", 1.62, 17.56014444, -37.10382115, -8.90, -29.95),
        NavStar("Sirius", -1.44, 6.75247697, -16.71611569, -546.01, -1223.08),
        NavStar("Spica", 0.98, 13.41988313, -11.16132203, -42.50, -31.73),
        NavStar("Suhail", 2.23, 9.13326624, -43.43258935, -23.21, 14.28),
        NavStar("Vega", 0.03, 18.61564903, 38.78369185, 201.02, 287.46),
        NavStar("Zubenelgenubi", 2.75, 14.84797587, -16.04177819, -105.69, -69.00),
        NavStar("Polaris", 1.97, 2.53030100, 89.26410949, 44.22, -11.74),
        NavStar("N Vel", 3.16, 9.52037100, -57.03437700, -32.58, 6.08),
        NavStar("Acrab", 2.56, 16.09062000, -19.80545300, -6.75, -24.89),
        NavStar("Albaldah", 2.88, 19.16273100, -21.02361500, -1.17, -36.83),
        NavStar("Albireo", 3.05, 19.51202300, 27.95968100, -7.09, -5.63),
        NavStar("Alcyone", 2.85, 3.79141000, 24.10513700, 19.35, -43.11),
        NavStar("Alderamin", 2.45, 21.30963000, 62.58557300, 149.91, 48.27),
        NavStar("Algenib", 2.83, 0.22059800, 15.18359600, 4.70, -8.24),
        NavStar("Algol", 2.09, 3.13614800, 40.95564800, 2.39, -1.44),
        NavStar("Alhena", 1.93, 6.62852800, 16.39925200, -2.04, -66.92),
        NavStar("Aljanah", 2.48, 20.77017800, 33.97025600, 356.16, 330.28),
        NavStar("Almach", 2.10, 2.06498400, 42.32972500, 43.08, -50.85),
        NavStar("Alnasl", 2.98, 18.09680300, -30.42409100, -55.75, -181.53),
        NavStar("Alpha Ara", 2.84, 17.53069500, -49.87614500, -31.27, -67.15),
        NavStar("Alpha Hyi", 2.86, 1.97945100, -61.56985900, 262.54, 26.88),
        NavStar("Alpha Ind", 3.11, 20.62611600, -47.29150200, 49.20, 66.07),
        NavStar("Alpha Lup", 2.30, 14.69882300, -47.38820000, -21.15, -24.22),
        NavStar("Alpha Mus", 2.69, 12.61973900, -69.13556400, -39.87, -12.44),
        NavStar("Alpha Tuc", 2.87, 22.30837100, -60.25958700, -71.48, -38.15),
        NavStar("Aludra", 2.45, 7.40158400, -29.30310400, -3.76, 6.66),
        NavStar("Arneb", 2.58, 5.54550400, -17.82228900, 3.27, 1.54),
        NavStar("Aspidiske", 2.21, 9.28483800, -59.27522900, -19.03, 13.11),
        NavStar("Beta Ara", 2.84, 17.42166500, -55.52988400, -8.23, -24.71),
        NavStar("Beta Hyi", 2.82, 0.42791600, -77.25424700, 2220.12, 324.37),
        NavStar("Beta Lup", 2.68, 14.97553700, -43.13396000, -34.06, -38.30),
        NavStar("Beta TrA", 2.83, 15.91908300, -63.43072700, -188.45, -401.92),
        NavStar("Beta Tri", 3.00, 2.15905800, 34.98729700, 148.78, -39.13),
        NavStar("Caph", 2.28, 0.15288700, 59.14978000, 523.39, -180.42),
        NavStar("Castor", 1.58, 7.57663400, 31.88827600, -206.33, -148.18),
        NavStar("Cebalrai", 2.76, 17.72454300, 4.56730300, -40.67, 158.80),
        NavStar("Cih", 2.15, 0.94514300, 60.71674000, 25.65, -3.82),
        NavStar("Cor Caroli", 2.89, 12.93380700, 38.31838000, -233.43, 54.98),
        NavStar("Cursa", 2.78, 5.13082900, -5.08644600, -83.39, -75.44),
        NavStar("Delta Cen", 2.58, 12.13931000, -50.72242500, -47.53, -6.42),
        NavStar("Deneb Algedi", 2.85, 21.78401100, -16.12728600, 263.26, -296.23),
        NavStar("Dschubba", 2.29, 16.00555700, -22.62171000, -8.67, -36.90),
        NavStar("Epsilon Cen", 2.29, 13.66479600, -53.46639400, -14.60, -12.79),
        NavStar("Epsilon Per", 2.90, 3.96423000, 40.01021500, 12.61, -24.06),
        NavStar("Errai", 3.21, 23.65582000, 77.63227600, -48.85, 127.18),
        NavStar("Eta Cen", 2.33, 14.59178600, -42.15782400, -35.31, -32.44),
        NavStar("Fang", 2.89, 15.98086500, -26.11410500, -12.00, -25.71),
        NavStar("Gamma 2 Vel", 1.75, 8.15887600, -47.33658800, -5.93, 9.90),
        NavStar("Gamma TrA", 2.87, 15.31518100, -68.67954500, -66.48, -32.00),
        NavStar("Gomeisa", 2.89, 7.45251200, 8.28931500, -50.28, -38.45),
        NavStar("Hassaleh", 2.69, 4.94989400, 33.16609000, 3.63, -18.54),
        NavStar("Hatysa", 2.75, 5.59055100, -5.90990100, 2.27, -0.62),
        NavStar("Iota Cen", 2.75, 13.34329600, -36.71229500, -340.76, -87.98),
        NavStar("Kappa Sco", 2.39, 17.70813200, -39.02998300, -6.49, -25.55),
        NavStar("Kaus Borealis", 2.82, 18.46617900, -25.42170000, -44.81, -186.29),
        NavStar("Kaus Media", 2.72, 18.34990000, -29.82810300, 29.96, -26.38),
        NavStar("Kornephoros", 2.78, 16.50366800, 21.48961300, -98.43, -14.49),
        NavStar("Kraz", 2.65, 12.57312100, -23.39675900, 0.86, -56.00),
        NavStar("Larawag", 2.29, 16.83608000, -34.29323200, -611.83, -255.87),
        NavStar("Lesath", 2.70, 17.51273200, -37.29581100, -4.19, -29.14),
        NavStar("Markeb", 2.47, 9.36856200, -55.01066800, -10.72, 11.24),
        NavStar("Menkalinan", 1.90, 5.99214900, 44.94743300, -56.41, -0.88),
        NavStar("Merak", 2.34, 11.03067700, 56.38242700, 81.66, 33.74),
        NavStar("Mimosa", 1.25, 12.79535900, -59.68876400, -48.24, -12.82),
        NavStar("Mintaka", 2.25, 5.53344500, -0.29909200, 1.67, 0.56),
        NavStar("Mirach", 2.07, 1.16219400, 35.62055800, 175.59, -112.23),
        NavStar("Mirzam", 1.98, 6.37832900, -17.95591800, -3.45, -0.47),
        NavStar("Mizar", 2.23, 13.39874700, 54.92536200, 121.23, -22.01),
        NavStar("Muphrid", 2.68, 13.91141100, 18.39771700, -60.95, -358.10),
        NavStar("Naos", 2.21, 8.05973700, -40.00314800, -30.82, 16.77),
        NavStar("Nihal", 2.81, 5.47075600, -20.75944100, -5.03, -85.92),
        NavStar("Okab", 2.99, 19.09016900, 13.86347800, -7.04, -95.31),
        NavStar("Omicron 2 CMa", 3.02, 7.05040900, -23.83329100, -1.56, 4.28),
        NavStar("Paikauhale", 2.82, 16.59804300, -28.21601600, -8.59, -22.50),
        NavStar("Phact", 2.65, 5.66081700, -34.07410800, -0.10, -24.05),
        NavStar("Phecda", 2.41, 11.89716800, 53.69476000, 107.76, 11.16),
        NavStar("Pherkad", 3.00, 15.34548300, 71.83401600, -18.03, 17.68),
        NavStar("Pi Pup", 2.71, 7.28571100, -37.09747000, -10.57, 7.00),
        NavStar("Ras Elased Australis", 2.97, 9.76418800, 23.77425500, -46.09, -9.57),
        NavStar("Rastaban", 2.79, 17.50721300, 52.30138700, -15.59, 11.57),
        NavStar("Ruchbah", 2.66, 1.43021600, 60.23528300, 297.24, -49.49),
        NavStar("Sadalsuud", 2.90, 21.52598200, -5.57117200, 22.79, -6.70),
        NavStar("Sadr", 2.23, 20.37047300, 40.25667900, 2.43, -0.93),
        NavStar("Saiph", 2.07, 5.79594100, -9.66960500, 1.55, -1.20),
        NavStar("Sargas", 1.86, 17.62198000, -42.99782400, 6.06, -0.95),
        NavStar("Scheat", 2.44, 23.06290100, 28.08278900, 187.76, 137.61),
        NavStar("Seginus", 3.04, 14.53463600, 38.30825300, -115.55, 151.87),
        NavStar("Sheratan", 2.64, 1.91066800, 20.80803500, 96.32, -108.80),
        NavStar("Sigma Pup", 3.25, 7.48717900, -43.30143200, -59.86, 188.73),
        NavStar("Talitha", 3.12, 8.98682800, 48.04182600, -441.12, -215.21),
        NavStar("Tarazed", 2.72, 19.77099400, 10.61326100, 15.72, -3.08),
        NavStar("Tau Pup", 2.94, 6.83226600, -50.61456000, 34.23, -65.85),
        NavStar("Tiaki", 2.07, 22.71111500, -46.88457700, 135.68, -4.51),
        NavStar("Tianguan", 2.97, 5.62741300, 21.14254900, 2.39, -18.04),
        NavStar("Tureis", 2.83, 8.12573700, -24.30432400, -83.29, 46.38),
        NavStar("Unukalhai", 2.63, 15.73779800, 6.42562700, 134.66, 44.14),
        NavStar("Vindemiatrix", 2.85, 13.03627800, 10.95915000, -275.05, 19.96),
        NavStar("Wezen", 1.83, 7.13985700, -26.39320000, -2.75, 3.33),
        NavStar("Yed Prior", 2.73, 16.23909400, -3.69432300, -45.83, -142.91),
        NavStar("Zaurak", 2.97, 3.96715700, -13.50851500, 60.51, -111.34),
        NavStar("Zeta Ara", 3.12, 16.97700600, -55.99014100, -18.31, -35.29),
        NavStar("Zeta Cen", 2.55, 13.92566700, -47.28837500, -57.14, -44.75),
        NavStar("Zeta Oph", 2.54, 16.61931600, -10.56709000, 13.07, 25.44),
        NavStar("Zeta Per", 2.84, 3.90220000, 31.88363500, 4.41, -9.15),
        NavStar("Zosma", 2.56, 11.23513800, 20.52371700, 143.31, -130.43),
        NavStar("Zubeneschamali", 2.61, 15.28344900, -9.38291700, -96.39, -20.76),
    )

    private val L0 = arrayOf(doubleArrayOf(175347046.0, 0.0, 0.0), doubleArrayOf(3341656.0, 4.6692568, 6283.07585), doubleArrayOf(34894.0, 4.6261, 12566.1517), doubleArrayOf(3497.0, 2.7441, 5753.3849), doubleArrayOf(3418.0, 2.8289, 3.5231), doubleArrayOf(3136.0, 3.6277, 77713.7715), doubleArrayOf(2676.0, 4.4181, 7860.4194), doubleArrayOf(2343.0, 6.1352, 3930.2097), doubleArrayOf(1324.0, 0.7425, 11506.7698), doubleArrayOf(1273.0, 2.0371, 529.691), doubleArrayOf(1199.0, 1.1096, 1577.3435), doubleArrayOf(990.0, 5.233, 5884.927), doubleArrayOf(902.0, 2.045, 26.298), doubleArrayOf(857.0, 3.508, 398.149), doubleArrayOf(780.0, 1.179, 5223.694), doubleArrayOf(753.0, 2.533, 5507.553), doubleArrayOf(505.0, 4.583, 18849.228), doubleArrayOf(492.0, 4.205, 775.523), doubleArrayOf(357.0, 2.92, 0.067), doubleArrayOf(317.0, 5.849, 11790.629), doubleArrayOf(284.0, 1.899, 796.298), doubleArrayOf(271.0, 0.315, 10977.079), doubleArrayOf(243.0, 0.345, 5486.778), doubleArrayOf(206.0, 4.806, 2544.314), doubleArrayOf(205.0, 1.869, 5573.143), doubleArrayOf(202.0, 2.458, 6069.777), doubleArrayOf(156.0, 0.833, 213.299), doubleArrayOf(132.0, 3.411, 2942.463), doubleArrayOf(126.0, 1.083, 20.775), doubleArrayOf(115.0, 0.645, 0.98), doubleArrayOf(103.0, 0.636, 4694.003), doubleArrayOf(102.0, 0.976, 15720.839), doubleArrayOf(102.0, 4.267, 7.114), doubleArrayOf(99.0, 6.21, 2146.17), doubleArrayOf(98.0, 0.68, 155.42), doubleArrayOf(86.0, 5.98, 161000.69), doubleArrayOf(85.0, 1.3, 6275.96), doubleArrayOf(85.0, 3.67, 71430.7), doubleArrayOf(80.0, 1.81, 17260.15))
    private val L1 = arrayOf(doubleArrayOf(628331966747.0, 0.0, 0.0), doubleArrayOf(206059.0, 2.678235, 6283.07585), doubleArrayOf(4303.0, 2.6351, 12566.1517), doubleArrayOf(425.0, 1.59, 3.523), doubleArrayOf(119.0, 5.796, 26.298), doubleArrayOf(109.0, 2.966, 1577.344), doubleArrayOf(93.0, 2.59, 18849.23), doubleArrayOf(72.0, 1.14, 529.69), doubleArrayOf(68.0, 1.87, 398.15), doubleArrayOf(67.0, 4.41, 5507.55), doubleArrayOf(59.0, 2.89, 5223.69), doubleArrayOf(56.0, 2.17, 155.42), doubleArrayOf(45.0, 0.4, 796.3), doubleArrayOf(36.0, 0.47, 775.52), doubleArrayOf(29.0, 2.65, 7.11), doubleArrayOf(21.0, 5.34, 0.98), doubleArrayOf(19.0, 1.85, 5486.78), doubleArrayOf(19.0, 4.97, 213.3), doubleArrayOf(17.0, 2.99, 6275.96), doubleArrayOf(16.0, 0.03, 2544.31))
    private val L2 = arrayOf(doubleArrayOf(52919.0, 0.0, 0.0), doubleArrayOf(8720.0, 1.0721, 6283.0758), doubleArrayOf(309.0, 0.867, 12566.152), doubleArrayOf(27.0, 0.05, 3.52), doubleArrayOf(16.0, 5.19, 26.3), doubleArrayOf(16.0, 3.68, 155.42), doubleArrayOf(10.0, 0.76, 18849.23), doubleArrayOf(9.0, 2.06, 77713.77), doubleArrayOf(7.0, 0.83, 775.52), doubleArrayOf(5.0, 4.66, 1577.34))
    private val L3 = arrayOf(doubleArrayOf(289.0, 5.844, 6283.076), doubleArrayOf(35.0, 0.0, 0.0), doubleArrayOf(17.0, 5.49, 12566.15))

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun n2pi(a: Double): Double { val r = a % (2 * Math.PI); return if (r < 0) r + 2 * Math.PI else r }
    private fun n360(a: Double): Double { val r = a % 360.0; return if (r < 0) r + 360 else r }
    private fun series(t: Array<DoubleArray>, tau: Double): Double { var s = 0.0; for (x in t) s += x[0] * cos(x[1] + x[2] * tau); return s }

    fun julianDay(timeMs: Long): Double = timeMs / 86_400_000.0 + 2440587.5
    private fun tt(jd: Double) = (jd + DELTA_T / 86400.0 - 2451545.0) / 36525.0

    private class Nut(val dpsi: Double, val deps: Double, val eps: Double)
    private fun nutation(t: Double): Nut {
        val om = rad(125.04452 - 1934.136261 * t); val l = rad(280.4665 + 36000.7698 * t); val lm = rad(218.3165 + 481267.8813 * t)
        val dpsi = (-17.20 * sin(om) - 1.32 * sin(2 * l) - 0.23 * sin(2 * lm) + 0.21 * sin(2 * om)) * AS
        val deps = (9.20 * cos(om) + 0.57 * cos(2 * l) + 0.10 * cos(2 * lm) - 0.09 * cos(2 * om)) * AS
        return Nut(dpsi, deps, (84381.448 - 46.8150 * t - 0.00059 * t * t + 0.001813 * t * t * t) * AS + deps)
    }

    /** Geometric longitude of the Sun (rad) and distance (AU). */
    private fun sunGeometric(t: Double): DoubleArray {
        val tau = t / 10.0
        val l = (series(L0, tau) + tau * (series(L1, tau) + tau * (series(L2, tau) + tau * (series(L3, tau) + tau * -114.0)))) * 1e-8
        val lon = n2pi(l + Math.PI) - 0.09033 * AS
        val m = rad(357.52911 + 35999.05029 * t); val e = 0.016708634 - 0.000042037 * t
        val c = rad((1.914602 - 0.004817 * t) * sin(m) + 0.019993 * sin(2 * m) + 0.000289 * sin(3 * m))
        return doubleArrayOf(lon, 1.000001018 * (1 - e * e) / (1 + e * cos(m + c)))
    }

    private fun gast(jd: Double): Double {
        val d = jd - 2451545.0; val tu = d / 36525.0
        val gmst = 280.46061837 + 360.98564736629 * d + 0.000387933 * tu * tu - tu * tu * tu / 38710000.0
        val n = nutation(tt(jd))
        return n2pi(rad(gmst % 360.0) + n.dpsi * cos(n.eps))
    }

    /** GHA of Aries in degrees (jd: Julian day, UTC). */
    fun ariesGha(jd: Double): Double = deg(gast(jd))

    fun sunGhaDec(jd: Double): GhaDec {
        val t = tt(jd); val g = sunGeometric(t); val n = nutation(t)
        val lam = g[0] + n.dpsi - 20.4898 * AS / g[1]
        val ra = atan2(cos(n.eps) * sin(lam), cos(lam))
        return GhaDec(deg(n2pi(gast(jd) - ra)), deg(asin(sin(n.eps) * sin(lam))))
    }

    // ---- Moon and planets (same algorithms and reference values as the plotter core) ----
    data class Body(val gha: Double, val dec: Double, val dist: Double)   // dist: km for the Moon, AU for a planet
    enum class Planet(val label: String) { VENUS("Venus"), MARS("Mars"), JUPITER("Jupiter"), SATURN("Saturn") }

    private fun eclToEq(lam: Double, bet: Double, eps: Double, jd: Double, dist: Double): Body {
        val ra = atan2(sin(lam) * cos(eps) - tan(bet) * sin(eps), cos(lam))
        val dec = asin(sin(bet) * cos(eps) + cos(bet) * sin(eps) * sin(lam))
        return Body(deg(n2pi(gast(jd) - ra)), deg(dec), dist)
    }

    private fun vsop(s: Array<DoubleArray>, tau: Double): Double {
        var tot = 0.0; var tp = 1.0
        for (k in s) {
            var sum = 0.0; var i = 0
            while (i < k.size) { sum += k[i] * cos(k[i + 1] + k[i + 2] * tau); i += 3 }
            tot += sum * tp; tp *= tau
        }
        return tot * 1e-8
    }

    /** Moon (Meeus ch. 47, full tables; about 0.25' against JPL DE421): geocentric GHA, declination and distance in km. */
    fun moonGhaDec(jd: Double): Body {
        val t = tt(jd); val t2 = t * t; val t3 = t2 * t; val t4 = t3 * t
        val lp = 218.3164477 + 481267.88123421 * t - 0.0015786 * t2 + t3 / 538841.0 - t4 / 65194000.0
        val d = 297.8501921 + 445267.1114034 * t - 0.0018819 * t2 + t3 / 545868.0 - t4 / 113065000.0
        val m = 357.5291092 + 35999.0502909 * t - 0.0001536 * t2 + t3 / 24490000.0
        val mp = 134.9633964 + 477198.8675055 * t + 0.0087414 * t2 + t3 / 69699.0 - t4 / 14712000.0
        val f = 93.2720950 + 483202.0175233 * t - 0.0036539 * t2 - t3 / 3526000.0 + t4 / 863310000.0
        val a1 = 119.75 + 131.849 * t; val a2 = 53.09 + 479264.290 * t; val a3 = 313.45 + 481266.484 * t
        val e = 1 - 0.002516 * t - 0.0000074 * t2
        var sl = 0.0; var sr = 0.0; var sb = 0.0
        val lr = CelnavBodies.MOON_LR; var i = 0
        while (i < lr.size) {
            val arg = rad(lr[i] * d + lr[i + 1] * m + lr[i + 2] * mp + lr[i + 3] * f)
            val ef = when (abs(lr[i + 1])) { 0 -> 1.0; 1 -> e; else -> e * e }
            sl += ef * lr[i + 4] * sin(arg); sr += ef * lr[i + 5] * cos(arg); i += 6
        }
        val b = CelnavBodies.MOON_B; i = 0
        while (i < b.size) {
            val arg = rad(b[i] * d + b[i + 1] * m + b[i + 2] * mp + b[i + 3] * f)
            val ef = when (abs(b[i + 1])) { 0 -> 1.0; 1 -> e; else -> e * e }
            sb += ef * b[i + 4] * sin(arg); i += 5
        }
        sl += 3958 * sin(rad(a1)) + 1962 * sin(rad(lp - f)) + 318 * sin(rad(a2))
        sb += -2235 * sin(rad(lp)) + 382 * sin(rad(a3)) + 175 * sin(rad(a1 - f)) + 175 * sin(rad(a1 + f)) +
            127 * sin(rad(lp - mp)) - 115 * sin(rad(lp + mp))
        val n = nutation(t)
        return eclToEq(rad(lp + sl / 1e6) + n.dpsi, rad(sb / 1e6), n.eps, jd, 385000.56 + sr / 1000.0)
    }

    fun moonHpArcmin(distKm: Double): Double = deg(asin(6378.14 / distKm)) * 60.0
    fun moonSdArcmin(distKm: Double): Double = 0.2725 * moonHpArcmin(distKm)

    /** Navigational planets (truncated VSOP87, light time, aberration, nutation; about 0.06' against PyEphem). */
    fun planetGhaDec(p: Planet, jd: Double): Body {
        val t = tt(jd); val tau = t / 10.0
        val l0 = vsop(arrayOf(flat(L0), flat(L1), flat(L2), flat(L3)), tau)
        val b0 = vsop(CelnavBodies.EARTH_B, tau); val r0 = vsop(CelnavBodies.EARTH_R, tau)
        val ex = r0 * cos(b0) * cos(l0); val ey = r0 * cos(b0) * sin(l0); val ez = r0 * sin(b0)
        val (sl, sb, sr) = when (p) {
            Planet.VENUS -> Triple(CelnavBodies.VENUS_L, CelnavBodies.VENUS_B, CelnavBodies.VENUS_R)
            Planet.MARS -> Triple(CelnavBodies.MARS_L, CelnavBodies.MARS_B, CelnavBodies.MARS_R)
            Planet.JUPITER -> Triple(CelnavBodies.JUPITER_L, CelnavBodies.JUPITER_B, CelnavBodies.JUPITER_R)
            Planet.SATURN -> Triple(CelnavBodies.SATURN_L, CelnavBodies.SATURN_B, CelnavBodies.SATURN_R)
        }
        var x = 0.0; var y = 0.0; var z = 0.0; var dist = 0.0; var lt = 0.0
        repeat(3) {
            val tp = tau - lt / 365250.0
            val l = vsop(sl, tp); val b = vsop(sb, tp); val r = vsop(sr, tp)
            x = r * cos(b) * cos(l) - ex; y = r * cos(b) * sin(l) - ey; z = r * sin(b) - ez
            dist = sqrt(x * x + y * y + z * z); lt = 0.0057755183 * dist
        }
        var lam = atan2(y, x); var bet = atan2(z, sqrt(x * x + y * y))
        val th = l0 + Math.PI; val e = 0.016708634 - 0.000042037 * t; val per = rad(102.93735 + 1.71946 * t); val k = 20.49552 * AS
        val dl = (-k * cos(th - lam) + e * k * cos(per - lam)) / cos(bet)
        val db = -k * sin(bet) * (sin(th - lam) - e * sin(per - lam))
        lam += dl - 0.09033 * AS; bet += db
        val n = nutation(t)
        return eclToEq(lam + n.dpsi, bet, n.eps, jd, dist)
    }

    fun planetHpArcmin(distAu: Double): Double = 8.794 / 60.0 / distAu

    private fun flat(s: Array<DoubleArray>): DoubleArray {
        val o = DoubleArray(s.size * 3)
        for (i in s.indices) { o[i * 3] = s[i][0]; o[i * 3 + 1] = s[i][1]; o[i * 3 + 2] = s[i][2] }
        return o
    }

    fun sunSemiDiameterArcmin(jd: Double): Double = 959.63 / 60.0 / sunGeometric(tt(jd))[1]

    fun findStar(name: String): NavStar? = STARS.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Apparent place of the date. */
    fun starShaDec(s: NavStar, jd: Double): ShaDec {
        val t = tt(jd); val years = t * 100.0
        val d0 = rad(s.decDeg + s.pmDecMas / 3.6e6 * years)
        val a0 = rad(s.raHours * 15.0) + rad(s.pmRaMas / 3.6e6 * years) / cos(rad(s.decDeg))
        val zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) * AS
        val z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) * AS
        val th = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) * AS
        val aa = cos(d0) * sin(a0 + zeta)
        val bb = cos(th) * cos(d0) * cos(a0 + zeta) - sin(th) * sin(d0)
        val cc = sin(th) * cos(d0) * cos(a0 + zeta) + cos(th) * sin(d0)
        val a = atan2(aa, bb) + z; val d = asin(cc)
        val n = nutation(t); val sun = sunGeometric(t)[0]
        val e = 0.016708634 - 0.000042037 * t; val peri = rad(102.93735 + 1.71946 * t); val k = 20.49552 * AS
        val ca = cos(a); val sa = sin(a); val cd = cos(d); val sd = sin(d); val ce = cos(n.eps); val te = tan(n.eps)
        val da = (ce + sin(n.eps) * sa * sd / cd) * n.dpsi - ca * sd / cd * n.deps -
            k * (ca * cos(sun) * ce + sa * sin(sun)) / cd + e * k * (ca * cos(peri) * ce + sa * sin(peri)) / cd
        val dd = sin(n.eps) * ca * n.dpsi + sa * n.deps -
            k * (cos(sun) * ce * (te * cd - sa * sd) + ca * sd * sin(sun)) +
            e * k * (cos(peri) * ce * (te * cd - sa * sd) + ca * sd * sin(peri))
        return ShaDec(deg(n2pi(-(a + da))), deg(d + dd))
    }

    /** True altitude and azimuth of a body from a position (lon + east). */
    fun bodyAltAz(gha: Double, dec: Double, lat: Double, lon: Double): AltAz {
        val ha = rad(gha + lon); val de = rad(dec); val la = rad(lat)
        val alt = deg(asin(sin(la) * sin(de) + cos(la) * cos(de) * cos(ha)))
        return AltAz(alt, n360(deg(atan2(-sin(ha) * cos(de), sin(de) * cos(la) - cos(de) * sin(la) * cos(ha)))))
    }

    fun dipArcmin(eyeHeightM: Double): Double = if (eyeHeightM > 0) 1.76 * sqrt(eyeHeightM) else 0.0

    /** Mean refraction (Bennett) for the apparent altitude. */
    fun refractionArcmin(apparentAltDeg: Double): Double { val h = maxOf(apparentAltDeg, -1.0); return 1.0 / tan(rad(h + 7.31 / (h + 4.4))) }

    /** Observed altitude from the sextant altitude; index error + when the sextant reads high. */
    fun observedAltitude(sextantAltDeg: Double, indexErrArcmin: Double, eyeHeightM: Double, limb: Limb, semiDiameterArcmin: Double, parallaxArcmin: Double): Double {
        val ha = sextantAltDeg - indexErrArcmin / 60.0 - dipArcmin(eyeHeightM) / 60.0
        val ho = ha - refractionArcmin(ha) / 60.0 + parallaxArcmin * cos(rad(ha)) / 60.0
        return when (limb) { Limb.LOWER -> ho + semiDiameterArcmin / 60.0; Limb.UPPER -> ho - semiDiameterArcmin / 60.0; Limb.CENTER -> ho }
    }

    fun reduceSight(estLat: Double, estLon: Double, gha: Double, dec: Double, observedAltDeg: Double): Sight {
        val c = bodyAltAz(gha, dec, estLat, estLon)
        val a = (observedAltDeg - c.alt) * 60.0
        // great-circle destination from the assumed position along the azimuth (or its reciprocal)
        val brg = rad(if (a >= 0) c.zn() else n360(c.zn() + 180.0)); val dist = rad(abs(a) / 60.0); val la = rad(estLat)
        val lat2 = asin(sin(la) * cos(dist) + cos(la) * sin(dist) * cos(brg))
        val lon2 = rad(estLon) + atan2(sin(brg) * sin(dist) * cos(la), cos(dist) - sin(la) * sin(lat2))
        return Sight(c.alt, c.az, a, deg(lat2), ((deg(lon2) + 540.0) % 360.0) - 180.0)
    }
    private fun AltAz.zn() = az

    /** Advances a line of position by the run between two positions (where it was taken and now): same azimuth. */
    fun advanceSight(s: Sight, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Sight {
        val mid = rad((fromLat + toLat) / 2.0)
        val lat = s.lat + (toLat - fromLat)
        return s.copy(lat = lat, lon = s.lon + (toLon - fromLon) * cos(mid) / cos(rad((s.lat + lat) / 2.0)))
    }

    /** The sight referred to another assumed position: its intercept is the projection of its point on its azimuth. */
    fun referTo(s: Sight, estLat: Double, estLon: Double): Sight {
        val dn = (s.lat - estLat) * 60.0; val de = (s.lon - estLon) * 60.0 * cos(rad(estLat))
        return s.copy(interceptNm = dn * cos(rad(s.zn)) + de * sin(rad(s.zn)))
    }

    /** Least-squares fix from two or more sights around the assumed position; null when they are nearly parallel. */
    fun fixFromSights(estLat: Double, estLon: Double, sights: List<Sight>): Pair<Double, Double>? {
        if (sights.size < 2) return null
        var a = 0.0; var b = 0.0; var c = 0.0; var u = 0.0; var v = 0.0
        for (s in sights) { val cz = cos(rad(s.zn)); val sz = sin(rad(s.zn)); a += cz * cz; b += cz * sz; c += sz * sz; u += cz * s.interceptNm; v += sz * s.interceptNm }
        val det = a * c - b * b
        if (det < 0.03) return null
        val dn = (u * c - v * b) / det; val de = (v * a - u * b) / det
        return Pair(estLat + dn / 60.0, estLon + de / 60.0 / cos(rad(estLat)))
    }
}
