package com.ionsignal.minecraft.ioncore.network;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.database.DatabaseManager;
import com.ionsignal.minecraft.ioncore.json.JsonService;
import com.ionsignal.minecraft.ioncore.network.model.CommandEnvelope;

import io.vertx.core.Vertx;
import io.vertx.sqlclient.Tuple;
import io.vertx.pgclient.pubsub.PgSubscriber;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import org.jetbrains.annotations.NotNull;

public final class PostgresEventBus {
    private final IonCore plugin;
    private final JsonService jsonService;
    private final DatabaseManager databaseManager;
    private final NetworkCommandRegistrar commandRegistrar;

    private final String commandChannel;
    private final String eventChannel;

    private PgSubscriber subscriber;
    private boolean running = false;

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
        plugin.getLogger().info("Initializing PostgresEventBus");
        plugin.getLogger().info("Channels -> Commands: " + commandChannel + " | Events: " + eventChannel);
        start();
    }

    private void start() {
        if (!running)
            return;
        Vertx vertx = databaseManager.getVertx();
        this.subscriber = PgSubscriber.subscriber(vertx, databaseManager.getConnectOptions());
        this.subscriber.reconnectPolicy(retries -> {
            long delay = Math.min(retries * 1000L, 10000L);
            if (running) {
                plugin.getLogger().warning("EventBus connection lost. Reconnecting in " + delay + "ms (Attempt " + (retries + 1) + ")");
            }
            return delay;
        });
        this.subscriber.channel(commandChannel).handler(payload -> {
            plugin.getVirtualThreadExecutor().execute(() -> handleNotification(payload));
        });
        this.subscriber.connect().onComplete(ar -> {
            if (ar.succeeded()) {
                plugin.getLogger().info("EventBus Listener Connected.");
            } else {
                plugin.getLogger().warning("EventBus Listener Connection Failed: " + ar.cause().getMessage());
            }
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
        if (!running) {
            return CompletableFuture.failedFuture(new IllegalStateException("EventBus is offline"));
        }
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
        try {
            JsonNode payloadNode = unwrapEnvelope(jsonPayload);
            if (payloadNode == null) {
                return;
            }
            commandRegistrar.dispatch(payloadNode);
        } catch (Exception e) {
            plugin.getLogger().severe("Critical error processing notification: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Unwraps a CommandEnvelope and extracts the raw JsonNode payload.
     *
     * @param json
     *            The raw JSON string from the notification
     * @return The payload JsonNode, or null if deserialization fails
     */
    private JsonNode unwrapEnvelope(String json) {
        try {
            CommandEnvelope envelope = jsonService.fromJson(json, CommandEnvelope.class);
            if (envelope.payload() == null) {
                plugin.getLogger().warning("CommandEnvelope has null payload");
                return null;
            }
            if (plugin.getLogger().isLoggable(Level.FINE)) {
                plugin.getLogger().fine(
                        "Unwrapped envelope " + envelope.id() + ". Payload is generic JsonNode.");
            }
            return envelope.payload();
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Failed to deserialize CommandEnvelope: " + e.getMessage());
            e.printStackTrace();
            String preview = json.length() > 200 ? json.substring(0, 200) + "..." : json;
            plugin.getLogger().severe("Problematic JSON: " + preview);
            return null;
        }
    }

    public void shutdown() {
        this.running = false;
        if (subscriber != null) {
            subscriber.close();
            subscriber = null;
        }
        commandRegistrar.clear();
        plugin.getLogger().info("PostgresEventBus stopped.");
    }

    public @NotNull NetworkCommandRegistrar getCommandRegistrar() {
        return commandRegistrar;
    }
}