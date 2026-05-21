package com.bft.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * ECDSA digital signature utility using BouncyCastle.
 * Each node generates its own key pair and can sign/verify messages.
 */
public class ECDSASigner {
    private static final Logger log = LoggerFactory.getLogger(ECDSASigner.class);
    private static final String ALGORITHM = "SHA256withECDSA";
    private static final String CURVE = "secp256r1";

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private final KeyPair keyPair;
    private final int nodeId;

    public ECDSASigner(int nodeId) {
        this.nodeId = nodeId;
        this.keyPair = generateKeyPair();
        log.info("Node {} ECDSA key pair generated (curve: {})", nodeId, CURVE);
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
            kpg.initialize(new ECGenParameterSpec(CURVE), new SecureRandom());
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate ECDSA key pair", e);
        }
    }

    /**
     * Sign a message string and return Base64-encoded signature.
     */
    public String sign(String message) {
        try {
            Signature sig = Signature.getInstance(ALGORITHM, "BC");
            sig.initSign(keyPair.getPrivate());
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            byte[] signature = sig.sign();
            return Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            log.error("Signing failed for node {}: {}", nodeId, e.getMessage());
            throw new RuntimeException("Signing failed", e);
        }
    }

    /**
     * Verify a signature against a message using a given public key.
     */
    public static boolean verify(String message, String signatureBase64, PublicKey publicKey) {
        try {
            Signature sig = Signature.getInstance(ALGORITHM, "BC");
            sig.initVerify(publicKey);
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
            return sig.verify(signatureBytes);
        } catch (Exception e) {
            log.error("Verification failed: {}", e.getMessage());
            return false;
        }
    }

    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    public int getNodeId() {
        return nodeId;
    }
}
