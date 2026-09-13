# DEVLOG — Aeria Nexus (app Kotlin)

Bitacora de desarrollo del proyecto. Se actualiza cada dia de trabajo.
Regla: cada entrada nueva va ARRIBA, con fecha, que se hizo, decisiones tomadas y proximo paso.
Este archivo es la fuente de verdad para retomar el desarrollo en cualquier sesion.

---

## 2026-09-13 — El SOS de la bodycam hace grabar a las gafas (parte 1), y lo que dice el AAR

### Por que

El manager cerro una cuestion de producto: **el oficial no va a manejar los perifericos desde
la app**. En campo no hay tiempo. Solo pulsara el SOS o la grabacion de la bodycam, que lleva
en el pecho. Asi que las gafas tienen que obedecer sin que nadie toque el telefono.

Las gafas y la bodycam **no pueden hablarse**: las gafas solo entienden el GATT propietario
del fabricante y lo unico que lo habla es su SDK de Android, que corre en el movil. El puente
es el telefono, pero invisible.

### Lo que se ha hecho (parte 1: solo SOS)

- **`data/ReleSosGafas.kt`** (nuevo) — oye el SOS de la bodycam y manda grabar/parar a las
  gafas. Escucha `BodycamRepository.isStreaming` y **no** `buttonEvents`: el boton avisa
  antes pero se pierde en un microcorte, mientras que `isStreaming` lo alimentan el boton Y
  el STATUS de cada 5 s. En una emergencia importa mas no perderselo que ganar un segundo.
- **`data/GafasCommandRepository.kt`** — canal permanente con `mantenerCanal()` /
  `soltarCanal()` y bucle de reintento con espera creciente, copiando el patron de
  `BodycamRepository`. El bucle vigila **tambien el READY**, no solo la conexion: el SDK
  puede abrir el GATT y no dar nunca READY, y sin READY no acepta ordenes.
  `iniciarGrabacion()` acepta un callback con el resultado.
- **`BodycamService.kt`** — pasa a ser dueño de los DOS enlaces y arranca el rele. Va aqui
  porque es lo unico que mantiene el proceso vivo en segundo plano.
- **`feature/gafas/GafasControlViewModel.kt`** — ya **no** cierra el canal en `onCleared()`.
  Cerrarlo dejaria al oficial sin gafas en la siguiente emergencia. La pantalla FALCON LENS
  pasa a ser, de hecho, un indicador.
- **`data/GafasSondaEstado.kt`** (nuevo, solo debug) — sonda que engancha todos los avisos
  que las gafas empujan solas. Se lanza con `--es gafas_sonda escuchar|ciclo|parar`.

### Verificado contra el hardware el mismo dia

```
17:41:07.770  SOS de la bodycam: se manda grabar a las gafas
17:41:10.112  startRecord -> ok=true
17:41:10.113  las gafas estan grabando el SOS
17:41:26.310  fin del SOS: se manda parar a las gafas
17:41:27.546  stopRecord -> ok=true  VIDEO_1788984986630.mp4
17:41:28.262  aviso de video: VIDEO_1788984988214.mp4
```

Disparado por broadcast del boton 133 en la W1, con las dos MAC reales y el telefono en el
bolsillo. Unos 2,3 s desde que el rele reacciona hasta que las gafas confirman.

### Lo que se averiguo desmontando el AAR y midiendo

- **No existe ninguna orden para preguntar si las gafas graban.** Ni publica ni interna.
  Confirmado sobre los 13 metodos de `BleeqUpCommandManager` y los simbolos ofuscados.
- **Pero si avisan de lo que hacen solas.** `registerButtonCallback` (publico, no lo usaba
  nadie) entrega las pulsaciones fisicas, y se midio la semantica: **derecho corto = foto**
  (3/3), **derecho largo = arranca/para grabacion**, izquierdo corto sin efecto en camara.
  Con eso se puede sostener el estado sin mentir. Tambien hay `registerPowerCallback`
  (bateria de las gafas) sin usar.
- **Los avisos de camara llegan por CUALQUIER captura**, tambien las que no pidio el
  telefono, y **llegan al PARAR**, no al empezar (cierra el TODO que habia en el codigo).
  Lo que NO traen es el origen: las cadenas `appcapture` / `handcapture` / `aicapture` estan
  en `a/x`, que parsea el `fileType` de `Data`, y `Data` pertenece al listado REST por WiFi.
  El origen solo se sabe listando ficheros por el punto de acceso.
- **`startRecord` responde `ok=true` con el mensaje `start record failure` y graba bien.**
  Reproducido 3 veces. El `ok` vale; el texto no se le puede enseñar a nadie.
- **El opcode interno `0x10104` (`registerStateCallback`) NO es el estado de grabacion.**
  No disparo ni una vez en un ciclo completo. Con `unRegisterSomState` de por medio (SOM =
  System On Module) apunta a estado de sistema. Via cerrada, no volver a mirarla.
- **El espacio libre venia en KILOBYTES, no en bytes.** `total=26540012 libres=19679128` son
  25,3 GB de tarjeta y 18,8 GB libres, y la pantalla decia "0.0 GB". Arreglado en
  `GafasControlViewModel.formatearEspacio()` y el campo se renombro a `kilobytesLibres`.
- **El canal BLE se cae solo.** Medido: se solto a los 22 minutos sin que nadie lo pidiera.
  De ahi que el bucle de reintento no sea opcional.
- **El enlace LE solo existia dentro de la pantalla.** Con el GATT cerrado el `dumpsys` pasa
  de `ACL BR/EDR:Y LE:Y` a `LE:N`: el BR/EDR (audio) es del sistema y nunca se cae, que es
  por que el icono de la barra no parpadeaba mientras la pantalla decia "Conectando...".

### Decisiones tomadas

- **Parte 1 solo con el SOS (boton 133).** El 134 se queda fuera: no es "empieza a grabar"
  sino que conmuta un buffer que a menudo ya esta corriendo — el primer disparo de la prueba
  devolvio `BTN_REC_STOP`. Atarle las gafas seria arrancarlas y pararlas todo el turno.
  Decidirlo con datos de un turno real antes de activarlo.
- **Un fallo de las gafas nunca retrasa ni rompe el SOS.** El rele deja `NO_DISPONIBLES` y
  un `Log.e("SOS SIN GAFAS")`, y ese estado no se pisa al terminar el SOS.
- **Las fotos de las gafas se descartan**: sin uso practico, dicho por el usuario.

### Parte 2, el mismo dia: el estado deja de mentir

`grabando` ya no es "la ultima orden que dimos". Se mantiene con tres fuentes, en orden de
fiabilidad: lo que mandamos nosotros, el **boton fisico del agente** (derecho largo conmuta) y
el **aviso de video**, que llega al cerrar fichero y manda sobre los otros dos porque es el
unico dato duro: si hay aviso, la grabacion termino, venga de donde venga la orden.

Todo dentro de `GafasCommandRepository`: `escucharBotones()` nuevo, `onVideoInfo` pone
`grabando` a false, y ambos se sueltan en `desconectar()`.

Verificado contra el aparato:

```
17:57:42.090  boton derecho de las gafas: long        (el agente arranca)
17:58:16.232  boton derecho de las gafas: long        (el agente para)
17:58:18.340  aviso de video: VIDEO_1788984930954.mp4 (lo confirma 2,1 s despues)
```

Grabacion de 34 s hecha entera desde las gafas, sin tocar el telefono. **Ojo: se verifico la
maquina de estados en el log, no los pixeles** — la grabacion termino antes de poder capturar la
pantalla FALCON LENS en marcha. Y se vio un `getStorageInfo fallo: command timeout` aislado
justo despues de conectar, sin consecuencias pero conviene no olvidarlo.

El gesto largo sale de **dos muestras**, no de veinte. Por eso el aviso de video tiene la ultima
palabra y no al reves.

### Listado por WiFi: resuelto lo de los dos nombres de fichero

`data/GafasSondaListado.kt` (nuevo, solo debug) enciende el AP de las gafas, une el telefono y
vuelca el listado entero con todos los campos. Se lanza con
`--es gafas_listar <clave8> --es gafas_pais US`.

**Solo existe UN fichero.** El nombre que devuelve `stopRecord` **no esta en la tarjeta**;
el que anuncia el aviso de camara si, y su duracion cuadra con lo medido:

| Grabacion | Medido por reloj | `fileDuration` |
|---|---|---|
| ciclo 16:45 | 7,14 s | 7.198 ms |
| SOS 17:41 | 16,2 s | 16.265 ms |
| boton largo 17:57 | 34,1 s | 31.891 ms |
| ciclo 15:38 | 25,3 s | 24.194 ms |

Comprobado contra los 61 ficheros del listado: `VIDEO_1788985578117`, `VIDEO_1788989567464` y
`VIDEO_1788984986630` (los tres de `stopRecord`) **NO EXISTEN**. Es otro campo basura del SDK,
como el `start record failure`. **Regla: el aviso de camara es la fuente buena.** La parte 2 ya
se apoyaba en el, asi que no hay nada que cambiar.

### Dos hallazgos nuevos del listado, ninguno menor

1. **El reloj de las gafas salta hacia atras al apagarlas.** Las marcas de los nombres no son
   monotonas: 15:39 -> `1788985578949`, 16:45 -> `1788989568207` (+66,5 min, cuadra), pero
   17:41 -> `1788984988214`, que es **76 minutos ANTES** que el de las 15:39. El salto coincide
   con el apagado y encendido del aparato. **No se puede fechar evidencia por el nombre ni por
   `fileTime`.** El SDK trae `timeStampSync(device, callback)` justo para esto y **no lo llama
   nadie**: deberia ir en el `onReady` del canal.
2. **Las gafas trocean a los 5 minutos.** Seis ficheros con duracion clavada en ~300.000 ms
   (300184, 300395, 300350, 300028, 300484, 299851). Un SOS largo **no produce un video, produce
   varios**, y la parte 3 tiene que recogerlos todos. Encaja con el par `videoName`/`videoParent`
   de la clase `Data`, que el `FileData` publico tira.

Confirmado ademas lo que se sospechaba: los videos salen con `fileType='video'` a secas. El
`appcapture`/`handcapture`/`aicapture` es cosa de **fotos** y el SDK los colapsa a `image`; como
las fotos se descartan, esa via no sirve para nada. Tema cerrado.

**La parte 3 ya tiene cimientos:** `listar()` de `GafasMediaRepository` era un hueco con un TODO
de protocolo y ya no hace falta adivinarlo. Tipos validos `all` / `video` / `image`, borrado en
`/delete?fileName=`, y el listado trae nombre, tipo, miniatura, bytes, duracion, instante y
resolucion. **Pero ojo:** el SDK usa su propio OkHttp y no conoce la red del AP, asi que hay que
atarle el proceso con `bindProcessToNetwork`. En produccion **eso no se puede hacer** (ataria
Agora, las subidas y el mapa a una red sin internet); la sonda lo hace y lo deshace en un
`finally`, pero para la parte 3 hay que resolverlo de otra forma.

### Parte 3: la descarga, y el canal 149 deja de ser un problema

**El despliegue es en FILIPINAS.** Eso tumba la objecion regulatoria que bloqueaba todo desde el
09-sep: la banda de 5,8 GHz alli si se usa. Y probado contra el firmware el mismo dia, **`PH`
funciona**, asi que no hay que declarar `US` ni ningun pais falso:

    4/4 turnOnWifi clave='Aeria123' pais='PH' -> AP ENCENDIDO

Va en `local.properties` como `PAIS_PERIFERICOS=PH` -> `BuildConfig`, igual que las MAC, y se
aplica solo alrededor de `turnOnWifi` **restaurando el Locale despues**: cambiarlo afecta al
proceso entero (formatos de fecha y numero de toda la app) y no puede quedarse puesto.

**El protocolo entero, sacado del AAR** (al re-extraerlo sin colisiones de mayusculas/minusculas,
que en Windows habian machacado 13 clases):

    Listar:    https://<ip>/list?fileType=all|video|image
    Descargar: https://<ip>/<fileType>?fileName=<nombre>   (+ Range: bytes=N-)
    Borrar:    https://<ip>/delete?fileName=<nombre>

Con eso **no hace falta `bindProcessToNetwork`**: la descarga va con HTTP propio atando socket a
socket, y Agora, las subidas y el mapa siguen con su red. Era el bloqueo de fondo de la parte 3.

Ficheros: `data/DescargaDeGafas.kt` (el ciclo entero), `data/GafasPendientesRepository.kt` (lista
en disco), `data/AvisoDeGafas.kt` (la notificacion), y `listar()`/`descargar()` de
`GafasMediaRepository` ya de verdad — se fueron sus tres `TODO PROTOCOLO`.

**Verificado de punta a punta el 2026-09-13, sin ningun override de adb:**

    20:18:56  ReleGafas: la bodycam esta capturando: se manda grabar a las gafas
    20:19:10  aviso de video: VIDEO_1788989342108.mp4
    20:19:10  GafasPendientes: pendiente de descargar (1 en total)
    20:19:10  AvisoDeGafas: avisado: 1 video(s) pendiente(s)
    20:20:04  declarando pais 'PH' para encender el AP (canal 149)
    20:20:18  VIDEO_...mp4 en la boveda: gafas_agent_007_2026-09-13_20-20-17-005.mp4.fev
    20:20:18  descarga terminada: 1 traidos, 0 fallados
    20:20:18  AP apagado ok=true

14 segundos del boton al fichero cifrado.

### Dos fallos encontrados por el camino

1. **Las claves de un `LazyColumn` son unicas en TODA la lista, no por bloque `items()`.** La
   boveda usaba el nombre del fichero como clave en sus dos apartados, y un video recien traido
   esta en los dos a la vez —sellado Y pendiente de categorizar—, asi que **la app moria** al
   abrir la boveda. Arreglado con prefijos `pendiente:` / `boveda:`. Bug viejo, de la sesion de
   categorizacion del 09-sep; la descarga solo fue lo primero que lo destapo.
2. **`createTarget` nombraba con resolucion de segundos.** Con capturas manuales nunca fallo,
   pero bajando varios ficheros en bucle dos podian salir con el MISMO nombre y pisarse. Ahora
   lleva milisegundos y ademas comprueba que no exista.

### Aviso en vivo cuando el SOS se queda sin gafas

Era el hueco que quedaba del diseño: el fallo solo dejaba un `Log.e` que el oficial nunca iba a
ver. Ahora `AvisoDeGafas.avisarSosSinGafas()` lanza una notificacion **con sonido y vibracion**,
en canal propio y con id propio para que no quede tapada por la de "video pendiente".

**Solo en SOS.** Una grabacion rutinaria que se quede sin gafas sigue dejando solo el log: el
video se puede repetir, y un aviso sonoro por cada grabacion acabaria ignorandose, que es
justo lo que no puede pasarle al aviso importante. El rele distingue el caso mirando
`isStreaming` en el momento de arrancar.

Desde Android O el sonido y la vibracion los manda el CANAL, no la notificacion: por eso va en
`crearCanal(vibra = true)` y no con el `setDefaults` deprecado.

**Sin verificar contra el hardware**: para provocarlo hay que apagar las gafas y disparar un SOS.

### Pendiente / proximo paso

0. **Antes de entregar nada al manager: reactivar `FLAG_SECURE`** (`MainActivity.kt:79`, sigue
   comentado desde el 22-jul) y generar **release**, no el debug de 239 MB con las sondas dentro.

1. **Llamar a `timeStampSync` al quedar READY.** Hoy las gafas fechan mal sus ficheros.

2. **Parte 3**: descarga y entrega al backend. Ojo, **el AP WiFi esta apagado mientras
   graban**, asi que no se puede descargar durante el incidente; y si el oficial no
   interactua, hay que decidir CUANDO se descarga (al cerrar incidente, al volver a
   comisaria, al poner a cargar). Es operativa, preguntar al manager.
3. **Sin explicar**: `stopRecord` devuelve un nombre de fichero y el aviso anuncia otro
   distinto ~0,8 s despues (2 de 2). Puede ser el par `videoName`/`videoParent` de `Data`.
   Se resuelve listando por WiFi.
4. El boton "SOLTAR EL MANDO" de FALCON LENS suelta el canal y el bucle lo vuelve a levantar
   a los pocos segundos. Se dejo asi a peticion del usuario (no tocar la pantalla).

---

## 2026-09-12 — Mando a distancia de las gafas: grabar y parar desde Nexus

### Que se ha hecho

El SDK del fabricante ya traia `startRecord`, `stopRecord` y `takePhoto` (se vio leyendo
el AAR el 09-sep), pero no los llamaba nadie: lo unico que la app hacia con las gafas era
mirar si estaban enlazadas. Ahora hay mando de verdad.

- **`data/GafasSdkPuente.kt`** (nuevo): el arranque del SDK y el apano por reflexion que
  le entrega el aparato emparejado sin escanear, en UN solo sitio. Antes vivia dentro de
  `GafasApPrueba`; ahora lo comparten la sonda y el canal de mando, asi que el dia que el
  fabricante recompile el AAR solo hay un fichero que arreglar.
- **`data/GafasCommandRepository.kt`** (nuevo): abre y cierra el GATT propietario, y manda
  `startRecord` / `stopRecord` / `takePhoto`. Al quedar LISTO pregunta resolucion
  (`getCameraSetting`) y espacio libre (`getStorageInfo`). Registra tambien el
  `CameraListener` del SDK, que avisa de los ficheros que se van cerrando.
- **`feature/gafas/`** (nuevo): `GafasControlViewModel` + `GafasControlScreen`, al estilo
  del controlador de la bodycam. Se conecta sola al entrar y **suelta el GATT en
  `onCleared`**: sin eso Android cachea el enlace y las escrituras siguientes fallan con
  `write characteristic error` (costo tres ejecuciones averiguarlo en septiembre).
