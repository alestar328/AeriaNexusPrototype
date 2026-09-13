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
    /** `video` o `image`. Va en la ruta de descarga, no es solo informativo. */
    val tipo: String,
    val bytes: Long = -1L,
    /**
     * Fecha que declaran las gafas. **No es de fiar**: su reloj salta hacia atras
     * al apagarlas (medido el 2026-09-13), asi que quien fecha la evidencia es el
     * telefono. Se conserva solo como dato del aparato.
     */
    val fechaMillis: Long = -1L,
    val duracionMillis: Long = -1L,
) {
    /** `https://<ip>/<tipo>?fileName=<nombre>`, sacado del SDK del fabricante. */
    fun rutaDeDescarga(ip: String): String = "https://$ip/$tipo?fileName=$nombre"
}

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
 * ## El protocolo, sacado del AAR del fabricante el 2026-09-13
 *
 *     Listar:    https://<ip>/list?fileType=all|video|image
 *     Descargar: https://<ip>/<fileType>?fileName=<nombre>   (+ Range: bytes=N-)
 *     Borrar:    https://<ip>/delete?fileName=<nombre>
 *
 * Las credenciales del AP las da el propio SDK por BLE (`turnOnWifi`), y la IP no
 * se adivina: la dice el sistema en [pasarela] con el telefono ya unido.
 *
 * **Se hace con HTTP propio y no con el SDK a proposito.** El cliente del
 * fabricante es un OkHttp global que no sabe nada de la red del AP, asi que usarlo
 * obligaria a `bindProcessToNetwork`, y eso ataria TAMBIEN a Agora, las subidas y
 * el mapa a una red sin internet. Aqui cada socket se ata por su cuenta y el resto
 * de la app sigue con su red.
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
     * La red del AP mientras se este unido, o null.
     *
     * Se expone porque el SDK del fabricante hace sus peticiones con su propio
     * OkHttp y no sabe nada de esta red: para que llegue hay que atarle el proceso,
     * y eso necesita el objeto [Network]. Solo lo usa la sonda de depuracion; el
     * camino normal es [abrirSeguro], que ata el socket sin tocar al resto de la app.
     */
    val redDeLasGafas: Network? get() = red

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
     * [ssid] y [passphrase] los da el propio SDK al encender el AP por BLE
     * (`turnOnWifi`): no hay que adivinarlos. Ver [DescargaDeGafas].
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
     * Va contra `https://<ip>/list?fileType=all`, que es lo que usa el SDK del
     * fabricante. La peticion se ata a la red del AP en [abrirSeguro].
     */
    suspend fun listar(): List<ArchivoEnGafas> = withContext(Dispatchers.IO) {
        if (red == null) {
            Log.w(TAG, "listar() sin estar unido al AP de las gafas")
            return@withContext emptyList()
        }
        val ip = pasarela ?: return@withContext emptyList<ArchivoEnGafas>().also {
            Log.w(TAG, "listar() sin pasarela: el AP no dio ruta")
        }
        val conexion = abrirSeguro(URL("https://$ip/list?fileType=all"))
            ?: return@withContext emptyList()
        val cuerpo = try {
            if (conexion.responseCode !in 200..299) {
                Log.e(TAG, "el listado respondio ${conexion.responseCode}")
                return@withContext emptyList()
            }
            conexion.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            Log.e(TAG, "listado ilegible: ${e.message}")
            return@withContext emptyList()
        } finally {
            conexion.disconnect()
        }
        traducirListado(cuerpo)
    }

    /**
     * Traduce la respuesta del listado.
     *
     * Es tolerante a proposito: si un fichero viene con algun campo raro se
     * descarta ese y siguen los demas. Un listado que se cae entero por una linea
     * mala dejaria al agente sin poder traer NADA.
     */
    private fun traducirListado(cuerpo: String): List<ArchivoEnGafas> {
        return try {
            val datos = org.json.JSONObject(cuerpo).optJSONArray("data")
                ?: return emptyList<ArchivoEnGafas>().also { Log.w(TAG, "listado sin campo 'data'") }
            (0 until datos.length()).mapNotNull { i ->
                val objeto = datos.optJSONObject(i) ?: return@mapNotNull null
                val nombre = objeto.optString("fileName").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                ArchivoEnGafas(
                    nombre = nombre,
                    // El listado marca las fotos con su origen (appcapture, handcapture,
                    // aicapture); para la URL de descarga solo valen 'video' e 'image'.
                    tipo = if (objeto.optString("fileType") == "video") "video" else "image",
                    bytes = objeto.optLong("fileSize", -1L),
                    fechaMillis = objeto.optLong("fileTime", -1L),
                    duracionMillis = objeto.optLong("fileDuration", -1L),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "listado con formato inesperado: ${e.message}")
            emptyList()
        }
    }

    /**
     * Descarga un fichero de las gafas y lo deja **cifrado** en la boveda.
     *
     * El claro vive lo que dura la descarga y lo borra EvidenceCrypto al sellar,
     * igual que una captura del propio telefono. Si algo falla por el camino, el
     * temporal se descarta: media descarga no es evidencia.
     *
     * La URL se construye con [ArchivoEnGafas.rutaDeDescarga] y la pasarela real
     * del AP, no con una IP fija.
     */
    suspend fun descargar(archivo: ArchivoEnGafas): ResultadoDescarga =
        withContext(Dispatchers.IO) {
            if (red == null) return@withContext ResultadoDescarga.Fallo("No connection to the glasses")
            val destino = evidencia.createGafasVideoTarget(extension(archivo.nombre))
                ?: return@withContext ResultadoDescarga.Fallo("No private folder to download into")

            val ip = pasarela
                ?: return@withContext ResultadoDescarga.Fallo("The glasses access point gave no route")
            val conexion = abrirSeguro(URL(archivo.rutaDeDescarga(ip)))
                ?: return@withContext ResultadoDescarga.Fallo("Could not open ${archivo.nombre}")
            try {
                val codigo = conexion.responseCode
                if (codigo !in 200..299) {
                    evidencia.discard(destino)
                    return@withContext ResultadoDescarga.Fallo("The glasses answered $codigo")
                }
                conexion.inputStream.use { entrada ->
                    destino.file.outputStream().use { salida -> entrada.copyTo(salida, COPIA) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Descarga interrumpida de ${archivo.nombre}: ${e.message}")
                evidencia.discard(destino)
                return@withContext ResultadoDescarga.Fallo("Download interrupted: ${e.message}")
            } finally {
                conexion.disconnect()
            }

            // Mismo punto de enganche que la camara del telefono: se cifra SIEMPRE
            // al cerrar el destino, nunca durante la descarga.
            val sellada = evidencia.seal(destino)
            if (sellada == null) {
                Log.e(TAG, "${archivo.nombre} descargado pero SIN cifrar")
                return@withContext ResultadoDescarga.Fallo("Downloaded unencrypted: check the vault")
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

    private fun extension(nombre: String): String =
        nombre.substringAfterLast('.', "").lowercase().ifEmpty { "mp4" }

    companion object {
        /** Servidor de las gafas cuando el telefono esta en su punto de acceso. */

        private const val TIMEOUT_MS = 15_000L
        private const val LECTURA_MS = 30_000
        private const val COPIA = 64 * 1024
    }
}
