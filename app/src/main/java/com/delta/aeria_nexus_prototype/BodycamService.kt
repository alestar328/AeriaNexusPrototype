package com.delta.aeria_nexus_prototype

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.delta.aeria_nexus_prototype.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service que sostiene los enlaces con los perifericos del oficial.
 *
 * Sin el, Android congela el proceso al pasar a segundo plano (pantalla
 * apagada, otra app al frente) y el socket RFCOMM muere; la bodycam ademas
 * reacciona a esa caida. Vive exactamente mientras el usuario quiera el
 * enlace: BodycamRepository lo arranca en connect() y lo para en disconnect().
 *
 * Ademas de mantener el proceso vivo, es el **dueño del canal de mando de las
 * gafas** y de [ReleGafas]. Van aqui y no en una pantalla porque el oficial no
 * va a tener el telefono en la mano: cuando pulse el SOS de la bodycam, el canal
 * con las gafas tiene que llevar rato abierto. No contiene logica de conexion:
 * solo decide quien vive y cuanto.
 */
class BodycamService : Service() {

    private val alcance = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        val canal = NotificationChannel(
            CHANNEL_ID,
            "Bodycam link",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)

        // startForeground va en onCreate, no en onStartCommand: si el enlace
        // se cancela al instante, el servicio puede morir antes de procesar
        // onStartCommand y el sistema mata la app por no haberlo llamado.
        val notificacion = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Aeria Nexus")
            .setContentText("Bodycam link active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notificacion, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notificacion)
        }

        // El canal de las gafas se mantiene solo, con reintento: el GATT se cae por
        // su cuenta y un boton de la bodycam no puede esperar a que alguien abra una
        // pantalla.
        AppContainer.gafasCommandRepository.mantenerCanal()
        AppContainer.releGafas.vigilar(alcance)
    }

    override fun onDestroy() {
        super.onDestroy()
        alcance.cancel()
        // Sin bodycam no hay quien dispare la grabacion, asi que tener el canal de
        // las gafas abierto solo gastaria su bateria.
        AppContainer.gafasCommandRepository.soltarCanal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "bodycam_link"
        private const val NOTIFICATION_ID = 10

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BodycamService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BodycamService::class.java))
        }
    }
}