- **Operations**: la fila de BODYCAM CONTROL pasa a ser dos botones, BODYCAM y FALCON
  LENS. El composable del boton estaba duplicandose, asi que ahora es uno solo
  (`DeviceControlButton`), y `DeviceActionButton` se ha subido a `ui/components/CommonUi.kt`
  porque ya lo usan dos pantallas.

### Lo que este canal NO puede decir, y sale escrito en pantalla

**El SDK no tiene ninguna orden para preguntar si las gafas estan grabando.** Solo hay
arrancar y parar. Asi que el `grabando` de la app es *la ultima orden que dimos*, no un
hecho: si el agente usa el boton fisico de las gafas, el telefono no se entera y el
indicador miente. La pantalla lo dice con todas las letras en vez de aparentar certeza.

Queda una via para cerrar ese agujero cuando haya gafas delante: el SDK trae
`registerButtonCallback` (boton izquierdo/derecho, con `single`/`double`/`long`) y
`registerCameraCallback`. Si `onVideoInfo` llega al cerrar el fichero, sirve para corregir
el estado; hoy solo se registra y se enseña el nombre, porque **no se sabe si llega al
empezar o al terminar** y no se va a adivinar.

### Dos limites que no son nuestros

- **Grabar y descargar se excluyen**: las gafas apagan su punto de acceso WiFi mientras
  graban, para ahorrar bateria. El video no se puede traer hasta que la grabacion pare.
- El mando va por BLE, asi que **no depende del lio del canal 149**: esa decision (emitir
  en 5,8 GHz en Europa) solo bloquea la descarga del video, no el control.

R8 no es un riesgo aqui: `-keep class com.bleequp.bleequplibrary.** { *; }` ya estaba por
el Gson del AAR y cubre tambien los nombres internos que usa la reflexion.

### Estado: COMPILA, SIN PROBAR CONTRA LAS GAFAS

`assembleDebug` limpio, sin warnings nuevos. **No se ha ejecutado contra el hardware**: no
habia ningun aparato por adb en esta sesion. `startRecord` y `stopRecord` no se han visto
funcionar nunca; su existencia salio de leer el bytecode del AAR, no de una ejecucion.

### Proximo paso

1. Probar con las gafas puestas: conectar, GRABAR, PARAR, FOTO, y mirar en que momento
   llega `onVideoInfo` (`adb logcat -s GafasCommand AeriaGafasPuente`).
2. Si el orden de los avisos lo permite, corregir `grabando` con el `CameraListener` y
   enganchar los botones fisicos con `registerButtonCallback`.
3. Sigue pendiente pedirle al fabricante un `connect(mac)` que no pase por el escaneo,
   para quitar la reflexion.

---

## 2026-09-11 — Peticion del manager: original 1080p/30 + proxy 720p/15 (solo analisis)

Peticion: grabar dos videos a la vez, el original sin comprimir a 1080p/30 y un proxy
a 720p/15 que se borra al subirlo. Hoy **ninguna de las dos apps puede hacerlo tal cual**.
Sin codigo todavia; sin aparatos por adb, asi que nada medido.

- **Telefono:** graba con la app de camara del sistema (`CaptureVideo` en
  `ActiveIncidentScreen`). Ese intent no deja elegir resolucion ni fps y devuelve un
  solo fichero. Hace falta grabador propio: Camera2 -> GL -> dos encoders, con un solo
  microfono alimentando los dos muxers.
- **Bodycam:** graba a **720p/30 y 4 Mbps**, no a 1080p. Su HAL solo garantiza dos
  streams (preview + grabador, ver el comentario del monitor en `RecordingActivity`),
  asi que un tercer stream para el proxy no cabe. Y lo mas serio: **`VideoStamper`
  re-encoda el original para quemar el rotulo del oficial** y despues borra los
  segmentos. La evidencia que se sella y se sube es una segunda generacion, justo lo
  que la peticion prohibe. El rotulo fue requisito de producto (BC-1, 23-ago), asi que
  eso lo tiene que decidir el manager.
- **Propuesta para la bodycam:** original = remux sin recodificar
  (`IncidentAssembler.remux`, que ya existe); proxy = la pasada del `VideoStamper`,
  pero a 720p/15 y con bitrate bajo, con el rotulo solo ahi. No es simultaneo sino al
  parar, igual que hoy, y no cuesta mas tiempo que el que ya se gasta.
- **Gafas:** graban en el aparato (~12 Mbps medidos por el listado); un proxy solo
  saldria transcodificando en el telefono tras la descarga.

### Decidido con el usuario el mismo dia

- **Para que es el proxy:** el backend lo pasara por un LLM para transcribir lo que
  ocurre. Lo de los tokens es cosa del backend (ojo: en Gemini el coste por fotograma no
  depende de la resolucion; se les dijo).
- **Original a 1080p, copia a 720p.** En el telefono, con **CameraX**
  (`QualitySelector` FHD): el intent de la camara del sistema no deja pedir resolucion ni
  fps (solo calidad 0/1). CameraX solo admite una grabacion a la vez, asi que el proxy se
  genera despues, transcodificando.
- **Rotulo solo en el proxy.** El original no se recomprime. Lo que ata el video al
  agente y al aparato tiene que ser una **firma del manifest** con la clave IAM de la
  unidad mas la declaracion de `BindingAgente`, no un texto quemado. Queda pendiente y
  aparte.
- **SOS: lo graba Agora Cloud Recording (opcion D).** En la app, el SOS cierra la
  grabacion de CameraX, le cede la camara a Agora y se reanuda al terminar. La bodycam ya
  pierde la grabacion durante su SOS, asi que la nube cubre los dos aparatos.

### Lo que Cloud Recording obliga a hacer (investigado)

- **Lo arranca y lo para el backend, por REST** (acquire/start/stop, con Customer ID y
  secret). **Esas credenciales no pueden ir en el APK.**
- **Agora no avisa de que empieza un SOS:** sus avisos de canal (NCS) solo cubren
  entrar y salir (101-112), no publicar video. Los telefonos estan siempre dentro, asi
  que el aviso tiene que salir de las apps: telefono en `activateSos`/`cancelSos`,
  bodycam en `LivestreamService.start`/`stop`.
- **`maxIdleTime` solo corta con el canal VACIO.** Con telefonos siempre dentro nunca
  se para solo: el backend tiene que parar a mano, y si la app muere sin avisar, por
  latido con caducidad.
- El uid del grabador no puede coincidir con ninguno del canal (los telefonos entran
  con uid aleatorio) y todas las bodycam comparten el 9001: con dos a la vez no se
  distinguirian.
- Sin confirmar: si el modo individual da MP4 o solo HLS, y el cifrado en el
  almacenamiento. El canal va sin token (sin App Certificate).

### Hecho (tarde del mismo dia)

- **`docs/BACKEND-PROXY-AND-SOS.md`** (en ingles, como `UPLOAD-PROTOCOL.md`): `kind=proxy`
  con `proxy_of`, y los tres avisos del SOS (`sos/start|heartbeat|stop`, idempotentes
  por `sos_id`, latido cada 10 s y corte a los 30 s sin latido). Rango de uids
  90000-99999 reservado al grabador en la nube.
- **Telefono:**
  - `PhoneCameraOverlay` con CameraX (`LifecycleCameraController`, FHD con respaldo).
  - El claro se conserva al cifrar (`seal(conservarClaro)`); `ProxyRepository` y
    `ProxyEncoder` (Media3 Transformer) hacen el 720/15, lo cifran en `proxies/` y lo
    borran al entregarse. El `.proxy.json` junto al `.fev` guarda el enlace para
    reanudar; `reanudar()` rehace al arrancar los que quedaron a medias.
  - `EvidenceUploader` entrega primero el proxy y luego el original.
  - `SosNotifier` + `api_url` en `upload.conf`. Los telefonos entran al canal con uid
    >= 100000.
  - SOS desde la camara: cierra la grabacion, suelta la camara y lanza el SOS; al
    cancelarlo, la camara se reabre y graba sola.
- **Dependencias:** CameraX 1.6.2 y Media3 1.10.1. **Media3 1.11 no compila aqui**: viene
  con Kotlin 2.2 y el proyecto esta en 2.0.21. APK debug +5,4 MB; el de release no se
  midio porque `assembleRelease` sube la version.

### Estado: VERIFICADO EN SAMSUNG Y W1, contra los stubs

`tus_stub_server.py` en :1080, y un stub de SOS en :1081 que vive en el scratchpad de la
sesion, no en el repo. El Samsung va por datos moviles: se llego al PC con `adb reverse
tcp:1080 tcp:1080`.

- Original 1920x1080 a 30,0 fps (17 Mbps); proxy 1280x720 a 15,0 fps, 3,8 veces menor.
- `proxy_of` = sha256 del original descifrado. `.fev` para `vault:v1` + `srv`.
- Reanudacion tras reinstalar: el proxy y el original llegaron verificados y con su
  `incident_id`.
- SOS: start, latidos y stop en el stub, uid 977330591; y la camara volvio a grabar sola.

**El fallo que casi pasa:** con la vista previa a pantalla completa (`FILL`), CameraX
recorto la GRABACION a 886x1920. El controlador aplica a todos los casos de uso la
proporcion de la vista previa. Con `ScaleType.FIT_CENTER` sale el fotograma entero.

**Sin confirmar:** la vista previa salio negra tanto en CameraX como en el livestream de
Agora, sin errores de camara en el log. Todo apunta a que el Samsung tenia la camara
tapada; hay que mirarlo con la camara destapada.

### Proximo paso

1. Backend: implementar la especificacion (sobre todo `kind=proxy` y Cloud Recording).
2. Mirar la vista previa con la camara destapada.
3. Nada de esto esta commiteado.

---

## 2026-09-09 (3) — El AP de las gafas se enciende: era el codigo de pais

### El resultado, primero

El fabricante entrego por fin un SDK (`D:\newFalconDocs\Falconlibrary-release\bleequplibrary-release.aar`,
ya copiado a `app/libs/`). Con el, y con un arreglo que no estaba en ningun sitio, **el
punto de acceso de las gafas se enciende desde Nexus**:

    4/4 turnOnWifi clave='Aeria123' pais='ES' -> AP NO ENCENDIDO: Open WiFi failed
    4/4 turnOnWifi clave='Aeria123' pais='CN' -> AP ENCENDIDO  SSID='BleeqUp-Ranger-901FC'
    4/4 turnOnWifi clave='Aeria123' pais='US' -> AP ENCENDIDO  SSID='BleeqUp-Ranger-901FC'

Confirmado fuera del SDK, con el escaner del propio telefono:

    BSSID be:e0:33:66:1a:f8   5745 MHz   BleeqUp-Ranger-901FC   [WPA2-PSK-CCMP-128]

**5745 MHz es el canal 149.** Ese canal no esta permitido en el dominio regulatorio
europeo, y por eso el firmware se niega cuando el telefono dice `ES`: el SDK mete
`Locale.getDefault().getCountry()` en la trama, en 3 bytes, y no lo expone por parametro.
Un movil español nunca puede encender ese AP. **Esto es lo que llevaba semanas sin
funcionar, y no era codigo nuestro.**

### Lo que esto obliga a decidir, y no es tecnico

El unico canal que las gafas ofrecen para el video esta fuera de la banda que se puede
usar aqui. Encenderlo declarando un pais que no es el nuestro hace que funcione, pero es
emitir en 5,8 GHz en Europa. **Eso lo decide el cliente, no el desarrollo**, y va junto a
las otras cuatro decisiones del informe del 2026-09-07 (2). Mientras tanto queda a la
vista: la sonda lo pide con `--es gafas_pais`, no escondido en una constante.

### Los tres huecos PROTOCOLO, resueltos

De leer el bytecode del AAR (`javap`, R8, mapa `8f1dc9a`):

1. **Credenciales del AP** — `BleeqUpWifiManager.turnOnWifi(device, password, cb)`. La
   clave **la elegimos nosotros** y se valida contra `^[A-Za-z0-9]{8}$` (8 exactos). El
   SSID que devuelve es **el nombre BLE del aparato**: `BleeqUp-Ranger-901FC`. Seguridad
   WPA2-PSK, o sea que el `setWpa2Passphrase` de `GafasMediaRepository` vale tal cual.
2. **Listado** — `GET https://<ip>/list?fileType=<tipo>`, JSON a `MediaListResponse`
   (`fileName, fileType, fileThumbName, fileSize, fileDuration, fileTime, fileResolution`).
3. **Descarga** — `GET https://<ip>/<fileType>?fileName=<nombre>` con `Range: bytes=N-`
   para reanudar. Borrado: `GET https://<ip>/delete?fileName=<nombre>`. El
   `imu_log?fileName=` que se capturo el 07-sep no era un endpoint suelto: `imu_log` era
   el `fileType`.

**Es `https://`, no `http://`**, con certificado propio: el SDK monta un TrustManager que
acepta todo. `GafasMediaRepository.abrir()` va por `http://` y hay que cambiarlo, con el
TrustManager acotado a esa conexion como ya dice su comentario.

### Dos cosas del SDK que cambian el plan

- **`BleeqUpSDK.init(context, apiKey, cb)` no valida la clave.** Pone su bandera a cierto
  y responde "Certification successful" sin tocar la red. **No hace falta licencia de
  partner**; y la respuesta "funcionalidades internas: no" del 07-sep se queda corta: el
  SDK trae `startRecord`, `stopRecord`, `takePhoto`, `get/setCameraSetting`,
  `getStorageInfo`, `timeStampSync` y callbacks de boton y de energia.
- **El SDK no toca WiFi.** Cero referencias a `android.net.wifi` o `ConnectivityManager`:
  la `ip` se la pasa quien llama. Unirse al AP sigue siendo nuestro y la mecanica que ya
  estaba escrita sirve. Ojo: su OkHttp usa la red por defecto, asi que usar SU
  `getFileList`/`downloadFile` exigiria `bindProcessToNetwork`, que es justo lo que
  `GafasMediaRepository` evita para no dejar sin red a Agora y al mapa. **Recomendacion:
  el SDK solo para BLE; el HTTP lo seguimos haciendo nosotros.**

### El obstaculo real, que no era el que parecia

**Las gafas no se anuncian por BLE. Nunca.** Comprobado con escaneo propio en
`SCAN_MODE_LOW_LATENCY` y con `setLegacy(false)` (anuncio extendido), con el audio
Bluetooth conectado y desconectado: el telefono ve 10-14 aparatos y las gafas no salen.
El `startScan` del SDK —y su `reconnect`, que tambien escanea por dentro— **no puede
encontrarlas jamas**.

Pero estan emparejadas, y un `connectGatt` dirigido conecta en 30 ms sin necesidad de
anuncio. El volcado de servicios confirmo ademas que **el AAR es de este hardware**:

    SERVICIO 9b005ffe-1dea-2d9c-b841-9fd52e4c8b3a
        caract 941bfa82-4da9-f00d-01f6-ceb0af61daca  props=0x8   (write)
        caract d52ad907-65d8-16eb-4f71-477bfe6586a8  props=0x10  (notify)

(Los `66666666-`/`77777777-` que se anotaron el 07-sep tambien estan, pero son otro
servicio; el del SDK es este.)

Asi que a `BleeqUpDeviceManager` se le entrega el aparato hecho en vez de dejar que lo
busque: se construye el `BleeqUpDevice` desde el emparejado y se mete en su registro
interno. **Va por reflexion, y eso es deuda declarada**: el constructor y
`initializeName` son `internal` de Kotlin, y el registro solo es accesible por un
`access$getDevices$p` sintetico que genera R8. Si el fabricante recompila el AAR, esto se
rompe de golpe — el `catch` lo dice en el log en vez de fallar en silencio. **La salida
limpia es pedirles un `connect(mac)` que no pase por el escaneo**, y ahora esa peticion
se puede hacer con el dato medido en la mano.

### Hecho

- `app/libs/bleequplibrary-release.aar` + OkHttp 4.12 y Gson 2.11 en el catalogo. Un
  `.aar` **no declara sus dependencias**: sin ponerlas a mano compila y revienta en
  ejecucion.
- Regla `-keep` de R8 para `com.bleequp.bleequplibrary.**`: sus modelos se leen con Gson
  por reflexion sobre los nombres de campo, y renombrarlos romperia el listado **solo en
  release**.
- `data/GafasApPrueba.kt` (nuevo) — la sonda, con cuatro modos por intent:

      adb shell am start -n com.delta.aeria_nexus_prototype/.MainActivity --es gafas_ap Aeria123 --es gafas_pais CN
      adb shell am start -n ... --es gafas_ap estado     (WiFi on/off, cliente, almacen, camara)
      adb shell am start -n ... --es gafas_scan 1        (escaneo BLE crudo)
      adb shell am start -n ... --es gafas_gatt 1        (volcado de servicios GATT)

  Solo bajo `BuildConfig.DEBUG`, con la etiqueta `AeriaGafasAP`.

### Estado: VERIFICADO DE PUNTA A PUNTA EN EL SAMSUNG (RZCY510MBBM)

**El telefono se une al WiFi de las gafas y su servidor contesta.** La cadena entera, en
una sola pasada de 17 segundos:

    AP ENCENDIDO   SSID='BleeqUp-Ranger-901FC'  CLAVE='Aeria123'
    UNIDO          servidor=192.168.43.1
    list[video]    HTTP 200
    list[image]    HTTP 200
    list[all]      HTTP 200

La `192.168.43.1` ya no es la IP que se capturo una vez: sale de leer la pasarela de la
red por `LinkProperties`, con el telefono dentro.

El listado real, que es lo que llevaba meses sin poder verse:

    VIDEO_1751021937470.mp4   454.349.394 B   300.184 ms   1920x1080
    VIDEO_1777158818373.mp4   997.267.893 B   659.423 ms
    IMG_1751061898364.jpg       2.152.937 B                4656x3496

