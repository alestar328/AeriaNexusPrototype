package com.delta.aeria_nexus_prototype.data.audit

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

private const val TAG = "AeriaAuditoria"

/**
 * Catalogo de eventos del cliente (propuesta nuestra para el G0: el documento solo fija
 * las familias del §14). Solo lo que el telefono puede afirmar por si mismo; lo que
 * decide el servidor lo registra el servidor.
 */
enum class TipoEvento(val familia: String, val texto: String) {
    AUDITORIA_INICIADA("security", "Audit log started"),
    AUDITORIA_REINICIADA("security", "Audit log restarted without its key"),

    SESION_ABIERTA("session", "Session opened"),
    SESION_ACREDITADA("session", "Session accredited by AeriaOne"),
    SESION_NO_ACREDITADA("session", "AeriaOne did not accredit the session"),
    PIN_INCORRECTO("session", "Wrong PIN"),
    PIN_BLOQUEO_TEMPORAL("session", "PIN keypad locked out"),
    PIN_CAMBIADO("session", "PIN changed"),
    PIN_CAMBIO_FALLIDO("session", "PIN change could not be stored"),
    SESION_CERRADA("session", "Session closed"),

    BODYCAM_ENLACE("peripheral", "Bodycam link checked"),
    ATADURA_CREADA("peripheral", "Bodycam bound to officer"),
    ATADURA_RECHAZADA("peripheral", "Bodycam rejected the binding"),
    ATADURA_DESHECHA("peripheral", "Bodycam binding ended"),

    BOVEDA_CREADA("evidence", "Evidence vault created"),
    BOVEDA_ABIERTA("evidence", "Evidence vault unlocked"),
    BOVEDA_CONTRASENA_INCORRECTA("evidence", "Wrong vault password"),
    BOVEDA_CERRADA("evidence", "Evidence vault sealed"),
    EVIDENCIA_VISUALIZADA("evidence", "Evidence opened"),
}

/** Quien y desde donde, en el momento del evento (§14.1). */
data class ContextoDeAuditoria(
    val actor: String? = null,
    val tenant: String? = null,
    val dispositivo: String? = null,
    val instancia: String? = null,
    val release: String? = null,
    val sesion: String? = null,
)

/** Un evento ya escrito, tal como lo ve la pantalla del diario. */
data class EventoLeido(val orden: Long, val json: String)

/**
 * Diario de auditoria local a prueba de manipulacion (workflow 61).
 *
 * Cada evento se encadena al anterior con una HMAC-SHA256 cuya clave vive en el
 * Keystore y no sale del telefono ([CadenaDeAuditoria] explica que detecta y que no).
 * Es la mitad cliente: subirlo y correlarlo es del backend.
 *
 * **Registrar nunca falla hacia fuera.** Si la auditoria no puede escribir, se pierde
 * el evento y queda en el log de Android; lo que no puede pasar es que un fallo del
 * diario deje a un agente sin abrir sesion o sin ver su evidencia.
 *
 * **Que se guarda y que no:** identificadores (agente, dispositivo, sesion, fichero de
 * evidencia) y resultados. Nunca contenido: ni PIN, ni contrasena, ni notas, ni datos
 * de ciudadanos. El fichero esta en el almacenamiento privado de la app y va en claro a
 * proposito: lo que tiene que proteger es la integridad, no el secreto, y un auditor
 * tiene que poder leerlo.
 */
class AuditoriaLocal(context: Context) {

    /** Lo pone AppContainer: la identidad y la sesion las conoce IdentityRepository. */
    var contexto: () -> ContextoDeAuditoria = { ContextoDeAuditoria() }

    private val fichero = File(context.filesDir, "audit").apply { mkdirs() }.resolve(NOMBRE_FICHERO)

    // Un solo hilo: el orden de escritura es el orden de la cadena.
    private val escritor = Executors.newSingleThreadExecutor()

    private val cadena = CadenaDeAuditoria { datos ->
        Mac.getInstance(ALGORITMO).apply { init(clave()) }.doFinal(datos)
    }

    /**
     * Encadenar solo necesita la ultima linea: se lee el fichero una vez por proceso y
     * despues se recuerda. Solo la toca el hilo del escritor. Va declarada antes del
     * init, que ya encola trabajo para ese hilo.
     */
    private var ultimaEscrita: String? = null

    init {
        escritor.execute { comprobarArranque() }
    }

