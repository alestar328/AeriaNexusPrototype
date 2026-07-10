package com.delta.aeria_nexus_prototype.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Fuente unica de ubicacion del telefono. Mismos parametros que la app
 * Flutter original: alta precision, actualizacion cada segundo o 5 metros.
 *
 * Ambos flujos requieren el permiso ACCESS_FINE_LOCATION ya concedido;
 * la pantalla lo solicita antes de coleccionarlos.
 */
class LocationRepository(private val context: Context) {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** Posiciones GPS en tiempo real. Emite tambien la ultima conocida al iniciar. */
    @SuppressLint("MissingPermission")
    fun locationUpdates(): Flow<Location> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateDistanceMeters(5f)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it) }
            }
        }

        // La ultima posicion conocida da un primer fix inmediato si existe.
        fusedClient.lastLocation.addOnSuccessListener { ultima ->
            ultima?.let { trySend(it) }
        }
        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

        awaitClose { fusedClient.removeLocationUpdates(callback) }
    }

    /** Cantidad de satelites usados en el fix actual, para el panel de estado. */
    @SuppressLint("MissingPermission")
    fun satelliteCount(): Flow<Int> = callbackFlow {
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var usados = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) usados++
                }
                trySend(usados)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.registerGnssStatusCallback(context.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            locationManager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
        }

        awaitClose { locationManager.unregisterGnssStatusCallback(callback) }
    }
}
