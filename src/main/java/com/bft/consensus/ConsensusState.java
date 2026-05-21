package com.bft.consensus;

import com.bft.network.Message;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the state of a single consensus round (identified by sequence number).
 * Collects PREPARE and COMMIT votes and determines when quorum is reached.
 */
public class ConsensusState {

    public enum Phase {
        IDLE,         // No consensus activity
        PRE_PREPARED, // Pre-prepare received from leader
        PREPARED,     // 2f PREPARE messages collected
        COMMITTED,    // 2f+1 COMMIT messages collected
        EXECUTED      // Transaction executed
    }

    private final int sequenceNumber;
    private final int viewNumber;
    private Phase phase = Phase.IDLE;
    private Message prePrepareMsg;
    private String digest;
    private String payload;
    private long startTime;

    // Track votes by sender ID to prevent double-counting
    private final Set<Integer> prepareVotes = ConcurrentHashMap.newKeySet();
    private final Set<Integer> commitVotes = ConcurrentHashMap.newKeySet();

    // Store all messages for auditing
    private final List<Message> messageLog = Collections.synchronizedList(new ArrayList<>());

    public ConsensusState(int sequenceNumber, int viewNumber) {
        this.sequenceNumber = sequenceNumber;
        this.viewNumber = viewNumber;
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Set the pre-prepare message from the leader.
     */
    public void setPrePrepare(Message msg) {
        this.prePrepareMsg = msg;
        this.digest = msg.getDigest();
        this.payload = msg.getPayload();
        this.phase = Phase.PRE_PREPARED;
        messageLog.add(msg);
    }

    /**
     * Add a PREPARE vote. Returns true if the vote was new.
     */
    public boolean addPrepare(Message msg) {
        messageLog.add(msg);
        return prepareVotes.add(msg.getSenderId());
    }

    /**
     * Add a COMMIT vote. Returns true if the vote was new.
     */
    public boolean addCommit(Message msg) {
        messageLog.add(msg);
        return commitVotes.add(msg.getSenderId());
    }

    /**
     * Check if we have enough PREPARE votes (>= 2f).
     */
    public boolean isPrepared(int quorum) {
        return prepareVotes.size() >= quorum;
    }

    /**
     * Check if we have enough COMMIT votes (>= 2f+1).
     */
    public boolean isCommitted(int quorum) {
        return commitVotes.size() >= quorum;
    }

    /**
     * Verify that a message's digest matches the expected digest.
     */
    public boolean matchesDigest(Message msg) {
        return digest != null && digest.equals(msg.getDigest());
    }

    /**
     * Mark as prepared phase.
     */
    public void markPrepared() {
        this.phase = Phase.PREPARED;
    }

    /**
     * Mark as committed phase.
     */
    public void markCommitted() {
        this.phase = Phase.COMMITTED;
    }

    /**
     * Mark as executed.
     */
    public void markExecuted() {
        this.phase = Phase.EXECUTED;
    }

    /**
     * Get the latency of this consensus round in milliseconds.
     */
    public long getLatencyMs() {
        return System.currentTimeMillis() - startTime;
    }

    // Getters
    public int getSequenceNumber() { return sequenceNumber; }
    public int getViewNumber() { return viewNumber; }
    public Phase getPhase() { return phase; }
    public Message getPrePrepareMsg() { return prePrepareMsg; }
    public String getDigest() { return digest; }
    public String getPayload() { return payload; }
    public int getPrepareCount() { return prepareVotes.size(); }
    public int getCommitCount() { return commitVotes.size(); }
    public Set<Integer> getPrepareVoters() { return Collections.unmodifiableSet(prepareVotes); }
    public Set<Integer> getCommitVoters() { return Collections.unmodifiableSet(commitVotes); }
    public List<Message> getMessageLog() { return Collections.unmodifiableList(messageLog); }
    public long getStartTime() { return startTime; }

    @Override
    public String toString() {
        return String.format("ConsensusState{seq=%d, view=%d, phase=%s, prepares=%d, commits=%d}",
            sequenceNumber, viewNumber, phase, prepareVotes.size(), commitVotes.size());
    }
}