    /**
     * Anota un evento. El contexto se toma AHORA, en el hilo que llama: al cerrar
     * sesion el evento tiene que llevar la sesion que se cierra, no la que venga.
     */
    fun registrar(tipo: TipoEvento, datos: List<Pair<String, String?>> = emptyList()) {
        val momento = System.currentTimeMillis()
        val arranque = SystemClock.elapsedRealtime()
        val ctx = runCatching { contexto() }.getOrDefault(ContextoDeAuditoria())
        escritor.execute {
            try {
                anotar(tipo, datos, ctx, momento, arranque)
            } catch (e: Exception) {
                Log.e(TAG, "no se pudo registrar ${tipo.name}", e)
            }
        }
    }

    /** Lee el diario entero y comprueba la cadena. Va por el mismo hilo que escribe. */
    fun leer(): Pair<List<EventoLeido>, IntegridadDelDiario> = escritor.submit<Pair<List<EventoLeido>, IntegridadDelDiario>> {
        val lineas = lineas()
        val integridad = try {
            cadena.verificar(lineas)
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo verificar el diario", e)
            IntegridadDelDiario(eventos = 0, rotaEn = 1, motivo = "audit key unavailable")
        }
        val eventos = lineas.mapNotNull { linea ->
            val orden = CadenaDeAuditoria.ordenDe(linea) ?: return@mapNotNull null
            CadenaDeAuditoria.jsonDe(linea)?.let { EventoLeido(orden, it) }
        }
        eventos to integridad
    }.get()

    private fun anotar(
        tipo: TipoEvento,
        datos: List<Pair<String, String?>>,
        ctx: ContextoDeAuditoria,
        momento: Long,
        arranque: Long,
    ) {
        val ultima = ultimaLinea()
        val orden = (ultima?.let(CadenaDeAuditoria::ordenDe) ?: 0L) + 1
        val anterior = ultima?.let(CadenaDeAuditoria::macDe) ?: CadenaDeAuditoria.GENESIS

        val json = JsonPlano.objeto(
            listOf(
                "id" to UUID.randomUUID().toString(),
                "ts" to Instant.ofEpochMilli(momento).toString(),
                // La hora del telefono se puede cambiar (workflow 64). El tiempo desde el
                // arranque no, asi que un salto de reloj entre dos eventos se puede ver.
                "uptime_ms" to arranque.toString(),
                "type" to tipo.name,
                "family" to tipo.familia,
                "actor" to ctx.actor,
                "tenant" to ctx.tenant,
                "device" to ctx.dispositivo,
                "app_instance" to ctx.instancia,
                "release" to ctx.release,
                "session" to ctx.sesion,
            ) + datos,
        )
        val linea = cadena.linea(orden, anterior, json)
        fichero.appendText(linea + "\n", Charsets.UTF_8)
        ultimaEscrita = linea
        Log.i(TAG, "evento $orden ${tipo.name}")
    }

    /**
     * Sin clave en el Keystore y con diario escrito, la cadena ya no se puede ni
     * continuar ni verificar (datos de la app a medias, Keystore reiniciado). No se
     * borra: se aparta con fecha, porque es justo lo que alguien querra mirar, y se
     * empieza otra que lo dice en su primer evento.
     */
    private fun comprobarArranque() {
        try {
            val hayClave = keystore().containsAlias(ALIAS)
            if (!hayClave && fichero.isFile && fichero.length() > 0) {
                val apartado = File(fichero.parentFile, "eventos-sin-clave-${System.currentTimeMillis()}.log")
                fichero.renameTo(apartado)
                ultimaEscrita = null
                anotar(
                    TipoEvento.AUDITORIA_REINICIADA,
                    listOf("previous_log" to apartado.name),
                    contexto(),
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtime(),
                )
            } else if (!fichero.isFile || fichero.length() == 0L) {
                anotar(TipoEvento.AUDITORIA_INICIADA, emptyList(), contexto(), System.currentTimeMillis(), SystemClock.elapsedRealtime())
            }
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo preparar el diario", e)
        }
    }

    private fun lineas(): List<String> =
        if (fichero.isFile) fichero.readLines(Charsets.UTF_8).filter { it.isNotBlank() } else emptyList()

    private fun ultimaLinea(): String? = ultimaEscrita ?: lineas().lastOrNull()

    private fun keystore(): KeyStore = KeyStore.getInstance(PROVEEDOR).apply { load(null) }

    private fun clave(): SecretKey {
        (keystore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVEEDOR).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build())
        }.generateKey()
    }

    private companion object {
        const val PROVEEDOR = "AndroidKeyStore"
        // Propia y distinta de las de evidencia e identidad (IAM-05): comprometer una
        // no debe dejar reescribir el diario que cuenta lo que se hizo con las otras.
        const val ALIAS = "aeria.audit.hmac.v1"
        const val ALGORITMO = "HmacSHA256"
        const val NOMBRE_FICHERO = "eventos.log"
    }
}
