# Transcripcion de la evidencia con whisper.cpp (docs/TRANSCRIPT-FORMAT.md del
# repo BodyCamServer). Solo se compila para arm64-v8a: ver build.gradle.kts.
cmake_minimum_required(VERSION 3.22.1)

# Siempre optimizado, tambien en el APK de debug: sin -O3 una nota de un minuto
# tarda varios minutos en transcribirse y no se puede probar nada.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(transcripcion C CXX)

include(FetchContent)

# El codigo de whisper.cpp no se copia al repo: se baja fijado por version y por
# hash. Subir de version = cambiar estas dos lineas; engine.version sale solo.
FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.4.tar.gz
    URL_HASH SHA256=57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae
)

# Estatico dentro de una sola libtranscripcion.so, sin OpenMP (obligaria a
# empaquetar libomp) y para una CPU fija en vez de la de la maquina que compila.
# armv8.2-a+fp16+dotprod es cualquier movil de 2018 en adelante; en uno mas viejo
# la libreria no se carga (WhisperNativo comprueba la CPU antes).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)

FetchContent_MakeAvailable(whisper)

add_library(transcripcion SHARED transcripcion_jni.cpp)
target_link_libraries(transcripcion PRIVATE whisper log)
target_link_options(transcripcion PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
