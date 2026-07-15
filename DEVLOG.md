# DEVLOG — Aeria Nexus (app Kotlin)

Bitacora de desarrollo del proyecto. Se actualiza cada dia de trabajo.
Regla: cada entrada nueva va ARRIBA, con fecha, que se hizo, decisiones tomadas y proximo paso.
Este archivo es la fuente de verdad para retomar el desarrollo en cualquier sesion.

---

## 2026-07-15 (2) — SOS persistente en el mapa: marcador rojo tocable mientras dure la emergencia

### Hecho

- **`AgoraRepository`**: StateFlow nuevo `activeSosAlerts` (Map uid -> SosAlert). Un SOS entra al recibirse por el data stream y SOLO sale con su `emergency_cancel`: descartar el popup no lo borra. Los reenvios del heartbeat refrescan el mismo valor; un SOS nuevo del mismo uid lo reemplaza. El emisor nunca ve su propio marcador (Agora no devuelve mensajes propios).
- **`MapViewModel`**: `SosMarker` (uid, officer, posicion, hora de inicio) en `MapUiState.activeSos`. Se combina `activeSosAlerts` con `remoteAgents` para que el marcador siga la posicion viva del emisor (sigue compartiendo GPS durante el SOS); sin posicion viva se usa la coordenada de la alerta, y sin ninguna no se pinta (caso bodycam, sin GPS).
- **`MapScreen`**: `SosTooltip` como ViewAnnotation anclada BOTTOM — tarjeta con borde rojo critico, "SOS — Agent P-4471", "Since HH:mm:ss — tap to view live" y punto rojo PULSANTE en la coordenada (distingue emergencia activa de un companero normal). TODA la tarjeta es tocable y abre `livestream/{uid}` del emisor (param nuevo `onOpenLivestream` cableado en AppNavHost). Si el emisor ya no publica video, la pantalla muestra su aviso de SIGNAL CUT normal.
- Exclusion mutua con los avisos de corte ya existente: `emergency` borra el signal cut del uid y `emergency_cancel` borra el SOS activo y fija el corte.
- **Ajuste del mismo dia — banner fijo `SosBanner`**: el tooltip anclado a la coordenada queda fuera de pantalla si la camara esta lejos (habia que buscarlo con el zoom). Ahora cada SOS activo pinta ademas un banner rojo fijo en lo alto del mapa, visible siempre: tocar el cuerpo vuela la camara a la posicion del emisor (`flyToOwnPosition` renombrada a `flyToPosition`, reutilizada) y el boton VIEW LIVE abre el livestream directo. El tooltip pulsante en la coordenada se conserva.
- **Ajuste del mismo dia — `allowOverlapWithPuck(true)` en TODAS las ViewAnnotations** (marcadores de agentes, tooltip SOS y tooltip de signal cut): en Mapbox v11 el default es false y la anotacion se OCULTA cuando se solapa con el puck de la posicion propia — un agente en emergencia a pocos metros desaparecia del mapa hasta hacer zoom suficiente para separarlos. Este era el motivo de que el tooltip SOS "solo se viera con zoom" con los telefonos juntos.
- **Icono de gafas real en el header**: el usuario importo el vector eyeglasses_2 de Material Symbols como `drawable/icon_eyeglasses.xml` (el set de Material de Compose no trae gafas; hasta ahora se usaba Vrpano como sustituto). Se ajusto el tamano intrinseco 960dp -> 24dp y el indicador Falcon Lens lo carga con `ImageVector.vectorResource`.
- Compila limpio (assembleDebug). Sin dispositivos por adb: APK sin instalar.

### Pendiente (verificar con dos telefonos)

- SOS en A -> en B descartar el popup -> abrir Map: banner rojo arriba SIEMPRE visible; tocarlo vuela a la posicion de A (tooltip rojo pulsante que le sigue al moverse); VIEW LIVE o tocar el tooltip abren el livestream de A -> cancelar en A: banner y tooltip desaparecen y aparece el aviso de "Signal cut".

---

## 2026-07-15 — Bloqueo de capturas, SOS al mapa, ficha del agente en el livestream e icono de bodycam solo indicador

### Hecho

- **Capturas de pantalla bloqueadas en toda la app**: `FLAG_SECURE` en `MainActivity.onCreate` (antes de `setContent`). Bloquea screenshot y grabacion de pantalla y oculta la vista previa en apps recientes. Basta la ventana de la actividad: con una ventana segura visible, el sistema bloquea la captura aunque los dialogos de Compose abran ventana propia.
- **Popup SOS con icono de mapa**: en la fila "Location" de la alerta (`SosAlertOverlay`) aparece un icono de mapa (solo si la alerta trae coordenadas). Al tocarlo se cierra la alerta (para la sirena) y se navega al mapa tactico centrado en la posicion del emisor. Ruta `MAP_WITH_FOCUS` (`map?focusLat={..}&focusLng={..}`, args opcionales con default: navegar a `map` a secas sigue funcionando); `MapScreen` gana `focusLatitude/focusLongitude` — con foco la camara arranca ahi y el primer fix GPS NO la arrastra a la posicion propia (`initialCameraDone` nace true).
- **Ficha del agente en el livestream del SOS**: tarjeta sobre el video (abajo-izquierda; sobre el emisor sube para no tapar CANCEL SOS) con nombre y apellido, placa, rango y tipo de sangre. Datos: modelo nuevo `AgentIdCard` + padron estatico `agentsByBadge` en `OfficerSampleData` (P-4471 Carlos Mendez O+, P-3318 Lucia Torres A-; estatico hasta que exista login). El SOS solo viaja con la placa: `AgoraRepository` guarda `_sosOfficers` (uid -> placa, alimentado por los mensajes `emergency`) y `LivestreamViewModel` resuelve la ficha (la propia en modo emisor, la del emisor del SOS en modo receptor). El stream de la bodycam (officer "BODYCAM") no esta en el padron: sin tarjeta, comportamiento esperado.
- **Icono de bodycam del topbar = solo indicador**: `AppScaffold` ya no conecta/desconecta al tocarlo (se quitaron el clickable y el launcher de permisos); conserva el color por estado (gris/ambar/verde/rojo). La conexion se maneja desde BODYCAM CONTROL, que ya tenia su propio flujo con permisos.
- Compila limpio (assembleDebug). Sin dispositivos por adb al cierre: APK sin instalar.

### Pendiente (verificar en dispositivo)

- Screenshot bloqueado en varias pantallas (incluido el popup SOS abierto).
- Con dos telefonos: SOS en A -> en B tocar el icono de mapa del popup -> mapa centrado en A (marcador dorado ahi); "Accept & view live" -> tarjeta con Carlos Mendez / P-4471 / Patrol Officer / O+.
- Icono de bodycam del topbar ya no reacciona al toque y sigue cambiando de color al conectar desde BODYCAM CONTROL.

---

## 2026-07-12 (2) — Monitor de grabacion: ver en el movil lo que la bodycam graba

### Hecho

