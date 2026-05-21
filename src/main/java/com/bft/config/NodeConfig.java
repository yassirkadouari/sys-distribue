package com.bft.config;

import java.util.*;

/**
 * Node configuration loaded from environment variables.
 * Supports dynamic cluster configuration for Docker Compose deployment.
 */
public class NodeConfig {

    private final int nodeId;
    private final int totalNodes;
    private final int faultTolerance;
    private final int port;
    private final int metricsPort;
    private final String byzantineType; // "none", "silent", "equivocation", "replay"
    private final Map<Integer, String> peerAddresses; // nodeId -> "host:port"
    private final String host;

    public NodeConfig() {
        this.nodeId = getEnvInt("NODE_ID", 0);
        this.totalNodes = getEnvInt("TOTAL_NODES", 4);
        this.faultTolerance = getEnvInt("FAULT_TOLERANCE", 1);
        this.port = getEnvInt("NODE_PORT", 5000 + nodeId);
        this.metricsPort = getEnvInt("METRICS_PORT", 8080);
        this.byzantineType = getEnvString("BYZANTINE_TYPE", "none");
        this.host = getEnvString("NODE_HOST", "0.0.0.0");
        this.peerAddresses = parsePeers(getEnvString("PEERS", generateDefaultPeers()));

        // Validate 3f + 1 rule
        if (totalNodes < 3 * faultTolerance + 1) {
            throw new IllegalArgumentException(
                String.format("Need at least %d nodes for f=%d (got %d). Rule: n >= 3f + 1",
                    3 * faultTolerance + 1, faultTolerance, totalNodes));
        }
    }

    private String generateDefaultPeers() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < totalNodes; i++) {
            if (i > 0) sb.append(",");
            sb.append(i).append("=localhost:").append(5000 + i);
        }
        return sb.toString();
    }

    private Map<Integer, String> parsePeers(String peersStr) {
        Map<Integer, String> peers = new HashMap<>();
        if (peersStr == null || peersStr.isEmpty()) return peers;
        for (String entry : peersStr.split(",")) {
            String[] parts = entry.trim().split("=");
            if (parts.length == 2) {
                peers.put(Integer.parseInt(parts[0].trim()), parts[1].trim());
            }
        }
        return peers;
    }

    public int getNodeId() { return nodeId; }
    public int getTotalNodes() { return totalNodes; }
    public int getFaultTolerance() { return faultTolerance; }
    public int getPort() { return port; }
    public int getMetricsPort() { return metricsPort; }
    public String getByzantineType() { return byzantineType; }
    public Map<Integer, String> getPeerAddresses() { return peerAddresses; }
    public String getHost() { return host; }

    /** Quorum size needed for prepare phase: 2f */
    public int getPrepareQuorum() { return 2 * faultTolerance; }

    /** Quorum size needed for commit phase: 2f + 1 */
    public int getCommitQuorum() { return 2 * faultTolerance + 1; }

    /** Check if this node is the leader for a given view */
    public boolean isLeader(int view) { return (view % totalNodes) == nodeId; }

    /** Get the leader node ID for a given view */
    public int getLeaderId(int view) { return view % totalNodes; }

    public boolean isByzantine() { return !"none".equalsIgnoreCase(byzantineType); }

    private static int getEnvInt(String key, int defaultValue) {
        String val = System.getenv(key);
        return val != null ? Integer.parseInt(val) : defaultValue;
    }

    private static String getEnvString(String key, String defaultValue) {
        String val = System.getenv(key);
        return val != null ? val : defaultValue;
    }

    @Override
    public String toString() {
        return String.format("NodeConfig{id=%d, total=%d, f=%d, port=%d, byzantine=%s, peers=%s}",
            nodeId, totalNodes, faultTolerance, port, byzantineType, peerAddresses);
    }
}
