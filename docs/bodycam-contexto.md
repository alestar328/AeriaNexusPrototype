# Bodycam W1 — memoria historica y contexto para el port (fase 4)

Documento de referencia para portar la conexion y el control de la bodycam a
Aeria Nexus (Kotlin). Consolidado el 2026-07-09 a partir del codigo real y de
las memorias de desarrollo de los dos proyectos originales:

- App del telefono (Flutter): `C:\Users\newge\Desktop\Nueva carpeta (3)\BodyCam\Falcon-One-Demo-main\Falcon-One-Demo-main`
- App de la bodycam (Kotlin): `C:\Users\newge\Desktop\Nueva carpeta (3)\BodyCam\BodyCamServer`
  (rutas actualizadas 2026-07-10; en esa misma carpeta estan los APKs del fabricante MCP_*/Wear*)

---

## 1. Hardware

| Dato | Valor |
|---|---|
| Dispositivo | YIMAO W1, nombre BT `DSJ-ZXAN9A1` |
| Plataforma | UNISOC SL8541E (SHARKLE), Android 9, BT 4.2 |
| MAC Bluetooth | `40:45:DA:44:C8:9B` (verificado con `adb shell settings get secure bluetooth_address`) |
| MAC WiFi | `40:45:da:32:d6:92` |
| Serial adb | `30393016471440` |

Leccion historica: durante semanas se uso un MAC equivocado (`40:45:DA:9E:5F:4E`)
y `connect()` expiraba a los 13 s con `read ret: -1`. Si la conexion BT falla
con timeout, verificar el MAC contra el dispositivo real antes de tocar codigo.

La app del proveedor `com.smarteye.mcu` es el launcher HOME del equipo y es
priv-app de facto (permisos de plataforma, escribe en /sys). No se puede
desinstalar sin root ni reemplazar sin la platform key del OEM. La estrategia
adoptada es COEXISTIR: BodyCamServer captura los botones por broadcast en
paralelo, sin tocar a smarteye.

## 2. Arquitectura de transportes (decision 2026-06-02, vigente)

Cada cosa viaja por un canal distinto; no mezclarlos:

- **Bluetooth RFCOMM** — SOLO comandos y control (conectar, botones BTN_*,
  STATUS). Nunca video: el ancho de banda no da.
- **Agora** — video EN VIVO. La bodycam publica como broadcaster con
  **uid fijo 9001** en el canal `falcon_group_channel` (el mismo de los
  telefonos). Tambien es la via del data stream GPS/SOS entre telefonos.
- **HTTP sobre WiFi** — el archivo `.mp4` grabado: la bodycam lo sube a Nexus
  (`UploadService`) y el telefono puede descargarlo del servidor HTTP de la
  bodycam (puerto 8080). No es tiempo real.

El BT ha sido historicamente inestable (errores `acceptLoop error: -1`).
Los botones fisicos NO dependen del BT; la deteccion del SOS de la bodycam por
Agora (uid 9001 entra al canal) es la via CONFIABLE, y las notificaciones BT
son solo el camino rapido.

## 3. Botones fisicos de la W1 (VERIFICADO por logcat x3 — definitivo)

El firmware emite el broadcast `android.intent.action.SIDE_KEY_INTENT` con
extras `key_code` y `key_status`. Es un broadcast parallel/background sin
permiso: cualquier app lo recibe, incluso en segundo plano.

| key_code | Tecla | Boton fisico | Accion en BodyCamServer | Notificacion BT |
|---|---|---|---|---|
| 132 | F2 | PTT / audio | toggle microfono del livestream | `BTN_PTT` |
| 133 | F3 | **SOS** | toggle livestream Agora (uid 9001) | `BTN_STREAM_START/STOP` |
| 134 | F4 | Grabacion | toggle grabacion local | `BTN_REC_START/STOP` |

Puntos criticos:

- **El boton SOS (133) NO envia una senal SOS dedicada**: hace que la bodycam
  transmita por Agora. Que el uid 9001 este vivo en el canal ES la senal SOS.
  La grabacion normal (134) es solo local y NUNCA debe disparar SOS.
- `key_status`: ignorar `1` (release); aceptar `0` (press) y `-1` (asi lo
  manda el firmware W1).
- Debounce de 300 ms compartido entre el receiver y `onKeyDown` (la UI de
  BodyCamServer tambien recibe el KeyEvent cuando esta en foreground).
- Las acciones se ejecutan en un executor, no en el main thread:
  `RtcEngine.destroy()` bloqueaba ~550 ms y rompia el debounce.
- Historial de flip-flop: el mapeo F3/F4 se invirtio DOS veces por fiarse de
  las etiquetas impresas. Ante cualquier duda: `adb logcat -s FalconSmoke` y
  pulsar cada boton. No asumir nada.

## 4. Protocolo Bluetooth (texto plano, delimitado por saltos de linea)

UUID RFCOMM custom (evita chocar con el SPP nativo del firmware):

