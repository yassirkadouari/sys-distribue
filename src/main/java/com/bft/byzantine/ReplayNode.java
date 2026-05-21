package com.bft.byzantine;

import com.bft.network.Message;
import com.bft.network.NetworkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Replay Byzantine node: re-sends old messages from previous consensus rounds.
 * Tests the anti-replay protection of other nodes.
 */
public class ReplayNode implements ByzantineBehavior {
    private static final Logger log = LoggerFactory.getLogger(ReplayNode.class);

    private final List<Message> messageHistory = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private NetworkManager networkRef;
    private int replayedCount = 0;

    public ReplayNode() {
        // Periodically replay old messages
        scheduler.scheduleAtFixedRate(this::replayOldMessages, 5, 3, TimeUnit.SECONDS);
    }

    @Override
    public Message interceptMessage(Message msg, NetworkManager network) {
        this.networkRef = network;

        // Store a copy for later replay
        synchronized (messageHistory) {
            if (messageHistory.size() < 100) {
                messageHistory.add(msg);
            }
        }

        // Still send the original message (the replay node is not silent)
        return msg;
    }

    /**
     * Replay random old messages to confuse the consensus protocol.
     */
    private void replayOldMessages() {
        if (networkRef == null) return;

        synchronized (messageHistory) {
            if (messageHistory.isEmpty()) return;

            // Replay up to 3 old messages
            int count = Math.min(3, messageHistory.size());
            for (int i = 0; i < count; i++) {
                int index = (int) (Math.random() * messageHistory.size());
                Message oldMsg = messageHistory.get(index);

                // Re-send with the same ID (should be caught by anti-replay)
                networkRef.broadcast(oldMsg);
                replayedCount++;

                log.warn("☠ BYZANTINE [REPLAY] Replayed {} seq={} — total replayed: {}",
                    oldMsg.getType(), oldMsg.getSequenceNumber(), replayedCount);
            }
        }
    }

    @Override
    public String getType() { return "replay"; }

    @Override
    public String getDescription() {
        return "Replay node: re-sends old consensus messages to test anti-replay mechanisms";
    }
}
