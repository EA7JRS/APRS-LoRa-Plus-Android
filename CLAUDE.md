# APRS LoRa+ for Android — contexto del proyecto

Propietario: Manuel (EA7JRS). Idioma de trabajo: español, respuestas breves y directas.
Los archivos nuevos llevan la cabecera `Copyright (C) 2026 Manuel - EA7JRS` y licencia GPL-3.0-or-later.

## Qué es
App Android compañera del tracker **APRS LoRa+** (firmware ESP32, repositorio `EA7JRS/APRS-LoRa-Plus`, fork de CA2RXU). Es un proyecto aparte, en Kotlin.
Documento de diseño (Claude Docs): https://claude.ai/artifact/HAoogW2ycFTjYfH3jRDCSA

## Regla del proyecto (Manuel, 2026-11-06)
**Todas las funciones del proyecto han de ser controladas por esta app, y la app ha de mostrar todos los datos** (sensores de la T-Beam Supreme y del resto de placas, mensajes APRS, DAPNET...). Cada función nueva del firmware necesita una orden BLE y una pantalla aquí; matriz de cobertura en `docs/APP_COVERAGE.md` (copia del firmware). Mecanismos genéricos del firmware que lo cubren todo desde el principio: `actlist`/`act` (cualquier opción de menú, pestaña Remote), `screen` (espejo de la pantalla del equipo, pestaña Remote), `cfgget`/`cfgset` (toda la configuración, el fichero entero en bruto, pestaña Config).

