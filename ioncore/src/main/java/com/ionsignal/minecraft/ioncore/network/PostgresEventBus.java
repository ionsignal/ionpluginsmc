package com.ionsignal.minecraft.ioncore.network;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.database.DatabaseManager;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.Tuple;

import org.bukkit.Bukkit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.jetbrains.annotations.NotNull;

public final class PostgresEventBus {
    private final IonCore plugin;
    private final DatabaseManager databaseManager;
    private final NetworkCommandRegistrar commandRegistrar;
    private final Gson gson = new Gson();

    private final String commandChannel;
    private final String eventChannel;

    private PgConnection listenerConnection;
    private boolean running = false;
    private long reconnectTimerId = -1;

    public PostgresEventBus(@NotNull IonCore plugin, @NotNull DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
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
        // Exponential backoff or simple delay
        reconnectTimerId = databaseManager.getVertx().setTimer(5000, id -> {
            reconnectTimerId = -1;
            connectListener();
        });
    }

    public void broadcast(@NotNull String type, @NotNull Object payload) {
        if (!running)
            return;
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", type);
        envelope.addProperty("timestamp", System.currentTimeMillis());
        envelope.add("payload", gson.toJsonTree(payload));
        String jsonString = gson.toJson(envelope);
        databaseManager.getPgPool()
                .preparedQuery("SELECT pg_notify($1, $2)")
                .execute(Tuple.of(eventChannel, jsonString))
                .onFailure(e -> plugin.getLogger().warning("Failed to broadcast event [" + type + "]: " + e.getMessage()));
    }

    private void handleNotification(String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isEmpty())
            return;
        try {
            JsonObject root = gson.fromJson(jsonPayload, JsonObject.class);
            if (!root.has("type"))
                return;
            String type = root.get("type").getAsString();
            String data = root.has("payload") ? root.get("payload").toString() : "{}";
            commandRegistrar.dispatch(type, data);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse inbound event: " + e.getMessage());
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