package com.delta.aeria_nexus_prototype.data.identity

import android.content.Context
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate

/** Como quedo la clave y si su atestacion prueba frescura o solo esta bien formada. */
data class ResultadoDeClave(val nivel: NivelClave, val conRetoDelBackend: Boolean)

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
 * asigna el registro de dispositivos (paso 8). Lo que SI es real y no una maqueta:
 * la clave vive en el Keystore y no sale de alli, el CSR esta bien formado
 * (validado con `openssl req -verify`) y, cuando hay un reto emitido de fuera
 * ([RetoRepository]), la atestacion y la prueba de posesion acreditan frescura.
 *
 * Mientras no exista el canal, la CA y los retos entran por `tools/`; ver
 * `MainActivity`.
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
     * [retoDeAtestacion] deberia venir siempre del backend: viaja dentro de la
     * cadena de atestacion y es lo que impide reutilizar la atestacion de otro
     * terminal o de otro dia. Si no hay —porque nadie lo ha emitido todavia— se
     * genera uno local para que la cadena salga bien formada, pero entonces NO
     * prueba frescura, y quien llama tiene que decirlo en pantalla en vez de
     * dejarlo pasar. Por eso se devuelve tambien de donde salio el reto.
     */
    fun generarClave(retoDeAtestacion: ByteArray?): ResultadoDeClave {
        val reto = retoDeAtestacion ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        ClaveEnKeystore.terminal.generarPar(reto)
        return ResultadoDeClave(
            nivel = ClaveEnKeystore.terminal.nivelDeLaClave(),
            conRetoDelBackend = retoDeAtestacion != null,
        )
    }

    /** Paso 11: peticion de certificado con el Device ID como nombre comun. */
    fun crearCsr(deviceId: String, tenant: String): String {
        val der = ClaveEnKeystore.terminal.crearCsr(
            SujetoCsr(commonName = deviceId, organizationalUnit = tenant),
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

    /**
     * Ancla con la que se valida a los perifericos que se emparejan (workflow 31).
     *
     * En el modelo real la reparte el backend junto al resto de la politica
     * (workflow 65). Aqui se instala en el alta y se guarda en la carpeta privada.
     * Sin ancla no hay emparejamiento posible, y eso es lo correcto: aceptar a
     * cualquier camara que se identifique seria peor que no comprobar nada, porque
     * daria la impresion de que si se comprueba.
     */
    fun anclaDePerifericos(): X509Certificate? {
        val fichero = File(carpeta, FICHERO_ANCLA).takeIf { it.isFile } ?: return null
        return runCatching { Pem.leerCertificados(fichero.readText()).firstOrNull() }.getOrNull()
    }

    fun instalarAnclaDePerifericos(pem: String): X509Certificate {
        val ancla = Pem.leerCertificados(pem).firstOrNull()
            ?: error("El PEM del ancla no trae ningun certificado")
        File(carpeta, FICHERO_ANCLA).writeText(pem)
        return ancla
    }

    /** Cuantos certificados trae la atestacion del dispositivo; 0 si no la soporta. */
    fun certificadosDeAtestacion(): Int = ClaveEnKeystore.terminal.cadenaDeCertificados().size

    /**
     * Paso 15: instalar el certificado del terminal y su cadena.
     *
     * Acepta uno o varios certificados en PEM, del terminal hacia la raiz.
     */
    fun instalarCertificado(pemDeLaCadena: String): X509Certificate {
        val delTerminal = ClaveEnKeystore.terminal.instalarCertificadoEmitido(pemDeLaCadena)
        prefs.edit().putBoolean(CLAVE_ALTA_COMPLETA, true).apply()
        return delTerminal
    }

    /**
     * Paso 16: prueba de posesion. Firma el reto con la clave privada, que sigue
     * sin salir del Keystore.
     */
    fun pruebaDePosesion(reto: ByteArray): ByteArray = ClaveEnKeystore.terminal.firmarReto(reto)

    fun altaCompleta(): Boolean = prefs.getBoolean(CLAVE_ALTA_COMPLETA, false)

    /** §13: un reseteo tiene que destruir la identidad local, no solo olvidarla. */
    fun deshacerAlta() {
        ClaveEnKeystore.terminal.borrar()
        carpeta.deleteRecursively()
        prefs.edit().clear().apply()
    }

    private companion object {
        const val CLAVE_DEVICE_ID = "device_id"
        const val CLAVE_ALTA_COMPLETA = "alta_completa"
        const val FICHERO_ANCLA = "perifericos.ca.pem"
    }
}
