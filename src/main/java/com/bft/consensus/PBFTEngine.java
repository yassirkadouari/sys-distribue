package com.bft.consensus;

import com.bft.byzantine.ByzantineBehavior;
import com.bft.config.NodeConfig;
import com.bft.metrics.MetricsCollector;
import com.bft.network.Message;
import com.bft.network.NetworkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PBFT (Practical Byzantine Fault Tolerance) consensus engine.
 * Implements the three-phase protocol: Pre-Prepare → Prepare → Commit.
 * 
 * Protocol flow:
 * 1. Client sends request to the leader
 * 2. Leader assigns sequence number, broadcasts PRE_PREPARE
 * 3. Replicas verify and broadcast PREPARE
 * 4. When 2f PREPAREs collected → broadcast COMMIT
 * 5. When 2f+1 COMMITs collected → execute and reply
 */
public class PBFTEngine {
    private static final Logger log = LoggerFactory.getLogger(PBFTEngine.class);

    private final NodeConfig config;
    private final NetworkManager network;
    private final ViewManager viewManager;
    private final MetricsCollector metrics;
    private ByzantineBehavior byzantineBehavior;

    // Consensus state per sequence number
    private final ConcurrentHashMap<Integer, ConsensusState> consensusLog = new ConcurrentHashMap<>();

    // Sequence number counter (leader assigns)
    private final AtomicInteger sequenceCounter = new AtomicInteger(0);

    // Executed transactions (simulated state machine)
    private final ConcurrentHashMap<Integer, String> executedTransactions = new ConcurrentHashMap<>();

    // Pending client requests (leader queue)
    private final BlockingQueue<Message> pendingRequests = new LinkedBlockingQueue<>();

    // Track total processed
    private final AtomicInteger totalExecuted = new AtomicInteger(0);

    public PBFTEngine(NodeConfig config, NetworkManager network, MetricsCollector metrics) {
        this.config = config;
        this.network = network;
        this.metrics = metrics;
        this.viewManager = new ViewManager(config, network);
    }

    /**
     * Start the consensus engine.
     */
    public void start() {
        viewManager.start(() -> {
            log.info("Node {} view changed to {}, new leader = {}",
                config.getNodeId(), viewManager.getCurrentView(), viewManager.getCurrentLeader());
        });

        // Start request processor for the leader
        Thread requestProcessor = new Thread(this::processClientRequests, "pbft-processor-" + config.getNodeId());
        requestProcessor.setDaemon(true);
        requestProcessor.start();

        log.info("Node {} PBFT engine started (leader={}, view={})",
            config.getNodeId(), viewManager.getCurrentLeader(), viewManager.getCurrentView());
    }

    public void setByzantineBehavior(ByzantineBehavior behavior) {
        this.byzantineBehavior = behavior;
    }

    // ==================== CLIENT REQUEST ====================

    /**
     * Handle a client request. If we are the leader, start consensus.
     * If not, forward to the leader.
     */
    public void onClientRequest(Message msg) {
        log.info("Node {} received client request: seq={}", config.getNodeId(), msg.getSequenceNumber());

        if (config.isLeader(viewManager.getCurrentView())) {
            pendingRequests.offer(msg);
        } else {
            // Forward to leader
            network.sendTo(viewManager.getCurrentLeader(), msg);
        }
    }

