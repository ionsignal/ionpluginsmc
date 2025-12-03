package com.ionsignal.minecraft.ioncore.network;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * A thread-safe registry for network command handlers.
 * Acts as the switchboard connecting raw JSON messages to specific plugin logic.
 * 
 * UPDATED: Uses raw String payloads to decouple JSON libraries.
 */
public class NetworkCommandRegistrar {
    private final Logger logger;
    
    // Maps "COMMAND_NAME" -> Handler Function
    // The handler takes a raw JSON String and returns a Future result
    private final Map<String, Function<String, CompletableFuture<Object>>> handlers = new ConcurrentHashMap<>();

    public NetworkCommandRegistrar(Logger logger) {
        this.logger = logger;
    }

    /**
     * Registers a handler for a specific network command.
     *
     * @param type    The command key (e.g., "SPAWN_AGENT"). Case-insensitive.
     * @param handler The function to execute when this command is received.
     */
    public void register(@NotNull String type, @NotNull Function<String, CompletableFuture<Object>> handler) {
        handlers.put(type.toUpperCase(), handler);
        logger.info("[IonCore] Registered network command listener: " + type.toUpperCase());
    }

    /**
     * Dispatches an incoming payload to the appropriate handler.
     *
     * @param type       The command type.
     * @param jsonPayload The raw JSON payload string.
     * @return A future representing the result of the operation.
     */
    public CompletableFuture<Object> dispatch(@NotNull String type, @NotNull String jsonPayload) {
        String key = type.toUpperCase();
        if (!handlers.containsKey(key)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown command type: " + type));
        }
        try {
            // Execute the registered handler
            return handlers.get(key).apply(jsonPayload);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }
    
    public void clear() {
        handlers.clear();
    }
}