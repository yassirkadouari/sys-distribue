package com.bft.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects performance and consensus metrics for monitoring and dashboard display.
 * Tracks throughput, latency, phase transitions, and byzantine activity.
 */
public class MetricsCollector {
    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    private final int nodeId;

    // Transaction metrics
    private final AtomicInteger totalTransactions = new AtomicInteger(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);
    private final List<TransactionMetric> transactionHistory = Collections.synchronizedList(new ArrayList<>());

    // Phase tracking
    private final ConcurrentHashMap<Integer, Map<String, Long>> phaseTimestamps = new ConcurrentHashMap<>();

    // Byzantine activity log
    private final List<ByzantineEvent> byzantineEvents = Collections.synchronizedList(new ArrayList<>());

    // Throughput calculation
    private final AtomicInteger windowTransactions = new AtomicInteger(0);
    private volatile long windowStartTime = System.currentTimeMillis();
    private volatile double currentThroughput = 0.0;

    // Message counters
    private final AtomicInteger messagesSent = new AtomicInteger(0);
    private final AtomicInteger messagesReceived = new AtomicInteger(0);

    // Start time
    private final long startTime = System.currentTimeMillis();

    public MetricsCollector(int nodeId) {
        this.nodeId = nodeId;

        // Throughput calculator thread
        Thread throughputCalc = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000);
                    long now = System.currentTimeMillis();
                    long elapsed = now - windowStartTime;
                    if (elapsed > 0) {
                        currentThroughput = (windowTransactions.getAndSet(0) * 1000.0) / elapsed;
                        windowStartTime = now;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "throughput-calc-" + nodeId);
        throughputCalc.setDaemon(true);
        throughputCalc.start();
    }

    /**
     * Record when a consensus phase starts for a sequence number.
     */
    public void recordPhaseStart(int seqNum, String phase) {
        phaseTimestamps.computeIfAbsent(seqNum, k -> new ConcurrentHashMap<>())
                       .put(phase, System.currentTimeMillis());
    }

    /**
     * Record a transaction execution with its latency.
     */
    public void recordTransactionExecuted(int seqNum, long latencyMs) {
        totalTransactions.incrementAndGet();
        totalLatencyMs.addAndGet(latencyMs);
        windowTransactions.incrementAndGet();

        TransactionMetric metric = new TransactionMetric(seqNum, latencyMs, System.currentTimeMillis());
        transactionHistory.add(metric);

        // Keep only last 1000 entries
        if (transactionHistory.size() > 1000) {
            transactionHistory.subList(0, transactionHistory.size() - 1000).clear();
        }
    }

    /**
     * Record byzantine activity from a node.
     */
    public void recordByzantineActivity(int suspectedNodeId, String activityType) {
        ByzantineEvent event = new ByzantineEvent(suspectedNodeId, activityType, System.currentTimeMillis());
        byzantineEvents.add(event);
        log.warn("⚠ Byzantine activity detected: node {} — {}", suspectedNodeId, activityType);
    }

    public void incrementMessagesSent() { messagesSent.incrementAndGet(); }
    public void incrementMessagesReceived() { messagesReceived.incrementAndGet(); }

    // ==================== METRICS SNAPSHOT ====================

    /**
     * Get a complete metrics snapshot as a Map (for JSON serialization).
     */
    public Map<String, Object> getSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();

        snapshot.put("nodeId", nodeId);
        snapshot.put("timestamp", System.currentTimeMillis());
        snapshot.put("uptimeSeconds", (System.currentTimeMillis() - startTime) / 1000);

        // Performance metrics
        Map<String, Object> performance = new LinkedHashMap<>();
        performance.put("totalTransactions", totalTransactions.get());
        performance.put("throughputTxPerSec", Math.round(currentThroughput * 100.0) / 100.0);
        performance.put("averageLatencyMs", totalTransactions.get() > 0
            ? totalLatencyMs.get() / totalTransactions.get() : 0);
        performance.put("messagesSent", messagesSent.get());
        performance.put("messagesReceived", messagesReceived.get());
        snapshot.put("performance", performance);

        // Recent transactions
        List<Map<String, Object>> recentTx = new ArrayList<>();
        int start = Math.max(0, transactionHistory.size() - 20);
        for (int i = start; i < transactionHistory.size(); i++) {
            TransactionMetric tm = transactionHistory.get(i);
            Map<String, Object> tx = new LinkedHashMap<>();
            tx.put("seqNum", tm.seqNum);
            tx.put("latencyMs", tm.latencyMs);
            tx.put("timestamp", tm.timestamp);
            recentTx.add(tx);
        }
        snapshot.put("recentTransactions", recentTx);

        // Byzantine events
        List<Map<String, Object>> events = new ArrayList<>();
        for (ByzantineEvent be : byzantineEvents) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("suspectedNode", be.suspectedNodeId);
            ev.put("type", be.activityType);
            ev.put("timestamp", be.timestamp);
            events.add(ev);
        }
        snapshot.put("byzantineEvents", events);

        // Phase timestamps for recent sequences
        Map<String, Object> phases = new LinkedHashMap<>();
        phaseTimestamps.entrySet().stream()
            .sorted(Map.Entry.<Integer, Map<String, Long>>comparingByKey().reversed())
            .limit(10)
            .forEach(e -> phases.put("seq_" + e.getKey(), e.getValue()));
        snapshot.put("consensusPhases", phases);

        return snapshot;
    }

    /**
     * Get transaction throughput history for chart rendering.
     */
    public List<Map<String, Object>> getLatencyHistory() {
        List<Map<String, Object>> history = new ArrayList<>();
        for (TransactionMetric tm : transactionHistory) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("seq", tm.seqNum);
            point.put("latency", tm.latencyMs);
            point.put("time", tm.timestamp);
            history.add(point);
        }
        return history;
    }

    // Inner records for metrics data
    public record TransactionMetric(int seqNum, long latencyMs, long timestamp) {}
    public record ByzantineEvent(int suspectedNodeId, String activityType, long timestamp) {}
}
