package com.bft.byzantine;

import com.bft.network.Message;
import com.bft.network.NetworkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Equivocation Byzantine node: sends contradictory messages to different peers.
 * This is the most dangerous attack — the node sends different values to different replicas,
 * attempting to create a fork in the consensus.
 */
public class EquivocationNode implements ByzantineBehavior {
    private static final Logger log = LoggerFactory.getLogger(EquivocationNode.class);
    private int equivocatedCount = 0;

    @Override
    public Message interceptMessage(Message msg, NetworkManager network) {
        if (msg.getType() == Message.Type.PRE_PREPARE || msg.getType() == Message.Type.PREPARE) {
            equivocatedCount++;

            // Send the original message via normal broadcast (handled by caller)
            // But also send a contradictory message to half the peers
            Message fakemsg = new Message(
                msg.getType(),
                msg.getSenderId(),
                msg.getViewNumber(),
                msg.getSequenceNumber(),
                "{\"type\":\"transfer\",\"from\":\"FAKE_" + UUID.randomUUID().toString().substring(0, 4)
                    + "\",\"to\":\"attacker\",\"amount\":" + (equivocatedCount * 100) + "}"
            );
            fakemsg.setId("eq-" + UUID.randomUUID().toString().substring(0, 6));

            // Send contradictory message to odd-numbered peers
            for (int peerId : network.getPeerKeys().keySet()) {
                if (peerId != msg.getSenderId() && peerId % 2 == 1) {
                    network.sendTo(peerId, fakemsg);
                }
            }

            log.warn("☠ BYZANTINE [EQUIVOCATION] Sent contradictory {} for seq={} — count: {}",
                msg.getType(), msg.getSequenceNumber(), equivocatedCount);

            return msg; // Still send the original to even-numbered peers
        }
        return msg;
    }

    @Override
    public String getType() { return "equivocation"; }

    @Override
    public String getDescription() {
        return "Equivocation node: sends contradictory PREPARE/COMMIT messages to different peers (fork attempt)";
    }
}