- **BodyCamServer (W1)**: durante la grabacion, `RecordingActivity` copia su
  `TextureView` a un bitmap 640x360 cada 250 ms (hilo UI, bitmap reutilizado) y
  lo comprime a JPEG q60 (`latestJpeg()` en el companion). NO se abre un tercer
  stream de camara: el HAL UNISOC no garantiza PRIV+PRIV+YUV y la via segura es
  leer el preview que ya existe. `FileServerService` ahora sirve en `/preview`
  y `/preview/stream` "la fuente viva que haya": `PreviewController` (visor de
  foto) o el monitor de grabacion; con la fuente activa pero sin frame aun, el
  stream espera el siguiente tick en vez de cortarse.
- **Aeria Nexus**: `BodycamViewfinderViewModel/Screen` ganan `ViewfinderMode`:
  - `PHOTO` (igual que antes): PREVIEW_START/STOP, boton de captura.
  - `RECORDING`: sin comandos de visor (la grabacion ya alimenta el stream y
    salir de mirar NO la detiene), titulo "RECORDING MONITOR", badge REC,
    boton rojo Stop que envia REC_STOP, y cierre automatico cuando
    `isRecording` pasa a false (lo pare este boton, el fisico u otro agente).
  - Controlador: el panel superior ofrece segun la fuente activa VIEW LIVE
    FEED (stream) / VIEW RECORDING (grabando) / REMOTE VIEWFINDER (libre).
  - Ruta nueva `bodycam/recording` (misma pantalla, modo RECORDING).
- Ambos APK compilados e instalados: BodyCamServer en la W1 y Aeria Nexus en el
  Samsung A56; MainActivity de la W1 relanzada para levantar el servicio BT.

### Orientacion ajustada en campo (mismo dia)

- El monitor salia girado 90 a la derecha (getBitmap trae el buffer sin la
  matriz de la vista). Fix: rotacion POR FUENTE en el telefono —
  `streamPreviewFrames(rotationDegrees)` con `PREVIEW_ROTATION_DEGREES = 0`
  (visor de foto, ya calibrado) y `MONITOR_ROTATION_DEGREES = -90` (monitor);
  el ViewModel del visor pasa la que toca segun el modo. La W1 no gasta CPU
  extra en girar.

### Pendiente (verificar en campo)

- VIEW RECORDING con la rotacion -90 aplicada: frames derechos a ~4 fps; Stop
  desde el monitor cierra la pantalla y guarda el .mp4. Requiere WiFi en la W1
  (el monitor viaja por HTTP igual que el visor de foto).

---

## 2026-07-12 — Fix: bodycam no conectaba en el Samsung A56 (faltaba BLUETOOTH_SCAN)

### Hecho

- **Diagnostico por logcat**: cada intento moria en `cancelDiscovery()` con `Need android.permission.BLUETOOTH_SCAN`. En el Redmi (Android 11) funcionaba porque ahi basta el `BLUETOOTH_ADMIN` legacy; en Android 12+ `cancelDiscovery()` exige `BLUETOOTH_SCAN` de runtime, que la app nunca declaraba ni pedia (el comentario del manifest "desde 12 basta CONNECT" era falso).
- **Fix**:
  - Manifest: `BLUETOOTH_SCAN` con `neverForLocation` (no usamos el escaneo para ubicar).
  - `BodycamRepository`: constante publica `BLUETOOTH_RUNTIME_PERMISSIONS` (CONNECT + SCAN) y `hasBluetoothPermission()` ahora exige ambos.
  - `AppScaffold` y `BodycamControllerScreen`: launcher cambiado a `RequestMultiplePermissions` pidiendo los dos a la vez (mismo grupo "Dispositivos cercanos": un solo dialogo).
- APK instalado en el Samsung A56 (RZCY510MBBM).

### Pendiente

- Verificar en el Samsung: tocar el icono de la bodycam, aceptar "Dispositivos cercanos" y confirmar que conecta. Ojo: la W1 reporta `wifi=false` desde las 22:38 — el enlace BT no lo necesita, pero visor remoto y descarga de grabaciones si.

---

## 2026-07-11 (4) — Foto remota: fix del PHOTO y visor remoto (viewfinder) por WiFi

### Hecho

- **Fix del error "Photo failed" (BodyCamServer, proyecto de la W1)**: el logcat de la W1 mostro `takePicture failed` — en el HAL UNISOC `takePicture` exige una preview realmente activa y `PhotoController` llamaba a `startPreview()` sin surface. Fix: `setPreviewTexture(SurfaceTexture(0))` + espera del primer frame (latch 2 s) antes de disparar + `release()` de la camara en el catch (antes un fallo dejaba la camara abierta para siempre). VERIFICADO en hardware: la foto sale y se guarda.
- **Visor remoto para foto a distancia (Opcion A acordada)** — el agente deja la bodycam fija y encuadra desde el telefono:
  - **BodyCamServer**: comandos nuevos `PREVIEW_START`/`PREVIEW_STOP`; `PreviewController` nuevo mantiene Camera1 abierta, guarda el ultimo frame NV21 y lo sirve como JPEG (calidad 60, ~640x480) en `GET /preview` del NanoHTTPD 8080; `PHOTO` con el visor activo dispara sobre ESA misma sesion (lo que ves es lo que capturas, max resolucion) y rearranca la preview. STATUS ahora incluye `"preview"`. Exclusividad de camara respetada: REC_START/STREAM_START (comando o boton fisico) apagan el visor; tambien se apaga al desconectarse el telefono (bateria).
  - **Aeria Nexus**: `BodycamRepository` parsea `file_server_ip/port` del STATUS, expone `isPreviewing` y `fetchPreviewFrame()` (HTTP + rotacion 90º, constante `PREVIEW_ROTATION_DEGREES` ajustable en campo); pantalla nueva `BodycamViewfinderScreen` + ViewModel (ruta `bodycam/viewfinder`): frames a ~1.4 fps, boton grande de captura, reintento automatico reenviando PREVIEW_START tras 5 frames fallidos (microcortes BT), PREVIEW_STOP al salir. Entrada: boton "REMOTE VIEWFINDER" en el controlador (oculto durante livestream). `ViewLiveFeedButton` generalizado a `SecondaryPanelButton`.
- Ambos APK compilados e instalados: BodyCamServer en la W1 (30393016471440) y Aeria Nexus en el Redmi.
- `docs/bodycam-contexto.md` actualizado (comandos PREVIEW_*, campo preview del STATUS, endpoint /preview).

### Depuracion en vivo del visor (mismo dia) — dos bugs encontrados y corregidos

1. **Cleartext HTTP bloqueado en el telefono**: el manifest no permitia trafico http:// (bloqueado por defecto desde Android 9); el fetch del frame moria al instante sin llegar a la red (sintoma: reintentos de PREVIEW_START cada 3,5 s en la W1 y CERO GET /preview). Fix: `android:usesCleartextTraffic="true"` (el servidor de la W1 es http en LAN; el trafico a internet sigue TLS) + Log.w en fetchPreviewFrame para que nunca vuelva a ser invisible.
2. **NanoHTTPD 2.3.1 tardaba ~10 s FIJOS por peticion** (timeout > los 3 s del telefono): su HTTPSession hace `inetAddress.getHostName()` — reverse DNS bloqueante — con cada conexion; el router no responde PTR y espera ~10 s. Diagnostico concluyente: localhost 0 s vs red 10,1-10,4 s consistente, y el `GET` se logueaba 10 s despues del connect TCP. Fix: NanoHTTPD incorporado como fuente en `src/main/java/fi/iki/elonen/NanoHTTPD.java` con `remoteHostname = remoteIp` (dependencia gradle eliminada). Resultado medido: 0,09-0,39 s por peticion.

