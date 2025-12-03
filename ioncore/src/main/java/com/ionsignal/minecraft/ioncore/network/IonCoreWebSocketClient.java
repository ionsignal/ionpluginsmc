package com.ionsignal.minecraft.ioncore.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.bukkit.plugin.Plugin;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A generic WebSocket client that routes JSON messages to the NetworkCommandRegistrar.
 * It is agnostic of the specific commands (e.g., Spawn/Despawn) it handles.
 */
public class IonCoreWebSocketClient extends WebSocketClient {
    private static final Logger LOGGER = Logger.getLogger(IonCoreWebSocketClient.class.getName());
    private final Plugin plugin;
    private final NetworkCommandRegistrar registrar;
    private final Gson gson = new Gson();
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.DISCONNECTED);

    public IonCoreWebSocketClient(@NotNull String url, @NotNull String key, @NotNull Plugin plugin, @NotNull NetworkCommandRegistrar registrar) {
        super(URI.create(url));
        this.plugin = plugin;
        this.registrar = registrar;
        addHeader("X-API-Key", key);
        // Ensure connection attempts don't block threads indefinitely
        setConnectionLostTimeout(30); 
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        state.set(ConnectionState.CONNECTED);
        LOGGER.info("[IonCore] WebSocket connection established.");
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            
            if (!json.has("type")) {
                LOGGER.warning("[IonCore] Received malformed message: missing 'type'");
                return;
            }
            
            String type = json.get("type").getAsString();
            String requestId = json.has("requestId") ? json.get("requestId").getAsString() : null;
            
            // Extract payload as raw string
            String payloadStr = "{}";
            if (json.has("payload")) {
                if (json.get("payload").isJsonObject()) {
                    payloadStr = json.get("payload").getAsJsonObject().toString();
                } else if (json.get("payload").isJsonPrimitive()) {
                    payloadStr = json.get("payload").getAsString();
                }
            }

            // Dispatch String
            registrar.dispatch(type, payloadStr)
                .thenAccept(result -> {
                    LOGGER.info("[IonCore] Command processed successfully: " + type);
                    if (requestId != null) {
                        sendAck(requestId, "SUCCESS", result);
                    }
                })
                .exceptionally(ex -> {
                    LOGGER.log(Level.WARNING, "[IonCore] Command execution failed: " + type, ex);
                    if (requestId != null) {
                        sendAck(requestId, "ERROR", ex.getMessage());
                    }
                    return null;
                });

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[IonCore] Error processing message", e);
        }
    }

    private void sendAck(String requestId, String status, Object data) {
        if (!isOpen()) return;
        try {
            JsonObject ack = new JsonObject();
            ack.addProperty("type", "ACK");
            ack.addProperty("refRequestId", requestId);
            ack.addProperty("status", status);
            ack.add("data", gson.toJsonTree(data));
            send(gson.toJson(ack));
        } catch (Exception e) {
            LOGGER.warning("[IonCore] Failed to send ACK: " + e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        state.set(ConnectionState.DISCONNECTED);
        if (remote) {
            LOGGER.warning(String.format("[IonCore] Connection closed by server: [%d] %s", code, reason));
            // Note: Reconnection logic is handled by the container/scheduler, not recursively here
        } else {
            LOGGER.info("[IonCore] Client closed connection.");
        }
    }

    @Override
    public void onError(Exception ex) {
        state.set(ConnectionState.ERROR);
        LOGGER.log(Level.SEVERE, "[IonCore] WebSocket Error", ex);
    }
    
    public ConnectionState getConnectionState() {
        return state.get();
    }
}