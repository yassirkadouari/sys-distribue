package com.bft.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Objects;
import java.util.UUID;

/**
 * PBFT protocol message. All inter-node communication uses this format.
 * Each message is signed with the sender's ECDSA key.
 */
public class Message {

    public enum Type {
        CLIENT_REQUEST,  // Client → Leader
        PRE_PREPARE,     // Leader → All replicas
        PREPARE,         // Replica → All replicas
        COMMIT,          // Replica → All replicas
        REPLY,           // Replica → Client
        VIEW_CHANGE,     // Replica → All (leader timeout)
        NEW_VIEW,        // New leader → All
        KEY_EXCHANGE,    // Node → Node (share public keys)
        HEARTBEAT        // Periodic health check
    }

    private static final Gson GSON = new GsonBuilder().create();

    private String id;           // Unique message ID
    private Type type;
    private int senderId;        // Node ID of the sender
    private int viewNumber;      // Current view number
    private int sequenceNumber;  // Consensus sequence number
    private String payload;      // Transaction data (JSON string)
    private String digest;       // SHA-256 hash of payload
    private String signature;    // ECDSA signature (Base64)
    private long timestamp;      // Unix timestamp ms

    public Message() {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.timestamp = System.currentTimeMillis();
    }

    public Message(Type type, int senderId, int viewNumber, int sequenceNumber, String payload) {
        this();
        this.type = type;
        this.senderId = senderId;
        this.viewNumber = viewNumber;
        this.sequenceNumber = sequenceNumber;
        this.payload = payload;
        this.digest = computeDigest(payload);
    }

    /**
     * Compute SHA-256 digest of the payload.
     */
    private static String computeDigest(String data) {
        if (data == null) return "";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Get the content to be signed: type + view + sequence + digest.
     */
    public String getSignableContent() {
        return String.format("%s|%d|%d|%s|%d", type, viewNumber, sequenceNumber, digest, senderId);
    }

    // Serialization
    public String toJson() { return GSON.toJson(this); }
    public static Message fromJson(String json) { return GSON.fromJson(json, Message.class); }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public int getSenderId() { return senderId; }
    public void setSenderId(int senderId) { this.senderId = senderId; }

    public int getViewNumber() { return viewNumber; }
    public void setViewNumber(int viewNumber) { this.viewNumber = viewNumber; }

    public int getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(int sequenceNumber) { this.sequenceNumber = sequenceNumber; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; this.digest = computeDigest(payload); }

    public String getDigest() { return digest; }
    public void setDigest(String digest) { this.digest = digest; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Message message = (Message) o;
        return senderId == message.senderId && viewNumber == message.viewNumber
            && sequenceNumber == message.sequenceNumber && type == message.type
            && Objects.equals(digest, message.digest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, senderId, viewNumber, sequenceNumber, digest);
    }

    @Override
    public String toString() {
        return String.format("Msg{%s, from=%d, v=%d, seq=%d, id=%s}",
            type, senderId, viewNumber, sequenceNumber, id);
    }
}
