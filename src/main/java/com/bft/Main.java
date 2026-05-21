package com.bft;

import com.bft.byzantine.*;
import com.bft.client.ClientSimulator;
import com.bft.config.NodeConfig;
import com.bft.consensus.PBFTEngine;
import com.bft.crypto.ECDSASigner;
import com.bft.crypto.ShamirThreshold;
import com.bft.metrics.MetricsCollector;
import com.bft.metrics.MetricsServer;
import com.bft.network.MessageHandler;
import com.bft.network.NetworkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * Main entry point for a Byzantine Consensus node.
 * 
 * Each node runs as an independent process with its own:
 * - ECDSA key pair for message signing
 * - TCP server for peer communication
 * - PBFT consensus engine
 * - Metrics HTTP server for dashboard
 * - Optional byzantine behavior
 * 
 * Configuration is via environment variables:
 *   NODE_ID, TOTAL_NODES, FAULT_TOLERANCE, NODE_PORT, METRICS_PORT,
 *   BYZANTINE_TYPE (none|silent|equivocation|replay), PEERS
 */
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("═══════════════════════════════════════════════════════");
        log.info("  Byzantine Consensus Résilient — PBFT Node Starting  ");
        log.info("═══════════════════════════════════════════════════════");

        try {
            // 1. Load configuration
            NodeConfig config = new NodeConfig();
            log.info("Configuration: {}", config);

            // 2. Initialize ECDSA signer
            ECDSASigner signer = new ECDSASigner(config.getNodeId());
            log.info("ECDSA key pair generated for node {}", config.getNodeId());

            // 3. Demonstrate Shamir threshold (on startup)
            demonstrateShamirThreshold(config);

            // 4. Initialize network manager
            NetworkManager network = new NetworkManager(config, signer);
            network.registerOwnKey();

            // 5. Initialize metrics
            MetricsCollector metrics = new MetricsCollector(config.getNodeId());

            // 6. Initialize PBFT engine
            PBFTEngine engine = new PBFTEngine(config, network, metrics);

            // 7. Set up byzantine behavior if configured
            if (config.isByzantine()) {
                ByzantineBehavior behavior = createByzantineBehavior(config.getByzantineType());
                engine.setByzantineBehavior(behavior);
                log.warn("⚠ BYZANTINE MODE ACTIVE: {} — {}", behavior.getType(), behavior.getDescription());
            }

            // 8. Set up message handler
            MessageHandler handler = new MessageHandler(config, engine);
            network.onMessage(handler::handle);

            // 9. Start network server
            network.startServer();

            // 10. Start metrics HTTP server
            MetricsServer metricsServer = new MetricsServer(config, metrics, engine);
            metricsServer.start();

            // 11. Wait for network stabilization then connect to peers
            Thread.sleep(2000);
            network.connectToPeers();

            // 12. Start PBFT engine
            engine.start();

            // 13. Start client simulator on the leader node
            // All nodes run the simulator but only the leader processes requests
            ClientSimulator clientSim = new ClientSimulator(engine, config.getNodeId());
            if (config.isLeader(0)) {
                clientSim.start(3000); // One transaction every 3 seconds
                log.info("📤 Client simulator started (this node is the initial leader)");
            }

            log.info("═══════════════════════════════════════════════════════");
            log.info("  Node {} FULLY OPERATIONAL", config.getNodeId());
            log.info("  Role: {}", config.isLeader(0) ? "LEADER" : "REPLICA");
            log.info("  Byzantine: {} ({})", config.isByzantine() ? "YES" : "NO", config.getByzantineType());
            log.info("  Metrics: http://localhost:{}/metrics", config.getMetricsPort());
            log.info("═══════════════════════════════════════════════════════");

            // Keep the node running
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down node {}...", config.getNodeId());
                engine.shutdown();
                metricsServer.stop();
                network.shutdown();
                clientSim.stop();
            }));

            // Block main thread
            Thread.currentThread().join();

        } catch (Exception e) {
            log.error("Fatal error: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * Create the appropriate byzantine behavior based on config.
     */
    private static ByzantineBehavior createByzantineBehavior(String type) {
        return switch (type.toLowerCase()) {
            case "silent" -> new SilentNode();
            case "equivocation" -> new EquivocationNode();
            case "replay" -> new ReplayNode();
            default -> throw new IllegalArgumentException("Unknown byzantine type: " + type);
        };
    }

    /**
     * Demonstrate Shamir's Secret Sharing on startup.
     * Splits a shared secret among nodes and shows reconstruction.
     */
    private static void demonstrateShamirThreshold(NodeConfig config) {
        log.info("--- Shamir Threshold Signature Demo ---");

        ShamirThreshold shamir = new ShamirThreshold(
            config.getFaultTolerance() + 1,  // threshold: f + 1
            config.getTotalNodes()            // total shares: n
        );

        // Generate a random shared secret
        BigInteger secret = new BigInteger(256, new SecureRandom());
        log.info("Original secret (first 8 hex): {}...",
            secret.toString(16).substring(0, Math.min(8, secret.toString(16).length())));

        // Split into shares
        var shares = shamir.split(secret);
        for (var share : shares) {
            log.info("Share {}: {}", share.x(), share);
        }

        // Reconstruct from threshold shares
        var subset = shares.subList(0, config.getFaultTolerance() + 1);
        BigInteger reconstructed = shamir.reconstruct(subset);

        boolean match = secret.equals(reconstructed);
        log.info("Reconstructed (first 8 hex): {}...",
            reconstructed.toString(16).substring(0, Math.min(8, reconstructed.toString(16).length())));
        log.info("Secret reconstruction: {} ✓", match ? "SUCCESS" : "FAILED ✗");
        log.info("--- End Shamir Demo ---");
    }
}