### Visor verificado en campo y subida a MJPEG (2026-07-12)

- Visor VERIFICADO con hardware: frames visibles; orientacion ajustada en campo (`PREVIEW_ROTATION_DEGREES` 90 -> 0: la imagen salia girada a la derecha; con 0 se omite la copia de bitmap).
- El usuario pidio mas fluidez: el polling (~1.4 fps, un ciclo HTTP por frame) se reemplazo por **MJPEG streaming**: `GET /preview/stream` en la W1 (multipart/x-mixed-replace, un hilo empuja el frame actual cada 150 ms ≈ 6-7 fps por una unica conexion; termina al apagarse el visor o cortarse el cliente); en el telefono `BodycamRepository.streamPreviewFrames(): Flow<Bitmap>` (parser multipart por Content-Length) y el ViewModel colecta el flow, reenviando PREVIEW_START si el stream se corta. `GET /preview` (frame unico) se conserva para diagnostico con curl.

### Pendiente (verificar en campo)

- Fluidez del visor MJPEG (~6-7 fps) y foto con el visor abierto: confirmar que la preview sigue viva tras disparar (verificado una vez con el polling: IMG_20260712_054158.jpg).

---

## 2026-07-11 (3) — Operations responsive: fin de los botones apinados

### Hecho

- **Diagnostico**: la columna central de `OperationsScreen` sumaba alturas fijas (150+150+64 + fila de radio con aspectRatio 4:3 ≈ 120 + separaciones ≈ 508.dp) pero el alto real disponible entre header y EMERGENCY ronda 400-480.dp en un telefono tipico (menos en 16:9): desbordaba en casi cualquier dispositivo.
- **Fix en `OperationsScreen`**:
  - Las tarjetas NEW INCIDENT y CONTINUE ACTIVE INCIDENT ya no miden 150.dp fijos: se reparten con `weight(1f)` el alto que queda en cada telefono.
  - `BoxWithConstraints` con umbral `maxHeight < 500.dp` activa modo compacto: iconos y textos internos mas pequenos, el icono decorativo de CONTINUE se omite (tres lineas de texto no caben con el), bodycam 52.dp y radio 64.dp.
  - Fila de radio/llamada sin `aspectRatio` (atada al ancho robaba ~120.dp de alto); ahora altura propia con `heightIn` (96/64.dp).
  - `heightIn` en vez de `height` en los botones de alto fijo para tolerar fuente del sistema grande.
  - EMERGENCY intacto (72.dp, accion critica) con separacion garantizada de 10.dp.
- Compila limpio (assembleDebug).

### Pendiente

- Verificacion visual en el dispositivo donde se veian apinados (no habia ninguno conectado por adb al cerrar).

---

## 2026-07-11 (2) — Tooltip de "Signal cut" en el mapa de las demas unidades

### Hecho

- **`AgoraRepository`**: StateFlow nuevo `sosSignalCuts` (Map uid -> SosCancel). Se alimenta SOLO con `emergency_cancel` recibidos por el data stream, asi que el emisor nunca ve su propio aviso (Agora no devuelve mensajes propios) — requisito explicito del usuario. Un `emergency` nuevo del mismo uid borra su corte anterior; `dismissSignalCut(uid)` lo quita a mano. El corte sintetizado de la bodycam NO se fija (sin GPS, no hay donde anclarlo).
- **`MapViewModel`**: `SignalCutMarker` (uid, officer, posicion, hora del corte HH:mm:ss) en `MapUiState.signalCuts`; si la cancelacion no trae lat/lng se usa la ultima posicion conocida del agente y sin ninguna de las dos no se muestra.
- **`MapScreen`**: `SignalCutTooltip` como ViewAnnotation anclada BOTTOM sobre la ultima posicion del emisor — tarjeta con icono de aviso, "Agent P-4471" (el id sigue hardcodeado en OfficerSampleData hasta que exista login; viaja en el campo `officer` del mensaje) y "Signal cut: HH:mm:ss", con punto rojo en la coordenada. Persiste al navegar entre pestanas (estado en el repositorio).
- **Mismo dia, ajuste de persistencia**: la tarjeta ya NO se descarta al tocarla (un toque accidental no borra un aviso critico); ahora lleva una X (target 36.dp) como unico camino para cerrarla. Sigue borrandose sola si el mismo agente emite un SOS nuevo.
- Compila limpio (assembleDebug).

### Pendiente

- Prueba con dos telefonos: SOS en A -> cancelar en A -> tooltip en el mapa de B (y no en A); tocar para descartar; nuevo SOS del mismo agente limpia el tooltip viejo.

---

## 2026-07-11 — Fase 4 (parte 2a): controlador remoto de la bodycam desde el movil

### Decisiones de diseno (acordadas con el usuario)

- El movil tiene CONTROL TOTAL de la bodycam; esto reemplaza la regla historica "solo conectar" del 2026-06-02 (los botones fisicos siguen funcionando igual).
- **Livestream desde el movil = SOS para todos**: mismo significado que el boton fisico 133. No existe el "visor privado".
- Set aparte de botones locales SIN SOS: foto (PHOTO) y grabacion (REC_START/STOP).
- PTT y apagado quedan fuera: el protocolo BT no tiene comando de mic (BTN_PTT es solo notificacion; habria que agregar MIC_ON/OFF a BodyCamServer) y apagar el equipo requiere permisos de plataforma que nuestro APK no tiene.

### Hecho

- **`feature/bodycam/`** (pantalla nueva `BodycamControllerScreen` + ViewModel, ruta `bodycam`): estado de conexion + bateria de la bodycam, boton rojo LIVESTREAM — SOS (con aviso explicito de que alerta a todas las unidades; parpadea mientras transmite y un segundo toque manda STREAM_STOP), botones PHOTO y RECORD (locales, sin SOS), VIEW LIVE FEED (abre `livestream/9001`), conectar/desconectar con permiso BT en el gesto. Al pedir el stream desde esta pantalla, el visor se abre solo cuando llega `OK:STREAM_START`; si responde `ERROR:` (p. ej. sin WiFi) se muestra el mensaje y no se navega. Entrada: boton "BODYCAM CONTROL" en Operations.
- **`BodycamRepository`**: StateFlows nuevos `isRecording`/`isStreaming` — se adelantan con `OK:*`/`BTN_*` y se corrigen con el campo `recording`/`streaming` del STATUS cada 5 s (resincroniza tras micro-cortes); SharedFlow `commandResponses` para feedback de comandos; todo se resetea al perder el enlace. OJO: `STREAM_START` apaga `isRecording` (camara exclusiva en la W1).
- **`AgoraRepository` — deteccion SOS de bodycam (via confiable, sin BT)**: cuando el video del uid 9001 empieza a publicarse (`onRemoteVideoStateChanged` STARTING/DECODING) se emite un `SosAlert` (officer "BODYCAM", sin posicion — la W1 no emite GPS); al pararse el video o salir del canal, `SosCancel`. Con esto TODOS los telefonos reciben la emergencia, la dispare el movil o el boton fisico 133. Guard `bodycamStreamActive` contra dobles emisiones.
- **`SosAlertViewModel`**: el popup+sirena del uid 9001 se suprime en el telefono que tiene la bodycam conectada por BT (ese agente la disparo el mismo y ya lo ve en el controlador); las demas unidades si lo reciben.
- Compila limpio (assembleDebug).

