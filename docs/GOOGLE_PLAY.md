# Google Play — trámites de APRS LoRa+

Copyright (C) 2026 Manuel - EA7JRS · GPL-3.0-or-later. Comprobado con la documentación de Google el 2026-11-10; los requisitos cambian, revísalos en Play Console antes de cada paso.

## Estado

| Paso | Quién | Estado |
|---|---|---|
| Objetivo Android 16 (API 36) | app | hecho (AGP 8.11.1, Gradle 8.13) |
| Paquete `.aab` firmado en la CI | app | hecho; falta la clave de subida en los secretos |
| Versión de Google Play sin voz digital (mbelib) | app | hecho (`-Palp.play=true`) |
| Botón para permitir alertas a pantalla completa | app | hecho (menú lateral > Emergency alerts) |
| Política de privacidad | tracker | `web/privacy.html` → `https://ea7jrs.github.io/APRS-LoRa-Plus/privacy.html` al publicar la release del tracker; contacto ea7jrs.manuel@gmail.com |
| Cuenta de desarrollador | Manuel | creada y verificada el 2026-11-10 (nombre de desarrollador «EA7JRS»), datos de desarrollador completos |
| Clave de subida y secretos | Manuel | pendiente |
| 12 probadores 14 días | Manuel | pendiente |
| Vídeo del servicio en primer plano | Manuel | pendiente |

