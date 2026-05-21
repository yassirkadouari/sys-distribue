package com.bft.network;

import com.bft.config.NodeConfig;
import com.bft.consensus.PBFTEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches incoming messages to the appropriate PBFT engine handler.
 * Acts as the routing layer between network and consensus.
 */
public class MessageHandler {
    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    private final NodeConfig config;
    private final PBFTEngine engine;

    public MessageHandler(NodeConfig config, PBFTEngine engine) {
        this.config = config;
        this.engine = engine;
    }

    /**
     * Handle an incoming message by dispatching to the correct engine method.
     */
    public void handle(Message msg) {
        log.debug("Node {} handling {} from node {}", config.getNodeId(), msg.getType(), msg.getSenderId());

        switch (msg.getType()) {
            case CLIENT_REQUEST -> engine.onClientRequest(msg);
            case PRE_PREPARE    -> engine.onPrePrepare(msg);
            case PREPARE        -> engine.onPrepare(msg);
            case COMMIT         -> engine.onCommit(msg);
            case VIEW_CHANGE    -> engine.onViewChange(msg);
            case NEW_VIEW       -> engine.onNewView(msg);
            case HEARTBEAT      -> engine.onHeartbeat(msg);
            case REPLY          -> {} // Ignore reply messages from other nodes
            default -> log.warn("Unknown message type: {}", msg.getType());
        }
    }
}