### Pendiente

- Prueba fisica: W1 con BodyCamServer + WiFi, un telefono conectado por BT (stream desde el movil, foto, grabacion, feedback de error sin WiFi) y un segundo telefono para verificar el popup SOS "BODYCAM" + "Accept & view live" + signal cut al parar. De paso cae la prueba pendiente de coexistencia 2.4 GHz (stream Agora vs RFCOMM).
- Futuro del controlador: comando MIC_ON/OFF en BodyCamServer para el PTT remoto; IR/LED/torch ya existen en el protocolo si se quieren exponer.

---

## 2026-07-10 (5) — Enlace BT con la bodycam robusto: reconexion automatica + foreground service

### Investigacion (por que era inestable)

1. Sin reconexion: cualquier micro-corte BT (atenuacion corporal, interferencia) dejaba el enlace en ERROR hasta tocar el icono a mano. Igual en la app Flutter original.
2. Sin foreground service: Android congelaba el proceso en segundo plano y el socket moria; encima BodyCamServer detenia la grabacion al perder el cliente.
3. Coexistencia WiFi/BT en la W1: el livestream Agora satura el 2.4 GHz y ahoga el RFCOMM (mismo chip/antena). No se arregla, se mitiga con reconexion; el SOS confiable sigue siendo el uid 9001 por Agora.
4. APK del fabricante (com.smarteye.mcu): tiene BLUETOOTH_ADMIN y usa startDiscovery (degrada enlaces si alguien navega su UI); su servidor SPP usa el UUID estandar 00001101, no choca con el nuestro.

### Hecho — telefono (BodycamRepository + BodycamService)

- Bucle unico de conexion mientras el enlace "se desee": reintenta con backoff 1/2/5/10 s; el toque del icono en ambar cancela.
- Watchdog de lectura: 12 s sin lineas (el poll STATUS de 5 s garantiza trafico) = enlace muerto -> cierre y reconexion inmediata, sin esperar el timeout de supervision BT (~20 s).
- Fallback al canal 1 por reflexion si el SDP falla (estaba en Flutter, no se habia portado).
- Escrituras serializadas (lock propio, separado del de connect/disconnect).
- `BodycamService`: foreground service `connectedDevice` que vive exactamente mientras el enlace se desea. OJO: startForeground va en onCreate, no en onStartCommand — si el servicio muere a los ms (BT apagado) el sistema mata la app por RemoteServiceException (crash real visto en el Redmi).
- Permiso `BLUETOOTH_ADMIN` legacy (maxSdk 30) al manifest: cancelDiscovery() lo exige hasta Android 11; sin el, todos los intentos fallaban en el Redmi (Android 11).
- Con Bluetooth apagado: ERROR inmediato sin bucle ni servicio.

### Hecho — bodycam (BodyCamServer, APK reinstalado en la W1)

- Ya NO se detiene la grabacion al perder el cliente BT (con reconexion automatica ese comportamiento destruia evidencia por un micro-corte; la grabacion solo para por boton fisico o REC_STOP).
- `send()` sincronizado: respuestas (hilo del cliente) y notificaciones BTN_* (executor) compartian stream sin lock y podian entrelazar lineas.
- Pantalla del server: la linea de estado BT ahora es una unica fuente de verdad refrescada cada segundo (`BtServerService.isRunning`/`connectedClient` estaticos, mismo patron que isStreaming). Antes "Esperando telefono…" se escribia una vez y quedaba obsoleto aun con el movil conectado; ahora muestra en verde nombre y MAC del cliente (verificado: "Conectado: Alejoelmasfuertejo (1C:CC:D6:F5:DC:93)").

### Verificado en campo (Redmi Note 8 Pro + W1 fisica)

- Conexion real por RFCOMM, poll STATUS cada 5 s.
- Servidor matado a mano -> el telefono detecta al instante y reintenta con backoff -> servidor revivido -> reconectado SOLO en 4 s y el poll continua.
- 150 s con la pantalla del Redmi apagada: 30/30 STATUS recibidos, cero cortes (antes el proceso se congelaba).

### Pendiente / notas operativas

- En telefonos MIUI de campo conviene poner la app en bateria "Sin restricciones" (MIUI puede matar incluso FGS en casos extremos).
- Prueba pendiente con livestream Agora activo en la W1 (coexistencia 2.4 GHz): esperar cortes y verificar que la reconexion los absorbe.
- Los cambios de BodyCamServer estan en su proyecto (Desktop/Nueva carpeta (3)/BodyCam/BodyCamServer), sin commitear.

---

## 2026-07-10 (4) — Logo Aeria One como icono de la app y splash screen

### Hecho

