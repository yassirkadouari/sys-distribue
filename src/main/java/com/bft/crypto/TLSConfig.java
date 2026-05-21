package com.bft.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.*;
import java.security.*;
import java.security.cert.X509Certificate;

/**
 * TLS mutual authentication configuration.
 * Each node uses its own certificate and verifies peers' certificates.
 * For simulation, uses a simple trust-all approach (educational).
 */
public class TLSConfig {
    private static final Logger log = LoggerFactory.getLogger(TLSConfig.class);

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Create an SSLContext configured for mutual TLS.
     * In production, use proper keystores. For demo, uses trust-all.
     */
    public static SSLContext createSSLContext(int nodeId) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLSv1.3");

            // For demo purposes, use trust-all manager
            // In production, load actual keystores with mutual auth
            TrustManager[] trustManagers = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };

            sslContext.init(null, trustManagers, new SecureRandom());
            log.info("Node {} TLS 1.3 context initialized", nodeId);
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SSL context for node " + nodeId, e);
        }
    }

    /**
     * Create an SSLServerSocketFactory for accepting TLS connections.
     */
    public static SSLServerSocketFactory getServerSocketFactory(int nodeId) {
        return createSSLContext(nodeId).getServerSocketFactory();
    }

    /**
     * Create an SSLSocketFactory for outbound TLS connections.
     */
    public static SSLSocketFactory getSocketFactory(int nodeId) {
        return createSSLContext(nodeId).getSocketFactory();
    }
}
