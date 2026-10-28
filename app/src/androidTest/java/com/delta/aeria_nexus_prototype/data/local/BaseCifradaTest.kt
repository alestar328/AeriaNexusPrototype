package com.delta.aeria_nexus_prototype.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Migracion de una base en claro de una version anterior a una cifrada. */
@RunWith(AndroidJUnit4::class)
class BaseCifradaTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nombre = "prueba_cifrado.db"
    private val base: File get() = context.getDatabasePath(nombre)

    @Before
    @After
    fun limpiar() {
        context.deleteDatabase(nombre)
        File(context.noBackupFilesDir, "$nombre.key").delete()
    }

    @Test
    fun cifraUnaBaseEnClaroSinPerderFilas() {
        crearBaseEnClaro()

        val clave = BaseCifrada.preparar(context, nombre) {}

        val cabecera = base.readBytes().copyOf(15).toString(Charsets.US_ASCII)
        assertNotEquals("SQLite format 3", cabecera)
        val cifrada = SQLiteDatabase.openDatabase(base.path, clave, null, SQLiteDatabase.OPEN_READWRITE, null, null)
        try {
            cifrada.rawQuery("SELECT narrative FROM incidents").use {
                assertTrue(it.moveToFirst())
                assertEquals("robo en la calle 5", it.getString(0))
            }
            assertEquals(3, cifrada.version)
        } finally {
            cifrada.close()
        }
    }

    @Test
    fun laSegundaVezDevuelveLaMismaClaveYNoTocaLaBase() {
        crearBaseEnClaro()
        val primera = BaseCifrada.preparar(context, nombre) {}
        val tamano = base.length()

        val segunda = BaseCifrada.preparar(context, nombre) {}

        assertTrue(primera.contentEquals(segunda))
        assertEquals(tamano, base.length())
    }

    @Test
    fun sinSuClaveLaBaseSeApartaYNoSeBorra() {
        crearBaseEnClaro()
        BaseCifrada.preparar(context, nombre) {}
        val tamano = base.length()
        File(context.noBackupFilesDir, "$nombre.key").delete()

        var apartada: File? = null
        BaseCifrada.preparar(context, nombre) { apartada = it }

        val enCuarentena = requireNotNull(apartada)
        try {
            assertTrue(enCuarentena.isFile)
            assertEquals(tamano, enCuarentena.length())
            assertFalse(base.exists())
            assertTrue(BaseCifrada.hayAvisoPendiente(context))
        } finally {
            listOf("", "-wal", "-shm", "-journal", ".key", ".aviso")
                .forEach { File(enCuarentena.path + it).delete() }
        }
    }

    /** Como la dejaba la version sin cifrar: en WAL y con una fila sin volcar. */
    private fun crearBaseEnClaro() {
        base.parentFile?.mkdirs()
        val enClaro = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(base, null)
        enClaro.enableWriteAheadLogging()
        enClaro.execSQL("CREATE TABLE incidents (id TEXT PRIMARY KEY, narrative TEXT)")
        enClaro.execSQL("INSERT INTO incidents VALUES ('INC-1', 'robo en la calle 5')")
        enClaro.version = 3
        enClaro.close()
    }
}
