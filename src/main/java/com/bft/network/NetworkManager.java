package com.bft.network;

import com.bft.config.NodeConfig;
import com.bft.crypto.ECDSASigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Manages TCP connections between all PBFT nodes.
 * Provides broadcast and point-to-point messaging.
 */
public class NetworkManager {
    private static final Logger log = LoggerFactory.getLogger(NetworkManager.class);

    private final NodeConfig config;
    private final ECDSASigner signer;
    private final Map<Integer, PublicKey> peerKeys = new ConcurrentHashMap<>();
    private final Map<Integer, PrintWriter> outgoing = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private ServerSocket serverSocket;
    private Consumer<Message> messageConsumer;
    private volatile boolean running = true;

    // Track messages for anti-replay
    private final Set<String> seenMessages = ConcurrentHashMap.newKeySet();

    public NetworkManager(NodeConfig config, ECDSASigner signer) {
        this.config = config;
        this.signer = signer;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "net-" + config.getNodeId());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Set the callback for received messages.
     */
    public void onMessage(Consumer<Message> consumer) {
        this.messageConsumer = consumer;
    }

    /**
     * Start listening for incoming connections.
     */
    public void startServer() {
        executor.submit(() -> {
            try {
                serverSocket = new ServerSocket(config.getPort());
                log.info("Node {} listening on port {}", config.getNodeId(), config.getPort());

                while (running) {
                    Socket client = serverSocket.accept();
                    executor.submit(() -> handleConnection(client));
                }
            } catch (IOException e) {
                if (running) {
                    log.error("Server error on node {}: {}", config.getNodeId(), e.getMessage());
                }
            }
        });
    }

    /**
     * Connect to all peer nodes. Retries with exponential backoff.
     */
    public void connectToPeers() {
        for (Map.Entry<Integer, String> entry : config.getPeerAddresses().entrySet()) {
            int peerId = entry.getKey();
            if (peerId == config.getNodeId()) continue;

            executor.submit(() -> connectWithRetry(peerId, entry.getValue()));
        }
    }

    private void connectWithRetry(int peerId, String address) {
        String[] parts = address.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);

        int retries = 0;
        int maxRetries = 30;
        while (running && retries < maxRetries) {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 3000);
                socket.setTcpNoDelay(true);

                PrintWriter writer = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
                outgoing.put(peerId, writer);

                // Send key exchange
                Message keyMsg = new Message(Message.Type.KEY_EXCHANGE, config.getNodeId(), 0, 0,
                    signer.getPublicKeyBase64());
                writer.println(keyMsg.toJson());

                log.info("Node {} connected to peer {} ({})", config.getNodeId(), peerId, address);

                // Start reading from this connection
                handleConnection(socket);
                return;

            } catch (IOException e) {
                retries++;
                long delay = Math.min(1000L * retries, 5000L);
                log.debug("Node {} retry {} connecting to peer {} ({}): {}",
                    config.getNodeId(), retries, peerId, address, e.getMessage());
                try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
        log.warn("Node {} failed to connect to peer {} after {} retries", config.getNodeId(), peerId, maxRetries);
    }

    private void handleConnection(Socket socket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                try {
                    Message msg = Message.fromJson(line);

                    // Handle key exchange
                    if (msg.getType() == Message.Type.KEY_EXCHANGE) {
                        handleKeyExchange(msg, socket);
                        continue;
                    }

                    // Anti-replay: skip already-seen messages
                    String msgKey = msg.getSenderId() + ":" + msg.getId();
                    if (!seenMessages.add(msgKey)) {
                        log.debug("Dropping duplicate message: {}", msg);
                        continue;
                    }

                    // Limit seen messages cache size
                    if (seenMessages.size() > 10000) {
                        seenMessages.clear();
                    }

                    // Verify signature if we have the public key
                    if (peerKeys.containsKey(msg.getSenderId())) {
                        boolean valid = ECDSASigner.verify(
                            msg.getSignableContent(), msg.getSignature(), peerKeys.get(msg.getSenderId()));
                        if (!valid) {
                            log.warn("Invalid signature from node {} on message {}", msg.getSenderId(), msg.getId());
                            continue;
                        }
                    }

                    // Deliver to consumer
                    if (messageConsumer != null) {
                        messageConsumer.accept(msg);
                    }
                } catch (Exception e) {
                    log.debug("Error processing message: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            if (running) {
                log.debug("Connection closed: {}", e.getMessage());
            }
        }
    }

    private void handleKeyExchange(Message msg, Socket socket) {
        try {
            // Decode public key from base64
            byte[] keyBytes = Base64.getDecoder().decode(msg.getPayload());
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("EC", "BC");
            PublicKey pubKey = kf.generatePublic(new java.security.spec.X509EncodedKeySpec(keyBytes));
            peerKeys.put(msg.getSenderId(), pubKey);

            // Set up outgoing if not already connected
            if (!outgoing.containsKey(msg.getSenderId())) {
                PrintWriter writer = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
                outgoing.put(msg.getSenderId(), writer);

                // Send our key back
                Message reply = new Message(Message.Type.KEY_EXCHANGE, config.getNodeId(), 0, 0,
                    signer.getPublicKeyBase64());
                writer.println(reply.toJson());
            }

            log.info("Node {} received public key from node {}", config.getNodeId(), msg.getSenderId());
        } catch (Exception e) {
            log.error("Key exchange failed: {}", e.getMessage());
        }
    }

    /**
     * Sign and broadcast a message to all connected peers.
     */
    public void broadcast(Message msg) {
        msg.setSignature(signer.sign(msg.getSignableContent()));

        String json = msg.toJson();
        for (Map.Entry<Integer, PrintWriter> entry : outgoing.entrySet()) {
            try {
                entry.getValue().println(json);
            } catch (Exception e) {
                log.debug("Failed to send to node {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    /**
     * Sign and send a message to a specific peer.
     */
    public void sendTo(int peerId, Message msg) {
        msg.setSignature(signer.sign(msg.getSignableContent()));

        PrintWriter writer = outgoing.get(peerId);
        if (writer != null) {
            try {
                writer.println(msg.toJson());
            } catch (Exception e) {
                log.debug("Failed to send to node {}: {}", peerId, e.getMessage());
            }
        }
    }

    /**
     * Get the number of connected peers.
     */
    public int getConnectedPeerCount() {
        return outgoing.size();
    }

    public Map<Integer, PublicKey> getPeerKeys() {
        return peerKeys;
    }

    /**
     * Register this node's own public key.
     */
    public void registerOwnKey() {
        peerKeys.put(config.getNodeId(), signer.getPublicKey());
    }

    public void shutdown() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) { /* ignore */ }
        executor.shutdownNow();
    }
}
