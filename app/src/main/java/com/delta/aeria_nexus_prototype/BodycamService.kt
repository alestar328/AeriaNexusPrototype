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

/**
 * Foreground service que sostiene el enlace Bluetooth con la bodycam.
 *
 * Sin el, Android congela el proceso al pasar a segundo plano (pantalla
 * apagada, otra app al frente) y el socket RFCOMM muere; la bodycam ademas
 * reacciona a esa caida. Vive exactamente mientras el usuario quiera el
 * enlace: BodycamRepository lo arranca en connect() y lo para en disconnect().
 * No contiene logica de conexion — solo mantiene el proceso vivo.
 */
class BodycamService : Service() {

    override fun onCreate() {
        super.onCreate()
        val canal = NotificationChannel(
            CHANNEL_ID,
            "Enlace con la bodycam",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)

        // startForeground va en onCreate, no en onStartCommand: si el enlace
        // se cancela al instante, el servicio puede morir antes de procesar
        // onStartCommand y el sistema mata la app por no haberlo llamado.
        val notificacion = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Aeria Nexus")
            .setContentText("Enlace con la bodycam activo")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notificacion, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notificacion)
        }
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
