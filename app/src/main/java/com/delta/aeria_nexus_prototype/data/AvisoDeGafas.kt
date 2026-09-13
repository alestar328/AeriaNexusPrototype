package com.delta.aeria_nexus_prototype.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.delta.aeria_nexus_prototype.MainActivity
import com.delta.aeria_nexus_prototype.R

private const val TAG = "AvisoDeGafas"

/**
 * Avisa al oficial de que hay video de las gafas esperando a descargarse.
 *
 * Es el unico momento en que la app le pide algo: el manager decidio que no maneje
 * los perifericos, pero el video **no puede llegar solo**. Se queda en la tarjeta
 * de las gafas y hay que ir a por el por su WiFi, que ademas esta apagado mientras
 * graban. Sin este aviso la grabacion existe y nadie lo sabe.
 *
 * Se manda al cerrar cada video, con la cuenta total: si hay tres pendientes, lo
 * que interesa es que hay tres, no cual fue el ultimo.
 */
class AvisoDeGafas(private val context: Context) {

    fun avisarDeVideosPendientes(cuantos: Int) {
        if (cuantos <= 0) return
        if (!puedeAvisar()) {
            // Sin permiso el aviso se descarta en silencio, y este es justo el caso
            // en que el oficial no se enteraria de que tiene evidencia sin traer.
            Log.w(TAG, "sin permiso de notificaciones: el oficial NO sabra que hay $cuantos video(s)")
            return
        }
        // IMPORTANCE_HIGH y no LOW como el canal del enlace: esto no es un
        // indicador de fondo, es lo unico que le dice al oficial que tiene
        // evidencia sin recoger.
        crearCanal(
            id = CANAL,
            nombre = "Glasses video",
            importancia = NotificationManager.IMPORTANCE_HIGH,
        )

        val texto = if (cuantos == 1) {
            "1 video on the glasses still to retrieve. Downloads over their Wi-Fi."
        } else {
            "$cuantos videos on the glasses still to retrieve. Download over their Wi-Fi."
        }
        val aviso = Notification.Builder(context, CANAL)
            .setContentTitle("Glasses video pending")
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(abrirLaApp())
            .setAutoCancel(true)
            .build()
        // Un solo id a proposito: el aviso se sustituye con la cuenta al dia en vez
        // de amontonar uno por video.
        ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?.notify(ID_AVISO, aviso)
        Log.i(TAG, "avisado: $cuantos video(s) pendiente(s)")
    }

    /**
     * Avisa de que el SOS esta en marcha **sin las gafas**.
     *
     * Es el aviso mas importante de los dos y por eso va aparte, con su propio
     * canal y su propio id: el otro dice "tienes trabajo pendiente" y este dice
     * "la camara que creias que estaba grabando no esta grabando". Si se mezclaran,
     * el segundo desapareceria bajo el primero.
     *
     * No cancela ni retrasa el SOS: las gafas son apoyo y la emergencia sigue.
     */
    fun avisarSosSinGafas() {
        if (!puedeAvisar()) {
            Log.e(TAG, "SOS SIN GAFAS y sin permiso de notificaciones: el oficial no se enterara")
            return
        }
        // Suena y vibra a proposito: el telefono va en el bolsillo y esto es lo
        // unico que puede sacar al oficial de la idea de que lleva dos camaras
        // grabando. Desde Android O eso lo manda el CANAL, no la notificacion.
        crearCanal(
            id = CANAL_SIN_GAFAS,
            nombre = "SOS without glasses",
            importancia = NotificationManager.IMPORTANCE_HIGH,
            vibra = true,
        )
        val texto = "The SOS is running but the glasses are NOT recording. " +
            "Check that they are switched on and linked."
        val aviso = Notification.Builder(context, CANAL_SIN_GAFAS)
            .setContentTitle("SOS without glasses")
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(abrirLaApp())
            .setCategory(Notification.CATEGORY_ERROR)
            .setAutoCancel(true)
            .build()
        ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?.notify(ID_SIN_GAFAS, aviso)
        Log.e(TAG, "avisado al oficial: SOS SIN GAFAS")
    }

    private fun puedeAvisar(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun abrirLaApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun crearCanal(id: String, nombre: String, importancia: Int, vibra: Boolean = false) {
        val canal = NotificationChannel(id, nombre, importancia)
            .apply { enableVibration(vibra) }
        ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?.createNotificationChannel(canal)
    }

    private companion object {
        const val CANAL = "gafas_video_pendiente"
        const val CANAL_SIN_GAFAS = "gafas_sos_sin_gafas"
        const val ID_AVISO = 20
        const val ID_SIN_GAFAS = 21
    }
}