**Los valores de `fileType`, que no estaban en el AAR**: `video`, `image`, `all`
(mezclados) y `photo` (siempre vacio en este aparato). Sin el parametro contesta
`HTTP 200` con `state:400, "Parameter fileType was not received"` — el estado va **en el
cuerpo, no en el codigo HTTP**, asi que mirar solo el 200 daria un fallo por bueno.

Y un campo que el modelo del SDK **no tiene**: `fileImuNames`, con los logs de
acelerometro y giroscopio de cada video
(`NCSAccelerometer_<ts>.log,NCSGyroscope_<ts>.log`). Si algun dia hacen falta para una
prueba, ya se sabe que existen y como se llaman.

El resto del canal de control responde igual de bien, todo en ~120 ms:

    wifiSwitch ok=true encendido=false      wifiConn ok=true conectado=false
    storage    ok=true whole=26540012 free=21590560
    camara     ok=true 1920x1080

**Lo que NO se ha probado**: descargar un fichero de verdad y sellarlo en la boveda.

### Tres detalles que costaron tiempo y conviene no repetir

- **El AP se apaga solo a los ~5 minutos.** Medido: encendido a las 22:06:15, todavia
  emitiendo a las 22:11:55, desaparecido a las 22:12:02. Dos intentos de union fallaron
  contra una red que ya no existia, y el sistema no dice eso: dice `onUnavailable` a los
  15 s, igual que si la clave estuviera mal. **Encender y unirse tienen que ir
  encadenados en la misma accion.**
- **Hay que cerrar el GATT al terminar.** Sin `disconnect`, cada pasada deja un
  `BluetoothGatt` abierto; Android los cachea, la siguiente conexion sale en 49 ms
  reusando el enlace viejo y las escrituras empiezan a fallar con
  `write characteristic error` — que parece un problema de las gafas y es nuestro.
  Tres ejecuciones perdidas hasta verlo.
- **`Locale.setDefault` es global al proceso.** La sonda lo cambia y se lo puede
  permitir; la implementacion de verdad **no** puede dejar el idioma cambiado (cambiaria
  la UI y los formatos de fecha de toda la app). Hay que ponerlo y restaurarlo alrededor
  de la llamada, sabiendo que aun asi es una variable global compartida.
- El USB del Samsung se cayo media docena de veces con el APK de 244 MB. Se paso adb a
  WiFi (`adb tcpip 5555`) y dejo de dar guerra. Ojo: eso no valdra cuando el telefono se
  una al AP de las gafas.

### Para manana (2026-09-10)

Por orden, y el primero no es de codigo:

1. **Llevar la decision del canal 149 al cliente.** El unico camino que las gafas ofrecen
   para el video emite en 5,8 GHz, fuera de lo que Europa permite; funciona declarando
   otro pais. Todo lo demas de este subsistema depende de esa respuesta, asi que la
   pregunta va antes que el codigo, no despues. Junto a las otras cuatro decisiones del
   informe del 07-sep.
2. **`GafasMediaRepository.listar()` de verdad.** El JSON ya esta delante y no hay nada
   que adivinar: `data[]` con `fileName, fileType, fileSize, fileDuration, fileTime,
   fileResolution` (y `fileImuNames`, que el modelo del SDK no tiene). Dos cuidados: el
   estado va **en el cuerpo**, no en el codigo HTTP, y `abrir()` tiene que pasar a
   `abrirSeguro()` porque el servidor es `https://`.
3. **Descargar un video contra la boveda y sellarlo.** Aqui esta el trabajo de verdad:
   los dos ficheros que hay pesan **454 MB y 997 MB**. Eso obliga a comprobar el espacio
   libre antes de empezar, a que la reanudacion por `Range` sea funcional y no un
   adorno, y a decidir que pasa si el AP se cae a mitad — media descarga no es evidencia.
   Y al terminar tiene que aparecer en bruto en la boveda, listo para categorizar.
4. **Sacar la sonda de `MainActivity`.** `GafasApPrueba` cumplio su papel; lo que valga
   se muda a `GafasMediaRepository` y el resto se borra. Lo que **no** se puede quedar
   como esta es el `Locale.setDefault` global.
5. **Escribir al fabricante** con dos preguntas concretas, que ahora se pueden hacer con
   datos medidos: un `connect(mac)` que no pase por el escaneo (las gafas no se anuncian
   nunca, asi que su propio `startScan` no sirve), y por que el AP esta fijado a un canal
   que Europa no permite.

### Resuelto el 2026-09-13: la tarde del 9 ya esta imputada

La sesion de la tarde del 9 (categorizacion en bruto, `RawEvidenceRepository`,
`CategorizeDialog`, BD a v2) **no estaba** en la tabla de horas: las cuatro filas de
desarrollo de ese dia salen de las marcas 08:41-12:21 y esa sesion es posterior. Las marcas
de fichero la situan entre **13:13 y 15:57 (2,7 h medidas)**, y fue a parar al commit
`22768a9` junto con el trabajo del 11. Anotada como bloque nuevo **`AN-7`** (boveda,
categorizacion de evidencia en bruto) con **3 h**, aplicando la regla de redondeo que fijo el
usuario ese dia: **siempre al alza al medio punto** (2,7 -> 3; 2,1 -> 2,5; 2,5 se queda en
2,5). El mes pasa de 30,2 a **33,2 h = 664 EUR**.

Sigue pendiente de confirmar la fila del **3 de septiembre**: 4 h `ESTIMADO`, sin commits ese
dia y con marcas de fichero que solo cubren 21:56-23:51 (~1,9 h). Ahi el riesgo es de
sobre-imputacion, no de defecto. Y el hueco **16:39-17:40 del 8** sigue sin contar a proposito.

---

## 2026-09-09 (2) — Categorizacion: el video de un periferico entra en bruto y lo coloca el agente

### La decision del cliente

Un periferico entrega su video **cuando su grabacion ya termino**, asi que no puede
colgarse solo del incidente activo. El flujo acordado:

1. El video baja y se cifra en la boveda.
2. Queda **en bruto**: aparato de origen, nombre original y **fecha de grabacion**.
3. El agente lo **categoriza**: clasificacion y **eleccion de incidente** — uno que ya
   existe, o uno nuevo que se crea ahi mismo.
4. Si no lo categoriza, se queda en bruto con su fecha. No se pierde y no se inventa a
   que pertenece.

**Por que elige el agente y no lo cruzamos por hora:** una conjetura dentro de una cadena
de custodia es peor que un hueco. El hueco se ve; la conjetura no.

### Hecho

- **`data/local/RawEvidenceEntities.kt`** (nuevo) — tabla `raw_evidence` y su DAO.
  `incidentId` nulo = sigue en bruto.
- **BD a v2 con `Migration(1, 2)` de verdad**, no `fallbackToDestructiveMigration()`: en
  los telefonos de prueba ya hay incidentes guardados y borrarlos para anadir una tabla
  vacia seria destruir evidencia por comodidad.
- **`data/RawEvidenceRepository.kt`** (nuevo) — registrar la importacion y categorizar.
- **`IncidentRepository`** — `crearIncidenteDeImportacion()` y
  `adjuntarEvidenciaImportada()`, mas `incrementarEvidencia` en el DAO (el contador se
  calculo al guardar el incidente y ahora puede crecer despues).
- **Boveda** — apartado `PENDING CATEGORIZATION` arriba del todo y
  `feature/vault/CategorizeDialog.kt` con clasificacion, incidente y etiqueta.
- **`GafasMediaRepository`** — tras sellar, registra en bruto en vez de terminar ahi.

### Dos detalles que parecen menores y no lo son

- **La fecha de grabacion se guarda aparte de la de descarga.** La boveda listaba por
  `lastModified`, que es *cuando se descargo*; para una prueba esa diferencia importa. Si
  el aparato no da la suya, la UI dice "No recording date" en vez de rellenarla con la
  otra. Ese es el motivo de que la tabla exista y no baste con mirar el disco.
- **El incidente creado se fecha en la GRABACION**, no en el momento de categorizar. Un
  incidente que dice haber ocurrido cuando el agente lo archivo es un dato falso.

### Pensado para tres aparatos, no para las gafas

Las gafas pueden funcionar a la vez que la bodycam, y **las pruebas se haran con los tres
trabajando**. Por eso el modelo es generico: `EvidenceSource` (FALCON_LENS / FALCON_CORE)
dice de cual vino cada pieza, y la misma via sirve para la bodycam en cuanto exista su
descarga. Nada asume que solo hay un periferico activo.

### Estado: COMPILA Y PASA LAS PRUEBAS JVM, sin ver en dispositivo

Como el protocolo de las gafas sigue sin resolverse, **hoy nada produce material en
bruto**. Para poder ver el flujo hay un gancho **solo en DEBUG** que marca un `.fev` que
ya esta en la boveda como recien importado:

    adb shell am start -n com.delta.aeria_nexus_prototype/.MainActivity --es import_raw last

Sin `--el recorded_at <millis>` la fila queda sin fecha de grabacion, que es justo el
caso que conviene ver. Su hash va como `DEBUG-IMPORT-NO-HASH`, a la vista: esa fila no
sale de un cifrado nuestro.

### Proximo paso

1. Verlo en el telefono con el gancho de DEBUG: crear incidente nuevo y adjuntar a uno
   existente, y comprobar que el contador de evidencia del incidente sube.
2. Decidir la subida: la evidencia categorizada **no se encola** hoy. Una captura del
   telefono si (`uploader.enqueue`), pero ahi se tiene el `Sealed` en la mano; aqui
   llegaria despues. Va con el backend, que sigue siendo un stub.
3. Lo de siempre: sin los tres datos del protocolo, esto no se ejecuta con gafas reales.

---

## 2026-09-09 — Que el PTT suene, que se oiga con la app cerrada, y el armazon de las gafas

### Hecho

Tres cosas, en ese orden.

**1. Tonos de walkie (`data/PttTones.kt`, nuevo).** El PTT no sonaba: ni al abrir, ni
al cerrar, ni al fallar. Cinco tonos sintetizados en PCM con `AudioTrack` (nada de
ToneGenerator: sus tonos son de telefonia y no permiten la subida y la bajada que hacen
reconocible el par abrir/cerrar).

| tono | forma | significado |
|---|---|---|
| `abrir()` | subida 880 -> 1320 Hz | tu microfono esta abierto |
| `cerrar()` | bajada 1320 -> 880 Hz | tu microfono se ha cerrado |
| `denegado()` | doble 300 Hz | NO se abrio: nadie te oye |
| `entra()` | 1568 Hz suelto, mas bajo | OTRO ha abierto el canal |
| `sale()` | 1046 Hz suelto, mas bajo | el otro ha soltado |

La regla que los separa sin pensar: **dos notas son tuyas, una nota es de otro.** Si el
que recibe oyese la misma subida que el que transmite, confundiria "estoy al aire" con
"hay alguien al aire", que es el error mas caro de todo el subsistema.

Cableado en `AgoraRepository`: el tono de abrir **antes** de publicar el microfono y el
de cerrar **despues** de apagarlo, para que el pitido no viaje por el canal. Los de
recepcion, en `ptt_on`, en `cerrarPttRemoto()` y en `marcarBodycamHablando()`.

**2. Recibir con la app cerrada (`RadioService.kt` + `BootReceiver.kt`, nuevos).**
Foreground service gemelo de `BodycamService`, con notificacion que **dice quien esta
hablando** — es la unica cara de la radio sin pantalla, el equivalente de
`PttAvisoOverlay`. Mas `POST_NOTIFICATIONS` en el manifest y su peticion en
`MainActivity`, sin el cual esa notificacion no se ve.

**3. Armazon del video de las gafas (`data/GafasMediaRepository.kt`, nuevo).** Mecanica
entera de descarga a la boveda, con tres huecos marcados `PROTOCOLO` y **no**
inventados. Ver la entrada del 2026-09-07 (2) para el porque.

### Decisiones que conviene no volver a discutir

- **`PttTones` vive duplicado** en las dos apps, como `EvidenceCrypto`/`EvidenceKeys`.
  El agente que lleva bodycam y telefono oye las dos, asi que **cambiar una frecuencia
  aqui obliga a cambiarla alli**. Lo unico que difiere a proposito es el
  `AudioAttributes`: la bodycam usa `USAGE_ALARM` (sus volumenes vienen a cero de
  fabrica), el telefono `USAGE_VOICE_COMMUNICATION_SIGNALLING`, para salir por donde
  sale la voz y al volumen que el agente ya tiene puesto.
- **Un tono no puede mentir.** Con el SOS emitiendo, `terminarPtt()` deja el microfono
  abierto a proposito: ahi **no** suena el tono de cierre, porque la voz sigue saliendo.
- **El tono de recepcion va en el FLANCO, no en el aviso.** `onRemoteAudioStateChanged`
  repite "esta hablando" cada pocos segundos durante toda la transmision (DECODING <->
  FROZEN con el PTT abierto) y el StateFlow se traga la repeticion porque el valor no
  cambia; un tono por aviso seria un chasquido continuo encima de la voz. De ahi
  `marcarBodycamHablando()`, unico sitio donde ya cambia `_bodycamHablando`.
- **El servicio es `specialUse` y no `mediaPlayback`.** Por contenido seria
  mediaPlayback, pero desde Android 15 un `BOOT_COMPLETED` no puede arrancar ese tipo
  (ni `microphone`, `camera`, `dataSync`, `phoneCall`, `mediaProjection`): lanza
  `ForegroundServiceStartNotAllowedException`. Al distribuirse fuera de Play, no hay
  revision de `specialUse` que pasar.
- **La radio solo corre con sesion abierta** (`ACTIVE` u `OFFLINE_GRANTED`). Un telefono
  en el canal tactico en nombre de un agente que no ha metido su PIN es lo que prohibe
  el workflow 34 cuando desata la bodycam al cerrar sesion.
- **Las gafas: ningun endpoint inventado.** Un endpoint adivinado que "casi" funciona
  cuesta mas que un hueco declarado — el siguiente no sabe si depura su codigo o una
  suposicion ajena.

### El limite que aparecio al hacerlo, y no es de Android

Tras un reinicio **la radio no se levanta sola**, y no por las restricciones del
sistema: `leerEstadoGuardado()` devuelve siempre `LOCKED`, o sea que la sesion no
sobrevive al proceso, por diseno. El `BootReceiver` solo despierta el proceso (con eso
corren `ensureStarted()` y `resumePending()`, que antes no pasaban hasta que alguien
tocaba el icono) y la radio espera al PIN. **El limite es el modelo IAM propio, no
Android.** El dia que se decida que la sesion sobreviva a un reinicio, arranca sola sin
tocar codigo.

Otro, del lado de las gafas: unirse a su AP con `WifiNetworkSpecifier` **exige app en
primer plano y un dialogo del sistema**. Esto nunca podra ser una sincronizacion de
fondo; es una accion del agente. Y como las gafas se desconectan de su WiFi mientras
graban, la descarga solo puede ocurrir con la grabacion parada.

### Estado: COMPILA, SIN VERIFICAR EN DISPOSITIVO

`assembleDebug` limpio y las pruebas JVM en verde. **Nada de esto se ha oido ni visto en
un telefono**: no habia ninguno accesible por adb en toda la sesion. Los cinco tonos, el
servicio, la notificacion y el arranque en boot estan verificados solo por compilacion.

### Probado en el Redmi el mismo dia: funciona, pero depende de ajustes del terminal

Primer intento: con la app quitada de recientes, a B **no le llegaba ni el beep ni la
voz**; en segundo plano si. Se endurecio lo endurecible —`stopWithTask="false"`,
`onTaskRemoved` que no para el servicio, peticion de exencion de bateria una sola vez y
atajo a la pantalla de autoarranque de MIUI en Xiaomi/Redmi/POCO— y **con esos ajustes
concedidos a mano, funciona**. El fallo era del aparato, no del servicio: MIUI mata el
proceso salvo autoarranque concedido, y **las apps instaladas desde Android Studio lo
llevan apagado de fabrica**.

**Lo que hay que sacar de aqui, y no es del codigo:** la radio depende de ajustes **por
terminal** que alguien tiene que conceder en cada telefono. Eso pertenece al alta del
terminal, no al APK; si no, habra agentes convencidos de estar a la escucha sin estarlo.

### Lo que ningun ajuste arregla, y por que hara falta push

Forzar detencion, reiniciar el telefono sin abrir la app, o cualquier muerte del proceso
(la sesion vuelve a `LOCKED` y la radio no vuelve sola).

**El limite ahi no esta en el servicio.** Hoy el PTT es de telefono a telefono por el canal
de Agora: A anuncia `ptt_on` por el data stream y B tiene que estar YA dentro del canal.
Con el proceso de B muerto no hay a quien anunciar, porque **no hay servidor en medio que
sepa que B existe**. Mantener el proceso vivo contra MIUI es una pelea que no se gana; ni
WhatsApp la pelea: cuando te llaman con la app cerrada, un **push FCM de alta prioridad**
lo recibe Google Play Services —proceso del sistema, exento de Doze y del gestor de
bateria del fabricante— y **ese push arranca el proceso**. Es el unico camino que el
sistema garantiza para despertar una app muerta.

Hoy no hay nada de eso en el proyecto: solo Agora RTC, sin Firebase, sin FCM, sin
Signaling (RTM) y con el backend en stub. **Dos vias, aparcadas por el usuario para mas
adelante:** Agora Signaling con push offline (hay que confirmar que esta en el plan
contratado) o backend propio con registro de tokens FCM. `RadioService` no se tira: con
push pasa a ser lo que el push arranca, en vez de lo que intenta sobrevivir.

