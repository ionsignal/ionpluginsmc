package com.ionsignal.minecraft.ioncore.network;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.config.TenantConfig;
import com.ionsignal.minecraft.ioncore.exceptions.RpcException;
import com.ionsignal.minecraft.ioncore.network.model.IonCommand;
import com.ionsignal.minecraft.ioncore.network.model.IonEvent;
import com.ionsignal.minecraft.ioncore.json.JsonService;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.ErrorListener;
import io.nats.client.Nats;
import io.nats.client.Options;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The stateless NATS implementation.
 */
public final class NatsBroker implements IonEventBroker {
    private final IonCore plugin;
    private final TenantConfig tenantConfig;
    private final JsonService jsonService;
    private final NetworkCommandRegistrar commandRegistrar;
    private final ExecutorService virtualThreadExecutor;

    private Connection nc;
    private Dispatcher dispatcher;
    private boolean running = false;

    public NatsBroker(IonCore plugin, TenantConfig tenantConfig, JsonService jsonService, ExecutorService virtualThreadExecutor) {
        this.plugin = plugin;
        this.tenantConfig = tenantConfig;
        this.jsonService = jsonService;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.commandRegistrar = new NetworkCommandRegistrar(plugin);
    }

    public void initialize() {
        if (running)
            return;
        this.running = true;
        plugin.getLogger().info("Initializing NatsBroker for Tenant: " + tenantConfig.getTenantId());
        Options.Builder builder = new Options.Builder()
                .server(tenantConfig.getNatsServerUrl())
                .maxReconnects(-1) // Infinite reconnects
                .connectionListener((conn, type) -> {
                    if (type == ConnectionListener.Events.CONNECTED) {
                        plugin.getLogger().info("[NATS] Connected to " + conn.getConnectedUrl());
                    } else if (type == ConnectionListener.Events.DISCONNECTED) {
                        plugin.getLogger().warning("[NATS] Disconnected.");
                    } else if (type == ConnectionListener.Events.RECONNECTED) {
                        plugin.getLogger().info("[NATS] Reconnected to " + conn.getConnectedUrl());
                    }
                })
                .errorListener(new ErrorListener() {
                    @Override
                    public void errorOccurred(Connection conn, String error) {
                        plugin.getLogger().severe("[NATS] Error: " + error);
                    }

                    @Override
                    public void exceptionOccurred(Connection conn, Exception exp) {
                        plugin.getLogger().severe("[NATS] Exception: " + exp.getMessage());
                    }
                });
        // Apply Token Authentication if provided
        if (tenantConfig.getNatsToken() != null && !tenantConfig.getNatsToken().isBlank()) {
            builder.token(tenantConfig.getNatsToken().toCharArray());
        }
        Options options = builder.build();
        // Connect asynchronously to prevent blocking server startup
        CompletableFuture.runAsync(() -> {
            // The retry loop MUST be inside the Virtual Thread
            while (this.running && this.nc == null) {
                try {
                    this.nc = Nats.connect(options);
                    this.dispatcher = nc.createDispatcher(msg -> {
                        virtualThreadExecutor.execute(() -> handleMessage(msg.getData()));
                    });
                    String cmdSubject = "ion.cmd." + tenantConfig.getTenantId() + ".>";
                    dispatcher.subscribe(cmdSubject);
                    plugin.getLogger().info("[NATS] Subscribed to commands on: " + cmdSubject);
                    // Exit the retry loop once successfully connected
                    break;
                } catch (Exception e) {
                    plugin.getLogger().warning("NATS not available yet, retrying in 3 seconds... (" + e.getMessage() + ")");
                    try {
                        Thread.sleep(3000); // Safe to sleep, we are on a Virtual Thread
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, virtualThreadExecutor);
    }

    private void handleMessage(byte[] data) {
        if (data == null || data.length == 0)
            return;
        try {
            String json = new String(data, StandardCharsets.UTF_8);
            JsonNode root = jsonService.readTree(json);
            // Bridging Logic: If the payload is wrapped in a legacy CommandEnvelope, extract it.
            // Otherwise, pass the raw root (preparing for Phase 2/3 Node.js raw payloads).
            JsonNode payloadToDispatch = root.has("payload") ? root.get("payload") : root;
            commandRegistrar.dispatch(payloadToDispatch);
        } catch (Exception e) {
            plugin.getLogger().severe("Critical error processing NATS message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public CompletableFuture<Void> broadcast(Object payload) {
        if (!running || nc == null || nc.getStatus() != Connection.Status.CONNECTED) {
            return CompletableFuture.failedFuture(new IllegalStateException("NATS is offline"));
        }
        return CompletableFuture.runAsync(() -> {
            String json = jsonService.toJson(payload);
            String type = "unknown";
            if (payload instanceof IonEvent e)
                type = e.type();
            else if (payload instanceof IonCommand c)
                type = c.type();
            String subject = "ion.evt." + tenantConfig.getTenantId() + "." + type;
            nc.publish(subject, json.getBytes(StandardCharsets.UTF_8));
        }, virtualThreadExecutor);
    }

    @Override
    public CompletableFuture<JsonNode> requestAsync(String subject, Object payload) {
        if (!running || nc == null || nc.getStatus() != Connection.Status.CONNECTED) {
            return CompletableFuture.failedFuture(new IllegalStateException("NATS is offline"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return jsonService.toJson(payload);
            } catch (Exception e) {
                throw new RuntimeException("Payload serialization failed", e);
            }
        }, virtualThreadExecutor)
                .thenCompose(json -> nc.request(subject, json.getBytes(StandardCharsets.UTF_8)))
                .orTimeout(tenantConfig.getRpcTimeoutSeconds(), TimeUnit.SECONDS)
                .thenApplyAsync(reply -> {
                    if (reply == null || reply.getData() == null || reply.getData().length == 0) {
                        throw new RuntimeException("NATS Request returned empty response.");
                    }
                    try {
                        JsonNode root = jsonService.readTree(new String(reply.getData(), StandardCharsets.UTF_8));
                        if (root.has("success")) {
                            if (root.get("success").asBoolean()) {
                                return root.has("data") ? root.get("data") : jsonService.getObjectMapper().createObjectNode();
                            } else {
                                String error = root.has("error") ? root.get("error").asText() : "UNKNOWN_ERROR";
                                String details = root.has("details") ? root.get("details").toString() : "";
                                throw new RpcException(error, details);
                            }
                        }
                        return root;
                    } catch (Exception e) {
                        if (e instanceof RpcException)
                            throw (RpcException) e;
                        throw new RuntimeException("Failed to parse RPC response", e);
                    }
                }, virtualThreadExecutor);
    }

    @Override
    public NetworkCommandRegistrar getCommandRegistrar() {
        return commandRegistrar;
    }

    @Override
    public void shutdown() {
        this.running = false;
        commandRegistrar.clear();
        if (dispatcher != null && nc != null) {
            nc.closeDispatcher(dispatcher);
        }
        if (nc != null) {
            try {
                nc.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        plugin.getLogger().info("NatsBroker stopped.");
    }
}