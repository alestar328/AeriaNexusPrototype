package com.delta.aeria_nexus_prototype.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import com.delta.aeria_nexus_prototype.data.crypto.EvidenceCrypto
import com.delta.aeria_nexus_prototype.data.model.EvidenceSource
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AeriaGafasMedia"

/** Un fichero tal y como lo anuncia el servidor de las gafas. */
data class ArchivoEnGafas(
    /** Nombre con el que lo llaman las gafas. Se conserva por trazabilidad. */
    val nombre: String,
    /** Ruta o URL con la que se descarga, tal cual venga en el listado. */
    val ruta: String,
    val bytes: Long = -1L,
    val fechaMillis: Long = -1L,
)

/** Resultado de traerse un fichero de las gafas a la boveda. */
sealed interface ResultadoDescarga {
    data class Cifrada(
        val archivo: ArchivoEnGafas,
        val sealed: EvidenceCrypto.Sealed,
    ) : ResultadoDescarga

    data class Fallo(val motivo: String) : ResultadoDescarga
}

/**
 * Trae el material de las gafas BleeqUp Ranger **directamente a la boveda**, sin
 * pasar por la galeria.
 *
 * ## Estado: ARMAZON. Nunca ha hablado con unas gafas.
 *
 * Compila y la mecanica esta entera —unirse a su punto de acceso, atar los
 * sockets a esa red, descargar en streaming, cifrar y borrar el claro— pero el
 * protocolo de las gafas es propietario y no esta documentado. **Faltan
 * exactamente tres datos**, marcados en el codigo con `PROTOCOLO`, y no se han
 * inventado a proposito: un endpoint adivinado que "casi" funciona es peor que un
 * hueco declarado.
 *
 *   1. Las credenciales del AP (SSID y clave). Probablemente se piden por BLE, en
 *      alguno de los dos servicios GATT propietarios sin documentar.
 *   2. El endpoint del listado y la forma de su respuesta.
 *   3. Como se construye la URL de descarga de cada fichero.
 *
 * Se averiguan con el camino 2 del informe (`docs/preguntar al manager sobre las
 * gafas.docx`): capturar el `btsnoop_hci.log` y el HTTP contra 192.168.43.1
 * mientras la app oficial hace una transferencia real. Hasta entonces [listar]
 * devuelve vacio y lo dice en el log.
 *
 * ## Por que NO se usa la app oficial
 *
 * Descarga a `DCIM/BleeqUp/MEDIA`, la galeria publica. Cifrar desde ahi seria
 * cifrar una copia mientras el original se queda legible por cualquier app con
 * permiso de medios: peor que no cifrar, porque parece resuelto. Este repositorio
 * escribe en la carpeta privada de [LocalEvidenceRepository] y de ahi al `.fev`.
 *
 * ## El detalle que condiciona todo lo demas
 *
 * El AP de las gafas **no tiene salida a internet**. Por eso la red se pide con
 * [WifiNetworkSpecifier] y **cada** peticion se abre con `Network.openConnection`:
 * atar el proceso entero con `bindProcessToNetwork` dejaria a Agora, a las subidas
 * de evidencia y al mapa sin red mientras dure la descarga. Ese es tambien el
 * motivo de que esto no pueda ser una sincronizacion de fondo: unirse a un AP con
 * [WifiNetworkSpecifier] exige app en primer plano y un dialogo del sistema que el
 * agente tiene que aceptar.
 *
 * Y las gafas se desconectan de su propio WiFi mientras graban, para ahorrar
 * bateria: la descarga solo puede ocurrir con la grabacion parada.
 */