**Decidido ya:** con la sesion cerrada, una transmision entrante **se avisa sin abrir
audio** (notificacion "Agente X esta transmitiendo"); se pierde ese mensaje y se acepta,
por el mismo criterio del workflow 34 que desata la bodycam al cerrar sesion.

**Y una consecuencia que habra que tragarse con push:** del push a estar en el canal hay
segundos, asi que la primera palabra de A se pierde. Por eso WhatsApp suena en vez de
conectar directo. En una radio real se pulsa, se espera el tono y se habla — el tono de
apertura de hoy es justo lo que lo resuelve.

### Proximo paso

1. Probar en el Samsung: los cinco tonos (con `adb logcat -s NexusTone:W`), el servicio
   con la app deslizada en recientes, y la notificacion diciendo quien habla.
2. Medir el **consumo de bateria de un turno entero** en el canal. Es la condicion para
   poder prometer el PTT con la app cerrada, y hasta tenerla no se promete.
3. Comprobar que MIUI y compania no matan el servicio (exencion de optimizacion de
   bateria, y lista blanca a mano en el Redmi).
4. Pendiente por decision del usuario: hablar con la app cerrada (tipo `microphone`),
   dejado comentado en `RadioService` y en el manifest.

---

## 2026-09-08 — PTT en las dos direcciones: oir a la bodycam y hablar desde el telefono

### Lo que habia y lo que faltaba

El PTT nacio en la bodycam: boton fisico F2, la voz sale por **Agora** y no por
Bluetooth, porque el requisito es que llegue a **todos** los moviles del sistema y el
BT solo alcanza al emparejado. Esa mitad quedo cerrada hoy en las dos apps (ver abajo).
Faltaba la otra: un agente **sin la bodycam encima** no tenia forma de hablar. El boton
"Push to talk" de Operations existia comentado desde el prototipo, decorativo.

### Mitad 1 — escuchar el PTT de la bodycam

Los telefonos entran al canal con `autoSubscribeAudio = false` (si no, cada voz
publicada sonaria en todo el canal). La bodycam publicaba y **no la oia nadie**. Se
abrio una excepcion explicita: `AgoraRepository.escucharBodycam()`, llamada desde
`onUserJoined` cuando el uid es 9001, y `stopWatching(9001)` ya no la silencia — el PTT
vive aunque se cierre el livestream.

El estado se pinta con `onRemoteAudioStateChanged`, y ahi hubo una trampa medida con la
W1: con el PTT abierto y sano el estado **va y viene entre DECODING (2) y FROZEN (3)**
cada pocos segundos, porque basta un silencio del agente para congelar el flujo. Solo
STOPPED y FAILED cierran el aviso; tratar FROZEN como "ya no habla" hacia parpadear la
banda durante toda la transmision.

La UI es `PttAvisoOverlay`: banda superior azul, sin sonido, no descartable, montada en
`AppNavHost` **despues** del SOS para que la emergencia quede por encima. Deliberadamente
NO es el popup del SOS: el PTT es trafico rutinario y una alarma modal por cada
transmision acabaria enseñando a los agentes a descartar tambien las de verdad.

### Mitad 2 — hablar desde el telefono (lo de hoy)

`PttButton` en Operations, **mantener pulsado para hablar**. Aqui si se puede, al reves
que en la bodycam: el firmware de la W1 solo emite el broadcast al **soltar** F2 y
obliga a un conmutador; en una pantalla no hay esa limitacion, y mantener es lo que
evita el fallo clasico de la radio, dejarse el microfono abierto.

En `AgoraRepository`, `iniciarPtt(officer)` / `terminarPtt()`. Deshacen las tres cosas
del modo receptor estricto (`enableLocalAudio`, `adjustRecordingSignalVolume`,
`muteLocalAudioStream`) y publican el microfono en caliente con
`updateChannelMediaOptions`, sin salir del canal.

**Lo que no es obvio: publicar no basta.** Los demas siguen con
`autoSubscribeAudio = false`. Con la bodycam se resolvio cableando el uid 9001, pero el
uid de un telefono es **aleatorio**, asi que hay que anunciarse: dos mensajes nuevos por
el data stream que ya llevaba el GPS y el SOS.

    {"type":"ptt_on","officer":"<num>","ts":<millis>}
    {"type":"ptt_off","ts":<millis>}

Al recibir `ptt_on` el receptor abre `muteRemoteAudioStream(uid, false)` y pinta la
banda — esta si **con nombre**, porque el anuncio lleva el numero de oficial dentro, al
contrario que la de la bodycam, condenada a ser generica mientras todas compartan el uid
9001. Con `ptt_off` vuelve a silenciar.

### Los cuatro cruces que habia que atar

- **Quien se va del canal con el PTT abierto no manda su `ptt_off`.** `onUserOffline`
  cierra el PTT de ese uid o la banda se quedaria puesta para siempre.
- **`ptt_off` no puede silenciar a quien se esta viendo en livestream**: ese audio lo
  abrio `startWatching`, no el PTT. Se lleva la cuenta en `uidsEnEscucha`.
- **Cancelar el SOS no puede cortar un PTT abierto.** `stopCameraPublish` mantiene el
  microfono si el PTT sigue pulsado; la camara se corta, la voz no.
- **Salir de la pantalla con el boton pulsado** no genera evento de soltar. Un
  `DisposableEffect` cierra el microfono al desmontar.

El indicador ON AIR se enciende con lo que el repositorio **confirma** haber abierto, no
con la pulsacion: un boton que dice ON AIR sin que salga voz es peor que uno que no
responde, porque el agente cree que le estan oyendo. Es la misma leccion que la bodycam
pago con el micro ocupado.

### Limite conocido, a proposito

**La bodycam no oye el PTT de los telefonos.** Entra al canal con
`autoSubscribeAudio = false` y nunca se suscribe a nadie: es una camara, no una radio.
El agente que la lleva escucha por su telefono, que si esta suscrito. Si algun dia se
pide que la unidad reproduzca por su altavoz, es un cambio del lado BodyCamServer.

Sigue sin verificar el **eco en el telefono emparejado** con la bodycam que transmite:
la prueba de hoy se hizo con ese movil sin volumen. Si aparece, la solucion es que el
telefono atado a esa camara no se suscriba a su audio.

### Estado

Compila (`compileDebugKotlin`, BUILD SUCCESSFUL). El PTT del telefono **no se ha
probado todavia en aparatos**.

### Proximo paso

Probar el PTT del telefono con dos moviles reales (Samsung + Redmi), que es la unica
forma de ver el `ptt_on`/`ptt_off` viajando de verdad, y de paso cerrar lo del eco.

---

## 2026-09-07 (2) - Que se puede hacer con las gafas: averiguarlo antes de prometer cifrado

### La pregunta

Con la conectividad ya funcionando, la siguiente era la de verdad: se puede cifrar el
video de las gafas y podemos crear funcionalidades dentro de ellas. Se investigo antes
de escribir una linea de codigo, que era justo lo pedido.

### Lo que hay dentro del aparato

Se desmonto la app oficial (`com.bleequp.cycleride.cycleride`, instalada en el Samsung;
Flutter, todo en `libapp.so`) y se leyeron los servicios que las gafas anuncian por
Bluetooth. Tres canales:

- **BLE GATT** con notificaciones para el control, con dos servicios propietarios
  (`66666666-...` y `77777777-...`), sin documentar.
- **Bluetooth clasico HFP/A2DP** para audio e intercomunicador. Usa **Agora RTC**, el
  mismo SDK que Nexus para la red tactica.
- **WiFi propio de las gafas para el video**: levantan un punto de acceso y sirven HTTP
  en `192.168.43.1` (80 y 443; los logs de IMU por `https://.../imu_log?fileName=`). Ni
  RTSP ni HLS: el video no se emite, se descarga. La app avisa al usuario de que se
  desconecta de ese WiFi durante la grabacion para ahorrar bateria de las gafas.

### Las dos respuestas

**Funcionalidades internas: no.** No hay SDK ni API publica, el firmware es OTA firmado
por el fabricante (`FirmwareUpgradeManager`) y ni ellos tienen paridad de plataformas
(3K/60 fps solo en iOS). La unica puerta es comercial: partner.bleequp.com.

**Cifrado: solo en el telefono**, y ahi ya esta hecho (la boveda). El problema no es
cifrar, es como llega el fichero: **la app oficial lo descarga a
`/storage/emulated/0/DCIM/BleeqUp/MEDIA`**, la galeria publica, justo lo que el proyecto
dejo de hacer el 2026-08-30. Apoyarse en ella significaria cifrar una copia mientras el
original se queda en claro y legible por cualquier app con permiso de medios: peor que
no cifrar, porque parece resuelto.

La via limpia es que Nexus hable directo con las gafas (unirse a su AP, listar,
descargar contra la boveda, no tocar la galeria). Es posible en Android, pero exige
reventar el protocolo propietario, sin documentar y roto potencialmente en cada
actualizacion de firmware.

### Ademas, y pesa mas que el cifrado

La app del fabricante manda datos a `bleequp.cloud`, `bleequp.net.cn`, `111.230.42.62`
(Tencent) y un bucket de Aliyun en Pekin. Con las gafas dentro del flujo de evidencia y
esa app en el mismo telefono, eso es una decision de cliente, no tecnica.

### Entregado

`docs/preguntar al manager sobre las gafas.docx`: el informe con las dos respuestas, los
tres caminos (preguntar al fabricante bajo NDA / medir el protocolo capturando trafico /
apoyarse en su app, descartado) y cuatro decisiones que no son tecnicas.

### Proximo paso

Esperar la decision. Si sale la via 2, lo primero es una captura de trafico (HCI snoop
del telefono + HTTP) mientras la app oficial hace una transferencia: un par de horas
dicen si el protocolo es abordable o un pozo, y solo despues se puede estimar de verdad.
Sin decision, no se escribe codigo de esto.

---

## 2026-09-07 — Gafas BleeqUp: mirar el enlace en vez de fabricarlo

### Hecho

Segundo periferico del cinturon: las gafas de realidad aumentada **BleeqUp Ranger**
(`BleeqUp-Ranger-901FC`, MAC `F0:74:E4:79:7C:B1`). De momento solo conectividad: el
icono de gafas de la barra superior se pone verde cuando estan enlazadas, igual que
el de la bodycam. Desde el telefono no se controla nada de ellas.

- **`data/GafasRepository.kt`** (nuevo) — sigue el enlace y lo publica en un
  `StateFlow<GafasState>` con tres valores (DISCONNECTED, CONNECTING, CONNECTED).
- **`AppContainer` + `AeriaNexusApp`** — se construye con el resto y empieza a vigilar
  en el arranque, junto a la red tactica.
- **`ui/components/AppScaffold.kt`** — el icono de gafas deja de estar fijo en gris y
  toma el color del estado.
- **`OperationsScreen` + `OperationsViewModel` + `AppNavHost`** — el permiso de
  Bluetooth se pide al entrar en Operations (ver mas abajo).
- **`GAFAS_MAC`** en `local.properties` y `BuildConfig`, igual que `BODYCAM_MAC`.
  Ojo: `local.properties` no va a git, asi que en una maquina nueva hay que anadirlo
  a mano o las gafas quedan deshabilitadas (queda avisado en el log).

### Por que no se parece por dentro a la bodycam

La bodycam es nuestra: corre `BodyCamServer`, el telefono abre un RFCOMM contra un
UUID propio y ese socket ES el enlace. Con las gafas no hay nada que abrir —no son
nuestras y no exponen ningun servicio que nos interese— asi que el repositorio **no
conecta: observa**. Se apunta a los avisos del sistema y pregunta al perfil de audio
por el que las gafas se enlazan. De ahi que no haya servicio en primer plano, ni
backoff, ni watchdog: no hay enlace propio que mantener vivo, y montar uno para
enterarse de algo que el sistema ya sabe seria gastar radio y bateria a cambio de nada.

Tres detalles que si tienen su porque:

1. **Manda el ACL, no el perfil.** El aviso de perfil solo se usa para el amarillo de
   "conectando", que el ACL no da. Su desconexion se ignora a proposito: un perfil de
   audio puede caerse con las gafas todavia puestas y enlazadas, y eso pintaria el
   icono en gris con las gafas funcionando.
2. **Se escucha tambien el apagado del Bluetooth.** Al apagar la radio no llega
   ninguna desconexion por dispositivo; sin ese caso el icono se quedaria verde para
   siempre.
3. **No hay verde condicionado.** En la bodycam el verde exige enlace autenticado
   (workflow 31). Las gafas no se acreditan ante el telefono ni reciben ordenes suyas,
   asi que lo unico que se puede afirmar es si el agente las lleva enlazadas, y eso es
   justo lo que dice el color.

### El fallo que aparecio al probarlo: un indicador que mentia

Con las gafas puestas y conectadas, el icono seguia gris en el Samsung. No era el
observador: **`BLUETOOTH_CONNECT` estaba denegado**, y ese permiso solo se pedia desde
BODYCAM CONTROL. Sin el, Android no entrega los avisos de conexion y tampoco deja leer
el perfil, asi que los DOS iconos de la barra se quedan en gris con los aparatos
funcionando. Un agente que nunca entrase en esa pantalla tendria un indicador que le
miente, que es justo lo que el comentario del color de la bodycam dice que hay que
evitar.

Se pide ahora al entrar en Operations, una sola vez por sesion (`rememberSaveable`,
para no ponerle el dialogo delante una y otra vez a quien diga que no). Al conceder se
relee el estado en el acto: los avisos que no llegaron mientras faltaba el permiso no
vuelven solos.

### Estado: VERIFICADO EN EL SAMSUNG

`assembleDebug` limpio (sin warnings nuevos; el `getParcelableExtra` deprecado se
cambio por `IntentCompat`) y las 24 pruebas JVM en verde. En el Samsung, con las gafas
puestas:

1. Instalacion con el permiso revocado -> al entrar en Operations sale el dialogo.
2. Al concederlo, `Gafas: CONNECTED` en logcat e icono verde en el acto, sin reiniciar.

Tambien los dos sentidos del enlace, desconectando y reconectando las gafas desde los
ajustes del telefono (apagarlas no valia: estaban cargando del ordenador y no se
apagan enchufadas):

    22:17:28  Gafas: CONNECTED      icono verde
    22:31:56  Gafas: DISCONNECTED   icono gris
    22:36:45  Gafas: CONNECTED      icono verde otra vez

**El detalle que casi pasa por bug**: la reconexion de las 22:36 la hizo el sistema a
las 22:34 y la app no se entero hasta que volvio al primer plano. No es un fallo del
observador: con la app en segundo plano, Android APLAZA los broadcasts a los procesos
en cache. La bodycam no lo sufre porque su servicio en primer plano mantiene el proceso
despierto; las gafas no tienen servicio a proposito, asi que el estado se corrige al
volver a la app, con la relectura de la barra. Queda comentado en `AppScaffold`.

El Redmi (MIUI) sirvio para el arranque en gris con las gafas desconectadas, y de paso
para sacar la MAC de los emparejados. Sigue sin verse el apagado del Bluetooth y el
amarillo de CONNECTING (que en un reenlace dura un suspiro).

Aparte, el Samsung avisa de que las bibliotecas nativas de Agora y Mapbox no estan
alineadas a paginas de 16 kB. No afecta a esto, pero habra que mirarlo antes de
publicar.

### Proximo paso

Decidir si las gafas tienen que aparecer en algun sitio mas que la barra (ficha en
Operations, por ejemplo) o si con el icono basta.

---

## 2026-09-06 (5) — Workflows 33 y 34: la camara sirve a un agente sin convertirse en el

### Hecho

Es la pieza menos evidente del modelo. La evidencia tiene que poder decir **quien** la
grabo, no solo con que aparato; la forma facil seria darle a la camara la identidad
del agente, y el documento lo prohibe: "a peripheral binding does not copy the officer
identity to the peripheral", y en la capa de evidencia "the capture device signs as
itself; user attribution comes through the validated session and binding".

Asi que la atadura es una **declaracion firmada por el agente**: yo,
cmendez.aeriaone.com, autorizo a BWC-896E a operar en mi nombre desde DEV-74435826
hasta tal hora. La camara la guarda y la presenta; **no puede firmar como el agente**
porque nunca ha tenido su clave. Perder la camara obliga a revocar la camara, no al
agente.

- **`data/identity/BindingPeriferico.kt`** (Nexus) — el modelo y las dos declaraciones
  (crear y deshacer), en JSON canonico compuesto a mano: se firma el texto tal cual,
  asi que los dos extremos tienen que construirlo igual byte a byte y un serializador
  que reordene claves lo romperia.
- **`BodycamRepository`** — ata al terminar el emparejamiento y expone la atadura viva.
- **`BodyCamServer/BindingAgente.kt`** — el lado de la camara, con **dos anclas**: la
  de dispositivos para validar al telefono (workflow 31) y la de USUARIO para validar
  al agente (workflow 33). Son dos porque el documento separa los dominios de
  confianza; con una sola, revocar un agente y revocar un terminal serian lo mismo.
- **`tools/alta-bodycam.sh`** reparte ahora las dos anclas a la camara.

### Cuatro decisiones que sostienen esto

1. **Firma la clave del AGENTE, no la del telefono.** Quien autoriza es la persona.
   Por eso solo se puede atar con la sesion abierta —la clave del agente esta
   autorizada solo entonces— y por eso cerrar sesion la deshace.
2. **La declaracion lleva dentro el nonce de la sesion de emparejamiento.** Sin eso,
   una atadura capturada ayer valdria hoy; con eso es tan efimera como el
   emparejamiento que la respalda.
3. **El fin va firmado igual que la creacion.** Si cualquiera pudiera deshacerla,
   bastaria acercarse a la camara para dejar al agente sin atribucion en mitad de un
   incidente.