```
FA1C0000-1337-4242-CAFE-DEADBEEF0001
```

El telefono es el CLIENTE (`createInsecureRfcommSocketToServiceRecord`, con
fallback por reflexion al canal 1 si el SDP falla); la bodycam es el SERVIDOR
(`listenUsingInsecureRfcommWithServiceRecord`, cierra el server socket tras
cada accept para liberar el slot SDP).

### Comandos telefono → bodycam (Cmd)

```
PING            → PONG
REC_START       → OK:REC_START    (o ERROR:Ya grabando)
REC_STOP        → OK:REC_STOP
PHOTO           → OK:PHOTO
STATUS          → STATUS:{json}   (ver abajo)
STREAM_START    → OK:STREAM_START (requiere WiFi; para la grabacion si estaba activa)
STREAM_STOP     → OK:STREAM_STOP
PREVIEW_START   → OK:PREVIEW_START (visor remoto: frames JPEG en GET /preview; requiere WiFi, no disponible grabando ni en stream)
PREVIEW_STOP    → OK:PREVIEW_STOP
IR_ON / IR_OFF  → OK:...
LED:N           → OK:LED:N        (0=off, 1-6 rojo, 7 verde, 8 rojo parpadeo, 9 amarillo parpadeo, 10 azul)
GPS_ON / GPS_OFF→ OK:...          (ver nota GPS)
TORCH_ON / TORCH_OFF → OK:...
```

### Respuesta STATUS (JSON en una linea)

```json
{"recording":false,"battery":87,"storage_mb":12034,"wifi":true,"api":true,
 "file_server_ip":"192.168.1.50","file_server_port":8080,
 "streaming":false,"stream_uid":9001,"stream_channel":"falcon_group_channel",
 "preview":false}
```

La app Flutter hace poll de `STATUS` cada 5 segundos con la conexion activa y
de ahi saca bateria, estado de grabacion y streaming (este ultimo como backup
del SOS por si se perdio el `BTN_STREAM_START`).

### Notificaciones no solicitadas bodycam → telefono (Ntf)

```
BTN_REC_START / BTN_REC_STOP        (boton 134: grabacion local)
BTN_STREAM_START / BTN_STREAM_STOP  (boton 133 SOS: livestream = emergencia)
BTN_PTT                             (boton 132: mic on/off, informativo)
```

Interpretacion en el telefono (asi lo hace map_controller.dart y asi debe
portarse): `BTN_STREAM_START` → activar video del uid 9001 + disparar el flujo
SOS; `BTN_STREAM_STOP` → cerrar popup/flujo; `BTN_REC_*` → solo indicador REC,
jamas SOS.

### Nota GPS de la bodycam (verificado 2026-06-02)

`GPS_ON` solo escribe `1` en el nodo sysfs `beidou_enable`: enciende el chip
pero NUNCA emite coordenadas por BT. No existe codigo en BodyCamServer que lea
la ubicacion. Decision de diseno: la fuente de ubicacion es el GPS del telefono
(co-localizado con el agente). No perder tiempo esperando lat/lon por BT.

## 5. BodyCamServer (APK dentro de la bodycam) — mapa de archivos

Paquete `com.falconone.bodycamserver`, 13 archivos Kotlin:

- `BtServerService` — foreground service STICKY con wakelock parcial y
  WifiLock HIGH_PERF. Corre el acceptLoop RFCOMM, procesa comandos, registra
  el `sideKeyReceiver` (botones) y chequea conectividad cada 30 s (WiFi +
  HEAD a `https://nexus.aeriaone.com/`). Si el cliente BT se desconecta en
  plena grabacion, la detiene.
- `LivestreamService` — broadcaster Agora uid 9001: camara trasera, encoder
  640x480 @ 15 fps, 700 kbps, orientacion adaptativa. `publishMicrophoneTrack`
  empieza en false; el PTT lo togglea. Token null (modo sin auth — coincide
  con el port de Aeria Nexus). LED azul mientras transmite.
- `RecordingActivity` — Camera2, graba a `/sdcard/FalconOne/VID_*.mp4` y al
  parar dispara auto-upload. Camara exclusiva: no puede grabar y transmitir a
  la vez (STREAM_START detiene la grabacion antes, con 500 ms de espera).
- `UploadService` — POST multipart a
  `https://nexus.aeriaone.com/api/incidents/upload/` con `officer_code`
  (hardcodeado `off-001`), `raw_metadata` (JSON device_id/modelo/loc/ts) y
  `video_file` o `photo_file`.
- `FileServerService` — NanoHTTPD en puerto **8080** ("servidor W1"):
  `GET /status`, `GET /recordings` (lista JSON, mas reciente primero),
  `GET /recordings/latest`, `GET /recordings/{filename}` (binario mp4/jpg),
  `GET /preview` (ultimo frame JPEG del visor remoto; 404 si PREVIEW_START
  no esta activo).
