package com.delta.aeria_nexus_prototype.data.local

import android.content.Context
import android.util.Log
import com.delta.aeria_nexus_prototype.data.crypto.DeviceKeyWrapper
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.zetetic.database.sqlcipher.SQLiteDatabase

private const val TAG = "AeriaBaseCifrada"

/**
 * Cifrado en reposo de la base de Room con SQLCipher (CRY-002): incidentes, sus
 * coordenadas, el agente y la narrativa no quedan legibles copiando el fichero.
 *
 * La clave son 32 bytes aleatorios de este telefono. En disco solo esta envuelta por
 * una clave de Keystore propia ([DeviceKeyWrapper.baseDeDatos]), que no sale del
 * aparato. No pide PIN: la base hace falta sin nadie delante (un SOS, una subida).
 *
 * Se pasa como clave en bruto (`x'…'`) y no como contraseña: ya es aleatoria, y asi
 * SQLCipher se ahorra su derivacion lenta en cada apertura.
 */
internal object BaseCifrada {

    private const val BYTES_CLAVE = 32
    private val SUFIJOS = listOf("", "-wal", "-shm", "-journal")
    private val CABECERA_EN_CLARO = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** Bases que no se pudieron abrir. Fuera de las copias, y nunca se borran solas. */
    private const val CARPETA_CUARENTENA = "cuarentena"

    /** Marca junto a una base en cuarentena de la que el agente aun no se ha enterado. */
    private const val SUFIJO_AVISO = ".aviso"

    /**
     * Deja la base lista para abrirla cifrada y devuelve la clave para Room. Cifra una
     * base en claro de una version anterior sin perder nada. Si una base cifrada ya no
     * se puede abrir, la aparta y llama a [alPonerEnCuarentena] con el fichero apartado.
     */
    fun preparar(context: Context, nombreBase: String, alPonerEnCuarentena: (File) -> Unit): ByteArray {
        System.loadLibrary("sqlcipher")
        val base = context.getDatabasePath(nombreBase)
        val clave = claveEnBruto(cargarOCrearClave(context, base, alPonerEnCuarentena))
        if (base.isFile && estaEnClaro(base)) cifrar(base, clave)
        return clave.toByteArray(Charsets.US_ASCII)
    }

    /** Hay una base en cuarentena de la que el agente aun no se ha enterado. */
    fun hayAvisoPendiente(context: Context): Boolean = avisos(context).isNotEmpty()

    fun avisoVisto(context: Context) {
        avisos(context).forEach { it.delete() }
    }

    private fun avisos(context: Context): List<File> {
        val carpeta = File(context.noBackupFilesDir, CARPETA_CUARENTENA)
        return carpeta.listFiles { f -> f.name.endsWith(SUFIJO_AVISO) }?.toList().orEmpty()
    }

    private fun cargarOCrearClave(context: Context, base: File, alPonerEnCuarentena: (File) -> Unit): ByteArray {
        val fichero = File(context.noBackupFilesDir, "${base.name}.key")
        if (fichero.isFile) {
            DeviceKeyWrapper.baseDeDatos.unwrap(fichero.readBytes())?.let { return it }
        }
        // Sin clave legible una base ya cifrada no se abre (Keystore borrado, datos de la
        // app a medias). Puede llevar incidentes sin subir: no se borra, se aparta con su
        // clave envuelta, por si el fallo del Keystore fuera pasajero. Una base en claro,
        // en cambio, se conserva y se cifra con la clave nueva.
        if (base.isFile && !estaEnClaro(base)) {
            val apartada = ponerEnCuarentena(context, base, fichero)
            Log.e(TAG, "base cifrada sin su clave: apartada en ${apartada.name}, se empieza de cero")
            alPonerEnCuarentena(apartada)
        }
        val nueva = ByteArray(BYTES_CLAVE).also { SecureRandom().nextBytes(it) }
        val temporal = File(fichero.path + ".tmp")
        temporal.writeBytes(DeviceKeyWrapper.baseDeDatos.wrap(nueva))
        check(temporal.renameTo(fichero)) { "no se pudo guardar la clave de la base" }
        return nueva
    }

    /** Un fichero vacio tambien cuenta como en claro: lo crea Android y no tiene nada. */
    private fun estaEnClaro(base: File): Boolean {
        if (base.length() == 0L) return true
        val cabecera = ByteArray(CABECERA_EN_CLARO.size)
        val leidos = base.inputStream().use { it.read(cabecera) }
        return leidos == cabecera.size && cabecera.contentEquals(CABECERA_EN_CLARO)
    }

    /**
     * Copia la base en claro a una cifrada con `sqlcipher_export` y la sustituye.
     *
     * Antes se vacia el WAL y se pasa a journal DELETE: un -wal en claro junto a la
     * base cifrada la corromperia al abrirla. El cambio de fichero es un renombrado,
     * asi que si la app muere a mitad la base en claro sigue entera y se repite.
     */
    private fun cifrar(base: File, clave: String) {
        val cifrada = File(base.parentFile, "${base.name}.cifrando")
        borrarConSusFicheros(cifrada)
        val enClaro = SQLiteDatabase.openDatabase(base.path, "", null, SQLiteDatabase.OPEN_READWRITE, null, null)
        try {
            enClaro.rawExecSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            enClaro.rawExecSQL("PRAGMA journal_mode = DELETE")
            val version = enClaro.version
            enClaro.rawExecSQL("ATTACH DATABASE ? AS cifrada KEY ?", cifrada.path, clave)
            enClaro.rawExecSQL("SELECT sqlcipher_export('cifrada')")
            enClaro.rawExecSQL("PRAGMA cifrada.user_version = $version")
            enClaro.rawExecSQL("DETACH DATABASE cifrada")
        } finally {
            enClaro.close()
        }
        check(cifrada.renameTo(base)) { "no se pudo sustituir la base en claro" }
        borrarConSusFicheros(cifrada)
        Log.i(TAG, "base en claro cifrada")
    }

    private fun ponerEnCuarentena(context: Context, base: File, claveVieja: File): File {
        val carpeta = File(context.noBackupFilesDir, CARPETA_CUARENTENA).apply { mkdirs() }
        val fecha = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val destino = File(carpeta, "$fecha.db")
        SUFIJOS.forEach { sufijo -> File(base.path + sufijo).renameTo(File(destino.path + sufijo)) }
        claveVieja.renameTo(File(destino.path + ".key"))
        File(destino.path + SUFIJO_AVISO).createNewFile()
        return destino
    }

    private fun borrarConSusFicheros(base: File) {
        SUFIJOS.forEach { File(base.path + it).delete() }
    }

    private fun claveEnBruto(clave: ByteArray): String =
        "x'" + clave.joinToString("") { "%02X".format(it) } + "'"
}
