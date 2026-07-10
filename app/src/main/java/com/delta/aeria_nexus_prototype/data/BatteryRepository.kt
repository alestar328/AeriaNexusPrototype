package com.delta.aeria_nexus_prototype.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Nivel de bateria del telefono para el panel de estado del mapa. */
class BatteryRepository(private val context: Context) {

    /** Porcentaje de bateria (0-100), emitido al iniciar y en cada cambio. */
    fun batteryPercent(): Flow<Int> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent?.let { trySend(percentOf(it)) }
            }
        }

        // ACTION_BATTERY_CHANGED es un broadcast pegajoso: registrarse devuelve
        // el ultimo valor de inmediato, sin esperar el proximo cambio.
        val ultimo = context.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        ultimo?.let { trySend(percentOf(it)) }

        awaitClose { context.unregisterReceiver(receiver) }
    }

    private fun percentOf(intent: Intent): Int {
        val nivel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val escala = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (nivel >= 0 && escala > 0) nivel * 100 / escala else 0
    }
}
