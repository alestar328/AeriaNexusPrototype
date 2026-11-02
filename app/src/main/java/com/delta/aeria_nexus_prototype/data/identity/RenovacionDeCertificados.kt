package com.delta.aeria_nexus_prototype.data.identity

import android.content.Context
import android.util.Log
import com.delta.aeria_nexus_prototype.data.audit.AuditoriaLocal
import com.delta.aeria_nexus_prototype.data.audit.TipoEvento
import java.security.cert.X509Certificate

private const val TAG = "AeriaRenovacion"

/**
 * Renueva los certificados del terminal y del agente con CLAVE NUEVA
 * (aeria-contracts, credential-lifecycle.md §1). Renovar es tambien rotar la clave.
 *
 * El recorrido: reto `RENOVACION`, par nuevo en otra generacion del Keystore, CSR con
 * el mismo sujeto firmado por la nueva, y la peticion firmada por la VIGENTE sobre
 * `nonce || csr_der`. La nueva solo pasa a vigente con su certificado instalado; la
 * vieja se borra cuando la nueva ya ha abierto una sesion (ver [limpiarAnteriores]).
 *
 * Bloqueante: quien llama tiene que estar en `Dispatchers.IO`.
 */
class RenovacionDeCertificados(
    context: Context,
    private val iam: IamClient,
    private val credential: CredentialRepository,
    private val enrollment: EnrollmentRepository,
    private val auditoria: AuditoriaLocal,
) {

    private val prefs = context.getSharedPreferences("aeria_renovacion", Context.MODE_PRIVATE)

    /**
     * Renueva lo que toque: faltan [DIAS_DE_MARGEN] dias o menos, o AeriaOne lo exige
     * (`renew_required`, tras rotar una CA). Como mucho un intento al dia por
     * certificado, para no gastar bateria ni retos si el servidor dice que no.
     */
    fun renovarSiToca(credenciales: CredencialesDeSesion?) {
        renovar(Tipo.TERMINAL, credenciales?.terminal?.renovarObligado == true)
        // La clave del agente solo firma con el PIN puesto en esta sesion.
        if (credential.puedeFirmarComoElAgente) {
            renovar(Tipo.AGENTE, credenciales?.agente?.renovarObligado == true)
        }
    }

    /**
     * La sesion se ha abierto con la generacion vigente: las anteriores ya no hacen falta
     * (§1.3). Lo firmado con ellas sigue valiendo en el servidor.
     */
    fun limpiarAnteriores() {
        listOf(ClaveEnKeystore.terminal, ClaveEnKeystore.agente)
            .filter { it.tieneAnteriores() }
            .forEach {
                it.borrarAnteriores()
                Log.i(TAG, "borrada la clave anterior a la renovacion")
            }
    }

    private fun renovar(tipo: Tipo, obligado: Boolean) {
        val actual = tipo.clave()
        val certificado = actual.certificado() ?: return
        val sujeto = actual.commonNameDelCertificado() ?: return
        // El terminal solo cuenta con su certificado de AeriaOne, no con el provisional.
        if (tipo == Tipo.TERMINAL && !sujeto.startsWith("DEV-")) return
        val ahora = System.currentTimeMillis()
        val queda = certificado.notAfter.time - ahora
        // Caducado no se renueva: hay que volver a enrolar (§1.1).
        if (queda <= 0) return
        if (!obligado && queda > DIAS_DE_MARGEN * DIA_MILLIS) return
        if (ahora - prefs.getLong(tipo.claveDelUltimoIntento, 0L) < DIA_MILLIS) return
        prefs.edit().putLong(tipo.claveDelUltimoIntento, ahora).apply()

        val nueva = actual.siguienteGeneracion()
        try {
            val reto = iam.pedirReto(PropositoDelReto.RENOVACION, sujeto)
            nueva.generarPar()
            val csrDer = nueva.crearCsr(SujetoCsr(commonName = sujeto, organizationalUnit = unidadDe(certificado)))
            val firma = actual.firmarReto(reto.nonce + csrDer)
            val renovado = iam.renovarCertificado(
                reto = reto,
                tipo = tipo.codigo,
                sujeto = sujeto,
                serieActual = certificado.serialNumber.toString(16),
                csrPem = Pkcs10.aPem(csrDer),
                firma = firma,
            )
            nueva.instalarCertificadoEmitido(renovado.cadenaPem)
            check(nueva.commonNameDelCertificado() == sujeto) { "el certificado renovado viene a nombre de otro" }
            nueva.promover()
            enrollment.guardarCas(renovado.cas)
            prefs.edit().remove(tipo.claveDelUltimoIntento).apply()
            auditoria.registrar(TipoEvento.CERTIFICADO_RENOVADO, listOf("kind" to tipo.codigo, "subject" to sujeto))
            Log.i(TAG, "certificado ${tipo.codigo} de $sujeto renovado con clave nueva")
        } catch (e: Exception) {
            // La vieja sigue vigente: no se ha promovido nada.
            nueva.borrarSoloEsta()
            val codigo = (e as? IamException)?.codigo
            if (codigo == CODIGO_NO_TOCA) {
                Log.i(TAG, "AeriaOne dice que aun no toca renovar ${tipo.codigo}")
            } else {
                Log.w(TAG, "no se pudo renovar el certificado ${tipo.codigo}: ${e.message}")
                auditoria.registrar(
                    TipoEvento.CERTIFICADO_NO_RENOVADO,
                    listOf("kind" to tipo.codigo, "reason" to (codigo ?: e.message)),
                )
            }
        }
    }

    /** El OU del sujeto actual (el tenant): el CSR lleva el mismo sujeto (§1.2). */
    private fun unidadDe(certificado: X509Certificate): String =
        certificado.subjectX500Principal.name
            .split(",")
            .map { it.trim() }
            .firstOrNull { it.startsWith("OU=") }
            ?.removePrefix("OU=")
            .orEmpty()

    private enum class Tipo(val codigo: String, val claveDelUltimoIntento: String) {
        TERMINAL("device", "ultimo_intento_device"),
        AGENTE("user", "ultimo_intento_user");

        fun clave(): ClaveEnKeystore = if (this == TERMINAL) ClaveEnKeystore.terminal else ClaveEnKeystore.agente
    }

    private companion object {
        /** El cliente intenta renovar cuando faltan 30 dias o menos (§1.1). */
        const val DIAS_DE_MARGEN = 30L
        const val DIA_MILLIS = 24 * 60 * 60 * 1000L
        const val CODIGO_NO_TOCA = "renewal_not_due"
    }
}
