package com.ionsignal.minecraft.ioncore.network;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.fasterxml.jackson.databind.JsonNode;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registry for handling inbound network commands.
 */
public final class NetworkCommandRegistrar {
    private final IonCore plugin;
    private final Map<String, Consumer<String>> handlers = new ConcurrentHashMap<>();

    public NetworkCommandRegistrar(IonCore plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers a handler for a specific command type ID.
     *
     * @param typeId
     *            The command type string (e.g., "command:persona:spawn")
     * @param handler
     *            The handler to invoke with the raw JSON payload string
     */
    public void registerHandler(
            @NotNull String typeId,
            @NotNull Consumer<String> handler) {
        if (handlers.containsKey(typeId)) {
            plugin.getLogger().warning("Duplicate handler registration for type: " + typeId);
        }
        handlers.put(typeId, handler);
        plugin.getLogger().info("Registered handler for Type ID: " + typeId);
    }

    /**
     * Dispatches a command to its registered handler.
     *
     * @param payload
     *            The raw JsonNode payload from the envelope
     */
    public void dispatch(@NotNull JsonNode payload) {
        if (!payload.has("type")) {
            plugin.getLogger().warning("Received payload without 'type' discriminator. Dropping.");
            return;
        }
        String typeId = payload.get("type").asText();
        Consumer<String> handler = handlers.get(typeId);
        if (handler != null) {
            try {
                // Pass the raw JSON string to the handler
                // This allows the handler to use its own ObjectMapper (Standard Jackson)
                handler.accept(payload.toString());
            } catch (Exception e) {
                plugin.getLogger().severe(
                        "Error executing handler for " + typeId + ": " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            plugin.getLogger().warning(
                    "Received unhandled command type: " + typeId);
        }
    }

    /**
     * Clears all registered handlers.
     */
    public void clear() {
        handlers.clear();
    }
}