---
name: estilo-codigo-limpio
description: Convenciones de codigo Kotlin limpio, sin redundancias y con comentarios utiles para el proyecto Aeria Nexus. Usar siempre que se escriba o modifique codigo Kotlin en este proyecto.
---

# Estilo de codigo limpio

Regla general: el codigo lo mantendra personal junior. Ante la duda entre una solucion elegante y una solucion obvia, elegir la obvia.

## Nombres

- Codigo en ingles para identificadores tecnicos estandar (clases, funciones del framework), pero se permiten nombres de dominio en espanol cuando aporten claridad al equipo (`IncidenteRepository`, `cargarPatrullas`). Ser consistente dentro de cada archivo: no mezclar `loadIncidentes` con `obtenerUnits`.
- Nombres completos y descriptivos: `contadorReintentos`, no `cnt` ni `cr`.
- Booleanos con prefijo de pregunta: `esVisible`, `tienePermisos`, `estaCargando`.
- Composables en PascalCase y sustantivos: `TarjetaIncidente`, no `MostrarTarjeta`.

## Comentarios

- Sin emoticonos ni adornos en ningun comentario.
- Comentarios en espanol, frases completas, explicando el PORQUE, no el que:
  - Mal: `// incrementa el contador`
  - Bien: `// El servidor limita a 3 intentos por minuto, por eso se espera antes de reintentar.`
- KDoc (`/** ... */`) solo en clases publicas y funciones cuyo comportamiento no sea evidente por la firma.
- No dejar codigo comentado. Si algo se elimina, se elimina.
- No escribir comentarios que repitan el nombre de la funcion ni marcadores de seccion decorativos (`// ------ SECCION ------`).

## Sin redundancias

- Antes de crear una funcion, un componente o una constante, buscar si ya existe una equivalente en el proyecto y reutilizarla.
- Extraer a `ui/components/` cualquier Composable que se use en dos o mas pantallas.
- Constantes repetidas (dimensiones, duraciones, limites) se centralizan una sola vez; no duplicar valores magicos.
- Eliminar imports sin uso, parametros sin uso y valores por defecto que nunca se sobreescriben.
- No envolver llamadas en funciones que solo delegan sin aportar nada.

## Kotlin idiomatico pero legible

Usar con normalidad (un junior debe conocerlos):
- `data class`, `when`, `?.`, `?:`, `let` para null-check puntual, funciones de extension simples.
- `val` siempre que sea posible; `var` solo con justificacion.
- Argumentos con nombre cuando una llamada tenga mas de dos parametros del mismo tipo.

Evitar (dificultan la lectura):
- Cadenas largas de `let/run/also/apply` anidadas. Maximo un scope function por expresion.
- Expresiones de una linea que hagan tres cosas. Preferir variables intermedias con buen nombre.
- Sobrecarga de operadores, `infix` propios y DSLs caseros.

## Tamano y funciones

- Funciones de una sola responsabilidad, idealmente menos de 30 lineas.
- Archivos de menos de 300 lineas; si una pantalla crece, extraer sub-Composables privados en el mismo archivo antes de crear archivos nuevos.
- Maximo 3 niveles de indentacion dentro de una funcion; mas que eso indica que falta extraer una funcion.

## Verificacion antes de dar por terminado

- El proyecto debe compilar: `./gradlew assembleDebug` (en Windows `gradlew.bat assembleDebug`).
- Sin warnings nuevos del compilador Kotlin en los archivos tocados.
- Revisar el diff completo buscando codigo duplicado o restos de depuracion antes de finalizar.
