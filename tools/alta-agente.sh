#!/usr/bin/env bash
#
# Cierra a mano la credencial del agente (workflow 3) mientras AeriaOne no tenga
# CA ni canal. Hace de backend: comprueba la solicitud que genero la app, la
# firma y le devuelve el certificado.
#
#   Uso:  tools/alta-agente.sh <serial-adb>
#
# Antes hay que haber pasado por tools/alta-terminal.sh con el mismo terminal:
# la solicitud del agente va firmada por la clave del telefono, y aqui se
# comprueba esa firma contra el certificado que emitio aquel script.
#
# LA CA ES OTRA, y es a proposito. El documento de arquitectura separa el dominio
# de confianza "User Identity CA" del de "Device Identity CA" y pide perfiles y
# politicas distintos. Firmar las dos cosas con la misma clave seria justo la
# equivalencia que el modelo prohibe.
#
# La CA se crea la primera vez en tools/ca-pruebas/ y se reutiliza despues. Esa
# carpeta NO va a git: contiene claves privadas, aunque sean de juguete.

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

# --- CA de usuario, separada de la de dispositivo (una vez) ------------------

mkdir -p "$CA_DIR"
if [[ ! -f "$CA_DIR/user-ca.key" ]]; then
    echo "==> Creando CA de usuario de pruebas en $CA_DIR"
    openssl ecparam -genkey -name prime256v1 -out "$CA_DIR/user-ca.key" 2>/dev/null
    MSYS_NO_PATHCONV=1 openssl req -x509 -new -key "$CA_DIR/user-ca.key" -sha256 -days 365 \
        -subj "/O=AeriaOne/OU=Test/CN=AeriaOne User CA test" -out "$CA_DIR/user-ca.crt" 2>/dev/null
fi

# --- Paso 8: recoger la solicitud que genero la app --------------------------

echo "==> Sacando la solicitud del agente del terminal $SERIAL"
adb -s "$SERIAL" shell run-as "$PAQUETE" cat files/credential/user.csr.pem \
    | tr -d '\r' > "$TRABAJO/user.csr.pem"
adb -s "$SERIAL" shell run-as "$PAQUETE" cat files/credential/user.request.json \
    | tr -d '\r' > "$TRABAJO/user.request.json"

if ! grep -q "BEGIN CERTIFICATE REQUEST" "$TRABAJO/user.csr.pem"; then
    echo "No hay solicitud de agente en el terminal. Termina antes el alta desde la app." >&2
    exit 1
fi

# El segundo sed deshace el escapado de la barra: JSONObject escribe "\/" donde
# hay un "/", que es JSON legal pero rompe el base64 de la firma si se toma tal
# cual. Un parser de verdad lo desharia solo; este lector de andar por casa no.
campo() {
    sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$TRABAJO/user.request.json" \
        | sed 's|\\/|/|g'
}
USER_ID="$(campo userId)"
DEVICE_ID="$(campo deviceId)"
FIRMA_TERMINAL="$(campo deviceSignature)"

echo "==> Solicitud de $USER_ID desde el terminal $DEVICE_ID"

echo "==> Validando el CSR"
openssl req -in "$TRABAJO/user.csr.pem" -verify -noout -subject

# --- Paso 3 del catalogo: que el terminal es de confianza --------------------
#
# Esto es lo que el backend haria mirando su registro de dispositivos. Aqui se
# hace con criptografia: se comprueba que la firma que acompana a la solicitud
# la produjo la clave del telefono que esta CA dio de alta.

CERT_TERMINAL="$CA_DIR/dispositivos/$SERIAL.crt"
if [[ ! -f "$CERT_TERMINAL" ]]; then
    echo "No hay certificado de terminal para $SERIAL. Pasa antes tools/alta-terminal.sh." >&2
    exit 1
fi

echo "==> Comprobando la firma del terminal sobre la solicitud"
openssl req -in "$TRABAJO/user.csr.pem" -outform DER -out "$TRABAJO/user.csr.der"
printf '%s' "$FIRMA_TERMINAL" | openssl base64 -d -A -out "$TRABAJO/device.sig"
openssl x509 -in "$CERT_TERMINAL" -pubkey -noout > "$TRABAJO/device.pub"
openssl dgst -sha256 -verify "$TRABAJO/device.pub" \
    -signature "$TRABAJO/device.sig" "$TRABAJO/user.csr.der"

# --- Pasos 9 y 10: la CA de usuario valida y emite ---------------------------

echo "==> Firmando con la CA de usuario"
openssl x509 -req -in "$TRABAJO/user.csr.pem" \
    -CA "$CA_DIR/user-ca.crt" -CAkey "$CA_DIR/user-ca.key" -CAcreateserial \
    -days 30 -sha256 -out "$TRABAJO/user.crt" 2>/dev/null
openssl verify -CAfile "$CA_DIR/user-ca.crt" "$TRABAJO/user.crt"

# --- Pasos 11 a 15: devolver el certificado a la app -------------------------
# Igual que en el alta del terminal: el certificado y el reto del paso 14 van en
# el mismo intent, y el nonce lo genera este script.
RETOS_DIR="$CA_DIR/retos"
mkdir -p "$RETOS_DIR" "$CA_DIR/agentes"
openssl rand -out "$RETOS_DIR/posesion_agente.nonce" 32
CERT_AGENTE="$CA_DIR/agentes/$SERIAL.crt"
cp "$TRABAJO/user.crt" "$CERT_AGENTE"

echo "==> Instalando la credencial y emitiendo el reto del paso 14"
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell am force-stop "$PAQUETE"
adb -s "$SERIAL" shell am start -n "$PAQUETE/.MainActivity" \
    --es user_cert "$(openssl base64 -A -in "$TRABAJO/user.crt")" \
    --es challenge_purpose POSESION_AGENTE \
    --es challenge "$(openssl base64 -A -in "$RETOS_DIR/posesion_agente.nonce")" \
    --es challenge_issuer AeriaOne-challenge-service-test \
    --es challenge_ttl 300 > /dev/null

sleep 3
echo "==> Resultado (paso 14, prueba de posesion):"
adb -s "$SERIAL" logcat -d -s AeriaAlta | tail -4

echo "==> Verificando la respuesta al reto contra el certificado emitido"
"$(dirname "$0")/reto.sh" "$SERIAL" verificar POSESION_AGENTE "$CERT_AGENTE"