4. **La caducidad se comprueba al leer, no con un temporizador.** Un temporizador que
   no salta —proceso dormido, reloj cambiado— deja una atadura viva de mas, y aqui lo
   seguro es lo contrario.

La camara comprueba cuatro cosas antes de aceptar: que el certificado del agente lo
emitio la CA de usuario, que la firma cubre la declaracion, que la atadura habla de
ESTA camara y ESTA sesion, y que el nombre comun del certificado es el agente
declarado. Sin la ultima, un agente con certificado valido podria atar la camara a
nombre de otro.

En `IdentityRepository.lock()` el orden importa y esta comentado: primero se avisa
para deshacer las ataduras y despues se retira la autorizacion de la clave, porque
para firmar el fin hace falta esa clave.

### Estado: ESCRITO Y COMPILA, SIN VERIFICAR EN DISPOSITIVO

Los dos APK compilan limpios y las 24 pruebas JVM siguen en verde, pero **el
intercambio BIND/UNBIND no se ha ejecutado nunca contra el hardware**. Se cerro la
jornada antes. Lo pendiente, con los dos aparatos conectados:

1. Desbloquear y conectar la bodycam: en el telefono
   `camara BWC-896E atada a cmendez.aeriaone.com`, y en la camara
   `camara al servicio de cmendez.aeriaone.com`.
2. Bloquear la app y comprobar que la atadura se deshace con motivo CIERRE_DE_SESION.
3. La prueba que de verdad importa: que una atadura con el nonce de otra sesion se
   rechaza. El codigo lo contempla y nadie lo ha visto fallar.

Faltan tambien tres de los cinco motivos de fin del catalogo: reasignacion, revocacion
y fin de turno explicito. Solo estan cierre de sesion y caducidad.

### Cierre de la jornada

Se actualizo `tabla_horas_facturacion_proyecto.xlsx` con las tres sesiones del sistema
de autenticacion (10,4 h en total, bloques nuevos AN-2, AN-3 y BC-7) y se dejo anotado
en la propia hoja de donde sale cada numero: las del 4 y el 6 de septiembre del reloj
de sesion, el reparto por tarea dentro del 6 estimado sobre un total medido, y la del
3 de septiembre ESTIMADA y pendiente de confirmar.

---

## 2026-09-06 (4) — Workflows 13 y 31: la bodycam gana identidad propia y el canal deja de fiarse

### Hecho

Se ataca la deuda mas grave del repositorio: el canal con la W1 era un RFCOMM sin
cifrado de enlace con un UUID fijo **publicado en nuestra propia documentacion**.
Cualquiera que lo conociera podia mandarle `REC_STOP` a la camara, y la camara no
tenia forma de saber que no eramos nosotros; al reves igual, el telefono se conectaba
a la primera MAC que respondiera y se fiaba.

Es autenticacion MUTUA, asi que necesita las dos puntas. **BodyCamServer esta en
`C:\Users\newge\Desktop\Variedades\BodyCam\BodyCamServer`** (la ruta que figuraba en
`docs/bodycam-contexto.md` estaba desactualizada; conviene corregirla).

**En Aeria Nexus:**

- **`data/identity/EmparejamientoBodycam.kt`** — el protocolo y el lado del telefono,
  separados del transporte: recibe lineas y devuelve lineas. Eso es lo que permite
  probarlo entero sin hardware.
- **`data/BodycamRepository.kt`** — la app distingue por fin **conectado** de
  **autenticado** (`EnlaceAutenticado`: DESCONOCIDO, COMPROBANDO, SI, NO_SOPORTADO,
  RECHAZADO). El intercambio se atiende dentro del bucle de lectura que ya existia,
  no con lecturas bloqueantes aparte, porque competirian por las mismas lineas.
- **`EnrollmentRepository`** guarda el ancla de perifericos; se instala por intent.

**En BodyCamServer:**

- **`BodycamIdentity.kt`** — workflow 13: par EC P-256 en el Keystore de la W1, CSR,
  instalacion del certificado y prueba de posesion. `Der.kt` y `Pkcs10.kt` se copian
  de Aeria Nexus tal cual.
- **`Emparejamiento.kt`** — el lado de la camara. La camara valida al telefono con el
  mismo rigor con el que el telefono la valida a ella: si el telefono no se acredita,
  no se le firma nada.
- **`BtServerService`** — un intercambio por conexion: el nonce vale para esa y solo
  esa, que es lo que impide reutilizar una respuesta capturada.
- Se activa `buildConfig` en Gradle, que hacia falta para `BuildConfig.DEBUG`.

**`tools/alta-bodycam.sh`** — alta de la W1 con **la misma CA de dispositivos** que
los telefonos, como pide el documento ("Device Identity CA ... Android/BWC/goggles"),
y reparto del ancla a las dos puntas.

### El protocolo

    telefono -> bodycam   AUTH_HELLO:<version>:<deviceId>:<nonceA>
    bodycam  -> telefono  AUTH_ID:<bwcId>:<nonceB>:<certificado>
    telefono -> bodycam   AUTH_PROOF:<firma>:<certificado>
    bodycam  -> telefono  AUTH_OK:<firma>          o  AUTH_FAIL:<motivo>

Cada lado firma una transcripcion con **version, rol, los dos identificadores y los
dos nonces**. Cada trozo impide una cosa concreta: la version que se negocie a la
baja; **el rol** que se devuelva la firma del telefono haciendola pasar por la de la
camara; los dos IDs que valga una prueba de otro par; los dos nonces que un extremo
precalcule lo que va a firmar. Quitar cualquiera rompe una de las cuatro.

### Verificado (JVM, los dos extremos hablando entre si)

`EmparejamientoBodycamTest`, **6 pruebas**, con el responsable de la bodycam escrito
como implementacion de referencia en el codigo de pruebas. **Las negativas son el
motivo de que el fichero exista**: que el caso bueno funcione no demuestra nada.

| Ataque | Resultado |
|---|---|
| Devolver la firma del telefono como propia (reflexion) | rechazado |
| Firmar sobre otro nonce (respuesta de otra sesion) | rechazado |
| Camara con clave propia pero certificado de otra CA | rechazado |
| Sin ancla de confianza instalada | no se completa, y se distingue del rechazo |

Total del proyecto: **24 pruebas en verde**. Los dos APK compilan limpios.

### Lo que NO hace

- **No cifra el canal.** Autentica quien habla, no oculta lo que dice. Cifrarlo pide
  un acuerdo de claves efimero y nuestras claves son de firma (`PURPOSE_SIGN`): no
  sirven para derivar un secreto compartido. Es lo que queda del paso 6.
- **No corta el enlace cuando falla**, ni en el telefono ni en la camara. Hay
  terminales en campo con la version anterior y unidades sin dar de alta; cortar los
  dejaria sin camara sin ganar nada. Se marca, se registra y se ensena. **En los dos
  ficheros esta escrito donde y cuando invertir esto**: cuando toda la flota lleve la
  version nueva, un enlace RECHAZADO se cierra y uno NO_SOPORTADO deja de grabar.
- La W1 sigue sin poder pasar el alta de fabrica (decision D4). Lo que se acredita es
  que **esta instalacion de BodyCamServer** posee una clave que no sale del Keystore:
  periferico de garantia reducida, y llamarlo de otra forma seria mentir.

### Verificado en hardware real (W1 `30393016471440` + Redmi `u4vcjv7xeubiljhu`)

**Workflow 13, alta de la camara.** Par de claves generado en el Keystore de la W1
**con el reto emitido por el servicio**, CSR `CN=BWC-896E` con `verify OK`, emision
por `AeriaOne Device CA test` —la misma CA que los telefonos— y **prueba de posesion
true**. La camara ya es un tercer sujeto de confianza y no una extension del telefono.

**Workflow 31, caso bueno.** Del socket abierto al enlace acreditado, **229 ms**:

    telefono   I BodycamRepository: Enlace autenticado con BWC-896E · CN=BWC-896E,OU=QPD,O=AeriaOne
    bodycam    I Emparejamiento: telefono DEV-74435826 autenticado

**Workflow 31, prueba negativa.** Es la que da valor a la anterior: se le instalo al
telefono un ancla EQUIVOCADA (la CA de usuario, que no emitio ese certificado) y se
repitio el emparejamiento sin cambiar nada mas.

    telefono   E BodycamRepository: ENLACE NO FIABLE: El certificado de la bodycam no lo emitio AeriaOne
    bodycam    I Emparejamiento: telefono DEV-74435826 autenticado

El telefono rechazo a la camara **y la camara siguio aceptando al telefono**, que es
exactamente lo correcto: la camara tenia el ancla buena y el certificado del telefono
es legitimo. Cada punta valida por su cuenta, y eso es lo que hace que esto sea
autenticacion mutua y no un login. Sin esta prueba, un fallo de cableado —que el
ancla no llegara a leerse y se aceptara cualquier cosa— habria pasado las pruebas de
JVM y el log del caso bueno sin que nadie lo notara.

### Fallo de diseno encontrado al verificarlo

**El estado del enlace no se veia en ninguna parte.** Se construyo `EnlaceAutenticado`
y no se saco a la pantalla, asi que la app se veia IGUAL con un enlace autenticado que
con uno rechazado. El usuario, mirando la app durante la prueba negativa, concluyo que
habia funcionado y pregunto si habia que olvidar el emparejamiento Bluetooth. Tenia
toda la razon en no verlo: un dato que solo existe en el log no protege a nadie.

Corregido en `ui/components/AppScaffold.kt`, en la barra superior que el agente ve
siempre:

| Enlace | Icono | Etiqueta |
|---|---|---|
| Autenticado | verde | *(nada)* |
| Comprobando | ambar | CHECKING |
| Sin acreditar | ambar | UNVERIFIED |
| Rechazado | rojo | NOT TRUSTED |

Dos decisiones detras: **el verde se reserva para el enlace autenticado** —hasta
ahora "conectado" era verde a secas, y un enlace abierto con una camara que no ha
podido acreditarse es peor que no tener camara, porque el agente cree que la tiene—;
y **cuando todo esta bien no se escribe nada**, porque si cada enlace correcto pusiera
etiqueta, el agente dejaria de leerlas y la unica que importa pasaria desapercibida.
Va en texto ademas de en color: en esa barra el rojo ya significaba "error de
conexion".

### Nota para el dia 15: bonding no es autenticacion

Salio como duda durante la prueba y conviene tenerlo dicho. El emparejamiento
Bluetooth de Android es del TRANSPORTE: abre el socket. Nuestro intercambio viaja
como lineas de texto DENTRO de ese socket. Olvidar el bonding solo impediria
conectar; no cambia nada de la comprobacion criptografica. Por eso "se conecta sin
problema" y "el telefono la rechaza" son compatibles, y por eso hacia falta el
indicador.

### Proximo paso

- Cerrar la verificacion en campo de lo anterior.
- Workflows 33 y 34: binding agente-camara con caducidad y desemparejado al fin de
  turno. Ahora si tienen sobre que apoyarse.
- Corregir la ruta de BodyCamServer en `docs/bodycam-contexto.md`.

---

## 2026-09-06 (3) — Servicio de retos y workflow 27: el PIN autoriza la clave y esa clave firma

### Hecho

Se cierra la costura que veniamos arrastrando desde el workflow 12 y que estaba anotada en cada
entrega: las pruebas de posesion se firmaban sobre un valor que se inventaba el propio telefono
—`SecureRandom` para la atestacion y la cadena literal `"prueba-de-posesion"` para las firmas—, asi
que acreditaban que las dos mitades del par se corresponden pero **no que quien responde tenga la
clave ahora**. Una respuesta capturada valia para siempre. Es el IAM-04 textual, "fresh
challenge-response".

- **`data/identity/RetoRepository.kt` (nuevo)** — retos con **proposito**, **un solo uso** y
  **caducidad**. Cuatro propositos y no uno compartido: atestacion del terminal (wf 12 paso 10),
  posesion del terminal (paso 16), posesion del agente (wf 3 paso 14) y login (wf 27 pasos 11-12).
  Si el mismo nonce valiera para el alta y para el desbloqueo, quien capturase una respuesta de alta
  podria presentarla como inicio de sesion. El reto se retira ANTES de firmar, no despues: si se
  retirase despues, un fallo a mitad lo dejaria disponible para un segundo intento.
- **`tools/reto.sh` (nuevo)** — emite y verifica. El nonce sale de `openssl rand` en el PC, asi que
  el telefono no puede predecirlo. Al verificar comprueba **primero que la respuesta contesta al
  reto que emitimos** y despues la firma; sin esa primera comprobacion, una firma valida sobre
  cualquier otro valor pasaria por buena, que es justo el ataque que el reto viene a impedir.
- **`alta-terminal.sh` y `alta-agente.sh`** llevan ahora su reto dentro del mismo intent que el
  certificado, y verifican la respuesta al terminar.
- **Degradacion visible.** Si no hay reto emitido la app sigue funcionando —el agente tiene que
  poder trabajar sin backend— pero lo dice: en la pantalla del alta sale `SELF-CHALLENGED (proves
  no freshness)` y en el log un aviso de que esa firma es reutilizable.

Y con eso encima, los dos pasos del **workflow 27** que faltaban:

- **Paso 8, autorizar la clave.** `CredentialRepository` gana una autorizacion que solo el
  desbloqueo concede, y `firmarRetoDeSesion` **falla** si nadie ha puesto el PIN. La firma del alta
  va por otra funcion, `pruebaDePosesionDelAlta`, porque ocurre antes de que exista PIN alguno.
- **Paso 12, firmar el reto.** Tras verificar el PIN se firma el reto `LOGIN` con la clave del
  agente y la respuesta queda donde el servicio pueda verificarla. La autorizacion vive en memoria y
  muere con el proceso: cerrar la app obliga a volver a poner el PIN.
- **`OrigenDeSesion`** en `TrustStatus`: NINGUNA, SOLO_LOCAL o ACREDITADA. Un PIN correcto sin reto
  que firmar demuestra que quien tiene el telefono conoce el PIN y **nada mas**; nadie ha comprobado
  que la credencial siga siendo valida ni que el agente siga de alta. Eso no puede quedarse en un
  log, asi que la pantalla de bloqueo avisa ANTES de teclear: `AERIAONE CHALLENGE PENDING · your PIN
  will sign it with your credential`. Cuando ese aviso no aparece, tampoco es un detalle.
- Si la firma falla se entra igual y la sesion queda marcada como local: que falle el backend no
  puede dejar a un agente fuera de su terminal a las tres de la manana.

### Hasta donde llega la autorizacion, dicho sin adornos

**No es una barrera criptografica.** La clave del Keystore la puede usar el proceso pase lo que
pase, y quien ejecute codigo dentro de la app se salta ese booleano. Lo que si garantiza es
estructural y no es poco: **ningun camino del codigo puede firmar como el agente sin que se haya
introducido el PIN en esta sesion**, ni por un descuido nuestro ni desde un servicio en segundo
plano. La barrera de verdad —atar la clave a una autenticacion del sistema— es la decision D2.

### Verificado en el Redmi Note 8 Pro (`u4vcjv7xeubiljhu`), recorrido completo desde cero

| Paso | Reto | Resultado |
|---|---|---|
| wf 12 · 10 · atestacion | del servicio | TEE, cadena de 4, **"challenged by AeriaOne-challenge-service-test"** en pantalla |
| wf 12 · 16 · posesion del terminal | propio, un solo uso | nonce correcto · **Verified OK** |
| wf 3 · 8 · contrafirma del terminal | — | **Verified OK** |
| wf 3 · 14 · posesion del agente | propio, un solo uso | nonce correcto · **Verified OK** |
| wf 27 · 12 · login | propio, un solo uso | `sesion acreditada` · **Verified OK** |

Cinco comprobaciones criptograficas encadenadas, cada una con su nonce, y dos CA separadas.
`DEV-74435826` emitido por `AeriaOne Device CA test` y `cmendez.aeriaone.com` por
`AeriaOne User CA test`.

**Prueba negativa, que es la que da valor a las anteriores:** repetir la verificacion con la misma
respuesta devuelve `RECHAZADO: la respuesta no contesta al reto emitido`. El reto se destruye al
usarse en los dos extremos.

Antes de esto, el log de un desbloqueo decia
`desbloqueo sin reto del backend: la sesion es SOLO local`. Ahora dice
`reto de AeriaOne-challenge-service-test firmado como cmendez.aeriaone.com: sesion acreditada`.
Esa es la diferencia entre "la app pide un PIN" y "el agente demuestra quien es".

### Corregido: el PIN se pedia tres veces

Lo reporto el usuario. Elegir, confirmar y **desbloquear**. Las dos primeras se quedan —el PIN no se
puede recuperar y el workflow 6 no existe— pero la tercera sobraba: el agente acababa de teclearlo
dos veces doce segundos antes.

Ahora se entra directamente al confirmar, **llamando al mismo `unlock()`** y no a un atajo, aunque
cueste una derivacion mas de ~600 ms. Asi hay **un solo camino para abrir sesion** y ese camino
siempre pasa por el verificador, autoriza la clave y firma el reto. Un segundo camino que abriese
sesion sin verificar nada es la clase de atajo que despues aparece en produccion sin que nadie
recuerde haberlo escrito. Va fuera del hilo de la interfaz, como el resto.

### Tambien corregido

- **`destruirIdentidadLocal` dejaba los identificadores.** Tras un reset seguian en las
  preferencias el Device ID y el agente. Funcionalmente daba igual, pero un volcado en la auditoria
  habria mostrado a que agente y a que terminal pertenecio un telefono cuya identidad se supone
  destruida; el §13 pide destruirla, no olvidarla. Ahora el XML queda con dos booleanos y nada mas.
- Un reset mio no llego a aplicarse porque mande el `am start` con la salida a `/dev/null` y no vi
  el fallo. Lo detecto el usuario al ver que el telefono seguia enrolado. Nota operativa: no silenciar
  la salida de los intents de depuracion.

