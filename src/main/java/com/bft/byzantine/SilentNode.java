package com.bft.byzantine;

import com.bft.network.Message;
import com.bft.network.NetworkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Silent Byzantine node: drops all consensus messages.
 * Simulates a crashed or unresponsive node.
 * The network should still function as long as n >= 3f + 1.
 */
public class SilentNode implements ByzantineBehavior {
    private static final Logger log = LoggerFactory.getLogger(SilentNode.class);
    private int droppedCount = 0;

    @Override
    public Message interceptMessage(Message msg, NetworkManager network) {
        droppedCount++;
        log.warn("☠ BYZANTINE [SILENT] Dropping {} (seq={}) — total dropped: {}",
            msg.getType(), msg.getSequenceNumber(), droppedCount);
        return null; // Drop the message
    }

    @Override
    public String getType() { return "silent"; }

    @Override
    public String getDescription() {
        return "Silent node: drops all outgoing consensus messages (simulates crash/disconnect)";
    }
}