## Decisiones tomadas
- Enlace principal con el tracker: **BLE con KISS**. Alternativa: KISS por TCP (puerto 8001 por defecto) por WiFi. Bluetooth clásico y BLE TNC2 quedan fuera por ahora.
- **iGate móvil en la primera versión**: solo radio → APRS-IS. Internet → radio queda fuera.
- **Ir a (GO TO) con Google Maps**: solo en la app, no en el tracker. Detalle de estación, y en la notificación de mensaje si la estación tiene posición.
- Mensajería en el móvil: escribir, responder, estado (enviado / confirmado / fallido / rechazado).
- **Control del tracker por BLE** (decidido 2026-11-05): segundo servicio GATT en el firmware con JSON por líneas (`docs/BLE_CONTROL.md`, copia del firmware). Modo de radio con reinicio (APRS se guarda; los demás solo para el siguiente arranque, memoria RTC), perfil de baliza, perfil LoRa, baliza, digipeater, CW, sensores, GNSS, historial y avisos DAPNET, NMEA en bruto.
- **DAPNET**: recibir (lo que capta el tracker en modo DAPNET, también como notificación) y enviar desde el móvil por la API (`POST http://www.hampager.de:8080/calls`, autenticación básica, HTTP sin cifrar; texto máx. 80 ASCII).
- **NMEA**: verlo en la app, servidor TCP (puerto 10110) para otras apps, ubicación simulada; AIS de estaciones APRS lo genera el firmware (placas con `NMEA_OUTPUT`). Sin NMEA del tracker, el móvil genera RMC/GGA.
- **Manipulador en el móvil** (pedido 2026-11-05, modo MORSE del tracker): adaptadores de tipo teclado (VBand, Vail: Ctrl izq. o `[` = punto, Ctrl dcho. o `]` = raya; solo con la app en primer plano), MIDI (Vail: nota 0 elemento ya temporizado, 1 punto, 2 raya; la app pide modo MIDI con CC0=0 y no toca el tipo de manipulador del Vail) y serie USB (usb-serial-for-android 3.10.0 por JitPack (la 3.11.0 trae kotlin-stdlib 2.2); líneas DSR/CTS/CD/RI configurables, DTR y RTS a 1). Manipulador iámbico A/B en el móvil, tono local en el móvil. Tiempo real: orden `k` sin respuesta, el tracker reproduce con retardo fijo (`kcfg`, 150 ms); o texto: el móvil decodifica y manda palabras con `cw`. Las apps de terceros (Morse CW, Morse Code Complete, CW Studio) no sirven de puente: no exponen la manipulación a otras apps.
- **Firmware desde la app** (pedido 2026-11-05): pestaña Firmware. Versión instalada (evento `info`) y publicada (`https://ea7jrs.github.io/APRS-LoRa-Plus/ota/<placa>.json`, generado por `tools/make_web_assets.py` del firmware). Actualización por BLE solo en placas con segunda ranura (`caps.ota`): el móvil descarga y comprueba tamaño y SHA-256, orden `ota`, datos por la característica `...0005` sin respuesta, ventana de 8 KB con confirmación cada 4 KB; el tracker comprueba SHA-256 e identidad de placa, activa y reinicia. La actualización por internet es **trabajo de la app** (decidido por Manuel 2026-11-06): el tracker ya no lleva cliente HTTPS, ni `fwcheck`/`fwinstall`/`fwstatus`, ni actualización automática; la app descarga con el internet del móvil y envía por BLE. Tampoco se usará microSD en el tracker (decisión de Manuel). Instalador web por USB OTG abierto en Chrome (necesita Web Serial; un WebView no lo tiene). Las órdenes de firmware no exigen indicativo validado (no transmiten).
- **Validación del indicativo** (pedido 2026-11-05): el indicativo base debe tener DMR ID / CCS7 en RadioID.net (`https://database.radioid.net/api/dmr/user/?callsign=`). Sin validar la app solo escucha: no tramas APRS, ni iGate, ni DAPNET, ni órdenes al tracker salvo consultas. Resultado guardado; se revisa cada 90 días.
- **Emparejamiento con PIN** (firmware 5.3.4, 2026-11-06): el servicio de control exige enlace cifrado y emparejado (LE Secure Connections, MITM, bonding; el tracker «mostrar solo» con el PIN de *WebUI > Bluetooth > Control PIN*, 123456 por defecto). La app lee la característica *Eventos* (lectura cifrada) tras conectar: Android pide el PIN; si no lo hace, `createBond()`; espera hasta 2 min y relee. Mientras tanto y si falla, el KISS sigue (APRS sin PIN). Botón «Forget pairing» en Connect (`removeBond`, API oculta) para después de cambiar el PIN.
- Android Auto, fase 2: los mensajes entran como notificaciones `MessagingStyle` con acciones de responder y marcar como leído (lo que usan Telegram y WhatsApp). Fase 3 (lista de estaciones con mapa, categoría de puntos de interés) depende de que Google la permita; no confirmado.
- Alcance: uso personal para probar y depurar; publicar en Google Play cuando esté casi perfecta (cuenta de desarrollador, pruebas cerradas, política de privacidad, permisos de Bluetooth y ubicación; comprobar los números actuales en la documentación de Google).
- ACK con el mismo indicativo que el tracker (decidido por Manuel 2026-11-05): el firmware ya confirma los mensajes dirigidos a su propio indicativo (`msg_utils.cpp`, `checkReceivedMessage`), así que la app **no envía ACK cuando su indicativo y SSID coinciden con el del tracker** (campo "Tracker callsign" en Connect; `MessageEngine.trackerSendsAcks`, parámetro `sendAcks`). Vacío o distinto: la app envía los ACK. Sigue mostrando el mensaje y quitando repetidos.

## Datos del firmware que importan
- Servicio BLE KISS: `00000001-ba2a-46c9-ae49-01b0961f68bb`. El móvil **escribe** en `...0002` y el tracker **notifica** en `...0003`.
- Servicio BLE TNC2 (tipo Nordic UART, `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`): al revés que el Nordic estándar; el móvil escribe en `...0003` y el tracker notifica en `...0002`. (No implementado en la app.)
- Las tramas por BLE KISS son AX.25 UI dentro de KISS (FEND `0xC0`, escapes `0xDB 0xDC/0xDD`), en fragmentos del tamaño del MTU.
- Fuente: `src/ble_utils.cpp`, `src/kiss_utils.cpp`, `src/kiss_net.cpp` del repositorio del firmware.
- Destino (tocall) de las tramas que envía la app: `APZLRP` (rango experimental; registrar uno propio al publicar).

