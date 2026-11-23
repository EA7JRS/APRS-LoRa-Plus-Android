# APRS LoRa+ para Android — manual de usuario

Copyright (C) 2026 Manuel - EA7JRS. GPL-3.0-or-later. Versión **5.4.8** (la app Android ya lleva su propia versión; el resto del ecosistema va por la 5.4.7). Antes coincidía con el tracker, la caja del rotor, la
Display y el plotter).

La app es la compañera del tracker **APRS LoRa+**: enseña todo lo que el tracker oye y mide, y maneja todas sus funciones.
También sirve sola como herramienta de radio (receptores SDR, modos digitales, DX Cluster, libro de guardia, mapas, avisos) y
maneja la caja **APRS LoRa+ Rotor**. Las pantallas de la app están en inglés; aquí se citan con su nombre en inglés.

Guía rápida: [GUIA_RAPIDA.md](GUIA_RAPIDA.md). Inglés: [USER_MANUAL_EN.md](USER_MANUAL_EN.md). Protocolo BLE del tracker:
[BLE_CONTROL.md](BLE_CONTROL.md). Qué pantalla de la app cubre cada función del tracker: [APP_COVERAGE.md](APP_COVERAGE.md).

**Estado**: compila en la CI (APK de depuración y AAB de Google Play como artefactos) y está en las pruebas internas de
Google Play; el núcleo en Kotlin pasa sus comprobaciones. La mayoría de las funciones están **sin probar todavía en un
móvil** con equipos reales.

## 1. Primer arranque

1. **Bienvenida.** Escribe tu indicativo y tu DMR ID / CCS7 (7 cifras) y pulsa *Validate and enter*: la app comprueba en
   RadioID.net que ese ID es de ese indicativo. Se guarda y se vuelve a comprobar cada 90 días.
   *Listen only* entra sin validar: la app solo escucha (sin tramas APRS, iGate, DAPNET, spots ni subidas, y sin órdenes al
   tracker salvo consultas).
2. **Permisos.** Bluetooth (para el tracker), ubicación (posición para satélites, tiempo y avisos) y notificaciones (mensajes
   y alertas). Los demás se piden cuando hace falta (micrófono, USB).
3. **Une el tracker**: menú lateral > *Connect & settings* (apartado 2).

## 2. Enlaces

- **Tracker por BLE.** *Connect* busca; elige tu tracker. La app usa el servicio KISS para el APRS y el de control para lo
  demás. El de control pide emparejar con el PIN del tracker (WebUI > Bluetooth > *Control PIN*, de serie `123456`): Android lo
  pide la primera vez. Si cambias el PIN en el tracker, *Forget pairing* y vuelve a unir.
- **Tracker callsign** (Connect): si es el mismo que el tuyo, los ACK los manda el tracker y la app no.
- **Extra receivers**: más trackers solo como receptores (BLE KISS o KISS por TCP, puerto 8001), con su nombre en el registro.
- **Caja del rotor** (herramienta Rotor): BLE o WiFi (TCP 4534) con el PIN de la caja.
- **Radio (CAT)**: una emisora enchufada al móvil por USB (apartado 9).

El tracker admite un solo equipo de control a la vez: mientras la app está unida, la Display o el plotter esperan.

## 3. La interfaz tipo emisora

La pantalla principal se maneja como una emisora móvil: dos bandas, teclas táctiles y la tecla **[F]**.

- **Banda A (verde)**: el tracker: modo, frecuencia, perfil, S-meter de la última estación oída, luces TX / RX / MSG / EMG.
- **Banda B (cian)**: la emisora bajo CAT (en el móvil o en la caja del rotor) o el SDR.
- Casillas **BEACON / iGATE / ROTOR** y las últimas estaciones oídas.
- **[F]**: tres páginas de teclas. *APRS*: S.LIST, MSG, BEACON, BCN, GPS, LOG, IGATE, DAPNET, WX, MAP, DISP, HEARD.
  *RADIO*: DX, RBN, IBP, SAT, ROTOR, CAT, SDR, MODES, CWKEY, BEACONS, AIS, ADSB. *SYSTEM*: REMOTE, CONFIG, FIRMWARE,
  CONNECT, SENSORS, SIGID, RXPLUS, ABOUT, ALERTS, SPOTS, ASTRO.
- **Teclas táctiles**: mantén pulsada una tecla del menú [F] para ponerla en una de las cuatro junto a [F].
- **Menú lateral** (☰): todas las pantallas y herramientas, el tema (claro, oscuro, el de Android, automático = claro de la
  salida a la puesta del sol), las unidades (métrico, náutico, imperial) y las opciones de las alertas de emergencia
  (sonido, vibración, linterna, pantalla completa).
