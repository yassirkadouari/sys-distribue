package com.bft.consensus;

import com.bft.config.NodeConfig;
import com.bft.network.Message;
import com.bft.network.NetworkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages view changes — leader rotation when the current leader fails.
 * Implements a simplified view-change protocol.
 */
public class ViewManager {
    private static final Logger log = LoggerFactory.getLogger(ViewManager.class);

    private final NodeConfig config;
    private final NetworkManager network;
    private final AtomicInteger currentView = new AtomicInteger(0);

    // View change voting
    private final ConcurrentHashMap<Integer, ConcurrentHashMap.KeySetView<Integer, Boolean>> viewChangeVotes
        = new ConcurrentHashMap<>();

    // Timeout for leader liveness
    private static final long VIEW_CHANGE_TIMEOUT_MS = 10000; // 10 seconds
    private volatile long lastLeaderActivity = System.currentTimeMillis();
    private ScheduledExecutorService scheduler;
    private Runnable viewChangeCallback;

    public ViewManager(NodeConfig config, NetworkManager network) {
        this.config = config;
        this.network = network;
    }

    /**
     * Start the view change timeout monitor.
     */
    public void start(Runnable onViewChange) {
        this.viewChangeCallback = onViewChange;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "view-monitor-" + config.getNodeId());
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::checkLeaderTimeout, 5, 3, TimeUnit.SECONDS);
        log.info("Node {} view manager started (view={})", config.getNodeId(), currentView.get());
    }

    /**
     * Reset the leader activity timer (called when leader sends valid messages).
     */
    public void resetTimer() {
        lastLeaderActivity = System.currentTimeMillis();
    }

    /**
     * Check if the leader has timed out.
     */
    private void checkLeaderTimeout() {
        if (config.isLeader(currentView.get())) {
            return; // We are the leader, no timeout needed
        }

        long elapsed = System.currentTimeMillis() - lastLeaderActivity;
        if (elapsed > VIEW_CHANGE_TIMEOUT_MS) {
            log.warn("Node {} detected leader timeout ({}ms). Initiating view change.",
                config.getNodeId(), elapsed);
            initiateViewChange();
        }
    }

    /**
     * Initiate a view change to the next view.
     */
    public void initiateViewChange() {
        int newView = currentView.get() + 1;

        Message viewChangeMsg = new Message(
            Message.Type.VIEW_CHANGE,
            config.getNodeId(),
            newView,
            0,
            "{\"reason\":\"leader_timeout\"}"
        );

        network.broadcast(viewChangeMsg);
        onViewChangeReceived(config.getNodeId(), newView);
    }

    /**
     * Handle a received VIEW_CHANGE message.
     */
    public void onViewChangeReceived(int senderId, int proposedView) {
        if (proposedView <= currentView.get()) return;

        viewChangeVotes.computeIfAbsent(proposedView, k -> ConcurrentHashMap.newKeySet())
                       .add(senderId);

        int votes = viewChangeVotes.get(proposedView).size();
        int needed = config.getCommitQuorum(); // 2f + 1

        log.debug("Node {} view change vote for view {}: {}/{} votes",
            config.getNodeId(), proposedView, votes, needed);

        if (votes >= needed) {
            log.info("Node {} view change to view {} APPROVED ({}/{})",
                config.getNodeId(), proposedView, votes, needed);
            currentView.set(proposedView);
            lastLeaderActivity = System.currentTimeMillis();

            // Notify the new leader
            if (config.isLeader(proposedView)) {
                Message newViewMsg = new Message(
                    Message.Type.NEW_VIEW,
                    config.getNodeId(),
                    proposedView,
                    0,
                    "{\"newLeader\":" + config.getNodeId() + "}"
                );
                network.broadcast(newViewMsg);
            }

            if (viewChangeCallback != null) {
                viewChangeCallback.run();
            }

            // Clean up old votes
            viewChangeVotes.keySet().removeIf(v -> v <= proposedView);
        }
    }

    public int getCurrentView() { return currentView.get(); }
    public int getCurrentLeader() { return config.getLeaderId(currentView.get()); }

    public void shutdown() {
        if (scheduler != null) scheduler.shutdownNow();
    }
}
