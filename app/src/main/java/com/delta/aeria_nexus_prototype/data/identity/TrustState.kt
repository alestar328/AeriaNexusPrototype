package com.delta.aeria_nexus_prototype.data.identity

/**
 * Estados por los que pasa la app antes de dejar trabajar al agente.
 *
 * Hasta ahora la app arrancaba directamente operativa: instalar era estar dentro.
 * El modelo IAM de AeriaOne invierte esa premisa (Seguridad-Claves-Bodycam.md §8.2):
 * una copia del APK puede instalarse, pero no puede *activarse* ni llegar a
 * produccion sin dispositivo dado de alta, instancia activa, release aprobada y
 * vinculos usuario-dispositivo validos.
 *
 * Quien decide el estado es el backend, no el telefono. Mientras no exista ese
 * backend, [IdentityRepository] lo simula; la maquina de estados y las pantallas
 * ya son las definitivas.
 */
enum class TrustState {

    /** Recien instalada: no hay Device ID, ni instancia, ni claves. Workflows 12 y 22-23. */
    NOT_PROVISIONED,

    /** Alta en curso: generando claves, pidiendo certificado o esperando aprobacion. */
    ENROLLING,

    /** Provisionada y en reposo. Pide el PIN para autorizar el uso de la clave (workflow 27). */
    LOCKED,

    /** Sesion abierta y token vigente. Es el unico estado en el que se ve la app de siempre. */
    ACTIVE,

    /**
     * El token caduco por tiempo o el backend cerro la sesion (workflow 30).
     * Se vuelve a pedir el PIN, pero el mensaje es distinto: no es un arranque
     * en frio, es una jornada interrumpida.
     */
    SESSION_EXPIRED,

    /**
     * Sin cobertura, operando con el permiso firmado que se descargo estando en
     * linea (§12). Tiene caducidad propia y prohibe funciones: administrar,
     * exportar, borrar y cualquier cambio de confianza.
     */
    OFFLINE_GRANTED,

    /** Un interruptor de corte del §13 dejo el terminal fuera de servicio. Ver [TrustBlockReason]. */
    BLOCKED,
}

/**
 * Motivo por el que el terminal queda fuera de servicio.
 *
 * Son los interruptores de corte del §13 que apagan la app **entera**. Los otros
 * tres del documento no llegan aqui a proposito:
 *
 *   - retirada de un permiso (`video.upload`) -> no apaga la app, quita una funcion;
 *   - revocacion de un periferico (BWC-0042) -> deja la bodycam inservible, no el telefono;
 *   - cierre de una sesion concreta -> devuelve a [TrustState.SESSION_EXPIRED], no bloquea.
 *
 * Cada motivo lleva escrito lo que lee el agente. Es deliberado: un mensaje
 * generico de "acceso denegado" a las tres de la manana obliga a llamar a alguien
 * para averiguar que pasa, y el que llama esta en la calle.
 */
enum class TrustBlockReason(
    /** Titulo corto, en mayusculas, que se lee de un vistazo. */
    val title: String,
    /** Que ha pasado, en lenguaje operativo y sin jerga de PKI. */
    val message: String,
    /** Que tiene que hacer el agente ahora. Siempre hay una salida escrita. */
    val nextStep: String,
    /** Si reintentar puede arreglarlo solo (recuperar cobertura); si no, hace falta un tercero. */
    val canRetry: Boolean = false,
) {

    USER_DISABLED(
        title = "OFFICER ACCOUNT DISABLED",
        message = "Your AeriaOne account is no longer active. This is not a fault of the phone.",
        nextStep = "Contact your supervisor or the QPD administrator.",
    ),

    DEVICE_REVOKED(
        title = "DEVICE REVOKED",
        message = "This phone has been withdrawn from service by AeriaOne. It can no longer " +
            "authenticate, pair with a body camera or upload evidence.",
        nextStep = "Contact your supervisor. The phone must be enrolled again before duty use.",
    ),

    CERTIFICATE_REVOKED(
        title = "CREDENTIAL REVOKED",
        message = "The certificate that identifies you on this phone is no longer valid.",
        nextStep = "Contact your supervisor to have a new credential issued.",
    ),

    APP_INSTANCE_DISABLED(
        title = "INSTALLATION DISABLED",
        message = "This particular installation of Aeria Nexus has been disabled. Other phones " +
            "of the unit are not affected.",
        nextStep = "Reinstall from the official channel and enroll again, or contact your supervisor.",
    ),

    RELEASE_REVOKED(
        title = "APP VERSION WITHDRAWN",
        message = "This version of Aeria Nexus is no longer approved for duty use.",
        nextStep = "Install the current version from the official channel.",
    ),

    TENANT_SUSPENDED(
        title = "SERVICE SUSPENDED",
        message = "AeriaOne operations are suspended for your department. This affects every " +
            "officer, not only this phone.",
        nextStep = "Wait for instructions from your command. Evidence already captured is kept.",
    ),

    /**
     * §12: el permiso sin conexion caduca "fail safely". No es un castigo ni una
     * perdida de datos, y el mensaje tiene que dejarlo claro o el agente creera
     * que ha perdido lo que grabo.
     */
    OFFLINE_WINDOW_EXPIRED(
        title = "OFFLINE PERIOD ENDED",
        message = "The phone has been out of coverage longer than allowed. Everything you " +
            "captured is safe and encrypted on this device, and will be uploaded on reconnection.",
        nextStep = "Move to an area with coverage and try again.",
        canRetry = true,
    ),
}