## Estado (2026-11-06)
- `core/` (Kotlin puro): KISS, AX.25, TNC2, APRS (posición sin comprimir y comprimida, mensajes, ack, rej), motor de mensajes (3 envíos a 30 s / 60 s / 120 s, duplicados), reglas de iGate, geodesia, tabla de estaciones, JSON mínimo, protocolo de control, DAPNET (petición), NMEA (checksum, RMC/GGA), validación de indicativo (RadioID), telegrafía (manipulador iámbico A/B, decodificador, adaptadores de teclado y MIDI), firmware, SAT, edición de configuración, UBX (A-GPS). **Comprobado**: 298 comprobaciones en `core/src/test/.../SelfTest.kt`.
- `app/` (Android, Compose): servicio en primer plano, cliente BLE, cliente APRS-IS, notificaciones; pestañas Stations, Messages, Tracker, Sensors, DAPNET, Heard, Remote, Config, SAT, Firmware y Connect. **Compila en la CI** (APK de depuración como artefacto de cada ejecución); **sin probar en el móvil**.
- Revisión 2026-11-05, cambios:
  - BLE: un trozo cuya escritura rechaza la pila (ocupada) vuelve a la cola en vez de perderse; las escrituras empiezan tras `onDescriptorWrite`; tipo de escritura según las propiedades de la característica.
  - Ritmo de envío: 3 s entre tramas con firmware sin servicio de control (guarda **una sola** trama del móvil y muestra "BLE Tx" 1 s antes de enviarla; una segunda trama antes de eso pisa la primera) y 1,5 s con firmware que lo tiene (cola de 4).
  - Servicio: `startForeground` protegido (Android 14 exige el permiso de Bluetooth concedido); la notificación del servicio muestra el estado del enlace.
  - Notificaciones: historial sincronizado entre hilos; responder con el enlace parado avisa en vez de dar el mensaje por enviado.
  - `msgs.asReversed()` en lugar de `reversed()` (con compileSdk 35, `reversed()` puede enlazar con el método de Java 21, solo Android 15+).
  - Gradle: plugin foojay (JDK 17 para `core/` aunque la máquina tenga otro JDK), `compose.foundation` explícita, wrapper de Gradle 8.10.2.
- CI: `.github/workflows/build.yml` ejecuta `gradle :core:selfTest` y `gradle :app:assembleDebug` y sube el APK de depuración.
- Repositorio: `EA7JRS/APRS-LoRa-Plus-Android` (privado, creado por Manuel el 2026-11-05); primera subida el mismo día.

