package com.bft.byzantine;

import com.bft.network.Message;
import com.bft.network.NetworkManager;

/**
 * Interface for Byzantine node behaviors.
 * Implementations modify or drop messages to simulate different attack types.
 */
public interface ByzantineBehavior {

    /**
     * Intercept a message before it is sent.
     * @param msg The original message to send
     * @param network The network manager (for equivocation attacks)
     * @return The modified message, or null to drop the message (silent attack)
     */
    Message interceptMessage(Message msg, NetworkManager network);

    /**
     * Get the type name of this byzantine behavior.
     */
    String getType();

    /**
     * Get a description of the attack being simulated.
     */
    String getDescription();
}