- `HardwareController` — nodos sysfs del W1: IR, LED (aw2013), sensor de luz,
  motor IR-CUT dia/noche, GPS BeiDou. Escribe directo o via `sh -c echo`.
- `Protocol.kt` — Cmd/Rsp/Ntf + `ButtonDebounce` (300 ms).
- Otros: `MainActivity` (UI con botones grabar/stream, refresco 1 s),
  `BootReceiver` (arranque en boot), `ButtonAccessibilityService` (via
  alternativa de captura de botones, INACTIVA y con mapeo desactualizado),
  `CameraController`, `PhotoController`, `TorchController`.

## 6. Lado telefono (lo que hay que portar a Aeria Nexus)

En el proyecto Flutter el codigo BT del telefono YA es Kotlin nativo
(`android/app/src/main/kotlin/com/example/falcon_one_demo/`):

- **`BluetoothSppController.kt` — reutilizable casi directo.** Cliente RFCOMM
  con estados DISCONNECTED/CONNECTING/CONNECTED/ERROR, lectura en bucle de
  1024 bytes con callback en el main thread, y helpers de comandos.
  Leccion incorporada (fix 2026-06-02): todo cambio de estado pasa por un
  embudo unico `updateState(value, error)` que actualiza el campo Y notifica;
  antes el campo quedaba desincronizado al perder conexion y `connect()`
  rechazaba reconectar para siempre.
- **`BodyCamChannel.kt`** — solo es el puente MethodChannel/EventChannel hacia
  Flutter. En Aeria Nexus NO hace falta: el controller se conecta directo a un
  repositorio Kotlin (patron AppContainer).
- **Flujo de UI acordado ("solo conectar", 2026-06-02):** el boton de bodycam
  del panel SOLO conecta/desconecta el BT. Grabacion, livestream y PTT los
  dispara UNICAMENTE la bodycam con sus botones fisicos. Al conectar se inicia
  el poll de STATUS (bateria); nada de auto-grabar ni auto-stream.
- **Deteccion SOS de bodycam por dos vias** (deben coexistir, con guard
  anti-doble-popup): (a) rapida: `BTN_STREAM_START` por BT; (b) confiable y
  sin BT: el uid 9001 aparece/publica video en el canal Agora. Ambas terminan
  en el mismo flujo de emergencia.
- **`W1Service` (Dart → portar a Kotlin/OkHttp o HttpURLConnection):** cliente
  del servidor HTTP de la bodycam. La IP sale del campo `file_server_ip` del
  STATUS por BT. Flujo de importacion: `GET /recordings/latest` → descargar
  (timeout largo, 10 min) → subir a Nexus desde el telefono.

## 7. Persistencia y gotchas operativos

- Android cierra el socket RFCOMM si ninguna app en foreground lo sostiene.
  En la bodycam ese sosten es `BtServerService` (foreground + wakelock).
  **Si se formatea la W1 hay que reinstalar BodyCamServer y abrirlo una vez
  a mano**: en Android 9 una app recien instalada esta en stopped state y no
  recibe `BOOT_COMPLETED` hasta el primer arranque manual.
- La bodycam entra a Agora SIN token (null). El proyecto Agora debe seguir en
  modo "No Auth" — si aparece error 101/110, revisar la consola de Agora.
- En el canal, el uid 9001 es un dispositivo, no un agente: se excluye del
  contador de usuarios (ya implementado en `AgoraRepository.BODYCAM_UID`).
- El smoke test del proveedor (`FalconSmoke`) sigue registrado en
  BtServerService; los tags de log utiles son `FalconServer`, `FalconSmoke`,
  `FalconLive`, `FalconHTTP`, `FalconUpload` y en el telefono `FalconBT`.
- Estado de los repos originales al 2026-06-03: cambios funcionales SIN
  commitear en ambos (MAC corregido, updateState, "solo conectar", comentarios
  de mapeo). APKs debug entregados al socio para prueba de campo.

## 8. Plan de port a Aeria Nexus (fase 4)

1. `data/BodycamRepository.kt`: adaptar `BluetoothSppController` (mismo UUID,
   mismo MAC a `local.properties`/BuildConfig, embudo updateState, parser de
   lineas Ntf/Rsp/STATUS) exponiendo StateFlow de estado + SharedFlow de
   eventos, al estilo de `AgoraRepository`.
2. Permisos manifest: `BLUETOOTH_CONNECT` (runtime en API 31+) y
   `BLUETOOTH`/`BLUETOOTH_ADMIN` legacy para API < 31.
3. Integrar el SOS de bodycam con el flujo SOS existente: uid 9001 con video
   activo == emergencia (el `AgoraRepository` ya ve entrar al 9001 gracias a
   `autoSubscribeVideo=true`); `BTN_STREAM_*` como via rapida cuando haya BT.
4. UI: boton conectar/desconectar bodycam + bateria de la bodycam en el panel
   del mapa (campo `battery` del STATUS, distinto de la bateria del telefono).
5. Cliente W1 HTTP (fase 4/5) para importar la grabacion y subirla a Nexus.