- Control, DAPNET, NMEA y validación (2026-11-05): `TrackerBle` con cola GATT general (KISS, órdenes, suscripciones), `Controller` (eventos de control, páginas DAPNET, NMEA), `NmeaServer`, `MockLocation`, `DapnetSender`, `Licence`; pestañas Tracker, Sensors y DAPNET. Lo que no es Compose se comprobó contra `android.jar` de la API 35 (repositorio Reginer/aosp-android-jar, clonado parcial) con simulaciones de AndroidX y corrutinas; las pantallas Compose no se han podido compilar.
- Firmware: el servicio de control está en `main` de `EA7JRS/APRS-LoRa-Plus` desde la 5.3.4 (PR #2 fusionado el 2026-11-06): `src/ble_control.cpp`, `include/ble_control.h`, cola de 4 tramas del móvil en `ble_utils.cpp`, `LoRa_Utils::selectLoraIndex`, `PAGES_Utils::cwSendText`, modo pedido por BLE en `selectMode`, NMEA a BLE en `nmea_link.cpp`. `ble_control.cpp` y `ble_utils.cpp` pasan `g++ -Wall -Werror` en el PC con cabeceras simuladas (NimBLE 2.5.2, ArduinoJson 7.4.3 reales). La CI del firmware compila T-Beam v1.2 y Supreme en cada push a cualquier rama.

## Cobertura total (2026-11-06)
- Pestañas nuevas: **Heard** (estaciones oídas por el tracker, su registro de mensajes APRS y envío de mensajes desde el tracker: `heard`, `msgs`, `msg`), **Remote** (espejo de pantalla + todas las opciones de menú por `act`, con confirmación), **Config** (editor de todo `/tracker_conf.json`: se lee por trozos con `cfgget`, se edita en árbol y se sube entero con `cfgset`; contraseñas ocultas en pantalla). Sensors muestra ya gas / IAQ, IMU, magnetómetro, PMU, carga y corriente.
- Núcleo: eventos `Messages`, `MessageChanged`, `Heard`, `Screen`, `Actions`, `ConfigPiece`; `Control.chunked`/`configSet` (subida por trozos de ≤ 235 bytes, parada y espera con `ok`); `JsonEdit` (edición del árbol de la configuración). 227 comprobaciones.
- Sin probar en el móvil ni con el firmware real. Firmware: `main` de `EA7JRS/APRS-LoRa-Plus` (el servicio de control ya está fusionado; la T-Beam Supreme queda al 99,8 % de la partición: cualquier función grande nueva obliga a recortar o a cambiar la tabla de particiones).
- Pestaña **SAT** (2026-11-06): paso en curso (`sat`), lista de satélites con búsqueda y elección (`satlist`, `satsel`), receptor con Doppler y ajuste fino (`satrx`), transmisores (`sattxlist`), emisión con confirmación (`sattx`), rotor y objetivos fijos (`rot`, `targets`, `tgt`), posición del móvil (`pos`, cada 8 s, solo con la pestaña abierta; permiso de ubicación) y subida de `sats.txt` por BLE (`satdata`, descargado de `Sat.DEFAULT_DATA_URL` o elegido como archivo). Núcleo: `Sat.kt` y eventos `Sat*`, `Rotor`, `Targets`; 275 comprobaciones. `TrackerMode.SAT` = 9. Órdenes de solo escucha / lectura (`sat*`, `satsel`, `satrx`, `pos`) no exigen indicativo validado; `sattx`, `satdata` y `tgt` sí.
- Falta: `att` (móvil como inclinómetro del rotor; depende del montaje) y las sesiones interactivas de CW / WSPR (el firmware aún no las expone); la app usa `Remote` mientras tanto.

- **A-GPS** (pedido 2026-11-06; AssistNow Online/Offline terminaron el 31-05-2026): botón «Refresh phone A-GPS» en SAT (`sendExtraCommand` `force_time_injection` / `force_xtra_injection`, permiso `ACCESS_LOCATION_EXTRA_COMMANDS`); al u-blox del tracker (orden `gpsaid`, `caps.gpsaid`, firmware bae8dd7): hora y última posición real del móvil (UBX-MGA-INI-TIME_UTC / POS_LLH, sin cuenta) y Predictive Orbits de u-blox (gratis en M10, cuenta Thingstream; dirección con token en Sensors > A-GPS, se guarda una copia y se descarga como mucho cada 12 h; 2 descargas al día). `Ubx.kt` en el núcleo, `GpsAid.kt` en la app; `gpsaid` no exige indicativo validado. La posición del móvil para SAT ignora ubicaciones simuladas (la nuestra es la del tracker). Falta: cuenta Thingstream de Manuel y probar en la Supreme.

## Pendiente
- Probar el emparejamiento con PIN en el móvil (diálogo de Android, PIN equivocado, cambio de PIN y «Forget pairing»).
- Firmware por BLE: medir el tiempo de una actualización completa en la Supreme y probar la cancelación y la pérdida de enlace a mitad.
- Manipulador en el móvil: probar retardo y fidelidad con VBand, Vail (teclado y MIDI) y un adaptador serie.
- Android Auto: las notificaciones DAPNET no tienen acción de responder (POCSAG no lleva remitente); comprobar si el coche las muestra.
2. Probar en el móvil con el T-Beam Supreme (BLE KISS activado en el WebUI del tracker): conexión, recepción, envío y ACK de mensajes.
3. Posiciones Mic-E (muy usadas por otros equipos), objetos, meteorología, telemetría.
4. Guardar mensajes y estaciones entre sesiones.
5. Distancia y rumbo desde la posición propia; avisos de balizas de estaciones favoritas y emergencias (SOS / EAI) con **Ir a**.
6. Balizas de posición desde el móvil.
7. Comprobar en un coche o con el emulador de Android Auto cómo aparece la app y que la respuesta por voz llega al tracker.
8. Lista de estaciones en el coche (si Google lo permite).

## Cómo trabajar
- Núcleo sin Android: se puede compilar y ejecutar en cualquier sitio con JDK y `kotlinc`. En la sesión de la nube no hay acceso a Maven, Google ni Gradle; el compilador de Kotlin se obtuvo de `https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip`.
  `kotlinc core/src/main/kotlin core/src/test/kotlin -include-runtime -d core-test.jar && java -cp core-test.jar es.ea7jrs.aprslorap.core.SelfTestKt`
- La app Android solo se puede compilar donde haya Android SDK (GitHub Actions o Android Studio).
- No publicar nada ni crear repositorios sin que lo pida Manuel.
