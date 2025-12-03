package com.ionsignal.minecraft.ioncore.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.bukkit.plugin.Plugin;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.logging.Level;
import java.util.logging.Logger;

public class IonCoreWebSocketServer extends WebSocketServer {
    private static final Logger LOGGER = Logger.getLogger(IonCoreWebSocketServer.class.getName());
    private final NetworkCommandRegistrar registrar;
    private final Gson gson = new Gson();

    public IonCoreWebSocketServer(int port, NetworkCommandRegistrar registrar) {
        super(new InetSocketAddress(port));
        this.registrar = registrar;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        LOGGER.info("[IonCore Server] New connection from " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        LOGGER.info("[IonCore Server] Closed connection to " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            
            if (!json.has("type")) {
                conn.send(createError(null, "Missing 'type' field"));
                return;
            }
            
            String type = json.get("type").getAsString();
            String requestId = json.has("requestId") ? json.get("requestId").getAsString() : null;
            
            // Extract payload as a raw string
            String payloadStr = "{}";
            if (json.has("payload")) {
                if (json.get("payload").isJsonObject()) {
                    payloadStr = json.get("payload").getAsJsonObject().toString();
                } else if (json.get("payload").isJsonPrimitive()) {
                    payloadStr = json.get("payload").getAsString();
                }
            }

            // Dispatch String instead of JsonObject
            registrar.dispatch(type, payloadStr)
                .thenAccept(result -> {
                    LOGGER.info("[IonCore Server] Command processed: " + type);
                    if (conn.isOpen()) {
                        conn.send(createAck(requestId, "SUCCESS", result));
                    }
                })
                .exceptionally(ex -> {
                    LOGGER.log(Level.WARNING, "[IonCore Server] Command failed: " + type, ex);
                    if (conn.isOpen()) {
                        conn.send(createAck(requestId, "ERROR", ex.getMessage()));
                    }
                    return null;
                });

        } catch (JsonSyntaxException e) {
            conn.send(createError(null, "Invalid JSON"));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing message", e);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOGGER.log(Level.SEVERE, "[IonCore Server] Error", ex);
    }

    @Override
    public void onStart() {
        LOGGER.info("[IonCore Server] Listening on port " + getPort());
    }

    // --- Helpers ---

    private String createAck(String requestId, String status, Object data) {
        JsonObject ack = new JsonObject();
        ack.addProperty("type", "ACK");
        if (requestId != null) ack.addProperty("refRequestId", requestId);
        ack.addProperty("status", status);
        ack.add("data", gson.toJsonTree(data));
        return gson.toJson(ack);
    }

    private String createError(String requestId, String msg) {
        return createAck(requestId, "ERROR", msg);
    }
}