### Pendiente

- Rechazos de la politica de PIN y bloqueo escalonado **en pantalla** (la logica tiene pruebas JVM).
- El nonce de atestacion se queda sin consumir en `tools/ca-pruebas/retos/`: el script lo emite pero
  no lo verifica, porque interpretar la cadena de atestacion es del backend. Es coherente, pero
  conviene decirlo antes de que alguien vea el fichero suelto.

### Proximo paso

- **Workflow 28**: token de sesion. Es el siguiente por dependencia, pero obliga a inventarnos el
  formato del token y sus claims, y ya van tres formatos propuestos por nosotros (solicitud del
  agente, fichero de retos y ahora el token). Conviene llevarlos juntos a ciberseguridad el dia 15
  antes de anadir un cuarto.
- Alternativa con mas valor por hora: **workflow 31**, el canal con la bodycam, que sigue siendo la
  deuda mas grave del repositorio y no depende de nadie.

---

## 2026-09-06 (2) — Workflow 4: el PIN deja de ser una constante y pasa a ser un verificador

### Hecho

- **`data/identity/PinLocal.kt` (nuevo)** — el PIN **no se guarda en ninguna forma**, ni en claro ni
  en resumen. Se guarda un testigo conocido cifrado con AES-GCM bajo una clave derivada del PIN con
  PBKDF2-HMAC-SHA256: si el PIN es el correcto el testigo sale entero, y si no falla el tag y no hay
  nada que comparar. Es el mismo mecanismo que la boveda de evidencia, que lleva desde agosto.
- **El fichero va ademas envuelto por una clave de AndroidKeyStore propia**, distinta de la de la
  evidencia. `DeviceKeyWrapper` pasa de `object` a clase con dos instancias (`evidencia` e
  `identidad`), que es la separacion por proposito que pide IAM-05: comprometer la evidencia no
  puede comprometer la identidad. Esa segunda capa es la que importa de verdad con seis digitos:
  sin ella, sacar el fichero con `adb` y probar el millon de combinaciones en un PC son minutos.
- **El contador de intentos sale de SharedPreferences en claro** y entra en ese mismo fichero. Si se
  pudiera poner a cero editando un XML, el limite de intentos no limitaria nada. No hace falta el
  PIN para leerlo ni escribirlo —hay que contar los fallos precisamente cuando el PIN no se sabe—,
  solo la clave de Keystore. Ademas se borran del XML las claves viejas `fallos`, `bloqueos` y
  `fin_bloqueo`: quien abriera ese fichero el dia 15 concluiria justo lo contrario de lo que pasa.
- **`TrustState.PIN_SETUP`** — con certificado y sin PIN el alta no ha terminado. El alta pasa a
  tener tres tramos, y el ultimo es el unico que depende de una persona.
- **`feature/enrollment/PinSetupViewModel` + `PinSetupScreen`** — alta del PIN con confirmacion.
  Se pide dos veces porque el PIN no se puede recuperar: un digito mal al crearlo deja al agente
  fuera de su terminal en el arranque siguiente, y el workflow 6 no existe.
- **`ui/components/TecladoPin.kt`** — el teclado y los puntos salen de `LockScreen` porque los usan
  dos pantallas. No es solo no duplicar: el agente elige aqui el secreto que va a teclear cada dia,
  y si la pantalla donde lo elige tuviera otras teclas se equivocaria al crearlo.
- **`PoliticaPin`** — politica minima **propuesta por nosotros**: se rechaza el mismo digito seis
  veces y las secuencias en los dos sentidos, y nada mas. Reglas mas duras, con guantes y de noche,
  acaban en un PIN apuntado en la funda del telefono.
- **`PIN_DEMO` deja de ser el verificador de nadie.** Solo queda como el PIN que se pone el
  simulador de compilaciones debug al saltar a un estado bloqueado sin pasar por el alta.
- **Tests**: 4 pruebas JVM de la politica. Total del proyecto, **18 en verde**.

### El coste del PIN: por que 210.000 iteraciones estaban mal y que se hace ahora

Esto salio porque el usuario dijo que al desbloquear "tarda como 4 segundos". Tenia razon y la
explicacion que le di primero —"son cientos de milisegundos, es el precio de la seguridad"— era
falsa. Medido en el Redmi Note 8 Pro:

| Configuracion | Coste de una derivacion |
|---|---|
| 210.000 iteraciones (recomendacion de OWASP, copiada de la boveda) | **2.703 ms** |
| 50.000 iteraciones (calibrado) | **843 ms** |

Dos cosas iban mal y son distintas:

1. **El calculo corria en el hilo de la interfaz.** La app no tardaba: se congelaba. Por eso ademas
   el sexto punto del PIN no llegaba a pintarse nunca, que fue el sintoma que reporto el usuario
   antes. Ahora va en `Dispatchers.Default`, y la pausa de 150 ms deja de ser fija: es un **suelo**
   que solo se aplica si el trabajo real volvio antes, que es el caso de los rechazos por politica.
2. **El numero de iteraciones estaba copiado, no medido.** 210.000 es la recomendacion de OWASP para
   bases de datos de contrasenas; aqui el fichero va ademas sellado con una clave no exportable del
   Keystore, asi que el modelo de ataque no es el mismo.

**Ahora se calibra al crear el PIN**: se mide una muestra de 20.000 iteraciones, se extrapola a un
presupuesto de 400 ms y el resultado se guarda en el propio fichero, que ya tenia el campo. Un
terminal rapido usara mas iteraciones y uno lento seguira siendo usable, y ninguno recalibra al
desbloquear. Techo 600.000 (recomendacion actual de OWASP), suelo 50.000.

En el Redmi la calibracion dio `20.000 iteraciones en 381 ms -> se fijan 50.000`: **manda el suelo,
no el presupuesto**, y el desbloqueo cuesta 843 ms. Es tres veces mejor que antes y sigue sin llegar
a los 400 ms; se acepta porque el suelo esta para que un terminal lento no calibre la seguridad
hasta hacerla desaparecer.

**Por que se puede bajar aqui y no en la boveda**, que es la pregunta que van a hacer: la boveda
protege evidencia que puede salir del dispositivo. Este fichero no: va envuelto por una clave de
Keystore no exportable, asi que copiarlo a un PC no sirve de nada y el ataque realista es ejecutar
codigo como la app en este telefono. Ahi el millon de PIN posibles por 843 ms son mas de una semana
de computo continuo, y ese es el orden de magnitud que compra el parametro.

**ESTO NO PUEDE QUEDARSE DECIDIDO POR NOSOTROS.** El coste del KDF es politica de credenciales, y la
politica de credenciales es uno de los diez artefactos de diseno que el propio documento reconoce
pendientes. Va a la lista de decisiones como **D8**, con la medida delante, que es mucha mejor
posicion para discutirlo que en abstracto.

### Hasta donde llega el PIN, dicho claro

El PIN autoriza el uso de la credencial **dentro de la aplicacion**: es la app la que se niega a
seguir, no el Keystore. Atar la clave del agente a una autenticacion del sistema es la decision D2,
que sigue abierta. Lo que si es cierto: la clave privada no sale del Keystore pase lo que pase, y
borrar los datos de la app para reiniciar el contador destruye tambien las claves y deja el terminal
sin identidad. El atajo existe, pero cuesta la credencial.

### Verificado en el Redmi Note 8 Pro (`u4vcjv7xeubiljhu`)

- **Tercer caso de migracion**: un terminal con el alta completa bajo el codigo anterior arranca en
  PIN_SETUP y pide el PIN, en vez de quedarse con un verificador que ya no existe.
- Alta del PIN con calibracion (`se fijan 50000`) y desbloqueo correcto a continuacion (843 ms).
- Las claves viejas del XML de confianza ya no estan: el volcado solo tiene estado no secreto
  (device_id, user_id y las dos marcas de etapa).

### Pendiente (verificar en dispositivo)

- Rechazos por politica y PIN que no coincide **en pantalla** (la logica tiene pruebas JVM, el
  recorrido visual no se llego a hacer).
- Bloqueo temporal escalonado 1 / 5 / 30 min con el contador ya dentro del fichero sellado.

### Fallos encontrados al ejecutarlo, ya corregidos

1. **El sexto punto del PIN no se pintaba.** Se actualizaba el estado y se resolvia en la misma
   pasada, asi que Compose solo renderizaba el estado final, ya vacio. Lo reporto el usuario. Afecta
   a las dos pantallas —crear el PIN y desbloquear— y en la de desbloqueo importa mas: con un PIN
   erroneo el agente veia los puntos vaciarse sin haber visto nunca el ultimo.
2. **El simulador dejaba PIN_SETUP incoherente**: marcaba la credencial como no emitida cuando en
   ese tramo el certificado si existe y lo unico que falta es el PIN. El terminal habria vuelto a
   ENROLLING en el arranque siguiente.

### Deuda que se cierra

- Contador de intentos en SharedPreferences en claro: **cerrada**.
- PIN de demostracion como verificador: **cerrada**; la constante solo se alcanza en debug.

### Proximo paso

- **Servicio de retos del backend simulado**, que es lo que queda del hito del 15. Es ademas la
  costura mas visible que arrastramos: hoy el reto de la prueba de posesion se lo inventa el propio
  telefono, asi que acredita que las dos mitades del par se corresponden pero **no frescura**
  (IAM-04). Con el, los pasos 11 a 14 del workflow 27 dejan de ser papel.

---

## 2026-09-06 — Workflow 3: la credencial del agente, separada de la del telefono

### Hecho

Antes del codigo, una sesion de inventario: se cruzo el catalogo de 68 workflows y el documento de
arquitectura contra el repositorio y se dejo el resultado en **`Seguimiento-IAM-AeriaOne.xlsx`**
(10 hojas: catalogo, los 162 pasos de las 8 hojas de detalle marcados uno a uno, lo accionable sin
backend, lo bloqueado, el plan G0-G7, septiembre, las decisiones D1-D7, los requisitos IAM-01..20 y
la deuda). El recuento: **4 hechos, 22 parciales, 25 sin empezar y 17 que no nos tocan**; y
**21 workflows se cierran enteros sin backend**, 29 a medias y 18 no.

Y despues el workflow 3, que es el que tocaba por orden del plan.

- **`data/identity/ClaveEnKeystore.kt` (nuevo, sustituye a `DeviceKeystore.kt`)** — una clase por
  alias, con dos instancias: `terminal` (`aeria.device.key`, workflow 12) y `agente`
  (`aeria.user.key`, workflow 3). El fichero anterior era un `object` con el alias incrustado, asi
  que la segunda clave habria significado copiar 120 lineas de mecanica de Keystore. La diferencia
  real entre las dos es una sola: la del terminal se genera con reto de atestacion y la del agente
  no, porque la atestacion acredita la plataforma, no a la persona, y la plataforma ya quedo
  acreditada en el alta anterior.
- **`data/identity/CredentialRepository.kt` (nuevo)** — los 5 pasos nuestros de los 16: par de
  claves del agente (6), peticion (7), entrega (8), instalacion del certificado (11) y prueba de
  posesion (14). Al instalar se comprueba ademas que el nombre comun del certificado es el agente
  que pedimos: sin eso, una CA mal configurada podria devolver un certificado a nombre de otro y el
  telefono lo aceptaria sin rechistar.
- **La solicitud del agente va contrafirmada por el terminal.** El paso 8 del catalogo dice que la
  peticion viaja "through the authenticated AeriaOne backend together with the relevant enrollment
  context", y ese contexto es justo lo que se pierde al dejar un fichero suelto: cualquiera podria
  presentar un CSR de agente. Asi que el terminal firma el DER del CSR con SU clave y la solicitud
  lleva dentro la prueba de que salio de un telefono dado de alta. Es la mitad del paso 5 (atadura
  agente-dispositivo) que se puede hacer desde este lado. **El formato del fichero es propuesta
  nuestra** y esta marcado como tal: la especificacion de API es uno de los diez artefactos que el
  propio documento reconoce pendientes.
- **`data/identity/Pem.kt` (nuevo)** — lectura de certificados en PEM, que iban a ser dos copias de
  la misma expresion regular. `instalarCertificadoEmitido` tambien sube a `ClaveEnKeystore`, con la
  comprobacion de que el certificado corresponde a la clave.
- **El alta pasa a tener dos etapas visibles.** `IdentityRepository` lleva ahora dos marcas
  separadas, una por identidad: terminal acreditado y agente con credencial. Entre las dos el
  telefono se queda en ENROLLING, no en LOCKED, y esa es la traduccion literal de la regla de no
  equivalencia: el telefono esta acreditado y aun asi no hay a quien pedirle un PIN, porque todavia
  no hay identidad de persona.
- **`feature/enrollment/`** — el asistente gana la fase del agente (4 pasos), que arranca sola en
  cuanto el terminal tiene certificado, sin boton: en el modelo real es el IAM quien entrega la
  identidad activa al proceso de credenciales, y pedirle al agente una confirmacion seria
  inventarse una decision que no es suya. `EnrollmentScreen.kt` se paso de las 300 lineas de la
  regla del proyecto y el dibujo de los pasos salio a `EnrollmentPasos.kt`.
- **`tools/alta-agente.sh` (nuevo)** — hace de backend para el workflow 3. **Firma con una CA
  distinta** de la del terminal, como pide el documento (dominios de confianza "User Identity CA" y
  "Device Identity CA" separados, con perfiles y politicas propios). Antes de emitir comprueba la
  contrafirma del terminal contra el certificado que emitio `alta-terminal.sh`, que ahora lo guarda.
- **Tests** (`CredencialDelAgenteTest.kt`) — 5 pruebas JVM: que la peticion va al nombre del agente
  y NO lleva el Device ID; que la contrafirma del terminal verifica; que **la clave del agente no
  sirve para firmar como el terminal**, que es la prueba que sostiene toda la regla de no
  equivalencia; y que cambiar un solo byte del CSR invalida la contrafirma.

### Decisiones tomadas

- **La clave del agente se genera sin reto de atestacion.** El workflow solo pide que sea
  "hardware-backed/non-exportable"; atestar otra vez la misma plataforma no anade nada que el
  backend vaya a comprobar en el paso 14, que es prueba de posesion.
- **D2 sigue abierta y sigue marcada en un solo sitio.** Tampoco aqui se llama a
  `setUserAuthenticationRequired`. Ahora el KDoc que lo explica esta en `ClaveEnKeystore`, que es el
  unico punto donde habra que tocar cuando se cierre — antes estaba en `DeviceKeystore`.
- **El identificador del agente deja de ser una constante en cuanto hay certificado**: sale del
  nombre comun que emitio la CA. Es el primer dato de la identidad que ya no nos inventamos.

### Verificado

- `assembleDebug` y `testDebugUnitTest` limpios, sin warnings nuevos. **13 pruebas JVM en verde**
  (8 del workflow 12 y 5 del 3).
- Las dos peticiones, validadas desde fuera con `openssl req -verify`:
  `CN=DEV-92A71C` y `CN=cmendez.aeriaone.com`, ambas "self-signature verify OK".
- El circuito de `alta-agente.sh` se probo entero con openssl, sin telefono: extraccion de los
  campos del JSON, decodificacion de la contrafirma, `openssl dgst -verify` contra el certificado
  del terminal (**Verified OK**), emision con la CA de usuario y `openssl verify` (**OK**). Y la
  comprobacion negativa: la contrafirma de OTRO telefono se rechaza.
- Contraste de los dos certificados emitidos, que es lo que hay que ensenar:
  `CN=cmendez.aeriaone.com` emitido por `AeriaOne User CA test`, frente a `CN=DEV-8DB2C628` del
  terminal. Dos sujetos y dos emisores.

### Verificado en el Redmi Note 8 Pro (`u4vcjv7xeubiljhu`), mismo dia

**Migracion de un telefono ya dado de alta.** El Redmi tenia el workflow 12 cerrado desde el
2026-09-04 (`provisionada=true`, sin marca de credencial). Se instalo encima sin desinstalar y al
arrancar hizo exactamente lo previsto sin tocar nada: entro en ENROLLING en vez de LOCKED, arranco
sola la fase del agente, genero el segundo par **en el TEE** ("separate from the device key" en la
propia pantalla) y dejo la solicitud contrafirmada por `DEV-8DB2C628`.

**Circuito completo con las dos CA:**

| Paso | Resultado |
|---|---|
| CSR del agente | `Certificate request self-signature verify OK` · `CN=cmendez.aeriaone.com` |
| Contrafirma del terminal | `openssl dgst -verify` -> **Verified OK** |
| Emision con la CA de usuario | `openssl verify` -> **OK** |
| Certificado instalado | `CN=cmendez.aeriaone.com` emitido por `CN=AeriaOne User CA test` |
| Prueba de posesion (paso 14) | **true** |

Y el contraste que es lo que hay que ensenar el dia 15: el mismo telefono lleva
`CN=DEV-8DB2C628` emitido por `AeriaOne Device CA test` y `CN=cmendez.aeriaone.com` emitido por
`AeriaOne User CA test`. Dos claves, dos CA, dos certificados.

Arranque en frio despues: **LOCKED con `cmendez.aeriaone.com`**, que es el nombre comun que emitio
la CA y no la constante del fuente.

**Destruccion de la identidad (§13).** Forzar NOT_PROVISIONED por intent deja las dos carpetas
(`files/enrollment` y `files/credential`) vacias, `aeria_credential.xml` vacio y las dos claves
borradas del Keystore. Se destruyen las dos o ninguna, que es lo que hace `destruirIdentidadLocal`.

### Fallo encontrado al ejecutarlo, ya corregido

