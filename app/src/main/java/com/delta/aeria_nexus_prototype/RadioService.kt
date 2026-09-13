package com.delta.aeria_nexus_prototype

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.delta.aeria_nexus_prototype.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val TAG = "AeriaRadio"

/**
 * Foreground service que mantiene viva la radio tactica cuando la app no esta
 * delante.
 *
 * El motor de Agora nunca colgo de una pantalla —AeriaNexusApp entra al canal al
 * arrancar el proceso—, asi que en segundo plano el PTT ya sonaba. Lo que no
 * habia era nada que impidiese al sistema matar el proceso: la radio aguantaba
 * mientras Android quisiera, y de rebote solo mientras BodycamService estuviera
 * levantado por el enlace Bluetooth. Un agente sin bodycam emparejada tenia la
 * radio a merced del gestor de memoria. Este servicio es lo que convierte eso en
 * una garantia, y sobrevive a que el agente deslice la app en recientes.
 *
 * **Tipo `specialUse` y no `mediaPlayback`, a proposito.** Reproducir la voz que
 * llega es media playback de manual, pero desde Android 15 un receptor de
 * BOOT_COMPLETED no puede arrancar un servicio de ese tipo (ni `microphone`,
 * `camera`, `dataSync`, `phoneCall` o `mediaProjection`): lanza
 * ForegroundServiceStartNotAllowedException. `specialUse` no esta en esa lista.
 * Normalmente obliga a justificarse ante Google Play, pero estas dos apps se
 * distribuyen firmadas fuera de la tienda, asi que no hay revision que pasar.
 *
 * **Solo con sesion abierta.** Lo arranca y lo para AeriaNexusApp siguiendo el
 * estado de confianza: un telefono en el canal tactico en nombre de un agente que
 * no ha metido su PIN es exactamente lo que prohibe el workflow 34, el mismo
 * motivo por el que cerrar sesion desata la bodycam.
 *
 * PENDIENTE — hablar con la app cerrada (PTT propio en segundo plano). Hoy este
 * servicio solo cubre RECIBIR. Capturar microfono de fondo exige anadir el tipo
 * `microphone`, que Android no deja arrancar ni desde boot ni desde background:
 * habria que declararlo tambien en el manifest y levantar el servicio con los dos
 * tipos mientras la app esta delante (al abrir sesion), mas un boton de PTT en
 * esta misma notificacion, porque el telefono no tiene tecla fisica:
 *
 *     // val tipos = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
 *     //     ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
 *     // startForeground(NOTIFICATION_ID, notificacion, tipos)
 *     // ...y en la notificacion:
 *     // .addAction(R.drawable.ic_launcher_foreground, "Hablar", pendingIntentPtt)
 *
 * No se hace todavia: cambia el tipo de servicio con el que se arranca en boot
 * (`microphone` esta prohibido ahi) y obliga a partir el arranque en dos casos.
 */
class RadioService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val canal = NotificationChannel(
            CHANNEL_ID,
            "Tactical radio",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)

        // startForeground en onCreate, igual que BodycamService: si el servicio se
        // para al instante, el sistema mata la app por no haberlo llamado.
        arrancarEnPrimerPlano(TEXTO_EN_REPOSO)
        vigilarQuienHabla()
        Log.d(TAG, "Radio tactica en marcha")
    }

    private fun arrancarEnPrimerPlano(texto: String) {
        val notificacion = construirNotificacion(texto)
        // El tipo specialUse no existe como constante hasta API 34. Por debajo, la
        // llamada de dos argumentos usa lo declarado en el manifest.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notificacion,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notificacion)
        }
    }

    /**
     * La notificacion es la unica cara de la radio cuando la app no esta abierta,
     * asi que dice quien habla en vez de limitarse a declarar que el servicio
     * existe. Es el equivalente sin pantalla de PttAvisoOverlay.
     */
    private fun vigilarQuienHabla() {
        val agora = AppContainer.agoraRepository
        scope.launch {
            combine(agora.bodycamHablando, agora.pttsRemotos) { bodycam, remotos ->
                textoDeEstado(bodycam, remotos)
            }
                .distinctUntilChanged()
                .collect { texto ->
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, construirNotificacion(texto))
                }
        }
    }

    private fun textoDeEstado(bodycamHablando: Boolean, remotos: Map<Int, String>): String {
        val oficial = remotos.values.firstOrNull { it.isNotBlank() }
        return when {
            oficial != null -> "Officer $oficial is speaking"
            remotos.isNotEmpty() -> "A unit is speaking"
            // La bodycam va la ultima: su uid es fijo y compartido, asi que no se
            // puede decir cual habla. Ver el pendiente de _oficialHablando.
            bodycamHablando -> "A bodycam is speaking"
            else -> TEXTO_EN_REPOSO
        }
    }

    private fun construirNotificacion(texto: String): Notification {
        val abrirApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Aeria Nexus")
            .setContentText(texto)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(abrirApp)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * El agente ha quitado la app del multitarea. La radio NO se para: es
     * justamente el caso para el que existe este servicio, y en Android de serie
     * el sistema lo respeta (`stopWithTask=false`).
     *
     * Si tras esto deja de sonar el PTT, el proceso lo ha matado el gestor de
     * bateria del fabricante —MIUI lo hace salvo autoarranque concedido y
     * bateria "sin restricciones"—, no el codigo. Este log es lo que distingue un
     * caso del otro: si aparece y luego no aparece "Radio tactica parada", el
     * servicio siguio vivo.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "App quitada del multitarea: la radio sigue en marcha")
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        Log.d(TAG, "Radio tactica parada")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "radio_tactica"
        private const val NOTIFICATION_ID = 11
        private const val TEXTO_EN_REPOSO = "Listening on the channel"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RadioService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RadioService::class.java))
        }
    }
}