- **DISP**: brújula, cielo GPS (del tracker o del móvil: satélites por constelación, barras de SNR, errores), altitud y reloj
  con sol, crepúsculo y luna (fase verdadera), y un **almanaque**: próxima fase de la luna, perigeo o apogeo, marea de
  equilibrio (orienta; no es la predicción de ningún puerto), próximos eclipses de Luna y de Sol, próximo equinoccio o
  solsticio, distancia del Sol e índice UV de cielo despejado, y el rebote lunar (EME) en 432 MHz: pérdida, eco y Doppler.

## 4. APRS

- **S.LIST / ficha**: estaciones oídas (por el tracker y por APRS-IS) con distancia, rumbo, símbolo, velocidad, altitud,
  tiempo y estado Mic-E. La ficha tiene brújula (norte arriba / rumbo arriba), **GO TO** (ruta en Google Maps), MESSAGE y
  **ROTOR**. Admite objetos e ítems, tramas de terceros y objetos borrados.
- **MSG**: conversaciones (RX / TX / PAGER), estado de cada mensaje (enviado, confirmado, fallido, rechazado), tres envíos,
  sin repetidos, *TxCLR* cancela los pendientes. Los mensajes llegan como notificaciones con responder y marcar como leído,
  que es lo que lee en voz alta Android Auto.
- **IGATE**: iGate móvil, solo radio → APRS-IS (`qAO`, de solo recepción). Pasa cada trama tal cual llega, sin quitar espacios ni bytes; no descarta duplicados (lo hacen los servidores) y no guarda lo oído con la conexión caída para mandarlo después. Una sola conexión, a `rotate.aprs2.net` por defecto. Delante de cada trama manda, si los sabe, RSSI, SNR, modem, velocidad y frecuencia de la recepción en una línea `#is2-meta` (los servidores sin IS2 la toman por un comentario).
- **WX**: estaciones meteorológicas APRS con viento y Beaufort, rocío, sensación, base de nubes y tendencia de la presión
  (3 h), ordenadas por distancia, hora, temperatura o viento.
- **HEARD**: las estaciones que oye el propio tracker y su registro de mensajes; enviar un mensaje desde el tracker. *QSY*
  para las que anuncian frecuencia (T-TWR).
- **DAPNET**: avisos recibidos por el tracker en modo DAPNET, y avisos enviados desde el móvil por la API de DAPNET (con
  cuenta; hasta 80 caracteres ASCII).
- **NMEA**: servidor TCP (puerto 10110) con las sentencias del tracker para otras apps (OpenCPN, Navionics, el plotter), las
  estaciones APRS como blancos AIS, y el GNSS del tracker como ubicación del móvil (ubicación simulada).

## 5. Mapa

MAP: OpenStreetMap, IGN Base, ortofoto PNOA, MTN, oscuro, Esri (con tu clave de ArcGIS) y OpenSeaMap, con estaciones, barcos
AIS, aviones, tu posición y el haz del rotor. Capas encima, cada una con su transparencia: avisos oficiales, terremotos, línea
día / noche, nubes del Meteosat, mapas de OpenWeather (con tu clave; como mucho 30 teselas por minuto), radar y rayos de
AEMET, METAR / TAF y SIGMET, auroras, D-RAP, ciclones tropicales, cuadrícula Maidenhead, zonas CQ / ITU, círculo máximo,
balizas IBP, locators trabajados, quién me oye por WSPR y PSK Reporter, globos (SondeHub), incendios, inundaciones, boyas,
luces de las ciudades y las balizas oídas por el SDR. Tocar una marca abre su ficha.

## 6. Control del tracker

| Herramienta | Qué hace |
|---|---|
| **Tracker** | Modo de radio (APRS LoRa / FSK, Morse, WSPR, FT8, FT4, FST4W, LR-FHSS, DAPNET, SAT, M17, FM, AIS; el tracker se reinicia), perfiles de baliza y LoRa, baliza ahora, digipeater, mensajes CW, escucha CW, radio FM de la T-TWR, radio comercial |
| **Sensors** | Batería, temperatura, humedad, presión, QNH, altitud barométrica, gas / IAQ, IMU, magnetómetro, PMU; sensores del móvil (inclinación, brújula con la declinación del WMM2025, barómetro); A-GPS para el u-blox del tracker; tiempo de internet enviado al tracker |
| **Satellites** | Paso en curso, lista con grupos y favoritos, receptor con Doppler, transmisores, emisión con confirmación, rotor y objetivos fijos (también *Repeaters near me* con RepeaterBook Connect), subida de la lista de satélites, servidor rotctld (puerto 4533) para Look4Sat / GPredict |
| **Remote** | Espejo de la pantalla del tracker y todas las opciones de su menú |
| **Config** | Toda la configuración del tracker en árbol: leer, editar, guardar; copiarla a un archivo y cargarla de nuevo |
| **Firmware** | Versiones instalada y publicada, firmware oficial o modificado, actualización por Bluetooth (placas con segunda ranura) y el instalador web |

