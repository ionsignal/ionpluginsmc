package com.ionsignal.minecraft.ioncore.network;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.network.model.IonCommand;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registry for handling inbound network commands.
 */
public final class NetworkCommandRegistrar {
    private final IonCore plugin;
    private final Map<Class<? extends IonCommand>, Consumer<IonCommand>> handlers = new ConcurrentHashMap<>();

    public NetworkCommandRegistrar(IonCore plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers a type-safe handler for a specific command class.
     * 
     * @param commandClass
     *            The concrete command class (e.g., SpawnPayload.class)
     * @param handler
     *            The handler to invoke when this command type is received
     * @param <T>
     *            The command type
     */
    public <T extends IonCommand> void registerHandler(
            @NotNull Class<T> commandClass,
            @NotNull Consumer<T> handler) {
        handlers.put(commandClass, cmd -> handler.accept(commandClass.cast(cmd)));
        plugin.getLogger().info("Registered handler for: " + commandClass.getSimpleName());
    }

    /**
     * Dispatches a command to its registered handler.
     * 
     * @param command
     *            The deserialized command payload
     */
    public void dispatch(@NotNull IonCommand command) {
        Class<?> commandClass = command.getClass();
        Consumer<IonCommand> handler = handlers.get(commandClass);
        if (handler != null) {
            try {
                handler.accept(command);
            } catch (Exception e) {
                plugin.getLogger().severe(
                        "Error executing handler for " + commandClass.getSimpleName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            plugin.getLogger().warning(
                    "Received unhandled command type: " + commandClass.getSimpleName() +
                            " (Type ID: " + command.type() + ")");
        }
    }

    /**
     * Clears all registered handlers.
     */
    public void clear() {
        handlers.clear();
    }
}