`tools/alta-agente.sh` no podia decodificar la contrafirma: `JSONObject` escribe `\/` donde hay una
barra —JSON legal— y el lector de campos a base de `sed` se lo tragaba tal cual, con lo que el
base64 salia invalido y openssl daba `Error reading signature file`. El `campo()` deshace ahora ese
escapado. Un parser de verdad lo habria hecho solo; este lector de andar por casa no.

**Recorrido desde cero, encadenado.** Con el terminal limpio (el boton "ENROLL THIS PHONE" lo pulso
el usuario: MIUI rechaza `adb shell input` por `INJECT_EVENTS`):

1. Fase 1 sola -> Device ID **nuevo**, `DEV-0F6A5674`, distinto del anterior, que es la prueba de
   que el borrado destruyo la identidad y no solo la olvido. `files/credential` vacio: la fase 2
   espera al certificado en vez de adelantarse.
2. `alta-terminal.sh` -> certificado del terminal, prueba de posesion **true**.
3. **La fase 2 arranco sola** al llegar ese certificado, y la solicitud salio contrafirmada ya con
   `DEV-0F6A5674`.
4. `alta-agente.sh` -> contrafirma **Verified OK**, emision **OK**, posesion **true**.
5. Arranque en frio: **LOCKED** con `cmendez.aeriaone.com` y `DEV-0F6A5674`.

### Notas

- La regla de no equivalencia deja de ser una frase del documento y pasa a ser algo que se puede
  ensenar: dos claves, dos CA, dos certificados, y una prueba que falla si se intercambian.
- Lo que sigue faltando y conviene decir el dia 15: el reto de la prueba de posesion se lo sigue
  inventando el telefono, asi que acredita que las dos mitades del par se corresponden pero no
  frescura; y la identidad del agente no la emite ningun IAM.

### Proximo paso

- **Workflow 4**: PIN real que sustituya el `PIN_DEMO` de `IdentityRepository` y que autorice el uso
  de la clave del agente, con el contador de intentos fuera de SharedPreferences. Es lo unico que
  falta del hito del 15.
- Antes, en cuanto haya un telefono conectado, cerrar la verificacion en campo de esta entrada.

---


## 2026-09-04 (2) — Workflow 12: alta del telefono con clave en Keystore y PKCS#10 a mano

### Hecho

De los 21 pasos del catalogo, los 9 que son nuestros. Los otros 12 son del backend y no existen.

- **`data/identity/Der.kt`** — codificador DER minimo (entero, bit string, OID, UTF8String,
  secuencia, conjunto, contexto implicito). Unas 130 lineas frente a los varios megas de
  BouncyCastle: **confirmada la decision D3**, escribirlo a mano.
- **`data/identity/Pkcs10.kt`** — peticion de certificado RFC 2986. Dos cosas salen gratis y estan
  documentadas en el fichero: `PublicKey.getEncoded()` ya es un SubjectPublicKeyInfo en DER, y la
  firma que produce `Signature` para ECDSA ya es la SEQUENCE de r y s. Se firma el bloque
  `certificationRequestInfo` **tal y como se codifico**, sin recodificarlo, que es la forma clasica
  de que la CA rechace la peticion.
- **`data/identity/DeviceAttributes.kt`** — pasos 3, 5 y 6. Modelo, Android, parche de seguridad,
  huella del firmante del APK, disponibilidad de Keystore, capacidad declarada de hardware,
  indicios de manipulacion y emulador.
- **`data/identity/DeviceKeystore.kt`** — pasos 10, 15 y 16. Par EC P-256 generado DENTRO del
  Keystore; no hay ninguna funcion que devuelva la clave privada, solo un manejador para firmar.
- **`data/identity/EnrollmentRepository.kt`** — orquesta el alta y guarda el CSR.
- **`feature/enrollment/`** — asistente de seis pasos visibles que resume los 21 del catalogo.
- **`tools/alta-terminal.sh`** — hace de backend a mano: saca el CSR con `run-as`, lo valida, lo
  firma con una CA de pruebas y devuelve el certificado a la app por intent. La carpeta de la CA
  esta en `.gitignore`.
- **Tests** (`app/src/test/.../Pkcs10Test.kt`) — 7 pruebas en JVM: codificacion DER (entero con bit
  alto, OID con componentes grandes, longitud en forma larga), verificacion de que la firma cubre
  el bloque correcto **releyendo el DER con un lector independiente**, y formato PEM.

### Decisiones tomadas

- **La clave se intenta en tres escalones**: StrongBox con atestacion, TEE con atestacion, TEE sin
  atestacion. Hay terminales de campo que fallan en cada uno; quedarse sin clave seria peor que
  quedarse sin atestacion, que el backend puede exigir o no por politica.
- **NO se llama a `setUserAuthenticationRequired`.** Eso es la decision D2, que sigue abierta: atar
  la clave a la credencial del sistema significa que sin patron de pantalla no hay identidad. Esta
  marcado en el KDoc de `DeviceKeystore` que cuando D2 se cierre se cambia ahi y en ningun sitio mas.
- **`DevicePosture` no tiene ninguna propiedad "apto"**, y es a proposito: decidir la elegibilidad
  es el paso 4 y ese paso es del backend. La app informa, no decide. Los indicios de root se
  reportan y el alta continua.
- **El limite de privacidad se dice antes de empezar**, en la pantalla, no en una politica que nadie
  lee: que se lee del telefono, que la clave no sale de ahi y que las apps y archivos personales
  quedan fuera. Es el paso 19 del workflow puesto donde se ve.

### Verificado en el Redmi Note 8 Pro (`u4vcjv7xeubiljhu`)

Alta real ejecutada en el terminal:

| Paso | Resultado |
|---|---|
| 3 | Xiaomi Redmi Note 8 Pro · Android 11 · patch 2022-04-01 |
| 5 | Keystore disponible, sin indicios de manipulacion |
| 8 | `DEV-8DB2C628` |
| 10 | **Trusted execution environment · cadena de atestacion de 4 certificados** |
| 11 | PKCS#10 · ECDSA P-256 · SHA-256 |

Y el circuito completo con el CSR que salio del telefono:

```
openssl req -in device.csr.pem -verify -noout
    -> Certificate request self-signature verify OK
    -> Subject: O=AeriaOne, OU=QPD, CN=DEV-8DB2C628
openssl x509 -req ... -CA ca.crt      -> certificado emitido
openssl verify -CAfile ca.crt         -> device.crt: OK
```

Certificado devuelto a la app, instalado sobre la clave del Keystore, y **prueba de posesion del
paso 16: `true`** — la clave que vive en el TEE y la que certifico la CA son el mismo par. El
terminal arranca ya bloqueado mostrando su Device ID real.

### Lo que NO esta hecho (decirlo el dia 15 antes de que lo pregunten)

- **No hay backend.** Los pasos 2, 4, 7, 13, 14, 17 y 18 no ocurren.
- **El Device ID se lo inventa el telefono.** Lo emite el registro de dispositivos (paso 8).
- **El reto de atestacion lo genera el propio dispositivo**, asi que la cadena sale bien formada
  pero no prueba frescura. Es la costura mas visible de que falta la otra mitad.
- **La app no interpreta la atestacion**, solo la transporta. Verificarla (arranque verificado,
  nivel de la clave, reto) es del backend, y es donde se sostienen de verdad los pasos 5 y 6.

### Notas de plataforma

- MIUI apaga la pantalla y bloquea el terminal a mitad de sesion: `adb shell svc power stayon usb`
  antes de una tanda de capturas.

### Proximo paso

- **Workflow 3** (certificado del agente: segundo par de claves, CSR e instalacion de la cadena) y
  **workflow 4** (PIN real, que sustituye el `PIN_DEMO` de `IdentityRepository`).
- El **backend simulado** con CA y servicio de retos, que es lo que convierte `tools/alta-terminal.sh`
  en algo que se pueda ensenar sin explicar que lo estamos haciendo a mano.

---

## 2026-09-04 — Maquina de estados de confianza y pantalla de bloqueo (UX del IAM, sin criptografia)

### Hecho

Se adelanta la capa visual del modelo IAM antes que la logica. Justificacion: el documento de
ciberseguridad pone el diseno de UI **fuera de su alcance** (§1.3 Out of Scope, "product UI design"),
asi que no hay nada que negociar con ellos, y las 7 decisiones abiertas D1-D7 no bloquean ninguna
pantalla. Ademas es un seguro para el dia 15: si el workflow 12 se atasca, hay demo igual.

- **`data/identity/TrustState.kt`** — siete estados de arranque: NOT_PROVISIONED, ENROLLING, LOCKED,
  ACTIVE, SESSION_EXPIRED, OFFLINE_GRANTED y BLOCKED. Y `TrustBlockReason` con los cortes del §13,
  cada uno con **que ha pasado**, **que hacer** y si reintentar sirve.
- **`data/identity/IdentityRepository.kt`** — un solo `StateFlow<TrustStatus>` (estado + motivo +
  identidad + intentos + fin de bloqueo). Politica de reintentos del workflow 27 paso 7: 5 intentos
  por tanda, bloqueo de 1 min -> 5 min -> 30 min. Sin criptografia: es el hueco con la forma que
  dejaran los workflows 12, 4 y 27-28.
- **`feature/lock/LockScreen.kt` + `LockViewModel.kt`** — pantalla de PIN. Muestra la identidad
  provisionada y NO pide usuario (workflow 27 paso 4), teclado de 68.dp, PIN de 6 digitos con
  validacion automatica al completarlo, cuenta atras visible en el bloqueo temporal.
- **`feature/lock/TrustBlockedScreen.kt`** — terminal fuera de servicio, con los identificadores en
  monoespaciada para poder dictarlos por radio.
- **`feature/enrollment/NotProvisionedScreen.kt`** — marcador provisional del alta, con la etiqueta
  "ENROLLMENT NOT IMPLEMENTED YET · WORKFLOW 12 / 22-23" a la vista.
- **`navigation/TrustGate.kt`** — la puerta. MainActivity monta esto y no `AppNavHost`; la app de
  siempre solo existe dentro de la rama ACTIVE.
- **`navigation/TrustStateSimulator.kt`** — selector de estado en compilaciones debug (pestana
  estrecha en el borde izquierdo), mas un atajo por intent equivalente:
  `adb shell am start -n com.delta.aeria_nexus_prototype/.MainActivity --es trust_state BLOCKED --es block_reason DEVICE_REVOKED`

### Decisiones tomadas

- **Los textos de interfaz siguen en ingles**, como el resto de la app (el cliente es QPD,
  Filipinas). La skill `ui-ux-policial` dice espanol; manda la coherencia con el codigo existente.
- **La pantalla de bloqueo no usa `AppScaffold`.** La barra superior lleva estado de bodycam, gafas
  e indicador REC y la inferior la navegacion: es informacion operativa y no puede verse antes de
  autenticarse. Quien encuentre el telefono solo ve de quien es y que hace falta un PIN.
- **Solo 7 de los 9 interruptores de corte del §13 producen pantalla completa.** Retirar un permiso
  quita una funcion, revocar un periferico deja inservible la bodycam pero no el telefono, y cerrar
  una sesion concreta devuelve a SESSION_EXPIRED. Esta razonado en el propio enum.
- **PIN de 6 digitos con autovalidacion** en vez de boton de OK: con guantes, un toque de mas en
  cada desbloqueo del turno se nota.
- **PIN de la demostracion `004471` en claro en el fuente**, con comentario. Hasta el workflow 4 no
  hay verificador real y esconderlo daria el espejismo de que aqui ya hay seguridad.

### Verificado en el Redmi Note 8 Pro (`u4vcjv7xeubiljhu`)

Recorrido completo: arranque en DEVICE NOT ENROLLED -> LOCKED con la identidad -> PIN erroneo ->
5 fallos y bloqueo temporal con cuenta atras -> PIN correcto -> app operativa. Y el corte
DEVICE_REVOKED. MIUI rechaza `adb shell input` (INJECT_EVENTS), asi que los toques los hizo el
usuario; de ahi el atajo por intent, que si permite alcanzar cualquier estado desde consola.

Dos fallos encontrados **en el telefono, no en el emulador ni en las previews**, ya corregidos:

1. El contenido cabia justo en un 19,5:9 y se habria cortado en un 16:9. Ahora la ficha de
   identidad va dentro de un scroll con `weight(1f)` y el teclado fuera: lo que cede es la ficha,
   nunca las teclas.
2. En `PinDots` la rama de error iba antes que la de relleno, asi que un PIN fallido pintaba los
   seis puntos rojos y llenos con el campo vacio — parecia que el PIN seguia escrito. Ahora el
   relleno dice cuantos digitos hay y el borde dice si el intento anterior fallo.

### Deuda y notas

- El contador de intentos vive en `SharedPreferences` sin cifrar: se borra limpiando datos de la
  app. Aceptable hoy porque no protege nada; el workflow 4 lo mueve a Keystore.
- **La decision D1 (un PIN o dos) ya se puede mirar en vez de discutirla**: instalado, el recorrido
  real es PIN de 6 digitos al arrancar y despues contrasena de boveda para ver evidencia. Dos
  secretos en el mismo turno.
- La pestana del simulador roza el borde de la tarjeta en OPERATIONS. Solo afecta a debug.

### Proximo paso

- **Workflow 12**: atributos del telefono, par de claves en Keystore no exportable y PKCS#10 escrito
  a mano (decision D3), con pruebas contra `openssl`. Aterriza sobre NOT_PROVISIONED / ENROLLING,
  que ya existen y ya son alcanzables.
- Llevar a ciberseguridad, ademas de las 7 decisiones: los textos de los cortes del §13 y una
  propuesta de "grace period for ongoing recording" (§12.2 lo reconoce pendiente por su parte).

---

## 2026-09-03 — Documentos IAM de AeriaOne: lectura, reparto y estimacion (sin codigo)

### Hecho

- Sesion de analisis, no de desarrollo. Ciberseguridad entrego dos documentos, ya en `docs/`:
  - `Seguridad-Claves-Bodycam.md` / `.pdf` — arquitectura IAM de AeriaOne (tenant QPD): 16 secciones,
    requisitos IAM-01..20 y dos anexos. El unico grafico va embebido en base64 dentro del `.md`.
  - `AeriaOne_IAM_Workflows-v1.xlsx` — catalogo de 68 workflows, 9 con hoja de detalle
    (2, 3+4, 7, 11, 12, 21, 22+23, 27). Se lee con python-docx/zipfile; no hay que abrirlo a mano.
- **La columna de estado del Excel es de DISENO, no de implementacion**: 5 cerrados, 3 a rehacer,
  59 sin desarrollar, 1 sin marcar. Ninguno de los 9 detallados trata la captura de evidencia, asi
  que en ese terreno vamos por delante de su diseno.
- **Hallazgo de alcance**: la W1 es un Android 9 que ejecuta nuestra propia app (BodyCamServer), asi
  que su alta como dispositivo y el emparejamiento autenticado los podemos hacer nosotros por
  software. Lo que no podemos dar es garantia de plataforma (el alta de fabrica presupone secure boot
  y credenciales de fabrica retiradas; la W1 lleva el launcher del proveedor como priv-app).
- **Deuda concreta detectada**: el canal con la bodycam es `createInsecureRfcommSocketToServiceRecord`
  con UUID fijo (`BodycamRepository.kt:462`) — sin autenticacion de ningun tipo.
- Reparto: **47 de los 68 tocan al APK o a la bodycam**; en 24 somos el actor principal.
- Estimacion: **414-576 h** de trabajo nuestro (500-690 con contingencia del 20 %), repartidas en
  8 grupos G0-G7. El minimo defendible son G0-G3: 216-288 h.
- Entregables de la sesion:
  - Documento web (vivo, se actualiza al cerrar cada fase):
    https://claude.ai/code/artifact/09bfff05-9c4b-488a-8311-600aead761e8
  - `docs/Plan-IAM-Aeria-Nexus.docx` — mismo contenido para Drive, con la tabla de horas por tarea.

### Decisiones tomadas

- El plan se organiza en 8 grupos y no por familias del catalogo: la secuencia real del agente
  (puesta en servicio → jornada → excepciones) es la que decide el orden de implementacion.
- Las estimaciones incluyen pruebas (~30 %) y documentacion (~10 %) dentro de cada tarea.
- El coste del backend (2.500-4.200 h) se documenta como orden de magnitud y NO como compromiso
  nuestro: faltan los 10 artefactos de diseno que el propio documento reconoce pendientes.

### Decisiones pendientes (bloquean el grupo G1 — ver apartado 7 del plan)

1. **D1** — Un PIN o dos: el PIN de identidad frente a la contrasena de la boveda que ya existe.
2. **D2** — Como se protege la clave del agente: Keystore atado a credencial del sistema, o PIN de
   app con limite de intentos (el documento excluye biometria y MFA).
3. **D3** — PKCS#10 a mano (propuesto, por la regla de app ligera) o con BouncyCastle.
4. **D4** — La W1 no puede pasar por el alta de fabrica: periferico de garantia reducida.
5. **D5** — La boveda local choca con la revocacion granular (IAM-14).
6. **D6** — Clave publica real de Nexus, formato del `kid` y politica de rotacion (hoy `dev-2026-08`).
7. **D7** — Quien opera el servicio de firma de produccion (hoy keystore local + `local.properties`).

### Compromiso de septiembre (acordado 2026-09-03)

Ritmo pactado: 5 h de lunes a viernes y 8 h sabados y domingos. Del 4 al 30 de septiembre son
**159 h brutas**; descontando un 15 % por esperas de herramienta quedan **135 h efectivas**.
Comprometidas 122 h, margen 13 h. A 20 EUR/h netos: 2.440 EUR.

**Hito 1 — martes 15 de septiembre (AUDITORIA). 72 h brutas / 61 efectivas, 54 comprometidas.**