## 1. Cuenta de desarrollador (Manuel)
1. [play.google.com/console](https://play.google.com/console) con tu cuenta de Google → cuenta **personal** → pago único de 25 USD.
2. Verificación de identidad (documento) y del teléfono / correo de contacto que verán los usuarios.
3. Las cuentas personales creadas después del 13-11-2023 necesitan una **prueba cerrada con 12 probadores como mínimo, apuntados sin interrupción 14 días**, antes de pedir el acceso a producción.

## 2. Clave de subida (Manuel, en tu PC; no la subas al repositorio)
```
keytool -genkeypair -v -keystore aprslorap-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```
Guarda el `.jks` y las contraseñas en lugar seguro (si se pierde, Google permite cambiar la clave de subida, no la de firma).
En GitHub: `EA7JRS/APRS-LoRa-Plus-Android` > Settings > Secrets and variables > Actions > New repository secret:

| Secreto | Valor |
|---|---|
| `ALP_UPLOAD_KEYSTORE` | el `.jks` en base64 (`base64 -w0 aprslorap-upload.jks` en Linux; `certutil -encode` en Windows y quitar las líneas BEGIN/END) |
| `ALP_UPLOAD_STORE_PASSWORD` | contraseña del almacén |
| `ALP_UPLOAD_KEY_ALIAS` | `upload` |
| `ALP_UPLOAD_KEY_PASSWORD` | contraseña de la clave |

Con eso cada ejecución de la CI deja el artefacto **aprs-lora-plus-play-aab** (`app-release.aab`). En Play Console activa **Play App Signing** (Google guarda la clave de firma final).

## 3. Crear la app en Play Console
- Nombre: **APRS LoRa+** · Idioma por defecto: inglés (EE. UU.); traducción al español · App · **De pago** (decidido por Manuel el 2026-11-10; una app publicada gratis no puede pasar a ser de pago, al revés sí).
- Categoría: **Comunicación** (o Herramientas). Etiquetas: radioafición, APRS.
- Contacto: ea7jrs.manuel@gmail.com (lo verán los usuarios). Política de privacidad: la dirección de arriba.

### Textos de la ficha
**Descripción breve (≤ 80)**
- EN: `APRS LoRa+ tracker companion: messages, stations, map, alerts, rotor and more.`
- ES: `App del tracker APRS LoRa+: mensajes APRS, estaciones, mapa, alertas y rotor.`

**Descripción completa (EN)**
```
APRS LoRa+ is the companion app for the APRS LoRa+ tracker (ESP32 LoRa, FSK and FM boards) and its family of devices.
It is made for licensed radio amateurs.

• Radio-style screen: tracker band with S-meter, TX/RX lights, CAT or SDR band, beacon, iGate and rotor status.
• APRS messages with delivery status, replies from notifications and Android Auto.
• Stations heard, map with OpenStreetMap, IGN and sea charts, weather radar, range rings and rotor beam.
• Emergency alerts: APRS emergency beacons and messages, AIS distress and collision, man down and EAI.
• Full control of the tracker over Bluetooth with PIN: modes, profiles, sensors, GNSS sky view, satellites with Doppler,
  DAPNET, CW, AIS, configuration and firmware updates.
• Rotor box, DX cluster with tuning, SDR receivers, digital modes (CW, RTTY, PSK, FT8, FT4, WSPR, FST4W), logbook with
  QRZ, LoTW and eQSL.

A licensed amateur radio callsign is needed to transmit: the app checks it in RadioID.net and only listens until then.
Open source (GPL-3.0). No ads, no tracking.
```
**Descripción completa (ES)**
```
APRS LoRa+ es la app compañera del tracker APRS LoRa+ (placas ESP32 con LoRa, FSK y FM) y de su familia de equipos.
Está pensada para radioaficionados con licencia.

• Pantalla tipo emisora: banda del tracker con S-meter, luces TX/RX, banda CAT o SDR, estado de baliza, iGate y rotor.
• Mensajes APRS con estado de entrega y respuesta desde las notificaciones y Android Auto.
• Estaciones oídas y mapa con OpenStreetMap, IGN y cartas náuticas, radar meteorológico, círculos de alcance y haz del rotor.
• Alertas de emergencia: balizas y mensajes APRS de emergencia, socorro y colisión AIS, hombre caído y EAI.
• Control completo del tracker por Bluetooth con PIN: modos, perfiles, sensores, cielo GNSS, satélites con Doppler,
  DAPNET, CW, AIS, configuración y actualización del firmware.
• Caja del rotor, cluster DX con sintonía, receptores SDR, modos digitales (CW, RTTY, PSK, FT8, FT4, WSPR, FST4W) y libro de
  guardia con QRZ, LoTW y eQSL.

Para transmitir hace falta un indicativo de radioaficionado: la app lo comprueba en RadioID.net y hasta entonces solo escucha.
Código abierto (GPL-3.0). Sin anuncios ni seguimiento.
```

**Gráficos**: icono `docs/play/icon-512.png` (512 × 512, PNG sin transparencia) e imagen destacada `docs/play/feature-1024x500.png` (1024 × 500, PNG sin transparencia), hechos con el logo de la bienvenida; al menos 2 capturas de teléfono (del móvil cuando funcione, o de las maquetas).

## 4. Contenido de la app (Play Console > Política > Contenido de la app)
- **Anuncios**: no.
- **Acceso a la app**: todas las funciones sin cuenta; para transmitir hace falta indicativo validado → explicar a los revisores que sin tracker la app abre en modo escucha («Listen only» en la bienvenida).
- **Clasificación de contenido**: cuestionario IARC; utilidad / comunicación, sin contenido sensible (los mensajes son entre usuarios: responder «sí» a interacción entre usuarios).
- **Público objetivo**: 18+ (radioaficionados con licencia).
- **Seguridad de los datos** (propuesta; Google considera «recogido» lo que sale del móvil):
  - Ubicación aproximada y precisa: **sí**, compartida con servicios a petición del usuario (tiempo de internet, APRS por radio / APRS-IS); no obligatoria; no vendida; cifrada en tránsito salvo APRS-IS y DAPNET (sin cifrar por el protocolo).
  - Información personal (nombre de usuario = indicativo): **sí** (RadioID, APRS-IS, QRZ, LoTW, PSK Reporter, WSPRnet).
  - Mensajes (otros mensajes in-app): **sí**, mensajes APRS y DAPNET enviados por el usuario.
  - Audio, fotos, contactos, salud, finanzas, identificadores del dispositivo, analítica: **no**.
  - Los datos se pueden borrar desinstalando o borrando los datos de la app (no hay cuenta del desarrollador).
- **Permisos sensibles**:
  - **Servicio en primer plano «connectedDevice»**: descripción «Keeps the Bluetooth link with the user's APRS LoRa+ tracker while the screen is off, to receive APRS messages, emergency alerts and to relay the user's replies»; impacto si se interrumpe: «Messages and emergency alerts from the radio would be lost»; **vídeo** (ver abajo).
  - **USE_FULL_SCREEN_INTENT**: no es app de llamadas ni de alarmas → declarar que la usa para **alertas de emergencia** (balizas y mensajes de socorro, AIS-SART, hombre caído); la app la pide al usuario y funciona sin ella.
  - **ACCESS_MOCK_LOCATION**: función opcional que da la posición del GNSS del tracker a otras apps; si Google la rechaza, se quita de la versión de Google Play.
  - **Ubicación**: solo en primer plano (no hay `ACCESS_BACKGROUND_LOCATION`).

### Vídeo del servicio en primer plano (Manuel, 30–60 s, se sube a YouTube como «no listado»)
1. Abrir la app → menú lateral → Connect → buscar → tocar el tracker (emparejamiento con PIN dentro de la app).
2. Volver a la pantalla Radio: el tracker conectado, una estación recibida (luz RX).
3. Bloquear la pantalla, mandar un mensaje APRS al tracker desde otra estación → la notificación llega con la pantalla apagada.
4. Responder desde la notificación.

## App de pago (Manuel, 2026-11-10)
- **Perfil de pagos** en Play Console (*Configuración > Perfil de pagos*): cuenta de comerciante de Google con cuenta bancaria y datos fiscales; sin él no se puede poner precio.
- **Estado de comerciante (UE)**: vendiendo, se declara «comerciante» y Google publica en la ficha dirección, teléfono y correo de contacto.
- **Precio**: por país (Google convierte); el IVA de la UE lo cobra e ingresa Google. Los ingresos tributan en tu IRPF: consultar con un gestor.
- **Probadores**: en las pruebas internas y cerradas la instalan gratis.
- **GPL-3**: se puede vender, pero quien la compre tiene derecho al código fuente y a redistribuirlo; con el repositorio privado hay que ofrecerlo (enlace o petición por correo). Precedente: APRSdroid es GPL y se vende en Google Play.
- **Servicios de terceros con condiciones no comerciales** (revisar o quitar en la versión de pago):
  - RainViewer: solo uso personal y educativo → quitar el radar de RainViewer o pedir licencia comercial; AEMET OpenData sí permite reutilización comercial con atribución.
  - Teselas de tile.openstreetmap.org: la política de uso de la OSMF no admite apps con muchos usuarios → proveedor de teselas propio o de pago (o IGN, CC BY 4.0).
  - Base de señales de Artemis (sigidwiki): sin licencia explícita.
  - RepeaterBook, RadioID.net, DAPNET, APRS-IS: comprobar sus condiciones para apps de pago.
  - Símbolos APRS de aprs.fi (hessu/aprs-symbols) y logotipos de marcas entre ellos.

## 5. Pruebas y producción
1. **Prueba interna** (hasta 100 personas, sin revisión larga): subir el `.aab`, probar tú.
2. **Prueba cerrada**: lista de correo con **12 probadores o más** (radioaficionados del grupo, URE, amigos); que la instalen desde el enlace de Play y la mantengan **14 días**. Responder a lo que digan (Google pregunta cómo fue la prueba).
3. **Solicitar acceso a producción** con las respuestas del cuestionario → revisión de Google (unos días) → publicar.

## 6. Antes de producción (pendiente en la app)
- Símbolos APRS: algunos son logotipos de marcas → sustituir o quitar en la versión de Google Play.
- Destino APRS `APZLRP` (experimental) → pedir uno propio en el registro de tocalls de APRS (aprs.org / hessu `aprs-deviceid`).
- Android Auto con plantillas (si se hace): solo en pruebas internas o cerradas.
- Revisar licencias de lo incluido (`About`): ft8_lib (MIT), wsprd y FST4W (GPL-3), osmdroid (Apache 2.0), fuentes OFL, BouncyCastle (MIT).
