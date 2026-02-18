package com.ionsignal.minecraft.ioncore.network;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.database.DatabaseManager;
import com.ionsignal.minecraft.ioncore.json.JsonService;
import com.ionsignal.minecraft.ioncore.network.model.CommandEnvelope;
import com.ionsignal.minecraft.ioncore.network.model.IonCommand;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Tuple;

import org.bukkit.Bukkit;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.jetbrains.annotations.NotNull;

public final class PostgresEventBus {
    private final IonCore plugin;
    private final DatabaseManager databaseManager;
    private final JsonService jsonService;
    private final NetworkCommandRegistrar commandRegistrar;

    private final String commandChannel;
    private final String eventChannel;

    private PgConnection listenerConnection;
    private boolean running = false;
    private long reconnectTimerId = -1;

    public PostgresEventBus(@NotNull IonCore plugin, @NotNull DatabaseManager databaseManager, @NotNull JsonService jsonService) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.jsonService = jsonService;
        this.commandRegistrar = new NetworkCommandRegistrar(plugin);
        this.commandChannel = plugin.getConfig().getString("database.channels.commands",
                plugin.getConfig().getString("database.channels.inbound", "ion:commands"));
        this.eventChannel = plugin.getConfig().getString("database.channels.events",
                plugin.getConfig().getString("database.channels.outbound", "ion:events"));
    }

    public void initialize() {
        if (running)
            return;
        this.running = true;
        plugin.getLogger().info("Initializing PostgresEventBus (Vert.x 5.0)...");
        plugin.getLogger().info("Channels -> Commands: " + commandChannel + " | Events: " + eventChannel);
        connectListener();
    }

    private void connectListener() {
        if (!running)
            return;
        Vertx vertx = databaseManager.getVertx();
        PgConnection.connect(vertx, databaseManager.getConnectOptions())
                .onSuccess(conn -> {
                    this.listenerConnection = conn;
                    plugin.getLogger().info("EventBus Listener Connected.");
                    // Handle Connection Loss
                    conn.closeHandler(v -> handleClose("Connection Closed"));
                    conn.exceptionHandler(e -> handleClose("Exception: " + e.getMessage()));
                    // Setup Notification Handler
                    conn.notificationHandler(notification -> {
                        if (!notification.getChannel().equals(commandChannel))
                            return;
                        // Bridge to Main Thread
                        Bukkit.getScheduler().runTask(plugin, () -> handleNotification(notification.getPayload()));
                    });
                    conn.query("LISTEN " + commandChannel).execute()
                            .onFailure(e -> {
                                plugin.getLogger().warning("Failed to issue LISTEN command: " + e.getMessage());
                                conn.close();
                            });
                })
                .onFailure(e -> {
                    plugin.getLogger().warning("EventBus Listener Connection Failed: " + e.getMessage());
                    scheduleReconnect();
                });
    }

    private void handleClose(String reason) {
        if (!running)
            return;
        plugin.getLogger().warning("EventBus Listener Lost (" + reason + "). Reconnecting...");
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!running || reconnectTimerId != -1)
            return;
        reconnectTimerId = databaseManager.getVertx().setTimer(5000, id -> {
            reconnectTimerId = -1;
            connectListener();
        });
    }

    /**
     * Broadcasts an event to the PostgreSQL channel using the Strict Envelope structure.
     *
     * @param payload
     *            The event data payload (Envelope).
     * @return A future completing when the notification is sent.
     */
    public CompletableFuture<Void> broadcast(@NotNull Object payload) {
        if (!running)
            return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> future = new CompletableFuture<>();
        String jsonString = jsonService.toJson(payload);
        databaseManager.getPgPool()
                .preparedQuery("SELECT pg_notify($1, $2)")
                .execute(Tuple.of(eventChannel, jsonString))
                .onSuccess(rows -> future.complete(null))
                .onFailure(e -> {
                    plugin.getLogger().warning("Failed to broadcast event: " + e.getMessage());
                    future.completeExceptionally(e);
                });
        return future;
    }

    private void handleNotification(String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isEmpty())
            return;
        // Replaced manual parsing with strict Envelope unwrapping
        try {
            // Unwrap the envelope
            IonCommand command = unwrapEnvelope(jsonPayload);
            if (command == null) {
                // Warning logged in unwrapEnvelope
                return;
            }
            // Dispatch to typed handler
            commandRegistrar.dispatch(command);
        } catch (Exception e) {
            plugin.getLogger().severe("Critical error processing notification: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Unwraps a CommandEnvelope and extracts the typed IonCommand payload.
     *
     * @param json
     *            The raw JSON string from the notification
     * @return The typed command payload, or null if deserialization fails
     */
    private IonCommand unwrapEnvelope(String json) {
        try {
            // Deserialize to CommandEnvelope (Jackson handles polymorphic payload via IonCommand annotations)
            CommandEnvelope envelope = jsonService.fromJson(json, CommandEnvelope.class);
            // Validate payload
            if (envelope.payload() == null) {
                plugin.getLogger().warning("CommandEnvelope has null payload");
                return null;
            }
            // Log successful deserialization at debug level
            if (plugin.getLogger().isLoggable(Level.FINE)) {
                plugin.getLogger().fine(
                        "Unwrapped envelope " + envelope.id() + " with payload type: " +
                                envelope.payload().getClass().getSimpleName());
            }
            return envelope.payload();

        } catch (RuntimeException e) {
            plugin.getLogger().severe("Failed to deserialize CommandEnvelope: " + e.getMessage());
            String preview = json.length() > 200 ? json.substring(0, 200) + "..." : json;
            plugin.getLogger().severe("Problematic JSON: " + preview);
            return null;
        }
    }

    public void shutdown() {
        this.running = false;
        if (reconnectTimerId != -1) {
            try {
                databaseManager.getVertx().cancelTimer(reconnectTimerId);
            } catch (Exception ignored) {
                // error
            }
        }
        if (listenerConnection != null) {
            listenerConnection.close();
            listenerConnection = null;
        }
        commandRegistrar.clear();
        plugin.getLogger().info("PostgresEventBus stopped.");
    }

    public @NotNull NetworkCommandRegistrar getCommandRegistrar() {
        return commandRegistrar;
    }
}