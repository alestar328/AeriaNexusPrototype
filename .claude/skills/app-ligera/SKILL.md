---
name: app-ligera
description: Reglas de rendimiento y peso para mantener la app Aeria Nexus ligera y eficaz. Usar al agregar dependencias, modificar build.gradle.kts, trabajar con listas, imagenes, corrutinas o al detectar lentitud.
---

# App ligera y eficaz

Objetivo: arranque rapido, consumo bajo de bateria y datos, y un APK pequeno. Los dispositivos de los agentes pueden ser gama media con conectividad irregular.

## Dependencias: la regla mas importante

- Antes de agregar cualquier libreria, responder por escrito en el mensaje al usuario: que problema resuelve, cuanto pesa aproximadamente y por que no basta con el SDK o con codigo propio simple.
- Preferir siempre la opcion de Jetpack oficial sobre librerias de terceros.
- Prohibido agregar librerias para una sola funcion trivial (formatear una fecha, validar un texto). Eso se escribe a mano.
- Toda dependencia nueva va al catalogo `gradle/libs.versions.toml`, nunca con version inline en `build.gradle.kts`.

## Configuracion de build

- En `release`, activar `isMinifyEnabled = true` y `isShrinkResources = true` en cuanto la app tenga su primera version funcional, y probar el APK de release, no solo el de debug.
- Mantener `minSdk = 26`; no bajarlo, porque obliga a agregar librerias de compatibilidad.
- Recursos graficos: preferir iconos vectoriales (`VectorDrawable`) sobre PNG. Si se incluyen imagenes raster, usar WebP.

## Rendimiento en Compose

- Listas largas siempre con `LazyColumn`/`LazyRow`, nunca `Column` con scroll para datos dinamicos.
- Estado de UI en data classes inmutables con listas de solo lectura (`List`, no `MutableList`) para que Compose pueda omitir recomposiciones.
- No crear objetos costosos dentro de un Composable en cada recomposicion; usar `remember` para calculos derivados del estado.
- Animaciones cortas y funcionales (100 a 300 ms), solo para comunicar cambios de estado. Nada decorativo que consuma bateria.

## Trabajo asincrono y red

- Todo acceso a disco o red fuera del hilo principal, via corrutinas con `Dispatchers.IO` aplicado en el repositorio, no en el ViewModel.
- Disenar para conectividad irregular: las operaciones de red muestran estado de carga, tienen timeout razonable y ofrecen reintento manual. No reintentos automaticos infinitos que drenen bateria.
- No hacer polling periodico salvo justificacion operativa; preferir carga al entrar a la pantalla mas boton o gesto de refrescar.
- Cancelacion automatica: usar siempre `viewModelScope`; nunca `GlobalScope`.

## Memoria

- No guardar `Context`, `Activity` ni vistas en ViewModels ni en objetos singleton.
- Colecciones grandes de datos se paginan o se limitan; no cargar listas completas de miles de registros en memoria.

## Medir antes de optimizar

- No aplicar optimizaciones especulativas (caches propios, pools de objetos, microajustes) sin haber observado un problema real.
- Si el usuario reporta lentitud, primero reproducir y localizar la causa (Layout Inspector, recomposition counts, logs de tiempo) y despues corregir solo eso.
- Mantener la solucion simple: una optimizacion que un junior no pueda explicar no entra al proyecto.
