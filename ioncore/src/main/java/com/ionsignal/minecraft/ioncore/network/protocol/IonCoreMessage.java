package com.ionsignal.minecraft.ioncore.network.protocol;

import com.google.gson.Gson;

/**
 * Generic envelope for sending messages to the web client.
 * Replaces the Enum-based version to allow dynamic event types.
 */
public class IonCoreMessage<T> {
    private static final Gson GSON = new Gson();
    
    private final String serverId;
    private final long timestamp;
    private final String type; // Changed from Enum to String
    private final T payload;

    public IonCoreMessage(String serverId, String type, T payload) {
        this.serverId = serverId;
        this.timestamp = System.currentTimeMillis();
        this.type = type;
        this.payload = payload;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    // Getters
    public String getServerId() { return serverId; }
    public long getTimestamp() { return timestamp; }
    public String getType() { return type; }
    public T getPayload() { return payload; }
}