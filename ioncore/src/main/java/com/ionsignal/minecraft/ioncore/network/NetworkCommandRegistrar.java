package com.ionsignal.minecraft.ioncore.network;

import com.ionsignal.minecraft.ioncore.IonCore;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registry for handling inbound network commands.
 * <p>
 * This class maps command "types" (e.g., "SPAWN_AGENT") to functional handlers.
 * It is used by the {@link PostgresEventBus} to dispatch events received from the database.
 */
public final class NetworkCommandRegistrar {

    private final IonCore plugin;
    private final Map<String, Consumer<String>> handlers = new ConcurrentHashMap<>();

    public NetworkCommandRegistrar(IonCore plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers a handler for a specific command type.
     *
     * @param commandType The unique identifier for the command (e.g., "SPAWN_AGENT").
     * @param handler     The logic to execute when this command is received. The input String is the raw JSON payload.
     */
    public void registerHandler(@NotNull String commandType, @NotNull Consumer<String> handler) {
        handlers.put(commandType, handler);
        plugin.getLogger().info("Registered Network Handler: " + commandType);
    }

    /**
     * Dispatches an inbound command to the appropriate handler.
     *
     * @param commandType The type of command received.
     * @param payload     The data associated with the command.
     */
    public void dispatch(@NotNull String commandType, @NotNull String payload) {
        Consumer<String> handler = handlers.get(commandType);
        if (handler != null) {
            try {
                handler.accept(payload);
            } catch (Exception e) {
                plugin.getLogger().severe("Error executing network handler for " + commandType + ": " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            plugin.getLogger().warning("Received unknown network command: " + commandType);
        }
    }

    /**
     * Clears all registered handlers.
     */
    public void clear() {
        handlers.clear();
    }
}