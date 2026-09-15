package com.delta.aeria_nexus_prototype.data.audit

/** Resultado de recorrer el diario entero. */
data class IntegridadDelDiario(
    val eventos: Int,
    /** Numero de orden del primer evento que no cuadra; null si la cadena esta intacta. */
    val rotaEn: Long? = null,
    val motivo: String? = null,
) {
    val intacta: Boolean get() = rotaEn == null
}

/**
 * Formato y verificacion del diario de auditoria (workflow 61). No toca Android, para
 * poder probarlo en JVM: la clave la pone quien la construye.
 *
 * Cada linea es `orden TAB mac_anterior TAB json TAB mac`, y la mac se calcula sobre
 * las tres primeras partes. Como cada mac arrastra la anterior, **cambiar un evento,
 * insertar uno, borrar uno de en medio o reordenarlos rompe la cadena desde ese punto**.
 * Rehacerla exige la clave, que en el telefono vive en el Keystore y no sale.
 *
 * **Lo que NO detecta, dicho claro:** que alguien corte el final del fichero. Lo que
 * queda sigue siendo una cadena valida. Eso solo se ve comparando con un testigo de
 * fuera, que es el backend cuando reciba el diario (la mitad servidor del 61).
 *
 * Se usa TAB como separador y no un JSON con la mac dentro para no depender de que un
 * serializador respete el orden de las claves: se firma el texto tal cual se escribe.
 * Un TAB nunca aparece dentro del json porque [JsonPlano] lo escapa.
 */
class CadenaDeAuditoria(private val mac: (ByteArray) -> ByteArray) {

    /** Compone la linea del evento numero [orden], encadenada a [macAnterior]. */
    fun linea(orden: Long, macAnterior: String, json: String): String {
        val cuerpo = "$orden$SEP$macAnterior$SEP$json"
        return "$cuerpo$SEP${hex(mac(cuerpo.toByteArray(Charsets.UTF_8)))}"
    }

    /** Recorre todas las lineas en orden y se para en la primera que no cuadra. */
    fun verificar(lineas: List<String>): IntegridadDelDiario {
        var ordenEsperado = 1L
        var macEsperada = GENESIS
        lineas.forEachIndexed { indice, linea ->
            val partes = linea.split(SEP)
            if (partes.size != 4) return rota(indice, ordenEsperado, "malformed line")

            val orden = partes[0].toLongOrNull()
            if (orden != ordenEsperado) return rota(indice, ordenEsperado, "out of sequence")
            if (partes[1] != macEsperada) return rota(indice, ordenEsperado, "does not chain to the previous event")

            val cuerpo = linea.substring(0, linea.lastIndexOf(SEP))
            val calculada = hex(mac(cuerpo.toByteArray(Charsets.UTF_8)))
            if (!iguales(calculada, partes[3])) return rota(indice, ordenEsperado, "content was modified")

            macEsperada = partes[3]
            ordenEsperado++
        }
        return IntegridadDelDiario(eventos = lineas.size)
    }

    private fun rota(indice: Int, orden: Long, motivo: String) =
        IntegridadDelDiario(eventos = indice, rotaEn = orden, motivo = motivo)

    /** Comparacion en tiempo constante: no dar pistas de cuantos caracteres acertaron. */
    private fun iguales(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diferencia = 0
        for (i in a.indices) diferencia = diferencia or (a[i].code xor b[i].code)
        return diferencia == 0
    }

    companion object {
        const val SEP = "\t"

        /** Lo que encadena el primer evento: no hay anterior. */
        val GENESIS = "0".repeat(64)

        /** La mac de una linea ya escrita, para encadenar la siguiente. */
        fun macDe(linea: String): String? = linea.split(SEP).takeIf { it.size == 4 }?.get(3)

        fun ordenDe(linea: String): Long? = linea.split(SEP).firstOrNull()?.toLongOrNull()

        /** El json de una linea, para mostrarla. */
        fun jsonDe(linea: String): String? = linea.split(SEP).takeIf { it.size == 4 }?.get(2)

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * JSON de un solo nivel compuesto a mano, con el orden de claves en que se escriben.
 * Basta para un evento de auditoria y no necesita org.json, que no existe en las
 * pruebas JVM.
 */
object JsonPlano {

    fun objeto(campos: List<Pair<String, String?>>): String =
        campos.joinToString(separator = ",", prefix = "{", postfix = "}") { (clave, valor) ->
            "${texto(clave)}:${valor?.let(::texto) ?: "null"}"
        }

    fun texto(valor: String): String = buildString {
        append('"')
        for (c in valor) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