class GafasMediaRepository(
    private val context: Context,
    private val evidencia: LocalEvidenceRepository,
    private val enBruto: RawEvidenceRepository,
) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** La red de las gafas mientras dure la sesion de descarga. */
    @Volatile
    private var red: Network? = null

    private var callback: ConnectivityManager.NetworkCallback? = null

    val conectado: Boolean get() = red != null

    /**
     * Pasarela de la red de las gafas: **la IP real de su servidor**.
     *
     * [BASE] apunta a 192.168.43.1 porque es lo que se capturo una vez con la app
     * del fabricante, y eso no es una garantia. Con el telefono ya unido al AP,
     * esto lo dice el propio sistema, asi que un cambio de firmware no obliga a
     * adivinar otra vez.
     */
    val pasarela: String?
        get() = red?.let { r ->
            val enlace = connectivity.getLinkProperties(r) ?: return@let null
            // Solo IPv4: el AP tiene tambien ruta por defecto IPv6 y su pasarela
            // sale como "::", que no sirve para construir ninguna URL.
            enlace.routes
                .asSequence()
                .mapNotNull { it.gateway }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isAnyLocalAddress }
                ?.hostAddress
                ?: enlace.linkAddresses
                    .asSequence()
                    .map { it.address }
                    .filterIsInstance<java.net.Inet4Address>()
                    .firstOrNull()
                    ?.hostAddress
                    // Sin pasarela declarada, el servidor suele ser el .1 de la
                    // subred del propio telefono.
                    ?.replaceAfterLast('.', "1")
        }

    /**
     * Abre una peticion HTTPS **atada a la red de las gafas**, aceptando su
     * certificado propio.
     *
     * El servidor de las gafas habla `https://` con un certificado que no firma
     * nadie conocido. El SDK del fabricante lo resuelve con un TrustManager que
     * acepta todo, pero **se lo pone a su cliente OkHttp global**; aqui la
     * excepcion vive y muere en esta conexion, que es la unica forma de que
     * confiar en un certificado desconocido no se convierta en confiar en
     * cualquiera en toda la app.
     */
    fun abrirSeguro(url: java.net.URL): javax.net.ssl.HttpsURLConnection? {
        val red = this.red ?: return null
        return try {
            (red.openConnection(url) as javax.net.ssl.HttpsURLConnection).apply {
                val confia = arrayOf<javax.net.ssl.TrustManager>(
                    object : javax.net.ssl.X509TrustManager {
                        override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) = Unit
                        override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) = Unit
                        override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
                    },
                )
                sslSocketFactory = javax.net.ssl.SSLContext.getInstance("TLS")
                    .apply { init(null, confia, java.security.SecureRandom()) }
                    .socketFactory
                // El certificado va a nombre de cualquier cosa menos de una IP.
                setHostnameVerifier { _, _ -> true }
                connectTimeout = TIMEOUT_MS.toInt()
                readTimeout = LECTURA_MS
                requestMethod = "GET"
                // Cabecera tal y como la manda el SDK del fabricante. El nombre
                // no es "Content-Type" y no es una errata nuestra.
                setRequestProperty("contentType", "application/json; charset=UTF-8")
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir $url: ${e.message}")
            null
        }
    }

    /**
     * Se une al punto de acceso de las gafas y retiene esa red para las descargas.
     *
     * Muestra un dialogo del sistema que el agente tiene que aceptar, y la app
     * debe estar en primer plano. Devuelve false si no se consigue a tiempo.
     *
     * PROTOCOLO (1 de 3): [ssid] y [passphrase] no se conocen todavia. Al capturar
     * el BLE saldra de donde los saca la app oficial — lo mas probable es que se
     * pidan por GATT junto con la orden de encender el AP.
     */
    suspend fun conectar(ssid: String, passphrase: String?): Boolean =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Por debajo de Android 10 habria que ir por WifiManager.addNetwork,
                // que ademas de estar obsoleto cambia la red del telefono entero.
                // No se implementa a ciegas: ningun terminal de la flota lo necesita.
                Log.e(TAG, "Unirse al AP de las gafas exige Android 10 o superior")
                return@withContext false
            }
            desconectar()

            val especificador = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .apply { if (!passphrase.isNullOrEmpty()) setWpa2Passphrase(passphrase) }
                .build()
            val peticion = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // El AP de las gafas no da internet: exigir INTERNET haria que el
                // sistema descartase justo la red que queremos.
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(especificador)
                .build()

            val listo = CountDownLatch(1)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    red = network
                    listo.countDown()
                }

                override fun onUnavailable() {
                    Log.w(TAG, "El sistema no pudo unirse al AP $ssid")
                    listo.countDown()
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "Se perdio el AP de las gafas")
                    red = null
                }
            }
            callback = cb
            connectivity.requestNetwork(peticion, cb, TIMEOUT_MS.toInt())
            listo.await(TIMEOUT_MS + 1_000, TimeUnit.MILLISECONDS)
            red != null
        }

    /**
     * Suelta la red de las gafas. **Hay que llamarlo siempre al terminar**: la
     * peticion retiene el WiFi del telefono en ese AP sin internet mientras viva.
     */
    fun desconectar() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        red = null
    }

    /**
     * Lista el material que hay en las gafas.
     *
     * PROTOCOLO (2 de 3): ni el endpoint ni el formato de la respuesta se conocen.
     * Cuando se capture el trafico, esto es lo unico que hay que escribir: pedir la
     * URL real y traducir su respuesta a [ArchivoEnGafas]. La mecanica de red ya
     * esta debajo, en [abrir].
     */
    suspend fun listar(): List<ArchivoEnGafas> = withContext(Dispatchers.IO) {
        if (red == null) {
            Log.w(TAG, "listar() sin estar unido al AP de las gafas")
            return@withContext emptyList()
        }
        Log.w(
            TAG,
            "PROTOCOLO sin averiguar: no se conoce el endpoint del listado de $BASE. " +
                "Ver docs/preguntar al manager sobre las gafas.docx (camino 2).",
        )
        emptyList()
    }

    /**
     * Descarga un fichero de las gafas y lo deja **cifrado** en la boveda.
     *
     * El claro vive lo que dura la descarga y lo borra EvidenceCrypto al sellar,
     * igual que una captura del propio telefono. Si algo falla por el camino, el
     * temporal se descarta: media descarga no es evidencia.
     *
     * PROTOCOLO (3 de 3): [ArchivoEnGafas.ruta] se concatena a [BASE] tal cual. Si
     * el listado real devuelve URLs absolutas, o exige cabeceras o sesion, es aqui
     * donde se ajusta.
     */
    suspend fun descargar(archivo: ArchivoEnGafas): ResultadoDescarga =
        withContext(Dispatchers.IO) {
            if (red == null) return@withContext ResultadoDescarga.Fallo("Sin conexion con las gafas")
            val destino = evidencia.createGafasVideoTarget(extension(archivo.nombre))
                ?: return@withContext ResultadoDescarga.Fallo("Sin carpeta privada donde descargar")

            val conexion = abrir(archivo.ruta)
                ?: return@withContext ResultadoDescarga.Fallo("No se pudo abrir ${archivo.nombre}")
            try {
                val codigo = conexion.responseCode
                if (codigo !in 200..299) {
                    evidencia.discard(destino)
                    return@withContext ResultadoDescarga.Fallo("Las gafas respondieron $codigo")
                }
                conexion.inputStream.use { entrada ->
                    destino.file.outputStream().use { salida -> entrada.copyTo(salida, COPIA) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Descarga interrumpida de ${archivo.nombre}: ${e.message}")
                evidencia.discard(destino)
                return@withContext ResultadoDescarga.Fallo("Descarga interrumpida: ${e.message}")
            } finally {
                conexion.disconnect()
            }

            // Mismo punto de enganche que la camara del telefono: se cifra SIEMPRE
            // al cerrar el destino, nunca durante la descarga.
            val sellada = evidencia.seal(destino)
            if (sellada == null) {
                Log.e(TAG, "${archivo.nombre} descargado pero SIN cifrar")
                return@withContext ResultadoDescarga.Fallo("Descargado sin cifrar: revisar la boveda")
            }
            // Queda EN BRUTO: cifrado y fechado, pero sin incidente. Quien decide
            // a que pertenece es el agente, al categorizarlo — las gafas entregan
            // su video cuando la grabacion ya termino, asi que cruzarlo por hora
            // con el incidente activo seria una conjetura metida en una cadena de
            // custodia. Ver RawEvidenceRepository.
            enBruto.registrarImportacion(
                sellada = sellada,
                source = EvidenceSource.FALCON_LENS,
                originalName = archivo.nombre,
                recordedAtMillis = archivo.fechaMillis,
            )
            Log.d(TAG, "${archivo.nombre} en la boveda, sin categorizar: ${sellada.file.name}")
            ResultadoDescarga.Cifrada(archivo, sellada)
        }

    /**
     * Abre una peticion **atada a la red de las gafas**. Todo lo que hable con
     * 192.168.43.1 tiene que pasar por aqui: una URL abierta por la via normal se
     * iria por los datos moviles y no encontraria nada.
     *
     * Va por http:// a proposito. El servidor tambien escucha en 443, pero con un
     * certificado que no conocemos; el dia que haga falta ese puerto hay que
     * anadir un TrustManager **acotado a esta conexion**, nunca global.
     */
    private fun abrir(ruta: String): HttpURLConnection? {
        val red = this.red ?: return null
        val url = if (ruta.startsWith("http")) URL(ruta) else URL("$BASE/${ruta.trimStart('/')}")
        return try {
            (red.openConnection(url) as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS.toInt()
                readTimeout = LECTURA_MS
                requestMethod = "GET"
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir $url: ${e.message}")
            null
        }
    }

    private fun extension(nombre: String): String =
        nombre.substringAfterLast('.', "").lowercase().ifEmpty { "mp4" }

    companion object {
        /** Servidor de las gafas cuando el telefono esta en su punto de acceso. */
        const val BASE = "http://192.168.43.1"

        private const val TIMEOUT_MS = 15_000L
        private const val LECTURA_MS = 30_000
        private const val COPIA = 64 * 1024
    }
}
