package com.ionsignal.minecraft.ioncore;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.network.IonCoreWebSocketClient;
import com.ionsignal.minecraft.ioncore.network.IonCoreWebSocketServer;
import com.ionsignal.minecraft.ioncore.network.NetworkCommandRegistrar;
import org.bukkit.Bukkit;

import java.util.concurrent.CompletableFuture;

/**
 * Service Container for IonCore.
 * Manages the lifecycle of networking components and provides the public API for other plugins.
 */
public class CoreServiceContainer {
    private final IonCore plugin;
    private final NetworkCommandRegistrar commandRegistrar;
    private final Gson gson;
    
    // Nullable: Null if mode is "disabled" or configuration is invalid
    private IonCoreWebSocketClient webSocket;
    private IonCoreWebSocketServer webSocketServer;

    public CoreServiceContainer(IonCore plugin) {
        this.plugin = plugin;
        this.gson = new Gson();
        // Always initialize the registrar, even in offline mode, so Nerrus can register handlers safely.
        this.commandRegistrar = new NetworkCommandRegistrar(plugin.getLogger());
        
        initializeNetworking();
    }

    private void initializeNetworking() {
        String mode = plugin.getConfig().getString("mode", "disabled");
        
        // --- MODE: SERVER (For Testing/Local) ---
        if ("server".equalsIgnoreCase(mode)) {
            int port = plugin.getConfig().getInt("server.port", 8088);
            try {
                this.webSocketServer = new IonCoreWebSocketServer(port, commandRegistrar);
                this.webSocketServer.start();
                plugin.getLogger().info("[IonCore] Running in SERVER mode on port " + port);
            } catch (Exception e) {
                plugin.getLogger().severe("[IonCore] Failed to start WebSocket Server: " + e.getMessage());
            }
            return;
        }

        // --- MODE: CLIENT (Production) ---
        if ("client".equalsIgnoreCase(mode)) {
            // ... existing client initialization code from Phase 0 ...
            String url = plugin.getConfig().getString("client.broker-url");
            String key = plugin.getConfig().getString("api-key");
            // ... check url ...
            try {
                this.webSocket = new IonCoreWebSocketClient(url, key, plugin, commandRegistrar);
                this.webSocket.connect();
            } catch (Exception e) {
                plugin.getLogger().severe("[IonCore] Failed to connect Client: " + e.getMessage());
            }
            return;
        }
        
        plugin.getLogger().info("[IonCore] Networking disabled via config.");
    }

    /**
     * Gets the command registrar.
     * Use this to register listeners for incoming network commands.
     */
    public NetworkCommandRegistrar getCommandRegistrar() {
        return commandRegistrar;
    }

    /**
     * Broadcasts a message to the external web service.
     * Safe to call from any thread. Fails silently if networking is offline.
     *
     * @param type    The event type (e.g., "AGENT_SPAWNED").
     * @param payload The object to serialize as the payload.
     */
    public void broadcast(String type, Object payload) {
        // Prepare JSON
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject envelope = new JsonObject();
                envelope.addProperty("type", type);
                envelope.addProperty("timestamp", System.currentTimeMillis());
                envelope.add("payload", gson.toJsonTree(payload));
                String jsonStr = gson.toJson(envelope);

                // Strategy: Send to whichever channel is active
                if (webSocket != null && webSocket.isOpen()) {
                    webSocket.send(jsonStr);
                } else if (webSocketServer != null) {
                    // Broadcast to ALL connected Postman/Web clients
                    webSocketServer.broadcast(jsonStr);
                }
            } catch (Exception e) {
                // Log debug ...
            }
        });
    }

    public void shutdown() {
        try {
            if (webSocket != null) webSocket.close();
            if (webSocketServer != null) webSocketServer.stop();
        } catch (Exception e) {
            e.printStackTrace();
        }
        commandRegistrar.clear();
    }
}