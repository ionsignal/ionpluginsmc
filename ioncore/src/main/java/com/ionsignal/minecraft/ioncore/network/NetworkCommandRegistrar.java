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
    private final Map<String, Consumer<JsonNode>> handlers = new ConcurrentHashMap<>();

    public NetworkCommandRegistrar(IonCore plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers a handler for a specific command type ID.
     *
     * @param typeId
     *            The command type string (e.g., "persona.spawn")
     * @param handler
     *            The handler to invoke with the JsonNode payload.
     */
    public void registerHandler(
            @NotNull String typeId,
            @NotNull Consumer<JsonNode> handler) {
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
     *            The raw JsonNode representing the command payload. // [Modification] Updated doc
     */
    public void dispatch(@NotNull JsonNode payload) {
        if (payload == null || payload.isNull()) {
            plugin.getLogger().warning("Dispatch received null payload. Dropping.");
            return;
        }
        if (!payload.has("type")) {
            plugin.getLogger().warning("Received payload without 'type' discriminator. Dropping.");
            return;
        }
        String typeId = payload.get("type").asText();
        Consumer<JsonNode> handler = handlers.get(typeId);
        if (handler != null) {
            try {
                handler.accept(payload);
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