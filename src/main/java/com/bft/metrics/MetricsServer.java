package com.bft.metrics;

import com.bft.config.NodeConfig;
import com.bft.consensus.ConsensusState;
import com.bft.consensus.PBFTEngine;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Lightweight HTTP server exposing metrics and node status for the dashboard.
 * Endpoints:
 *   GET /metrics   — Full metrics snapshot (JSON)
 *   GET /status    — Node status (JSON)
 *   GET /consensus — Consensus log history (JSON)
 *   GET /health    — Health check
 */
public class MetricsServer {
    private static final Logger log = LoggerFactory.getLogger(MetricsServer.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final NodeConfig config;
    private final MetricsCollector metrics;
    private final PBFTEngine engine;
    private HttpServer server;

    public MetricsServer(NodeConfig config, MetricsCollector metrics, PBFTEngine engine) {
        this.config = config;
        this.metrics = metrics;
        this.engine = engine;
    }

    /**
     * Start the HTTP metrics server.
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", config.getMetricsPort()), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        // CORS-enabled endpoints
        server.createContext("/metrics", this::handleMetrics);
        server.createContext("/status", this::handleStatus);
        server.createContext("/consensus", this::handleConsensus);
        server.createContext("/health", this::handleHealth);

        server.start();
        log.info("Metrics server started on port {} for node {}", config.getMetricsPort(), config.getNodeId());
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        Map<String, Object> snapshot = metrics.getSnapshot();
        sendJson(exchange, snapshot);
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("nodeId", config.getNodeId());
        status.put("totalNodes", config.getTotalNodes());
        status.put("faultTolerance", config.getFaultTolerance());
        status.put("byzantine", config.isByzantine());
        status.put("byzantineType", config.getByzantineType());
        status.put("currentView", engine.getViewManager().getCurrentView());
        status.put("currentLeader", engine.getViewManager().getCurrentLeader());
        status.put("totalExecuted", engine.getTotalExecuted());
        status.put("port", config.getPort());
        status.put("metricsPort", config.getMetricsPort());
        status.put("timestamp", System.currentTimeMillis());
        sendJson(exchange, status);
    }

    private void handleConsensus(HttpExchange exchange) throws IOException {
        Map<String, Object> consensusData = new LinkedHashMap<>();
        List<Map<String, Object>> rounds = new ArrayList<>();

        engine.getConsensusLog().entrySet().stream()
            .sorted(Map.Entry.<Integer, ConsensusState>comparingByKey().reversed())
            .limit(50)
            .forEach(entry -> {
                ConsensusState state = entry.getValue();
                Map<String, Object> round = new LinkedHashMap<>();
                round.put("sequenceNumber", state.getSequenceNumber());
                round.put("viewNumber", state.getViewNumber());
                round.put("phase", state.getPhase().name());
                round.put("prepareCount", state.getPrepareCount());
                round.put("commitCount", state.getCommitCount());
                round.put("prepareVoters", state.getPrepareVoters());
                round.put("commitVoters", state.getCommitVoters());
                round.put("latencyMs", state.getLatencyMs());
                round.put("digest", state.getDigest() != null
                    ? state.getDigest().substring(0, Math.min(16, state.getDigest().length())) + "..."
                    : null);
                rounds.add(round);
            });

        consensusData.put("nodeId", config.getNodeId());
        consensusData.put("rounds", rounds);
        consensusData.put("totalRounds", engine.getConsensusLog().size());
        sendJson(exchange, consensusData);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("nodeId", config.getNodeId());
        health.put("timestamp", System.currentTimeMillis());
        sendJson(exchange, health);
    }

    private void sendJson(HttpExchange exchange, Object data) throws IOException {
        String json = GSON.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");

        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
    }
}
