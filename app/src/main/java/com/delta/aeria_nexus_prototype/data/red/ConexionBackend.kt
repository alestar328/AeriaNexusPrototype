package com.delta.aeria_nexus_prototype.data.red

import android.net.http.X509TrustManagerExtensions
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Unica forma de abrir una conexion con el backend AeriaOne (IAM, subida, SOS).
 *
 * Con https añade pinning (COM-004): ademas de la validacion normal de Android, la
 * cadena verificada tiene que acabar en una raiz de Let's Encrypt. Se fija la raiz y
 * no el certificado del servidor porque ese rota cada 90 dias.
 *
 * No va en network_security_config porque alli el pin se ata a un dominio escrito al
 * compilar, y el dominio del backend sale del QR de alta.
 *
 * El http lo para network_security_config fuera de depuracion; aqui no se mira.
 */
object ConexionBackend {

    /**
     * SHA-256 de la clave publica (SPKI) en base64. Dos raices para que un cambio de
     * cadena de Let's Encrypt no deje la app sin backend:
     *  - ISRG Root X1 (RSA, caduca en 2035): la que usan hoy los certificados.
     *  - ISRG Root X2 (ECDSA, caduca en 2040): la de respaldo.
     * Calculados el 2026-10-25 desde letsencrypt.org/certs con
     * `openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64`.
     */
    private val PINES = setOf(
        "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=",
        "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=",
    )

    /**
     * Pasada esta fecha (1-oct-2027, UTC) los pines dejan de exigirse y queda la
     * validacion normal, igual que `expiration` en network_security_config: una app
     * sin actualizar no se queda sin backend si Let's Encrypt cambia de raiz. Antes
     * de esa fecha hay que revisar las raices y sacar una version con la fecha nueva.
     */
    private const val PINES_CADUCAN_EN_MILLIS = 1_822_348_800_000L

    private val fabricas = ConcurrentHashMap<String, SSLSocketFactory>()

    fun abrir(url: URL): HttpURLConnection {
        val conexion = url.openConnection() as HttpURLConnection
        if (conexion is HttpsURLConnection && System.currentTimeMillis() < PINES_CADUCAN_EN_MILLIS) {
            conexion.sslSocketFactory = fabricas.getOrPut(url.host) { fabricaConPines(url.host) }
        }
        return conexion
    }

    fun abrir(url: String): HttpURLConnection = abrir(URL(url))

    private fun fabricaConPines(host: String): SSLSocketFactory {
        val factoria = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factoria.init(null as KeyStore?)
        val delSistema = factoria.trustManagers.filterIsInstance<X509TrustManager>().first()
        val contexto = SSLContext.getInstance("TLS")
        contexto.init(null, arrayOf(ConfianzaConPines(delSistema, host)), null)
        return contexto.socketFactory
    }

    /** SHA-256 de la clave publica del certificado, en el mismo formato que [PINES]. */
    private fun pinDe(certificado: X509Certificate): String {
        val huella = MessageDigest.getInstance("SHA-256").digest(certificado.publicKey.encoded)
        return Base64.encodeToString(huella, Base64.NO_WRAP)
    }

    /**
     * Valida como el sistema y despues mira la cadena YA verificada (la que construye
     * Android hasta su raiz de confianza), no la que manda el servidor: esa la puede
     * rellenar cualquiera con los certificados que quiera.
     */
    private class ConfianzaConPines(
        private val delSistema: X509TrustManager,
        private val host: String,
    ) : X509TrustManager {

        private val extensiones = X509TrustManagerExtensions(delSistema)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val verificada = extensiones.checkServerTrusted(chain, authType, host)
            if (verificada.none { pinDe(it) in PINES }) {
                throw CertificateException("El certificado de $host no sale de una raiz de Let's Encrypt")
            }
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            delSistema.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = delSistema.acceptedIssuers
    }
}
