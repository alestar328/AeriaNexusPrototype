package com.delta.aeria_nexus_prototype.data.identity

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Lectura de certificados en PEM, que es como los devuelven `openssl` y la
 * mayoria de CA.
 *
 * Solo hay lectura: escribir PEM ya lo hace [Pkcs10.aPem] para la peticion, y
 * certificados no emitimos nosotros.
 */
internal object Pem {

    private val BLOQUE_CERTIFICADO = Regex(
        "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----",
        RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * Todos los certificados del texto, en el orden en que aparecen. Por convenio
     * el primero es la hoja y los siguientes suben hacia la raiz.
     */
    fun leerCertificados(texto: String): List<X509Certificate> {
        val fabrica = CertificateFactory.getInstance("X.509")
        return BLOQUE_CERTIFICADO.findAll(texto).map { coincidencia ->
            val base64 = coincidencia.groupValues[1].filterNot { it.isWhitespace() }
            val der = Base64.getDecoder().decode(base64)
            fabrica.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }.toList()
    }
}
