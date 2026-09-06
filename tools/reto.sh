#!/usr/bin/env bash
#
# Servicio de retos del backend simulado.
#
#   Uso:  tools/reto.sh <serial-adb> emitir   ATESTACION_TERMINAL|POSESION_TERMINAL|POSESION_AGENTE|LOGIN [segundos]
#         tools/reto.sh <serial-adb> verificar ATESTACION_TERMINAL|POSESION_TERMINAL|POSESION_AGENTE|LOGIN <certificado.pem>
#
# POR QUE EXISTE. Hasta ahora las pruebas de posesion se firmaban sobre un valor
# que se inventaba el propio telefono. Eso acredita que las dos mitades del par se
# corresponden, pero no que quien responde tenga la clave AHORA: una respuesta
# capturada vale para siempre. El documento lo pide explicito en IAM-04.
#
# El nonce lo genera ESTE script y el telefono no puede predecirlo, asi que la
# firma que devuelve si prueba frescura. El transporte es de mentira —un intent en
# vez de una llamada autenticada— pero la propiedad criptografica es de verdad.
#
# El reto se guarda para poder comprobar despues que la respuesta contesta al reto
# que emitimos y no a otro. Esa carpeta NO va a git.

set -euo pipefail

SERIAL="${1:-}"
ACCION="${2:-}"
PROPOSITO="${3:-}"

if [[ -z "$SERIAL" || -z "$ACCION" || -z "$PROPOSITO" ]]; then
    sed -n '3,8p' "$0" >&2
    exit 1
fi

PAQUETE="com.delta.aeria_nexus_prototype"
RETOS_DIR="$(dirname "$0")/ca-pruebas/retos"
MINUSCULA="$(echo "$PROPOSITO" | tr '[:upper:]' '[:lower:]')"
mkdir -p "$RETOS_DIR"

case "$ACCION" in

emitir)
    VALIDEZ="${4:-300}"
    echo "==> Emitiendo reto de $PROPOSITO, valido $VALIDEZ s"
    # 32 bytes de /dev/urandom: el telefono no puede adivinarlos, que es lo unico
    # que este fichero tiene que garantizar.
    openssl rand -out "$RETOS_DIR/$MINUSCULA.nonce" 32
    adb -s "$SERIAL" shell am force-stop "$PAQUETE"
    adb -s "$SERIAL" shell am start -n "$PAQUETE/.MainActivity" \
        --es challenge_purpose "$PROPOSITO" \
        --es challenge "$(openssl base64 -A -in "$RETOS_DIR/$MINUSCULA.nonce")" \
        --es challenge_issuer AeriaOne-challenge-service-test \
        --es challenge_ttl "$VALIDEZ" > /dev/null
    sleep 2
    adb -s "$SERIAL" logcat -d -s AeriaReto | tail -2
    ;;

verificar)
    CERT="${4:-}"
    if [[ ! -f "$CERT" ]]; then
        echo "Falta el certificado con el que verificar la respuesta." >&2
        exit 1
    fi

    TRABAJO="$(mktemp -d)"
    trap 'rm -rf "$TRABAJO"' EXIT

    echo "==> Recogiendo la respuesta del terminal"
    adb -s "$SERIAL" shell run-as "$PAQUETE" \
        cat "files/challenges/$MINUSCULA.response.json" | tr -d '\r' > "$TRABAJO/respuesta.json"

    campo() {
        sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$TRABAJO/respuesta.json" \
            | sed 's|\\/|/|g'
    }
    SUJETO="$(campo subject)"
    printf '%s' "$(campo nonce)"     | openssl base64 -d -A -out "$TRABAJO/nonce.devuelto"
    printf '%s' "$(campo signature)" | openssl base64 -d -A -out "$TRABAJO/firma.bin"

    echo "==> Respuesta de: $SUJETO"

    # Primero: que contesta AL RETO QUE EMITIMOS. Sin esta comprobacion, una firma
    # valida sobre cualquier otro valor pasaria por buena, que es justo el ataque
    # que el reto viene a impedir.
    if ! cmp -s "$RETOS_DIR/$MINUSCULA.nonce" "$TRABAJO/nonce.devuelto"; then
        echo "RECHAZADO: la respuesta no contesta al reto emitido" >&2
        exit 1
    fi
    echo "==> El nonce devuelto es el que emitimos"

    echo -n "==> Firma sobre el reto: "
    openssl x509 -in "$CERT" -pubkey -noout > "$TRABAJO/publica.pem"
    openssl dgst -sha256 -verify "$TRABAJO/publica.pem" \
        -signature "$TRABAJO/firma.bin" "$TRABAJO/nonce.devuelto"

    # Un reto es de un solo uso tambien por este lado: si se quedase guardado, una
    # respuesta repetida volveria a pasar la comprobacion de arriba.
    rm -f "$RETOS_DIR/$MINUSCULA.nonce"
    echo "==> Reto consumido. Frescura acreditada (IAM-04)."
    ;;

*)
    echo "Accion desconocida: $ACCION" >&2
    exit 1
    ;;
esac
