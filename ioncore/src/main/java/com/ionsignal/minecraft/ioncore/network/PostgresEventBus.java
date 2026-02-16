package com.ionsignal.minecraft.ioncore.network;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.database.DatabaseManager;
import com.ionsignal.minecraft.ioncore.json.JsonService;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Tuple;

import org.bukkit.Bukkit;

import java.util.concurrent.CompletableFuture;

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
                plugin.getConfig().getString("database.channels.inbound", "ion_commands"));
        this.eventChannel = plugin.getConfig().getString("database.channels.events",
                plugin.getConfig().getString("database.channels.outbound", "ion_events"));
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
        // In this phase, we simply pass the raw JSON to the registrar.
        // The registrar or handlers will use JsonService to parse specific types as needed.
        // For now, we assume the payload contains a "type" field at the root or is wrapped in an envelope.
        // TODO: Implement CommandEnvelope unwrapping in Phase 2.
        // For now, we parse partially to find the type, or let the registrar handle it.
        // Given the current registrar signature (String commandType, Consumer<String> handler),
        // we need to extract the type here.
        try {
            var root = jsonService.readTree(jsonPayload);
            if (root.has("type")) {
                String type = root.get("type").asText();
                commandRegistrar.dispatch(type, jsonPayload);
            } else if (root.has("payload") && root.get("payload").has("type")) {
                // Handle Envelope unwrapping if present
                String type = root.get("payload").get("type").asText();
                commandRegistrar.dispatch(type, jsonPayload);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse incoming notification: " + e.getMessage());
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