## 7. Caja del rotor (herramienta Rotor)

Esfera con el estilo de sat-aprs (azul = posición, ámbar = objetivo, verde = rumbo del tracker cuando va a más de 3 km/h,
rojo = topes), ir a, mover manteniendo pulsado, STOP, aparcar, borrar fallo, elevación, calibración del control directo y la
configuración entera de la caja. **Follow**: la puntería del tracker, una estación APRS que oye el tracker (con estimación
si se mueve) o una baliza CW con locator; *Track sun / moon*. **Radio (CAT)** de la caja con S-meter, split, tono y el
Doppler del satélite. **Viento**: el viento de internet va a la caja, que aparca con rachas fuertes.

## 8. Herramientas de radio

| Herramienta | Qué hace |
|---|---|
| **DX** (DX Cluster) | Nodo telnet (de serie EA4URE), filtros por origen (personas, RBN CW / digital, los míos), banda, modo y actividad (POTA, SOTA, IOTA, WWFF, DME); banderas; TUNE al SDR y al CAT; ROTOR por el camino corto o largo; SPOT; LOG |
| **RBN** | Mapa del mundo con los avisos de la Reverse Beacon Network por el cluster, avisos de VHF / UHF |
| **IBP** | Las 18 balizas NCDXF / IARU, las 5 que emiten ahora con su círculo máximo, rejilla de lo oído, TUNE y ROTOR |
| **SDR** | SDR++ y gqrx (rigctl), SDRconnect (también su audio), SpyServer y el receptor RTL-SDR propio de la app (USB OTG) en WFM / NFM / AM / BLU / CW, con **panadapter** (espectro + cascada de toda la banda, tocar o arrastrar para sintonizar), Doppler y grabación; **voz digital** con el botón **DV** junto a los modos (la variante DMR, D-STAR, YSF, P25, NXDN, dPMR, M17 se elige debajo); APRS 9600 (G3RUH); lista de VOR; radiobalizas de 406 MHz. El nivel del receptor se muestra en dBFS (relativo; sin calibrar), con ganancia manual de serie (40 %) para que no sature |
| **MODES** | PSK31…250, RTTY, CW, FT8, FT4, WSPR, FST4W, NAVTEX y LSD (canal 70 de VHF, MF / HF), del receptor de la app o del micrófono |
| **SPOTS** | PSK Reporter, WSPRnet, UDP de WSJT-X (GridTracker, JTAlert…) y un puerto telnet como el de CW Skimmer |
| **LOG** | Libro de guardia (ADIF), importar / exportar, QRZ.com, LoTW (firmado en la app con tu .p12), eQSL y World Radio League |
| **CAT** | Emisora por USB (apartado 9) |
| **CWKEY** | Manipulador en el móvil (VBand, Vail, MIDI, serie USB), iámbico A / B, al tracker en modo MORSE |
| **BEACONS** | Balizas oídas por el SDR: LR-FHSS, APRS FSK, M17, WSPR, FST4W, FT8, FT4, CW; mapa y spots al cluster |
| **AIS** | Barcos (T-TWR o módulo AIS), los peligrosos primero, alarmas, GO TO |
| **ADSB** | Aviones del RTL-SDR o de un receptor dump1090 / readsb, con rumbo y elevación para el rotor |
| **SIGID** | Identificación de señales (base de Artemis, descarga opcional) |
| **ASTRO** | Navegación astronómica (apartado 10) |

## 9. Emisora por CAT en el móvil

Yaesu (de texto: FT-991 / 991A, FT-891, FT-710, FTDX; de 5 bytes: FT-817 / 818 / 857 / 897), Icom CI-V y Kenwood / Elecraft
por un adaptador serie USB (también la SCU-17). Frecuencia, modo, split y Doppler del satélite del tracker. El PTT viene
apagado, solo funciona manteniendo pulsado y con el indicativo validado. La emisora bajo CAT (la del móvil primero, si no la
de la caja) es la que usan TUNE del DX Cluster, IBP y los spots.

## 10. Navegación astronómica (Sextant)

Sol, Luna, Venus, Marte, Júpiter, Saturno y 157 estrellas con precisión de almanaque. *Visible*: lo que hay ahora en el cielo
y las tres estrellas que mejor se cruzan. Correcciones (error de índice, depresión, refracción, semidiámetro, paralaje),
diferencia de alturas y azimut, hasta cuatro rectas trasladadas por lo navegado y la situación. Latitud por la meridiana y
por la Polar con la hora del paso por el meridiano. Corrección total de la aguja con un astro, desvío y tablilla de desvíos.

