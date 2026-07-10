---
name: ui-ux-policial
description: Guia de UI y UX en Jetpack Compose para una app policial de uso en campo. Usar al crear o modificar pantallas, componentes visuales, tema, colores, tipografia o navegacion.
---

# UI y UX para uso policial en campo

Contexto de uso real: agentes en la calle, a veces con guantes, con sol directo en la pantalla, en situaciones de estres y con poco tiempo. Cada decision de UI se evalua contra ese escenario.

## Principios de UX

1. **Maximo 2 toques** para llegar a cualquier accion critica desde la pantalla principal.
2. **Una accion principal por pantalla**, representada por un boton grande y unico. Las acciones secundarias van visualmente subordinadas.
3. **Confirmacion solo para acciones destructivas o irreversibles** (borrar reporte, cerrar incidente). El resto de acciones deben ser inmediatas y con opcion de deshacer cuando aplique.
4. **Estado siempre visible**: toda pantalla con datos remotos muestra explicitamente uno de estos estados: cargando, contenido, vacio o error con boton de reintentar. Nunca una pantalla en blanco.
5. **Textos de interfaz en espanol**, cortos y en lenguaje operativo directo: "Reportar incidente", no "Proceder a la creacion de un nuevo registro de incidencia".

## Ergonomia tactil

- Area tactil minima de 48.dp en todo elemento interactivo; para acciones criticas de campo usar 56.dp o mas.
- Acciones frecuentes en la mitad inferior de la pantalla, alcanzables con el pulgar.
- Separacion minima de 8.dp entre elementos tocables para evitar pulsaciones erroneas.
- No depender de gestos ocultos (deslizar para borrar, mantener pulsado) como unico camino a una funcion; siempre debe existir un boton visible equivalente.

## Legibilidad en exteriores

- Contraste minimo 4.5:1 entre texto y fondo; para texto critico buscar 7:1.
- Tamano de fuente base de 16.sp; nada de texto informativo por debajo de 14.sp.
- Soportar tema oscuro desde el inicio (util en patrullas nocturnas para no deslumbrar). El tema ya existe en `ui/theme/Theme.kt`; todo color nuevo se define en `Color.kt` y se consume via `MaterialTheme.colorScheme`, nunca colores hardcodeados en los Composables.
- La informacion nunca se comunica solo con color: un estado urgente lleva color mas icono mas texto.

## Reglas de Compose

- Material 3 en todos los componentes; no mezclar con Material 2.
- State hoisting: los Composables de `ui/components/` reciben estado y lambdas, no ViewModels. Solo el Composable raiz de cada pantalla conoce el ViewModel.
- Patron de pantalla estandar:

```kotlin
@Composable
fun IncidenteScreen(viewModel: IncidenteViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    IncidenteContent(
        uiState = uiState,
        onReportar = viewModel::reportar
    )
}

// Separar el contenido permite previsualizarlo y probarlo sin ViewModel.
@Composable
private fun IncidenteContent(
    uiState: IncidenteUiState,
    onReportar: () -> Unit
) { /* ... */ }
```

- Toda pantalla y todo componente reutilizable lleva al menos un `@Preview` (incluyendo variante en tema oscuro) para que un junior vea el resultado sin desplegar la app.
- Listas siempre con `LazyColumn` y `key` estable por elemento.
- Usar `Scaffold` como raiz de pantalla para manejar insets, snackbars y barras de forma consistente.

## Accesibilidad

- Todo icono interactivo con `contentDescription` en espanol; iconos decorativos con `contentDescription = null`.
- La UI debe seguir siendo usable con el tamano de fuente del sistema al 130 por ciento: no fijar alturas que corten texto, preferir `heightIn` sobre `height`.
- Agrupar semanticamente las tarjetas con `Modifier.semantics(mergeDescendants = true)` para lectores de pantalla.
