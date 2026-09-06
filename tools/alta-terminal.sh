#!/usr/bin/env bash
#
# Cierra a mano el alta de un terminal (workflow 12) mientras AeriaOne no tenga
# CA ni canal de alta. Hace de backend: firma el CSR que genero la app y le
# devuelve el certificado.
#
# NO es la CA de pruebas del plan de septiembre, que es una tarea aparte con
# servicio de retos y validacion del contexto. Esto es lo minimo para poder
# ensenar el circuito completo el dia de la auditoria.
#
#   Uso:  tools/alta-terminal.sh <serial-adb>
#
# La CA se crea la primera vez en tools/ca-pruebas/ y se reutiliza despues. Esa
# carpeta NO va a git: contiene una clave privada, aunque sea de juguete.

set -euo pipefail

SERIAL="${1:-}"
if [[ -z "$SERIAL" ]]; then
    echo "Falta el serial adb. Los conectados ahora:" >&2
    adb devices -l >&2
    exit 1
fi

PAQUETE="com.delta.aeria_nexus_prototype"
CA_DIR="$(dirname "$0")/ca-pruebas"
TRABAJO="$(mktemp -d)"
trap 'rm -rf "$TRABAJO"' EXIT

# --- CA de pruebas (una vez) -------------------------------------------------

mkdir -p "$CA_DIR"
if [[ ! -f "$CA_DIR/ca.key" ]]; then
    echo "==> Creando CA de pruebas en $CA_DIR"
    openssl ecparam -genkey -name prime256v1 -out "$CA_DIR/ca.key" 2>/dev/null
    MSYS_NO_PATHCONV=1 openssl req -x509 -new -key "$CA_DIR/ca.key" -sha256 -days 365 \
        -subj "/O=AeriaOne/OU=Test/CN=AeriaOne Device CA test" -out "$CA_DIR/ca.crt" 2>/dev/null
fi

# --- Paso 12: recoger la peticion que genero la app --------------------------

echo "==> Sacando el CSR del terminal $SERIAL"
adb -s "$SERIAL" shell run-as "$PAQUETE" cat files/enrollment/device.csr.pem \
    | tr -d '\r' > "$TRABAJO/device.csr.pem"

if ! grep -q "BEGIN CERTIFICATE REQUEST" "$TRABAJO/device.csr.pem"; then
    echo "No hay CSR en el terminal. Haz primero el alta desde la app." >&2
    exit 1
fi

echo "==> Validando el CSR"
openssl req -in "$TRABAJO/device.csr.pem" -verify -noout -subject

# --- Pasos 13 y 14: la CA valida y emite -------------------------------------

echo "==> Firmando"
openssl x509 -req -in "$TRABAJO/device.csr.pem" \
    -CA "$CA_DIR/ca.crt" -CAkey "$CA_DIR/ca.key" -CAcreateserial \
    -days 30 -sha256 -out "$TRABAJO/device.crt" 2>/dev/null
openssl verify -CAfile "$CA_DIR/ca.crt" "$TRABAJO/device.crt"

# El certificado emitido se guarda porque alta-agente.sh lo necesita: es con lo
# que se comprueba que la solicitud del agente salio de un terminal dado de alta.
mkdir -p "$CA_DIR/dispositivos"
CERT_GUARDADO="$CA_DIR/dispositivos/$SERIAL.crt"
cp "$TRABAJO/device.crt" "$CERT_GUARDADO"

# --- Pasos 15 a 18: devolver el certificado a la app -------------------------
# El certificado y el reto de la prueba de posesion viajan en el mismo intent. El
# nonce lo genera este script: el telefono no puede predecirlo, y eso es lo que
# convierte el paso 16 en prueba de frescura y no solo de correspondencia.
RETOS_DIR="$CA_DIR/retos"
mkdir -p "$RETOS_DIR"
openssl rand -out "$RETOS_DIR/posesion_terminal.nonce" 32

echo "==> Instalando el certificado y emitiendo el reto del paso 16"
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell am force-stop "$PAQUETE"
adb -s "$SERIAL" shell am start -n "$PAQUETE/.MainActivity" \
    --es device_cert "$(openssl base64 -A -in "$TRABAJO/device.crt")" \
    --es challenge_purpose POSESION_TERMINAL \
    --es challenge "$(openssl base64 -A -in "$RETOS_DIR/posesion_terminal.nonce")" \
    --es challenge_issuer AeriaOne-challenge-service-test \
    --es challenge_ttl 300 > /dev/null

sleep 3
echo "==> Resultado (paso 16, prueba de posesion):"
adb -s "$SERIAL" logcat -d -s AeriaAlta | tail -4

echo "==> Verificando la respuesta al reto contra el certificado emitido"
"$(dirname "$0")/reto.sh" "$SERIAL" verificar POSESION_TERMINAL "$CERT_GUARDADO"
