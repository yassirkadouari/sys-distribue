#!/bin/bash
# ============================================================
# Generate self-signed TLS certificates for mutual authentication
# Creates a CA and per-node certificates signed by the CA
# ============================================================

set -e

CERT_DIR="./certs"
DAYS=365
KEY_SIZE=2048
CA_SUBJECT="/C=FR/ST=IDF/L=Paris/O=BFT-Consensus/CN=bft-ca"

echo "═══════════════════════════════════════════════"
echo "  Byzantine Consensus — TLS Certificate Generator"
echo "═══════════════════════════════════════════════"

# Create directory
mkdir -p "$CERT_DIR"

# 1. Generate CA key and certificate
echo "📜 Generating CA certificate..."
openssl genrsa -out "$CERT_DIR/ca-key.pem" $KEY_SIZE 2>/dev/null
openssl req -new -x509 -key "$CERT_DIR/ca-key.pem" \
    -out "$CERT_DIR/ca-cert.pem" \
    -days $DAYS -subj "$CA_SUBJECT" 2>/dev/null

echo "✓ CA certificate generated"

# 2. Generate per-node certificates
TOTAL_NODES=${1:-4}

for i in $(seq 0 $((TOTAL_NODES - 1))); do
    NODE_SUBJECT="/C=FR/ST=IDF/L=Paris/O=BFT-Consensus/CN=node${i}"
    
    echo "🔑 Generating certificate for Node $i..."
    
    # Generate node key
    openssl genrsa -out "$CERT_DIR/node${i}-key.pem" $KEY_SIZE 2>/dev/null
    
    # Generate CSR
    openssl req -new -key "$CERT_DIR/node${i}-key.pem" \
        -out "$CERT_DIR/node${i}.csr" \
        -subj "$NODE_SUBJECT" 2>/dev/null
    
    # Sign with CA
    openssl x509 -req -in "$CERT_DIR/node${i}.csr" \
        -CA "$CERT_DIR/ca-cert.pem" -CAkey "$CERT_DIR/ca-key.pem" \
        -CAcreateserial -out "$CERT_DIR/node${i}-cert.pem" \
        -days $DAYS 2>/dev/null
    
    # Create Java keystore (PKCS12)
    openssl pkcs12 -export -in "$CERT_DIR/node${i}-cert.pem" \
        -inkey "$CERT_DIR/node${i}-key.pem" \
        -certfile "$CERT_DIR/ca-cert.pem" \
        -out "$CERT_DIR/node${i}-keystore.p12" \
        -name "node${i}" -passout pass:changeit 2>/dev/null
    
    # Clean up CSR
    rm -f "$CERT_DIR/node${i}.csr"
    
    echo "  ✓ Node $i: key, cert, keystore generated"
done

# Create truststore with CA cert
echo "🔐 Creating truststore..."
keytool -import -file "$CERT_DIR/ca-cert.pem" \
    -alias ca -keystore "$CERT_DIR/truststore.p12" \
    -storetype PKCS12 -storepass changeit -noprompt 2>/dev/null

echo ""
echo "═══════════════════════════════════════════════"
echo "  ✅ All certificates generated in $CERT_DIR/"
echo "  Nodes: $TOTAL_NODES"
echo "  Validity: $DAYS days"
echo "═══════════════════════════════════════════════"
echo ""
echo "Files generated:"
ls -la "$CERT_DIR/"