    /**
     * Process pending client requests (leader only).
     */
    private void processClientRequests() {
        while (true) {
            try {
                Message request = pendingRequests.poll(1, TimeUnit.SECONDS);
                if (request == null) continue;

                if (!config.isLeader(viewManager.getCurrentView())) {
                    // Not leader anymore, re-queue
                    pendingRequests.offer(request);
                    Thread.sleep(500);
                    continue;
                }

                // Assign sequence number and start pre-prepare
                int seqNum = sequenceCounter.incrementAndGet();
                startPrePrepare(seqNum, request.getPayload());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Leader initiates the pre-prepare phase.
     */
    private void startPrePrepare(int seqNum, String payload) {
        int view = viewManager.getCurrentView();

        Message prePrepare = new Message(
            Message.Type.PRE_PREPARE,
            config.getNodeId(),
            view,
            seqNum,
            payload
        );

        // Create consensus state
        ConsensusState state = new ConsensusState(seqNum, view);
        state.setPrePrepare(prePrepare);
        consensusLog.put(seqNum, state);

        log.info("◆ Node {} [LEADER] PRE-PREPARE seq={}, view={}", config.getNodeId(), seqNum, view);
        metrics.recordPhaseStart(seqNum, "pre-prepare");

        // Apply byzantine behavior if any
        Message toSend = applyByzantine(prePrepare);
        if (toSend != null) {
            network.broadcast(toSend);

            // Leader also prepares
            Message selfPrepare = new Message(Message.Type.PREPARE, config.getNodeId(), view, seqNum, payload);
            state.addPrepare(selfPrepare);
        }
    }

    // ==================== PRE-PREPARE PHASE ====================

    /**
     * Handle a PRE_PREPARE message from the leader.
     */
    public void onPrePrepare(Message msg) {
        int seqNum = msg.getSequenceNumber();
        int view = msg.getViewNumber();

        // Validate: only accept from current leader
        if (msg.getSenderId() != config.getLeaderId(view)) {
            log.warn("Node {} rejected PRE_PREPARE from non-leader node {} (expected {})",
                config.getNodeId(), msg.getSenderId(), config.getLeaderId(view));
            return;
        }

        // Validate: don't accept for same seq in same view twice
        if (consensusLog.containsKey(seqNum) && consensusLog.get(seqNum).getPhase() != ConsensusState.Phase.IDLE) {
            log.debug("Node {} already has state for seq={}", config.getNodeId(), seqNum);
            return;
        }

        viewManager.resetTimer();

        // Accept the pre-prepare
        ConsensusState state = consensusLog.computeIfAbsent(seqNum, k -> new ConsensusState(seqNum, view));
        state.setPrePrepare(msg);

        log.info("◇ Node {} PRE-PREPARE accepted seq={}, view={}", config.getNodeId(), seqNum, view);
        metrics.recordPhaseStart(seqNum, "pre-prepare");

        // Broadcast PREPARE
        Message prepare = new Message(
            Message.Type.PREPARE,
            config.getNodeId(),
            view,
            seqNum,
            msg.getPayload()
        );

        Message toSend = applyByzantine(prepare);
        if (toSend != null) {
            network.broadcast(toSend);
            state.addPrepare(prepare); // Self-vote
        }
    }

    // ==================== PREPARE PHASE ====================

    /**
     * Handle a PREPARE message from a replica.
     */
    public void onPrepare(Message msg) {
        int seqNum = msg.getSequenceNumber();

        ConsensusState state = consensusLog.get(seqNum);
        if (state == null) {
            // Haven't seen pre-prepare yet, buffer the prepare
            state = consensusLog.computeIfAbsent(seqNum,
                k -> new ConsensusState(seqNum, msg.getViewNumber()));
        }

        // Verify digest matches
        if (state.getDigest() != null && !state.matchesDigest(msg)) {
            log.warn("Node {} PREPARE digest mismatch for seq={} from node {}",
                config.getNodeId(), seqNum, msg.getSenderId());
            metrics.recordByzantineActivity(msg.getSenderId(), "digest_mismatch");
            return;
        }

        boolean newVote = state.addPrepare(msg);
        if (!newVote) return;

        log.debug("Node {} PREPARE count for seq={}: {}/{}",
            config.getNodeId(), seqNum, state.getPrepareCount(), config.getPrepareQuorum());

        // Check if we reached prepare quorum (2f)
        if (state.getPhase() == ConsensusState.Phase.PRE_PREPARED
                && state.isPrepared(config.getPrepareQuorum())) {

            state.markPrepared();
            log.info("✓ Node {} PREPARED seq={} ({} votes)",
                config.getNodeId(), seqNum, state.getPrepareCount());
            metrics.recordPhaseStart(seqNum, "prepared");

            // Broadcast COMMIT
            Message commit = new Message(
                Message.Type.COMMIT,
                config.getNodeId(),
                viewManager.getCurrentView(),
                seqNum,
                state.getPayload()
            );

            Message toSend = applyByzantine(commit);
            if (toSend != null) {
                network.broadcast(toSend);
                state.addCommit(commit); // Self-vote
            }
        }
    }

    // ==================== COMMIT PHASE ====================

    /**
     * Handle a COMMIT message from a replica.
     */
    public void onCommit(Message msg) {
        int seqNum = msg.getSequenceNumber();

        ConsensusState state = consensusLog.get(seqNum);
        if (state == null) {
            state = consensusLog.computeIfAbsent(seqNum,
                k -> new ConsensusState(seqNum, msg.getViewNumber()));
        }

        boolean newVote = state.addCommit(msg);
        if (!newVote) return;

        log.debug("Node {} COMMIT count for seq={}: {}/{}",
            config.getNodeId(), seqNum, state.getCommitCount(), config.getCommitQuorum());

        // Check if we reached commit quorum (2f+1)
        if ((state.getPhase() == ConsensusState.Phase.PREPARED || state.getPhase() == ConsensusState.Phase.PRE_PREPARED)
                && state.isCommitted(config.getCommitQuorum())) {

            state.markCommitted();
            log.info("✓✓ Node {} COMMITTED seq={} ({} votes)",
                config.getNodeId(), seqNum, state.getCommitCount());
            metrics.recordPhaseStart(seqNum, "committed");

            // Execute the transaction
            executeTransaction(state);
        }
    }

    // ==================== EXECUTION ====================

    /**
     * Execute the agreed-upon transaction.
     */
    private void executeTransaction(ConsensusState state) {
        if (state.getPhase() == ConsensusState.Phase.EXECUTED) return;

        state.markExecuted();
        int seqNum = state.getSequenceNumber();
        String payload = state.getPayload();

        executedTransactions.put(seqNum, payload);
        int count = totalExecuted.incrementAndGet();

        long latency = state.getLatencyMs();
        metrics.recordTransactionExecuted(seqNum, latency);

        log.info("★ Node {} EXECUTED seq={} (latency={}ms, total={})",
            config.getNodeId(), seqNum, latency, count);

        // Send reply to client
        Message reply = new Message(
            Message.Type.REPLY,
            config.getNodeId(),
            viewManager.getCurrentView(),
            seqNum,
            "{\"status\":\"executed\",\"seq\":" + seqNum + ",\"latency\":" + latency + "}"
        );
        network.broadcast(reply);
    }

    // ==================== VIEW CHANGE ====================

    public void onViewChange(Message msg) {
        log.info("Node {} received VIEW_CHANGE from node {} for view {}",
            config.getNodeId(), msg.getSenderId(), msg.getViewNumber());
        viewManager.onViewChangeReceived(msg.getSenderId(), msg.getViewNumber());
    }

    public void onNewView(Message msg) {
        log.info("Node {} received NEW_VIEW for view {} from leader {}",
            config.getNodeId(), msg.getViewNumber(), msg.getSenderId());
        viewManager.resetTimer();
    }

    public void onHeartbeat(Message msg) {
        viewManager.resetTimer();
    }

    // ==================== BYZANTINE BEHAVIOR ====================

    /**
     * Apply byzantine behavior to a message before sending.
     * Returns null if the message should be dropped (silent node).
     */
    private Message applyByzantine(Message msg) {
        if (byzantineBehavior == null) return msg;
        return byzantineBehavior.interceptMessage(msg, network);
    }

    // ==================== PUBLIC INTERFACE ====================

    /**
     * Submit a transaction for consensus (called by client simulator).
     */
    public void submitTransaction(String transactionPayload) {
        Message request = new Message(
            Message.Type.CLIENT_REQUEST,
            -1, // client
            viewManager.getCurrentView(),
            0,
            transactionPayload
        );

        if (config.isLeader(viewManager.getCurrentView())) {
            onClientRequest(request);
        } else {
            network.sendTo(viewManager.getCurrentLeader(), request);
        }
    }

    public int getTotalExecuted() { return totalExecuted.get(); }
    public ViewManager getViewManager() { return viewManager; }
    public ConcurrentHashMap<Integer, ConsensusState> getConsensusLog() { return consensusLog; }
    public ConcurrentHashMap<Integer, String> getExecutedTransactions() { return executedTransactions; }

    public void shutdown() {
        viewManager.shutdown();
    }
}
