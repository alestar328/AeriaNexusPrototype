---
name: arquitectura-simple
description: Arquitectura MVVM simple y mantenible por personal junior para la app policial Aeria Nexus. Usar al crear pantallas, ViewModels, repositorios, modelos de datos o al decidir estructura de paquetes y navegacion.
---

# Arquitectura simple (MVVM sin complejidad innecesaria)

Objetivo: que un desarrollador junior pueda leer, entender y modificar cualquier archivo sin conocer frameworks avanzados. Se prefiere codigo explicito sobre abstracciones inteligentes.

## Estructura de paquetes

Organizar por funcionalidad (feature), no por tipo de clase:

```
com.delta.aeria_nexus_prototype/
  ui/theme/            # Theme, Color, Type (ya existe)
  ui/components/       # Composables reutilizables (botones, tarjetas, dialogos)
  feature/<nombre>/    # Una carpeta por pantalla o flujo
      <Nombre>Screen.kt      # Composable de la pantalla
      <Nombre>ViewModel.kt   # Estado y logica de la pantalla
      <Nombre>UiState.kt     # Data class con el estado de la UI
  data/                # Repositorios y fuentes de datos
      model/           # Data classes del dominio
  navigation/          # NavHost y rutas
```

## Reglas de capas

1. **Screen (Composable)**: solo pinta el estado y envia eventos al ViewModel. No contiene logica de negocio ni acceso a datos.
2. **ViewModel**: expone un unico `StateFlow<XUiState>` y funciones publicas para los eventos de la UI. No importa nada de Compose ni de Android UI.
3. **Repositorio**: unica puerta de acceso a datos (red, base local, memoria). Los ViewModels nunca acceden a fuentes de datos directamente.
4. **UiState**: siempre una `data class` inmutable con valores por defecto. Los cambios de estado se hacen con `copy()`.

Patron de ViewModel estandar del proyecto:

```kotlin
class IncidenteViewModel(
    private val repositorio: IncidenteRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(IncidenteUiState())
    val uiState: StateFlow<IncidenteUiState> = _uiState.asStateFlow()

    fun cargarIncidentes() {
        viewModelScope.launch {
            _uiState.update { it.copy(cargando = true) }
            val resultado = repositorio.obtenerIncidentes()
            _uiState.update { it.copy(cargando = false, incidentes = resultado) }
        }
    }
}
```

## Que NO usar (demasiado complejo para el equipo)

- NO usar Hilt, Dagger ni Koin. La inyeccion de dependencias se hace manualmente: las dependencias se crean en un objeto `AppContainer` unico y se pasan por constructor.
- NO usar clases genericas con multiples parametros de tipo, delegados propios ni reflexion.
- NO usar arquitecturas multicapa (use cases, interactors, mappers por capa) mientras la app sea pequena. El ViewModel llama al repositorio directamente.
- NO usar multi-modulo Gradle. Un solo modulo `app` con buenos paquetes es suficiente para este prototipo.
- NO crear interfaces con una sola implementacion "por si acaso". Se extrae interfaz solo cuando exista una segunda implementacion real (por ejemplo, una falsa para tests).

## Que SI usar

- Corrutinas con `viewModelScope` para todo trabajo asincrono. Nunca hilos manuales ni callbacks anidados.
- `StateFlow` para estado observable. Un solo flujo de estado por pantalla.
- Errores modelados en el UiState (`mensajeError: String?`), nunca excepciones que crucen capas sin capturar.
- Manejo de errores con `try/catch` en el ViewModel o `Result` de Kotlin, lo que sea mas legible en cada caso.

## Datos sensibles (contexto policial)

- Ningun dato de ciudadanos, agentes o incidentes se escribe en logs. Prohibido `Log.d` con contenido de modelos de datos.
- Si se persiste informacion sensible en el dispositivo, usar almacenamiento cifrado (EncryptedSharedPreferences o SQLCipher cuando se agregue base de datos) y documentarlo en el codigo.
- Las claves y URLs de entorno van en `local.properties` o BuildConfig, nunca hardcodeadas en fuentes.