*Current and course to steer*: con el rumbo efectivo deseado, la velocidad en el agua, el abatimiento (+ a estribor) y la
corriente da el rumbo verdadero a gobernar, la corrección, la velocidad efectiva y el rumbo de aguja (declinación del WMM y
tablilla). *Fix from two bearings*: situación por dos demoras (simultáneas o trasladadas) a dos puntos de posición escrita (o el mismo dos veces), con NOW en cada demora, rumbo y velocidad efectivos y Ct.

*Mark departure* y, pasado un rato gobernando a rumbo y velocidad constantes, *From DR*: la corriente por la
diferencia entre la estima y la situación del GNSS del tracker.

## 11. Alertas

- **Ventana de emergencia** sobre cualquier pantalla: balizas y mensajes APRS de emergencia, Mic-E «Emergency», peligro AIS y
  riesgo de abordaje, EAI y hombre al agua del tracker, DSC DISTRESS y 406 MHz DISTRESS BEACON. El móvil puede sonar, vibrar
  y encender la linterna; en Android Auto cada alerta llega como conversación (responder, marcar como leída, ir a).
- **Avisos oficiales** (ALERTS): AEMET (con polígono), MeteoAlarm, NWS, tsunami.gov, terremotos (USGS, EMSC, ShakeNet de Raspberry
  Shake), tiempo espacial del SWPC (escalas, Kp, flujo solar, número de manchas SSN, viento solar en tiempo real con densidad y
  presión, y una estimación de HF: MUF, LUF y estado de las bandas, con las fórmulas de sat-aprs; la absorción por rayos X solo de
  día, como el D-RAP de SWPC) y SAME de NOAA Weather Radio (del tracker o del RTL-SDR de la app), cada 15 min; también se mandan
  al tracker.

## 12. Mi barco (AIS)

Nombre, MMSI, distintivo marítimo (comprobado en la página de MarineTraffic o a mano) y medidas para el transponder APRS LoRa+
MAPRS o un tracker con el perfil náutico AIS.

## 13. Android Auto

Los mensajes y las alertas llegan como notificaciones de conversación: el coche los lee en voz alta y contestas con la voz.

## 14. Novedades del 23-11-2026

- **Ficha de estación:** tarjeta *LAST PACKET* con la línea tal como llegó (en colores), cada campo explicado y el recorrido (emisor, digipeaters, iGate, tu tracker con dBm y SNR); tarjeta *TELEMETRY* (informes `T#` con los nombres, unidades y ecuaciones que la estación anuncia; bits como luces) y el estado `>` con su antigüedad. Velocidad y rumbo con «~» cuando se calculan de dos posiciones; las estaciones meteorológicas no muestran velocidad ni rumbo (ese campo es el viento).
- **Mapa:** huella de las estaciones que se mueven (últimas 3 h), trazado del recorrido de la trama de la estación elegida (estación → digipeater o iGate → tu tracker) y los coches en movimiento dibujados girados al rumbo.
- **Entre sesiones:** estaciones (con huella), y conversaciones vuelven al abrir la app; las estaciones de más de 24 h no. Un mensaje que esperaba confirmación vuelve como no confirmado.
- **Mensajes:** boletines y mensajes de grupo (BLN, NWS, ALL, CQ…) se muestran y nunca se confirman; 5 intentos (30 s, 60 s, 2 min, 5 min, 15 min).
- **Baliza de posición desde el móvil** (*Connect > Position beacons from the phone*): con el GPS del móvil y tu indicativo, por el tracker; SmartBeaconing (30 min parado, 90 s a 100 km/h, giros) o intervalo fijo; símbolo y comentario. Solo con indicativo validado y el tracker conectado. Si el indicativo y el SSID son los del tracker, usa otro SSID: el tracker ya baliza con ese.
- **Config:** *Configuration check* con las mismas reglas que la WebUI del tracker (NOCALL, indicativos, ruta, frecuencias del plan de la IARU, contraseña del WiFi AP, PIN de control, enlaces KISS), con correcciones seguras; al guardar con errores pregunta antes.
- **Remote:** ◀ ▶ y *Back* hacen lo mismo que el botón del equipo (necesita el firmware 5.4.8 rehecho o posterior).
- **Official alerts:** Hp30 de GFZ (cada media hora) en la estimación de HF y en los avisos de tormenta geomagnética, Kp previsto, índice A y Dst.
- **NAVTEX y Wefax:** mensajes de un receptor Navtex de la red (TCP / UDP), sin borrado a mano (OMI MSC.508(105)); cartas, horario con fiabilidad y predicción de un circuito de HF del programa Wefax.