- **Logo vectorizado**: el unico asset disponible era `aeria_one_logo.png` (85x70 px, silueta negra con alpha; el `aeria-logo.png` del proyecto Flutter es un placeholder 1x1). Se trazo a vector con un script Python (upscale LANCZOS x8 → umbral → contornos por aristas → suavizado Chaikin → simplificacion RDP): 2 contornos (aguila + estrella), 122 puntos, fiel al original y nitido a cualquier tamano. Script en scratchpad de la sesion; si se pierde, se puede volver a trazar del PNG original.
- **Icono adaptativo**: `drawable/ic_launcher_foreground.xml` ahora es el aguila en blanco (viewport 108, logo en caja de 50 dp centrada = dentro del circulo seguro de 66 dp). Background = `@color/fondo_base` (#080B12) referenciado directo en los XML de `mipmap-anydpi`; se borro `ic_launcher_background.xml` del template. La capa `monochrome` (iconos tematicos Android 13+) reutiliza el mismo vector.
- **Splash**: `values-v31/themes.xml` con `windowSplashScreenBackground` = fondo de la app y `windowSplashScreenAnimatedIcon` = el icono adaptativo. Sin dependencia de core-splashscreen (Android 12+ lo da gratis; en API 26-30 se ve el fondo oscuro de siempre, sin destello).
- **Limpieza**: borrados los webp del template en `mipmap-*dpi` (con minSdk 26 siempre se usa el icono adaptativo de `mipmap-anydpi`).
- **Verificado en dispositivo**: splash con el aguila blanca centrada sobre fondo oscuro y el icono correcto en el cajon de apps.

### Pendiente

- El nombre visible sigue siendo "Aeria-Nexus-Prototype" (`app_name`); considerar "Aeria Nexus".

---

## 2026-07-10 (3) — Fix: la foto con el telefono no abria la hoja de clasificacion

### Hecho

- **Bug**: al tomar una foto sin bodycam, la camara del sistema volvia sin guardar nada y la hoja "Classify Evidence" no aparecia. Causa: `LocalEvidenceRepository.createTarget` insertaba el destino en MediaStore con `IS_PENDING=1`; un item pendiente solo lo puede escribir la app que lo creo, asi que la camara de Samsung (paquete ajeno, aun con el grant de uri del intent) fallaba y devolvia RESULT_CANCELED → `onPhonePhotoResult(false)` descartaba la captura en silencio.
- **Fix**: los destinos de foto y video (los escribe la app de camara externa) se insertan SIN `IS_PENDING`; el audio lo graba esta misma app, asi que conserva `IS_PENDING=1` hasta publicar. `publish()` no cambia (poner IS_PENDING=0 sobre una fila ya visible es inocuo).
- **Verificado en dispositivo (RZCY510MBBM)** con el flujo completo por adb: PHOTO → camara → Aceptar → hoja de clasificacion con los 9 tipos → "Evidence" → foto en `DCIM/localIncidents/photo_agent_007_2026-07-10_12-06-58.jpg`, contador EVIDENCE (1) y entrada "Photo — Evidence" en la lista.
- Leccion: con scoped storage, `IS_PENDING` solo sirve para archivos que escribe la propia app; nunca para uris entregados a otra app via intent.

### Pendiente

- Igual que la entrada anterior: probar video sin bodycam (mismo fix aplica) y el flujo con bodycam conectada.

---

## 2026-07-10 (2) — Captura local con el telefono cuando no hay bodycam

### Hecho

- **`data/LocalEvidenceRepository.kt`**: evidencia capturada con el telefono guardada como **album publico `localIncidents` visible en la galeria** via MediaStore — fotos y videos en `DCIM/localIncidents`, audio en `Music/localIncidents`. (Primera version usaba la carpeta privada de la app y el usuario no la veia en la galeria; se corrigio el mismo dia.) Nombres descriptivos `tipo_agent_007_yyyy-MM-dd_HH-mm-ss.ext` (agent_007 fijo hasta que exista login). Flujo MediaStore: insert con IS_PENDING=1 (oculto mientras se captura) -> publish al confirmar / discard al cancelar; duracion real con MediaMetadataRetriever; notas de audio REALES con MediaRecorder escribiendo al descriptor del uri (AAC/m4a; descarta grabaciones demasiado cortas; se libera en onCleared del ViewModel).
- **FileProvider** en el manifest (`${applicationId}.fileprovider` + `res/xml/file_paths.xml`): solo como fallback para Android 9 o menor (rutas publicas + MediaScanner + permiso WRITE_EXTERNAL_STORAGE maxSdk 28); desde Android 10 todo va por MediaStore sin permiso de almacenamiento.
- **ActiveIncidentScreen — bifurcacion por bodycam**:
  - SIN bodycam conectada: RECORD lanza la camara nativa en modo video (`ActivityResultContracts.CaptureVideo`), PHOTO en modo foto (`TakePicture`); el archivo queda en el album localIncidents y se registra como evidencia con su uri (`EvidenceRecord.mediaUri`, campo nuevo) y duracion real; la foto sigue pasando por la hoja de clasificacion.
  - CON bodycam: RECORD envia `REC_START`/`REC_STOP` y PHOTO envia `PHOTO` por Bluetooth (la captura queda en la bodycam), manteniendo el flujo de timeline/evidencia existente.
  - AUDIO siempre graba con el microfono del telefono (la bodycam no tiene comando de nota de audio); ahora es grabacion real, ya no simulada.
- Permisos pedidos en el gesto: CAMERA antes de abrir la camara (obligatorio porque la app declara CAMERA para el livestream — mismo gotcha que en Flutter) y RECORD_AUDIO antes de la nota de audio.
- Compila limpio (assembleDebug) e instalado en dispositivo; la verificacion manual en pantalla quedo pendiente porque el usuario estaba usando el telefono.

### Pendiente

- Prueba manual: video/foto/audio sin bodycam → archivos en localIncidents con nombre correcto; con bodycam conectada → REC_START/PHOTO reales por BT.
- Futuro: subir lo de localIncidents a Nexus (fase 5) y reemplazar agent_007 por el oficial autenticado.

---

## 2026-07-10 — Fase 4 (parte 1): conexion Bluetooth con la bodycam + indicadores reales

### Hecho

- **`data/BodycamRepository.kt`** (port del BluetoothSppController de Flutter): cliente RFCOMM insecure con el UUID custom de Falcon, API estandar de Android (`BluetoothManager` via getSystemService, no el deprecated `BluetoothAdapter.getDefaultAdapter`). Estados DISCONNECTED/CONNECTING/CONNECTED/ERROR en StateFlow; bucle de lectura por lineas; poll de STATUS cada 5 s del que se extrae la **bateria de la bodycam** (StateFlow propio); las notificaciones `BTN_*` de los botones fisicos se exponen en un SharedFlow para la integracion SOS de la parte 2. Cualquier caida de la conexion pasa por un unico embudo (`onConnectionLost`) — leccion del proyecto original donde el estado desincronizado impedia reconectar.
- **MAC de la bodycam** (`40:45:DA:44:C8:9B`) en `local.properties` -> BuildConfig `BODYCAM_MAC`, como el resto de secretos.
- **Permisos manifest**: `BLUETOOTH_CONNECT` (runtime en Android 12+) y `BLUETOOTH` legacy con maxSdkVersion 30 (minSdk 26).
- **Header (StatusBar de AppScaffold)**: los textos "FC" y "FL" ahora son iconos — videocamara (Videocam) para Falcon Camera y visor (Vrpano) para Falcon Lens. OJO: el set material-icons-extended de Compose NO trae icono de gafas (Eyeglasses no existe en esta version); Vrpano es lo mas parecido — sustituible por un vector propio si se quiere una gafa literal. El icono de la bodycam refleja el estado real por color (gris=desconectado, ambar=conectando, verde=conectado, rojo=error) y **tocarlo conecta/desconecta** (pide el permiso BLUETOOTH_CONNECT en el mismo gesto). El de gafas queda gris fijo (sin integrar). La StatusBar observa el repositorio directamente (componente global sin ViewModel propio, documentado en comentario).
- **`fcConnected` real**: `activeDevices()` de ActiveIncidentViewModel ya no lee el valor estatico del perfil; usa `bodycamRepository.isConnected`, asi las entradas de timeline y la evidencia registran "FC + AN" solo si la bodycam esta realmente conectada. FL sigue saliendo del perfil hasta integrar las gafas.
- Compila limpio debug y release.

### Pendiente

- Prueba fisica con la W1 encendida y BodyCamServer corriendo (no hacia falta hardware para esta parte; si para verificar la conexion real). Recordatorio operativo: la W1 debe tener BodyCamServer instalado y abierto al menos una vez.
- Parte 2 de la fase 4: consumir `buttonEvents` (BTN_STREAM_* = SOS bodycam, BTN_REC_* = indicador REC), mostrar bateria de la bodycam en el panel del mapa, y cliente W1 HTTP para importar grabaciones.

---

## 2026-07-09 — Investigacion bodycam W1 y memoria historica (preparacion fase 4)

### Hecho

- Investigada a fondo la conexion y el control de la bodycam en los dos proyectos originales: la app Flutter (lado telefono, incluido su Kotlin nativo `BluetoothSppController.kt`/`BodyCamChannel.kt`) y **BodyCamServer** (APK que corre dentro de la W1: BtServerService RFCOMM, LivestreamService Agora uid 9001, RecordingActivity, FileServerService NanoHTTPD:8080, UploadService a Nexus, HardwareController sysfs, Protocol).
- Asimiladas las memorias de desarrollo de ambos proyectos (`~/.claude/projects/...BodyCamServer/memory` y `...Falcon-One-Demo.../memory`), que documentan meses de hallazgos: MAC BT correcto tras semanas de timeouts, mapeo definitivo de botones fisicos verificado x3 por logcat (132=PTT, 133=SOS→livestream, 134=grabar), el GPS de la bodycam no emite coordenadas, la app del proveedor smarteye es launcher priv-app y se coexiste con ella, y el fix del embudo `updateState` para poder reconectar el BT.
- **Creado `docs/bodycam-contexto.md`** en este repo: memoria historica canonica con hardware, protocolo BT completo (Cmd/Rsp/Ntf/STATUS), botones, arquitectura de transportes (BT=control, Agora=video, HTTP=archivos), gotchas operativos y plan concreto de port para la fase 4.
- Memoria persistente nueva `bodycam-w1-contexto` + indice actualizado.

### Claves para la fase 4 (resumen)

- El SOS de la bodycam ES que el uid 9001 entre al canal Agora; nuestro AgoraRepository ya lo ve (autoSubscribeVideo=true y BODYCAM_UID ya excluido del contador). El BT (`BTN_STREAM_*`) es solo la via rapida.
- `BluetoothSppController.kt` del proyecto Flutter se porta casi directo como `BodycamRepository` (sin MethodChannel). UUID `FA1C0000-1337-4242-CAFE-DEADBEEF0001`, MAC a local.properties.
- Boton de bodycam en la UI = SOLO conectar/desconectar; todo lo demas lo mandan los botones fisicos. Poll STATUS cada 5 s para bateria/estado.

### Proximo paso

Sigue pendiente la prueba SOS con dos dispositivos (fase 3); despues, implementar fase 4 segun el plan de `docs/bodycam-contexto.md`.

---

## 2026-07-08 (3) — Fix: camara negra en el livestream del SOS (verificado en dispositivo)

### Hecho

- **Bug 1 — el motor de Agora nunca arrancaba** (`RtcEngine.create` devolvia null y todo fallaba en silencio: sin red, sin camara). Causa: en `AgoraRepository.ensureStarted` el config se armaba con `RtcEngineConfig().apply { mContext = context ... }`; como `RtcEngineConfig` tiene su propio `getContext()`, dentro del `apply` la referencia `context` resolvia a ese getter (null) y no al context de la app. `create()` valida `mContext` antes que nada y devuelve null sin log. Fix: asignaciones directas sobre la variable `config`, sin `apply`. LECCION: cuidado con `apply` sobre objetos Java con getters que colisionan con propiedades propias.
- **Bug 2 — camara en negro con el motor ya funcionando**: la preview usaba `SurfaceView` dentro de Compose; el SurfaceView se dibuja en una capa detras de la ventana y el fondo de la pantalla lo tapaba (captura activa, render invisible). Fix: `TextureView`, que se compone como una vista normal. Diagnostico via logs internos del SDK (`/sdcard/Android/data/<pkg>/files/agoraapi.log`, texto plano; `agorasdk.log` va cifrado).
- **Verificado en dispositivo fisico (RZCY510MBBM)**: la red se une al canal (contador de usuarios en verde = 1 en el mapa, GPS compartido), EMERGENCY abre la camara trasera EN VIVO con el badge "SOS BROADCASTING", y CANCEL SOS vuelve a Operations con el estado limpio.
- Los mensajes "failed to load library ...extension.so" del logcat son normales: son extensiones opcionales excluidas a proposito para reducir peso; el SDK las sondea y sigue.

### Pendiente

- Prueba con DOS dispositivos: recepcion del popup con sirena, "Accept & view live" mostrando el video del emisor, cancelacion remota y "Signal cut".

---

## 2026-07-08 (2) — Fase 3: livestream del SOS (camara del emisor en vivo)

### Hecho

- **SDK**: cambio de `voice-sdk` a `full-sdk` 4.5.2 (video). Exclusiones de modulos opcionales del POM (face-detect, virtual-background, screen-sharing, content-inspect, vqa, codecs AV1...) y ademas `packaging.jniLibs.excludes` para las extensiones .so que el core trae embebidas y no usamos (eco IA, denoise IA, audio espacial, audio-beauty, clear-vision, screen-capture): ahorran ~25 MB. Release final **95.4 MB** (el video cuesta ~29 MB sobre la version solo-datos; App Bundle entregara ~50 MB por dispositivo).
- **AgoraRepository**: audio/video en modo receptor estricto al entrar al canal (mic silenciado + captura apagada + volumen de grabacion 0, video propio mudo, `autoSubscribeAudio=false` / `autoSubscribeVideo=true`, altavoz por defecto), identico a Flutter. `activateSos()` ahora publica camara trasera + microfono al canal (`startCameraPublish`) ademas del broadcast con heartbeat; `cancelSos()` corta publicacion y avisa. Nuevas APIs: `attachLocalVideo`/`attachRemoteVideo` (VideoCanvas sobre SurfaceView), `startWatching`/`stopWatching` (suscribe/silencia video+voz del uid observado) y `remoteVideoStopped: StateFlow<Int?>` (video caido o emisor desconectado → aviso "Signal cut").
- **Pantalla `feature/livestream/`** (ruta `livestream/{uid}`, uid 0 = camara propia):
  - Emisor: al tocar EMERGENCY se piden permisos CAMERA+RECORD_AUDIO (el SOS se emite aunque se nieguen), se activa el SOS y se abre esta pantalla con la preview de la propia camara, badge "SOS BROADCASTING" parpadeante y boton CANCEL SOS. Salir con back NO corta el SOS (se cancela desde el boton o desde Operations); si se cancela desde otra pantalla, esta se cierra sola.
  - Receptor: el popup de alerta ahora tiene "Accept & view live" → abre esta pantalla reproduciendo la camara del emisor con su voz. Al cortarse la senal (cancelacion remota, video caido o emisor offline) aparece el aviso "SIGNAL CUT" con hora y ultima posicion; al salir se silencia la voz del emisor.
- Permisos nuevos en manifest: CAMERA, RECORD_AUDIO, MODIFY_AUDIO_SETTINGS.
- Bug corregido durante el desarrollo: el estado inicial del LivestreamViewModel arrancaba con `sosActive=false` y la pantalla del emisor se cerraba sola; ahora toma el valor real del repositorio.
- Compila limpio debug y release.

### Pendiente

- Prueba con dos dispositivos: SOS + aceptar + ver video + cancelar + signal cut.
- Sin BT/bodycam todavia (uid 9001 reservado); foreground service y centro de notificaciones SOS siguen pendientes de fases posteriores.

### Proximo paso

Prueba de campo del flujo SOS completo; luego fase 4 (bodycam BT + W1).

---

## 2026-07-08 — Fase 2 completada (red Agora: agentes en el mapa) + SOS basico

### Hecho

- **Red tactica Agora** (`data/AgoraRepository.kt`, port del CallService de Flutter):
  - SDK `io.agora.rtc:voice-sdk:4.5.2` (misma version nativa que usaba Flutter via agora_rtc_engine 6.5.3). Se eligio voice-sdk en lugar de full-sdk porque esta fase solo usa el data stream; al portar el livestream (fase 3 completa / bodycam) habra que cambiar a full-sdk.
  - Canal `falcon_group_channel` en **modo sin token** (join con token null): asi lo hace la app Flutter en produccion (el fetch al token server de Railway existe pero el join usa token vacio para que la bodycam pueda unirse). No se porto el token server.
  - `AGORA_APP_ID` en `local.properties` -> BuildConfig.
  - Modulo de audio deshabilitado (`disableAudio()`): no pide permiso de microfono y ahorra bateria; solo data stream.
  - Protocolo JSON identico al de Flutter (interoperan entre si): `location` (cada 1 s en movimiento + heartbeat 3 s), `emergency` (con heartbeat 3 s y dedupe por sessionKey uid@ts en receptores), `emergency_cancel`. Reenvio inmediato de posicion y SOS activo cuando entra un peer nuevo.
  - Marcadores remotos: se conserva la ultima posicion conocida en caidas transitorias; solo se quita el marcador si el peer sale del canal a proposito (USER_OFFLINE_QUIT). El uid 9001 (bodycam) no cuenta como usuario.
  - La red arranca en `AeriaNexusApp.onCreate` para recibir SOS sin abrir el mapa; el GPS se comparte en cuanto hay permiso de ubicacion.
- **Mapa (fase 2)**: marcadores de agentes remotos como ViewAnnotation Compose — punto dorado con anillo pulsante; a los 7 s sin mensajes pasa a gris con etiqueta "Last seen: HH:mm:ss" (igual que Flutter). Contador de usuarios conectados en el panel de estado (icono Groups, verde si conectado).
- **SOS (fase 3 parcial)**: el boton EMERGENCY de Operations ahora es real — un toque emite la alerta a todos los dispositivos (parpadea con "SOS ACTIVE — TAP TO CANCEL"), segundo toque la cancela. Los receptores ven un popup rojo global (`feature/sos/SosAlertOverlay` montado sobre el NavHost) con sirena en loop (`res/raw/emergency.wav`, copiada de Flutter), oficial emisor, hora y coordenadas; la cancelacion remota cierra el popup y muestra "Signal cut" con la ultima posicion.
- **Peso del APK**: voice-sdk arrastraba extensiones opcionales de audio (+120 MB). Se excluyeron (ains, aiaec, audio-beauty, spatial-audio, full-voice-drive...) y el release quedo limitado a ABIs ARM (las x86 son solo de emulador; debug las conserva). Release final: **66.5 MB** (antes de Agora eran 63 MB).
- Regla R8 nueva: `-keep class io.agora.** { *; }` (JNI). Compila limpio debug y release.

### Pendiente / deuda tecnica

- Probar con dos dispositivos reales (o telefono + app Flutter, son interoperables) antes de cerrar la fase.
- Foreground service para mantener GPS/red con la app en segundo plano (Flutter usaba CallForegroundTaskManager). Hoy la red muere si Android mata la app.
- Centro de notificaciones SOS (campana con historial) no portado; el popup solo muestra la alerta en vivo.
- "Accept & view livestream" no existe aun: requiere video (full-sdk), va con la fase 3 completa.

### Proximo paso

Prueba de campo de fase 2 + SOS con dos dispositivos; luego completar fase 3 (livestream del emisor con full-sdk) o saltar a fase 4 (bodycam BT).

---

## 2026-07-08 — Fix crash del mapa en dispositivo fisico (NPE en LogoUtils)

### Hecho

- **Bug**: al abrir la pestana Map en dispositivo real (Samsung, Xclipse 540) la app crasheaba con `NullPointerException` en `LogoUtils.getLogo` desde el `MapEffect` de `MapScreen.kt`.
- **Causa**: en la extension Compose de Mapbox v11, el `MapView` interno NO registra los plugins de adornos (logo, attribution, scalebar, compass); esos se manejan como slots de Compose. Llamar `mapView.logo` / `.attribution` / `.scalebar` dentro de `MapEffect` devuelve plugin nulo y revienta.
- **Fix**: se eliminaron esas llamadas del `MapEffect` (solo queda `location.updateSettings`, que si es plugin real) y los adornos se ocultan pasando slots vacios al composable: `logo = {}`, `attribution = {}`, `scaleBar = {}`, `compass = {}`.
- Compila limpio (assembleDebug) e instalado en dispositivo RZCY510MBBM.

### Proximo paso

Verificar en dispositivo que el mapa abre, el puck pulsa y llega el primer fix GPS; con eso se cierra fase 1 y sigue **Fase 2 — Red Agora**.

---

## 2026-07-05 — Fase 0 y Fase 1 completadas (mapa tactico con GPS propio)

### Hecho

- **Fase 0 (cimientos)**:
  - Repositorio Maven de Mapbox agregado en `settings.gradle.kts` (v11 no requiere token de descarga; verificado, resolvio sin credenciales).
  - Dependencias nuevas en el catalogo: `mapbox-maps` + `mapbox-maps-compose` 11.8.0, `play-services-location` 21.3.0.
  - Token publico de Mapbox en `local.properties` (clave `MAPBOX_ACCESS_TOKEN`) leido a `BuildConfig` en `app/build.gradle.kts` (`buildConfig = true`). Nada hardcodeado en fuentes.
  - Permisos en manifest: INTERNET, ACCESS_NETWORK_STATE, ACCESS_FINE/COARSE_LOCATION.
  - `AeriaNexusApp` (Application) creada: setea `MapboxOptions.accessToken` e inicializa `AppContainer.init(context)`.
- **Fase 1 (mapa tactico)**:
  - `data/LocationRepository.kt`: flujo de posiciones (FusedLocationProvider, alta precision, 1 s / 5 m, con lastLocation como primer fix) y flujo de satelites usados en el fix (GnssStatus).
  - `data/BatteryRepository.kt`: porcentaje de bateria via broadcast pegajoso ACTION_BATTERY_CHANGED.
  - `feature/map/MapViewModel.kt`: MapUiState (lat/lng/gpsReady/satelites/bateria); el GPS arranca solo tras conceder permiso.
  - `feature/map/MapScreen.kt`: MapboxMap Compose con el estilo custom de Falcon One (`mapbox://styles/fiddlie-ed/cmc9h7ar2035801sm6361cdtc`), camara 3D (zoom 16, pitch 60), puck pulsante, logo/attribution/scalebar ocultos, tarjeta de solicitud de permiso en runtime, boton recentrar (flyTo 800 ms) y panel de estado (bateria con glifo por nivel, satelites, coordenadas).
  - Pestana **Map** agregada a la barra inferior (segunda posicion) y ruta `map` en el NavHost.
- Compila limpio: debug y release (R8 no necesito reglas keep extra para Mapbox).

### Notas / deuda tecnica

- El APK paso de 1.3 MB a **63 MB en release** por las libs nativas de Mapbox (4 ABIs). Mitigacion prevista: distribuir como App Bundle (`bundleRelease`), que entrega ~15-20 MB por dispositivo. No urgente en prototipo.
- El estilo custom de Mapbox pertenece a la cuenta `fiddlie-ed` (mismo token). Si algun dia falla, fallback rapido: `Style.DARK`.
- Falta probar en dispositivo fisico real (mapa + fix GPS + permiso). Pendiente para la proxima sesion antes de dar la fase 1 por cerrada del todo.

### Proximo paso

Prueba en dispositivo de la fase 1; luego **Fase 2 — Red Agora**: port de CallService (join canal con token del server Railway, data stream de ubicaciones con throttle 1 s + heartbeat 3 s, contadores de usuarios), marcadores de agentes remotos en el mapa (dorado con pulso, gris + "last seen" a los 7 s) y foreground service.

---

## 2026-07-04 — Estado inicial del proyecto

### Contexto general

- **Aeria Nexus Prototype** (este repo): app Android nativa en Kotlin + Jetpack Compose para uso policial en campo. Mantenida por personal junior: codigo simple, sin frameworks complejos (sin Hilt, sin multi-modulo). Reglas de trabajo en `.claude/skills/` (arquitectura-simple, estilo-codigo-limpio, ui-ux-policial, app-ligera).
- **Falcon One Demo** (Flutter, `C:\Users\newge\Desktop\BodyCam\Falcon-One-Demo-main\Falcon-One-Demo-main`): version anterior de la app, con hardware e integraciones REALES. Es la fuente de las funcionalidades a portar.
- **Prototipo web Replit** (`Aeria-Nexus-Prototype/` dentro de este repo): prototipo React del flujo de reportes; ya fue portado completo a Kotlin.

### Hecho hasta hoy

1. **Skills del proyecto creadas** en `.claude/skills/`: reglas de arquitectura MVVM simple, estilo de codigo, UX/UI policial y rendimiento.
2. **Port completo del prototipo web Replit a Kotlin/Compose** (compila sin warnings; APK release 1.3 MB con R8):
   - 8 pantallas: Operations, Incidents (busqueda+filtros), Incident Detail, Active Incident (cronometros, grabacion simulada, clasificacion de evidencia, QR testigo), Draft Report IA (confianza por parrafo, evidencia enlazada), RMS Form (4 pasos), Submission Success, Profile.
   - Arquitectura: `data/model` + `data/` (repositorio en memoria + AppContainer manual), `feature/<pantalla>/` (Screen + ViewModel con StateFlow), `ui/components/`, `ui/theme/` (tema oscuro #080B12, azul #3B82F6), `navigation/AppNavHost.kt`.
   - Datos simulados en `OfficerSampleData.kt` y `ReportSampleData.kt`.
   - Nota: "Generate RMS Draft" navega fijo a INC-001 (igual que la web); la pantalla Timeline de la web no se porto porque su ruta no estaba registrada (inalcanzable).
3. **Fix de navegacion**: el flash entre pantallas era el windowBackground claro del tema XML. Corregido: `themes.xml` oscuro + fondo fijo bajo el NavHost + fundidos de 220/180 ms.
4. **Analisis completo de Falcon One (Flutter)**. Funcionalidades catalogadas:
   - Mapa tactico Mapbox (estilo custom, 3D, puck, marcadores de agentes con pulso y estado "last seen" a los 7 s de silencio).
   - Red Agora RTC: canal `falcon_group_channel`, data stream JSON (`location` cada 1 s + heartbeat 3 s, `emergency`, `emergency_cancel`), audio receive-only estricto, token server en Railway, foreground service para GPS en background.
   - SOS: broadcast con heartbeat y dedupe por sessionKey, popup con sirena en loop, aceptar → ver livestream del emisor, cancelacion remota, "Signal cut" con geocoding inverso, centro de notificaciones SOS.
   - Bodycam BT (SPP, UUID custom, MAC 40:45:DA:44:C8:9B): botones fisicos PTT=132, SOS=133 (→ livestream, entra a Agora como uid 9001), REC=134 (grabacion local). Poll STATUS cada 5 s. EL CODIGO NATIVO YA EXISTE EN KOTLIN dentro del proyecto Flutter (`android/.../BluetoothSppController.kt`, `BodyCamChannel.kt`, `GlassesChannel.kt`) — reutilizable casi directo.
   - W1 (servidor HTTP de la bodycam): /status, /recordings, /recordings/latest, descarga de video, polling 3 s.
   - Subida de evidencia REAL: POST multipart a `https://nexus.aeriaone.com/api/incidents/upload/` con officer_code + raw_metadata + video. Upload de fotos en stub (endpoint pendiente del backend).
   - Fotos: camara nativa o snapshot del stream Agora, carpeta falcon_pictures, galeria.
   - Gafas BleeQup: scan BLE, conexion, grabar, descarga por WiFi AP de las gafas con progreso.
   - LiveKit esta en pubspec pero es LEGADO sin uso — no se porta.
   - Credenciales hardcodeadas en Flutter (token Mapbox, Agora appId `ff51540c...`, token server, MAC bodycam): en el port van a `local.properties`/BuildConfig.

### Roadmap de port Falcon One → Kotlin (acordado, pendiente de arrancar)

- **Fase 0 — Cimientos**: permisos manifest, secretos a BuildConfig, dependencias (Mapbox Android SDK, Agora Android SDK, OkHttp).
- **Fase 1 — Mapa tactico**: pantalla Mapbox + GPS propio + bateria. Entregable: mi ubicacion en vivo.
- **Fase 2 — Red Agora**: CallService Kotlin, data stream ubicaciones, marcadores remotos, foreground service. Entregable: dos telefonos se ven en el mapa.
- **Fase 3 — SOS completo**: broadcast/cancel, sirena, notificaciones, livestream publish/watch, signal cut. Entregable: SOS entre dos telefonos.
- **Fase 4 — Bodycam BT + W1**: reutilizar BluetoothSppController, protocolo BTN_*/STATUS, uid 9001, cliente W1. Entregable: bodycam fisica dispara SOS e importa video.
- **Fase 5 — Evidencia a Nexus + fotos**: UploadService real, captura y galeria; conectar con el flujo Active Incident existente. Entregable: video real subido a Nexus.
- **Fase 6 — Gafas**: port GlassesChannel (SDK BleeQup). Entregable: gafas graban y descargan.
- **Fase 7 — Unificacion UX**: mapa/panel al design system Aeria Nexus, limpiar flujos demo.

### Decisiones pendientes (bloquean la fase 1)

1. Confirmar Mapbox como SDK de mapas (recomendado) o cambiar a otra opcion.
2. Mantener o eliminar los flujos de demo (chooser "Own/External", agente simulado "Officer 007").
3. Ubicacion del mapa en la app Kotlin: propuesta = cuarta pestana "Map" en la barra inferior; el boton EMERGENCY de Operations pasaria a disparar el SOS real (fase 3).

### Proximo paso

Al confirmar las 3 decisiones: ejecutar Fase 0 + Fase 1.

### Como compilar / verificar

- Debug: `gradlew.bat assembleDebug` — Release: `gradlew.bat assembleRelease` (R8 activado).
- Verificar siempre en dispositivo antes de marcar una fase como cerrada.
