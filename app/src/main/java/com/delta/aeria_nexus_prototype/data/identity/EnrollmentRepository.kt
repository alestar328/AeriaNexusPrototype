package com.delta.aeria_nexus_prototype.data.identity

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Alta del telefono como dispositivo BYOD de AeriaOne (workflow 12).
 *
 * De los 21 pasos del catalogo, 9 son nuestros y estan aqui: recoger los datos
 * del terminal (3), mirar su estado de seguridad (5 y 6), generar el par de
 * claves (10), construir la peticion de certificado (11), entregarla (12),
 * instalar el certificado que devuelva la CA (15) y demostrar posesion de la
 * clave (16). Los otros 12 son del backend.
 *
 * QUE FALTA, dicho sin rodeos: no hay backend. Los pasos 2, 4, 7, 13, 14, 17 y 18
 * no ocurren, y el Device ID se lo inventa este fichero cuando en realidad lo
 * asigna el registro de dispositivos (paso 8). La CA de pruebas y el servicio de
 * retos son una tarea aparte del plan de septiembre. Lo que SI es real y no una
 * maqueta: la clave vive en el Keystore y no sale de alli, y el CSR esta bien
 * formado (validado con `openssl req -verify`).
 *
 * Mientras no exista la CA, el certificado se puede inyectar a mano en
 * compilaciones debug para cerrar el circuito; ver `MainActivity`.
 */
class EnrollmentRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("aeria_enrollment", Context.MODE_PRIVATE)
    private val carpeta: File get() = File(context.filesDir, "enrollment").apply { mkdirs() }

    /** Paso 3: datos del terminal. */
    fun atributos(): DeviceAttributes = DeviceInspector.atributos(context)

    /** Pasos 5 y 6: lo que se puede observar del estado de seguridad. */
    fun postura(): DevicePosture = DeviceInspector.postura(context)

    /**
     * Pasos 8 y 9, simulados. En el modelo real este identificador lo emite el
     * registro de dispositivos y llega por la red; que lo genere el propio
     * telefono es exactamente lo que el backend vendra a corregir.
     */
    fun deviceId(): String = prefs.getString(CLAVE_DEVICE_ID, null) ?: run {
        val sufijo = ByteArray(4).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02X".format(it) }
        "DEV-$sufijo".also { prefs.edit().putString(CLAVE_DEVICE_ID, it).apply() }
    }

    /**
     * Paso 10: par de claves en el Keystore.
     *
     * El reto de atestacion tambien deberia venir del backend. Se genera aqui con
     * [SecureRandom] para que la cadena de atestacion salga bien formada, pero un
     * reto que se inventa el propio dispositivo no prueba frescura: es la costura
     * mas visible de que falta la otra mitad.
     */
    fun generarClave(): NivelClave {
        val reto = ByteArray(32).also { SecureRandom().nextBytes(it) }
        DeviceKeystore.generarPar(reto)
        return DeviceKeystore.nivelDeLaClave()
    }

    /** Paso 11: peticion de certificado con el Device ID como nombre comun. */
    fun crearCsr(deviceId: String, tenant: String): String {
        val publica = DeviceKeystore.clavePublica() ?: error("No hay clave del terminal")
        val privada = DeviceKeystore.manejadorDeClavePrivada() ?: error("No hay clave del terminal")
        val der = Pkcs10.crear(
            sujeto = SujetoCsr(commonName = deviceId, organizationalUnit = tenant),
            publicKey = publica,
            privateKey = privada,
        )
        return Pkcs10.aPem(der)
    }

    /**
     * Paso 12: entrega de la peticion.
     *
     * Sin canal al backend, "entregar" es dejarla en la carpeta privada de la app.
     * Es lo que permite sacarla con `adb` y firmarla con una CA de pruebas.
     */
    fun guardarCsr(pem: String): File =
        File(carpeta, "device.csr.pem").apply { writeText(pem) }

    fun csrGuardado(): String? = File(carpeta, "device.csr.pem").takeIf { it.exists() }?.readText()

    /** Cuantos certificados trae la atestacion del dispositivo; 0 si no la soporta. */
    fun certificadosDeAtestacion(): Int = DeviceKeystore.cadenaDeAtestacion().size

    /**
     * Paso 15: instalar el certificado del terminal y su cadena.
     *
     * Acepta uno o varios certificados en PEM, del terminal hacia la raiz.
     */
    fun instalarCertificado(pemDeLaCadena: String): X509Certificate {
        val cadena = leerPem(pemDeLaCadena)
        require(cadena.isNotEmpty()) { "No se encontro ningun certificado en el PEM" }

        val delTerminal = cadena.first()
        val publicaDelKeystore = DeviceKeystore.clavePublica()
            ?: error("No hay clave del terminal")
        // Si el certificado no corresponde a nuestra clave, instalarlo dejaria el
        // Keystore en un estado incoherente que solo se descubriria al firmar.
        require(delTerminal.publicKey.encoded.contentEquals(publicaDelKeystore.encoded)) {
            "El certificado no corresponde a la clave de este terminal"
        }

        DeviceKeystore.instalarCadena(cadena)
        prefs.edit().putBoolean(CLAVE_ALTA_COMPLETA, true).apply()
        return delTerminal
    }

    /**
     * Paso 16: prueba de posesion. Firma el reto del backend con la clave privada,
     * que sigue sin salir del Keystore.
     */
    fun pruebaDePosesion(reto: ByteArray): ByteArray = DeviceKeystore.firmarReto(reto)

    fun altaCompleta(): Boolean = prefs.getBoolean(CLAVE_ALTA_COMPLETA, false)

    /** §13: un reseteo tiene que destruir la identidad local, no solo olvidarla. */
    fun deshacerAlta() {
        DeviceKeystore.borrar()
        carpeta.deleteRecursively()
        prefs.edit().clear().apply()
    }

    private fun leerPem(texto: String): List<X509Certificate> {
        val fabrica = CertificateFactory.getInstance("X.509")
        return CABECERA_CERTIFICADO.findAll(texto).map { coincidencia ->
            val base64 = coincidencia.groupValues[1].filterNot { it.isWhitespace() }
            val der = Base64.getDecoder().decode(base64)
            fabrica.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }.toList()
    }

    private companion object {
        const val CLAVE_DEVICE_ID = "device_id"
        const val CLAVE_ALTA_COMPLETA = "alta_completa"

        val CABECERA_CERTIFICADO = Regex(
            "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
