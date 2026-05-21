package com.bft.client;

import com.bft.consensus.PBFTEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simulates client transaction requests to the PBFT cluster.
 * Generates realistic financial transfer transactions at a configurable rate.
 */
public class ClientSimulator {
    private static final Logger log = LoggerFactory.getLogger(ClientSimulator.class);

    private final PBFTEngine engine;
    private final ScheduledExecutorService scheduler;
    private final Random random = new Random();
    private final int nodeId;

    // Simulated accounts
    private static final String[] ACCOUNTS = {
        "Alice", "Bob", "Charlie", "Diana", "Eve", "Frank", "Grace", "Heidi"
    };

    private int txCount = 0;

    public ClientSimulator(PBFTEngine engine, int nodeId) {
        this.engine = engine;
        this.nodeId = nodeId;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "client-sim-" + nodeId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start generating transactions at the given rate.
     * Only the leader node should run the simulator actively.
     */
    public void start(long intervalMs) {
        // Initial delay to let the cluster stabilize
        scheduler.scheduleAtFixedRate(this::generateTransaction, 5000, intervalMs, TimeUnit.MILLISECONDS);
        log.info("Client simulator started on node {} (interval={}ms)", nodeId, intervalMs);
    }

    /**
     * Generate and submit a random financial transaction.
     */
    private void generateTransaction() {
        try {
            String from = ACCOUNTS[random.nextInt(ACCOUNTS.length)];
            String to;
            do {
                to = ACCOUNTS[random.nextInt(ACCOUNTS.length)];
            } while (to.equals(from));

            int amount = 10 + random.nextInt(990);
            String txId = UUID.randomUUID().toString().substring(0, 8);
            txCount++;

            String payload = String.format(
                "{\"txId\":\"%s\",\"type\":\"transfer\",\"from\":\"%s\",\"to\":\"%s\",\"amount\":%d,\"txNum\":%d}",
                txId, from, to, amount, txCount
            );

            engine.submitTransaction(payload);
            log.info("📤 Client submitted tx #{}: {} → {} ({}$)", txCount, from, to, amount);

        } catch (Exception e) {
            log.error("Error generating transaction: {}", e.getMessage());
        }
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    public int getTxCount() { return txCount; }
}
