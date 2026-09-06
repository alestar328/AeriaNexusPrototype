#!/usr/bin/env bash
#
# Alta de la bodycam (workflow 13) y reparto del ancla de confianza para el
# emparejamiento autenticado (workflow 31).
#
#   Uso:  tools/alta-bodycam.sh <serial-de-la-W1> <serial-del-telefono>
#
# Da identidad propia a la camara y deja a las dos puntas capaces de comprobarse
# entre si:
#
#   1. la W1 genera su par EC en el Keystore y su peticion de certificado;
#   2. la CA de dispositivos —LA MISMA que da de alta a los telefonos, como pide
#      el documento: "Device Identity CA ... Android/BWC/goggles"— la firma;
#   3. el certificado vuelve a la camara y se comprueba la posesion de la clave;
#   4. el ancla de dispositivos se instala en LAS DOS: la camara valida al telefono
#      y el telefono valida a la camara. Sin las dos, esto seria un login y no
#      autenticacion mutua;
#   5. la camara recibe ademas el ancla de USUARIO, que es otra distinta: la
#      necesita para comprobar la firma del agente que la ata a su nombre
#      (workflow 33). El documento separa los dos dominios de confianza, asi que
#      con una sola, revocar un agente y revocar un terminal serian lo mismo.
#
# La W1 tiene que estar conectada por adb. Si solo hay un dispositivo conectado,
# el segundo argumento se puede omitir y solo se dara de alta la camara.

set -euo pipefail

SERIAL_BWC="${1:-}"
SERIAL_TEL="${2:-}"
if [[ -z "$SERIAL_BWC" ]]; then
    echo "Falta el serial adb de la bodycam. Los conectados ahora:" >&2
    adb devices -l >&2
    exit 1
fi

PAQUETE_BWC="com.falconone.bodycamserver"
PAQUETE_TEL="com.delta.aeria_nexus_prototype"
CA_DIR="$(dirname "$0")/ca-pruebas"
TRABAJO="$(mktemp -d)"
trap 'rm -rf "$TRABAJO"' EXIT

if [[ ! -f "$CA_DIR/ca.key" ]]; then
    echo "No hay CA de dispositivos. Pasa antes tools/alta-terminal.sh con un telefono." >&2
    exit 1
fi

# --- Workflow 13, pasos 6 y 7: clave y peticion dentro de la camara ----------
#
# El reto de atestacion deberia venir del backend. Se emite aqui igual que en el
# telefono, para que la cadena que produzca la W1 no la elija ella.

RETOS_DIR="$CA_DIR/retos"
mkdir -p "$RETOS_DIR"
openssl rand -out "$RETOS_DIR/atestacion_bwc.nonce" 32

echo "==> Pidiendo a la bodycam que genere su identidad"
adb -s "$SERIAL_BWC" logcat -c
adb -s "$SERIAL_BWC" shell am force-stop "$PAQUETE_BWC"
adb -s "$SERIAL_BWC" shell am start -n "$PAQUETE_BWC/.MainActivity" \
    --es bwc_enroll 1 \
    --es bwc_challenge "$(openssl base64 -A -in "$RETOS_DIR/atestacion_bwc.nonce")" > /dev/null
sleep 4
adb -s "$SERIAL_BWC" logcat -d -s BwcAlta | tail -3

echo "==> Recogiendo la peticion"
adb -s "$SERIAL_BWC" shell run-as "$PAQUETE_BWC" cat files/identity/bwc.csr.pem \
    | tr -d '\r' > "$TRABAJO/bwc.csr.pem"
if ! grep -q "BEGIN CERTIFICATE REQUEST" "$TRABAJO/bwc.csr.pem"; then
    echo "La bodycam no dejo ninguna peticion. Mira el logcat de BwcAlta." >&2
    exit 1
fi
openssl req -in "$TRABAJO/bwc.csr.pem" -verify -noout -subject

# --- Pasos 10 y 11: la CA de dispositivos emite ------------------------------

echo "==> Firmando con la CA de dispositivos"
openssl x509 -req -in "$TRABAJO/bwc.csr.pem" \
    -CA "$CA_DIR/ca.crt" -CAkey "$CA_DIR/ca.key" -CAcreateserial \
    -days 30 -sha256 -out "$TRABAJO/bwc.crt" 2>/dev/null
openssl verify -CAfile "$CA_DIR/ca.crt" "$TRABAJO/bwc.crt"

mkdir -p "$CA_DIR/bodycams"
cp "$TRABAJO/bwc.crt" "$CA_DIR/bodycams/$SERIAL_BWC.crt"

# --- Pasos 12 y 13: devolver el certificado y el ancla -----------------------

echo "==> Instalando el certificado y el ancla en la bodycam"
adb -s "$SERIAL_BWC" logcat -c
adb -s "$SERIAL_BWC" shell am force-stop "$PAQUETE_BWC"
adb -s "$SERIAL_BWC" shell am start -n "$PAQUETE_BWC/.MainActivity" \
    --es bwc_cert "$(openssl base64 -A -in "$TRABAJO/bwc.crt")" \
    --es bwc_anchor "$(openssl base64 -A -in "$CA_DIR/ca.crt")" \
    --es bwc_user_anchor "$(openssl base64 -A -in "$CA_DIR/user-ca.crt")" > /dev/null
sleep 4
adb -s "$SERIAL_BWC" logcat -d -s BwcAlta BodycamIdentity | tail -5

# --- Workflow 31: el telefono tambien necesita el ancla ----------------------

if [[ -n "$SERIAL_TEL" ]]; then
    echo "==> Instalando el ancla en el telefono $SERIAL_TEL"
    adb -s "$SERIAL_TEL" logcat -c
    adb -s "$SERIAL_TEL" shell am force-stop "$PAQUETE_TEL"
    adb -s "$SERIAL_TEL" shell am start -n "$PAQUETE_TEL/.MainActivity" \
        --es peripheral_anchor "$(openssl base64 -A -in "$CA_DIR/ca.crt")" > /dev/null
    sleep 3
    adb -s "$SERIAL_TEL" logcat -d -s AeriaAlta | tail -2
else
    echo "==> Sin serial de telefono: recuerda instalarle el ancla o no podra"
    echo "    comprobar a la camara y el enlace quedara sin autenticar."
fi

echo "==> Alta de la bodycam terminada."
echo "    Conecta la bodycam desde la app y mira el logcat de BodycamRepository:"
echo "    deberia decir 'Enlace autenticado con BWC-xxxx'."
