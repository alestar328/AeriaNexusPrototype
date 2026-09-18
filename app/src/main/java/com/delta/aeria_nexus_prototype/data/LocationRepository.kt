package com.delta.aeria_nexus_prototype.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    fun tienePermiso(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Una sola posicion, para fechar un incidente en el sitio. Null si no hay
     * permiso o no llega un fix a tiempo: un incidente no puede quedarse esperando
     * al GPS dentro de un edificio. Si el fix nuevo no llega se usa la ultima
     * posicion conocida, que en la practica es de hace segundos.
     */
    @SuppressLint("MissingPermission")
    suspend fun posicionActual(): Location? {
        if (!tienePermiso()) return null
        val nueva = withTimeoutOrNull(ESPERA_FIX_MILLIS) {
            suspendCancellableCoroutine { continuacion ->
                val cancelacion = CancellationTokenSource()
                fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancelacion.token)
                    .addOnSuccessListener { continuacion.resume(it) }
                    .addOnFailureListener { continuacion.resume(null) }
                continuacion.invokeOnCancellation { cancelacion.cancel() }
            }
        }
        return nueva ?: suspendCancellableCoroutine { continuacion ->
            fusedClient.lastLocation
                .addOnSuccessListener { continuacion.resume(it) }
                .addOnFailureListener { continuacion.resume(null) }
        }
    }

    /**
     * Direccion legible de una posicion ("Quezon Ave, Quezon City"). Null sin red o
     * sin servicio de geocodificacion en el telefono: quien llama cae a las
     * coordenadas, que siempre son ciertas.
     */
    suspend fun direccionDe(posicion: Location): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val direcciones = withTimeoutOrNull(ESPERA_DIRECCION_MILLIS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuacion ->
                    geocoder.getFromLocation(
                        posicion.latitude,
                        posicion.longitude,
                        1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(resultado: MutableList<Address>) = continuacion.resume(resultado)
                            override fun onError(mensaje: String?) = continuacion.resume(emptyList())
                        },
                    )
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(posicion.latitude, posicion.longitude, 1) }
                        .getOrNull()
                        .orEmpty()
                }
            }
        }
        return direcciones?.firstOrNull()?.getAddressLine(0)
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

    private companion object {
        const val ESPERA_FIX_MILLIS = 10_000L
        const val ESPERA_DIRECCION_MILLIS = 5_000L
    }
}
