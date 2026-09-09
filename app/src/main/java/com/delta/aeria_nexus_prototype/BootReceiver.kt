package com.delta.aeria_nexus_prototype

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "AeriaBoot"

/**
 * Despierta el proceso cuando arranca el telefono.
 *
 * Casi todo su trabajo es existir: recibir BOOT_COMPLETED obliga a Android a crear
 * el proceso, y con el corre AeriaNexusApp.onCreate(), que es donde esta lo que
 * tiene que pasar despues de un reinicio aunque nadie abra la app — entrar al
 * canal tactico, retomar las subidas de evidencia a medias y empezar a vigilar el
 * estado de confianza. Hasta ahora nada de eso ocurria hasta que el agente tocaba
 * el icono.
 *
 * **La radio NO se levanta aqui, y no es un descuido.** Tras un reinicio el estado
 * de confianza vuelve siempre a LOCKED (`IdentityRepository.leerEstadoGuardado`):
 * la sesion no sobrevive al proceso, por diseno. Poner el telefono en el canal en
 * nombre de un agente que todavia no ha metido su PIN es justo lo que prohibe el
 * modelo IAM — el mismo motivo por el que cerrar sesion desata la bodycam. Asi que
 * la radio espera al desbloqueo, y el colector de AeriaNexusApp la arranca sola en
 * cuanto la sesion se abre. El dia que se decida que la sesion sobreviva a un
 * reinicio, arrancara aqui sin tocar una linea.
 *
 * (Ese es tambien el motivo de que el servicio sea de tipo `specialUse`: desde
 * Android 15 un BOOT_COMPLETED no puede levantar un `mediaPlayback`, que es lo que
 * por contenido seria. Ver RadioService.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "Telefono reiniciado: proceso despierto, radio a la espera del PIN")
    }
}