| Wf. | Alcance | h |
|---|---|---|
| 12 | Alta del telefono: atributos, par de claves no exportable, CSR y prueba de posesion | 20 |
| 3 | Certificado del agente: segundo par, CSR e instalacion de la cadena | 14 |
| 4 | PIN: alta, limite de intentos y bloqueo temporal | 12 |
| — | Backend simulado: CA de pruebas y retos | 8 |

Lo que se ensena el dia 15: teléfono limpio dado de alta con clave que no sale del Keystore,
certificado del agente separado del certificado del telefono (regla de no equivalencia), PIN con
bloqueo, y el plan como expediente en papel.
Lo que NO estara: la app sigue arrancando sin bloquearse, no hay token de sesion, y la evidencia
sigue como hoy (sin manifiesto ni firma). Todo corre contra un backend simulado nuestro:
criptografia real, plano de control fingido. **Decirlo antes de que lo pregunten.**

**Hito 2 — miercoles 30 de septiembre. 87 h brutas / 74 efectivas, 68 comprometidas.**

| Wf. | Alcance | h |
|---|---|---|
| 22+23 | Identidad de instalacion, arranque bloqueado y atadura agente-telefono | 16 |
| 27 | Desbloqueo: PIN, firma del reto y apertura | 12 |
| 28 | Sesion: token con alcance y custodia | 10 |
| 30 | Cierre de sesion y vuelta a bloqueado | 4 |
| — | Contratos G0: manifiesto de procedencia y recibo | 12 |
| — | Resto del stub, integracion, pruebas en Samsung y Redmi, documentacion | 14 |

Criterio de seleccion: son los workflows que ciberseguridad YA tiene desarrollados paso a paso en
sus 9 hojas de detalle, asi que no hay que negociar diseno para empezar. Quedan fuera el 11 (alta
de fabrica, no es nuestra) y el 7 (renovacion, necesita el ciclo de vida completo).

Fuera de septiembre y primer hito de octubre: 36+37 (manifiesto y firma de evidencia, 24 h) y
40 (credencial real en la subida, 6 h).

### Proximo paso

- **4 de septiembre**: arrancar el workflow 12 — atributos del telefono, par de claves en Keystore
  no exportable y el codigo de PKCS#10 (decision D3: a mano, con pruebas contra `openssl`).
- Los contratos G0 se mueven a la segunda quincena: para la auditoria pesa mas ensenar identidad
  funcionando que papel, y el papel ya lo cubre `docs/Plan-IAM-Aeria-Nexus.docx`.
- En paralelo, llevar a ciberseguridad las 7 decisiones y pedir los perfiles de certificado.

---

## 2026-08-30 (3) — La nota de audio tambien se clasifica, y el cierre del incidente espera a esa clasificacion

### Hecho

- **Reporte**: "cuando el incidente registrado es un audio, no da la opcion de categorizarlo; al pararlo se cierra y nos manda a Incidents".
  - Verificado en el Samsung: parar la nota con STOP **no** cerraba el incidente (se quedaba ACTIVE con EVIDENCE (1)). Lo que cerraba de golpe era END INCIDENT, que desde el arreglo del punto 3 del manager para la nota en curso y cerraba sin preguntar nada.
  - Las dos partes tenian la misma causa: el audio nunca pasaba por `pendingEvidence`, asi que no habia hoja de clasificacion en ninguno de los dos caminos.
- **`ActiveIncidentViewModel`**:
  - `stopAudioNote()` deja la nota en `pendingEvidence` en vez de guardarla con `EvidenceClass.EVIDENCE` fijo. La entrega a Nexus sigue saliendo al parar, sin esperar a la clasificacion, igual que en foto y video.
  - `classifyPendingEvidence` etiqueta segun el tipo (`Audio note — Witness Statement`); antes solo distinguia Video/Photo y una nota habria salido como "Photo".
  - `endIncident()` con una nota grabando: la para, muestra la hoja y NO cierra; el cierre ocurre en `endIfWaitingForClassification()` al clasificar o hacer skip (flag `endAfterClassify`). Si la grabacion se descarto por ser demasiado corta no hay nada pendiente y cierra igual.
- Compila limpio e instalado en el Samsung `RZCY510MBBM`.
- `version.properties` subido a mano a 1.3 (code 4) a peticion del usuario, sin correr release: el proximo `assembleRelease` horneara esa version. FLAG_SECURE sigue desactivado por decision del usuario tras avisarle.

### Verificado en dispositivo (con adb)

- Parar la nota con STOP → sale CLASSIFY EVIDENCE → al elegir Evidence, el incidente sigue ACTIVE con `Audio note — Evidence` en la lista.
- Nota grabando + END INCIDENT → sale CLASSIFY EVIDENCE → al elegir, cierra y navega a Incidents.
- El incidente cerrado (INC-2026-28512, de prueba) queda con EVIDENCE (2): `Audio note — Evidence` y `Audio note — Witness Statement`, ambas con el candado `EVIDENCE SEALED — UNLOCK VAULT TO VIEW` por tener la boveda bloqueada.
- Log del cifrado: `cifrado audio_..._m4a → .fev (29 KB, 7 ms, para [vault:v1, srv:dev-2026-08])` — la nota lleva los dos destinatarios.

### Pendiente

- Reproductor interno de la nota de audio (punto 2 del manager): sin verificar, hace falta la contrasena de la boveda del usuario para descifrar y darle al play.
- En la lista de Incidents queda `INC-2026-28512`, basura de esta prueba.
- Sigue abierto: avisar en la pantalla de incidente activo cuando la boveda no esta configurada.

---

## 2026-08-30 (2) — Feedback del manager: la nota de audio se reproduce dentro de la app y sobrevive al cierre del incidente

### Hecho

- **Punto 2 del manager (el audio se iba a una app externa)**: `AudioPlayRow` en `MediaPreview.kt` deja de lanzar `ACTION_VIEW` y reproduce con el `MediaPlayer` del framework (sin libreria nueva, regla de app ligera): boton play/pausa de 48.dp, barra de progreso y tiempo `m:ss / m:ss`. `DisposableEffect` libera el reproductor al salir de composicion, que ademas cierra el descriptor del fichero descifrado al bloquear la boveda. El motivo de fondo no es solo comodidad: sacar la nota al reproductor del sistema significaba entregarle a otra app la copia en claro de una evidencia. El video sigue abriendose con el reproductor del sistema (`openWithSystemPlayer`).
- **Punto 3 del manager (audio empezado y no parado al cerrar el incidente)**: la nota seguia grabando sin dueno; se cifraba al destruirse la pantalla (`onCleared`) y acababa en la boveda, pero nunca se enlazaba al incidente, asi que el detalle no tenia playback.
  - `ActiveIncidentViewModel`: la parte de parar la nota sale de `toggleAudioNote` a `stopAudioNote()` (suspend), y `endIncident()` la llama primero si habia grabacion en curso, antes de `endActiveIncident()`.
  - El cierre pasa a ser asincrono, asi que la pantalla ya no navega al pulsar END: `ActiveIncidentUiState.incidentEnded` lo dispara cuando el ViewModel termina. Si navegase antes, el `viewModelScope` se cancelaria a mitad del cifrado. Con el incidente ya cerrado no se pinta `NoActiveIncidentMessage`, que si no seria un parpadeo antes de salir.
- Compila limpio (assembleDebug) e instalado en el Samsung `RZCY510MBBM`. **Sin verificar todavia en el dispositivo.**

### Pendiente (verificar en dispositivo)

- Nota de audio: play/pausa dentro de la app, la barra avanza, al terminar vuelve al principio y NO se abre ninguna app externa.
- NEW INCIDENT → empezar nota de audio → END INCIDENT sin pararla → el incidente en Incidents lleva la nota como evidencia, con su duracion, y se reproduce (con la boveda desbloqueada).
- Salir de la pantalla del detalle mientras suena una nota: el audio se para.

### Nota de la sesion

- La primera foto de prueba (11:14:45) se capturo antes de crear la contrasena (11:15) y su cabecera solo lleva el destinatario `srv:dev-2026-08`: no se puede abrir en el telefono y la lista la marca `NO KEY`. Es el comportamiento correcto, pero conviene crear la boveda antes de capturar nada en la demo.
- Sigue abierto: avisar en la pantalla de incidente activo cuando la boveda no esta configurada, con acceso directo para crearla.

---

## 2026-08-30 — Boveda de evidencia: la captura del telefono sale de la galeria y queda tras una contrasena

### Hecho

- **Peticion**: que las fotos, videos y audios generados por la app queden en una "carpeta protegida por contrasena" para ensenarselo al manager. No hay login todavia, asi que la contrasena es del dispositivo, no del agente.
- **`data/crypto/EvidenceVault.kt` (nuevo)**: la boveda es un par RSA-2048 propio del telefono. Se cifra con la publica (siempre disponible, sin nadie delante: la captura se cierra en mitad de un servicio) y solo se descifra con la privada, que se reconstruye con la contrasena. La privada vive en `filesDir/vault.key` con doble proteccion: AES-GCM con clave PBKDF2-HMAC-SHA256 (210k iteraciones, salt aleatorio) y ese blob envuelto ademas por la clave de AndroidKeyStore, para que no se pueda hacer fuerza bruta sacando el fichero a un PC. La contrasena no se guarda en ningun formato: si es incorrecta, falla el tag de AES-GCM.
- **`EvidenceKeys`**: `recipients()` pasa a ser boveda + Nexus (opcion C hibrida de Seguridad-Claves-Bodycam.md §3.5). `DeviceKeyWrapper` deja de ser destinatario de la DEK — descifraba sin pedir nada, que dejaba la contrasena en decorativa — y pasa a proteger el fichero de claves de la boveda. RSA-OAEP/SHA-256 extraido a `rsaOaepWrap`/`rsaOaepUnwrap` para no duplicarlo entre Nexus y la boveda. El formato FEVD no cambia ni un byte: sigue siendo el mismo que produce la unidad bodycam.
- **`EvidenceCrypto.DELETE_PLAINTEXT` pasa a `true`** (EVD-007 resuelto): al cifrar se borra el original.
- **`LocalEvidenceRepository` reescrito**: fuera MediaStore, `localIncidents`, `publish()` y `MediaScannerConnection`. La camara y el grabador escriben por FileProvider en `files/captures` (privado), se cifra al cerrar y el claro desaparece; solo queda el `.fev` en `files/evidence`. La nota de audio interrumpida por la destruccion de la pantalla se cifra en un scope de aplicacion, como hace `EvidenceUploader`.
- **`data/VaultRepository.kt` (nuevo)**: lista los `.fev`, descifra bajo demanda a `cacheDir/vault` y borra esas copias al bloquear. Lee la cabecera del `.fev` para saber si se cifro para esta boveda sin descifrarlo (campo `openable`).
- **`feature/vault/` (nuevo)**: pantalla EVIDENCE VAULT con tres estados — crear contrasena, desbloquear, y lista de evidencia donde cada fila se despliega y reproduce con `EvidenceMediaPreview`. Entrada desde Profile, con el estado real (Not set up / Sealed / Unlocked).
- **`MediaPreview`**: recibe el nombre del `.fev` en vez de un uri y descifra si la boveda esta abierta. Estados visibles: EVIDENCE SEALED, DECRYPTING, NO KEY ON THIS DEVICE. Se quita `ContentResolver.loadThumbnail`: solo lo implementan proveedores del sistema como MediaStore, no el FileProvider propio.
- `EvidenceRecord.mediaUri` pasa a guardar el nombre del `.fev` (no se renombra la columna para no forzar una migracion de Room).
- Fuera el permiso `WRITE_EXTERNAL_STORAGE`: ya no se escribe nada publico.
- Compila limpio (assembleDebug, sin warnings nuevos). Sin dispositivos por adb: APK sin instalar.

### Decisiones

- Contrasena unica del dispositivo mientras no exista login real (AUTH-001).
- Si se olvida la contrasena, la evidencia local es irrecuperable a proposito; la copia entregada a Nexus se sigue abriendo en el servidor, asi que la cadena de custodia no depende de ella.
- Lo capturado ANTES de crear la boveda no lleva su envoltorio y no se puede abrir en el telefono: la lista lo marca como NO KEY en vez de dejar una vista previa que nunca carga.

### Pendiente (verificar en dispositivo)

- Profile → Evidence Vault → crear contrasena → NEW INCIDENT → foto, video y nota de audio → nada aparece en la galeria del telefono ni en el explorador de archivos.
- Volver a la boveda: las tres capturas se listan, se abren y se ven; LOCK las vuelve a sellar y la vista previa pasa a EVIDENCE SEALED.
- Contrasena incorrecta al desbloquear: mensaje "Wrong password" y sigue sellada.
- Matar la app y reabrir: la boveda arranca bloqueada y sigue pidiendo la contrasena.
- Detalle del incidente cerrado: la evidencia se ve con la boveda abierta y sale el candado con la boveda cerrada.

### Proximo paso

Prueba en dispositivo de lo anterior. Luego, si el manager valida el enfoque: contrasena por agente cuando exista login (AUTH-001) y decidir si la boveda se bloquea sola al salir de la app o por tiempo de inactividad.

---


## 2026-07-17 (2) — Fotos derechas en el visor y los incidents ya sobreviven al cierre de la app (Room)

### Hecho

- **Fotos giradas 90 grados en Incidents**: `BitmapFactory` ignora la etiqueta de orientacion EXIF que graba la camara. `MediaPreview.kt` gana `aplicarOrientacionExif`: lee la etiqueta con `ExifInterface` del framework (sin dependencia nueva) y rota el bitmap (90/180/270). Aplica al visor a pantalla completa y a las miniaturas pre-API 29 (en 29+ `loadThumbnail` de MediaStore ya orienta bien).
- **Persistencia con Room** (el usuario eligio Room sobre JSON local): los incidents cerrados desaparecian al matar la app porque solo vivian en un StateFlow.
  - Dependencias: `room-runtime` + `room-ktx` 2.7.2 y plugin KSP `2.0.21-1.0.28` (atado a la version de Kotlin: subir ambos juntos), todo via catalogo.
  - `data/local/` nuevo: `IncidentEntities.kt` (tablas `incidents`, `timeline_entries`, `evidence_records` con FK CASCADE y columna `position` porque @Relation no garantiza orden; enums como texto, conversion automatica de Room; mappers a/desde dominio), `IncidentDao.kt` (`getAll` ordenado por `createdAtMillis` DESC y `save` transaccional del incident completo) y `IncidentDatabase.kt` (`aeria_nexus.db`, version 1).
  - `IncidentRepository`: recibe el DAO por constructor (AppContainer lo crea en `init`, ahora con lateinit). Al arrancar carga los guardados y los antepone a los datos de ejemplo; `endActiveIncident()` ademas de anteponer al StateFlow guarda en Room (scope propio con Dispatchers.IO). Los datos de ejemplo NUNCA se persisten.
  - Pendiente para produccion (documentado en `IncidentDatabase`): la BD va sin cifrar; migrar a SQLCipher o equivalente antes de un despliegue real (regla de datos sensibles).
- Compila limpio (assembleDebug). APK sin instalar en dispositivo.

### Pendiente (verificar en dispositivo)

- Foto de evidencia en vertical se ve derecha en miniatura y a pantalla completa.
- NEW INCIDENT -> foto -> END INCIDENT -> matar la app -> reabrir -> el incident sigue primero en Incidents con su evidencia y timeline.

---

## 2026-07-17 — Los incidentes cerrados llegan a la pestana Incidents con sus fotos y videos visibles

### Hecho

- **Bug reportado**: las fotos/videos capturados con el telefono se guardaban en el album localIncidents (visibles en galeria) pero el incidente nunca aparecia en la pestana Incidents y el detalle no mostraba los archivos.
- **`IncidentRepository`**: `officerIncidents` paso de List estatica a StateFlow (datos de ejemplo + incidentes cerrados en la sesion). `endActiveIncident()` ya no descarta el incidente activo: lo convierte a `OfficerIncident` (fecha dd/MM/yyyy y hora del inicio real, duracion en minutos, agente del perfil, status DRAFT, priority MEDIUM, sync LOCAL_ONLY, typeCode "FIELD") conservando evidencia y timeline completos, y lo antepone a la lista.
- **`IncidentListViewModel`**: observa el StateFlow, asi el incidente recien cerrado aparece al entrar a la pestana (la navegacion de "End incident" ya llevaba ahi).
- **`ui/components/MediaPreview.kt`** (nuevo): `EvidenceMediaPreview` para la evidencia con `mediaUri` — miniatura real (160.dp, decodificada en IO con `loadThumbnail` de MediaStore en API 29+; fallback BitmapFactory/MediaMetadataRetriever para menos) e icono de imagen rota si el archivo se borro de la galeria. Foto: se abre a pantalla completa en un Dialog dentro de la app (decodificada con inSampleSize, tope 2048 px). Video: overlay de play y se abre con el reproductor del sistema via ACTION_VIEW + FLAG_GRANT_READ_URI_PERMISSION (sin ExoPlayer, regla de app ligera). Audio: fila "PLAY AUDIO NOTE" que abre el reproductor del sistema.
- **`IncidentDetailScreen`**: cada `EvidenceCard` con `mediaUri` muestra la vista previa encima del hash.
- Compila limpio (assembleDebug). Sin dispositivos por adb: APK sin instalar.

### Pendiente (verificar en dispositivo)

- NEW INCIDENT → foto + video con el telefono → END INCIDENT → el incidente aparece primero en Incidents como Draft con su contador de evidencia → abrirlo → miniaturas reales; tocar la foto la abre a pantalla completa y el video se reproduce en el reproductor del sistema.
- Nota: la lista sigue viviendo solo en memoria — al matar la app los incidentes cerrados desaparecen de la pestana (los archivos siguen en la galeria). Persistirlos (Room o JSON local) queda como siguiente paso si se quiere conservar entre sesiones.

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
