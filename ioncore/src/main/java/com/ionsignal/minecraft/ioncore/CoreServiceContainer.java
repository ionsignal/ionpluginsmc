package com.ionsignal.minecraft.ioncore;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ionsignal.minecraft.ioncore.debug.DebugSessionRegistry;
import com.ionsignal.minecraft.ioncore.debug.DebugVisualizationTask;
import com.ionsignal.minecraft.ioncore.debug.VisualizationProviderRegistry;
import com.ionsignal.minecraft.ioncore.network.IonCoreWebSocketClient;
import com.ionsignal.minecraft.ioncore.network.IonCoreWebSocketServer;
import com.ionsignal.minecraft.ioncore.network.NetworkCommandRegistrar;
import com.ionsignal.minecraft.ioncore.telemetry.TelemetryManager;

import java.util.concurrent.CompletableFuture;

/**
 * Service Container for IonCore.
 * Acts as the Composition Root, managing the lifecycle of all subsystems (Debug, Networking,
 * Telemetry) in a strict dependency order.
 */
public class CoreServiceContainer {
    private final IonCore plugin;
    private final Gson gson;

    // Subsystems
    private final NetworkCommandRegistrar commandRegistrar;
    private DebugSessionRegistry debugRegistry;
    private VisualizationProviderRegistry visualizationRegistry;
    private TelemetryManager telemetryManager;

    // Networking State
    private IonCoreWebSocketClient webSocket;
    private IonCoreWebSocketServer webSocketServer;

    public CoreServiceContainer(IonCore plugin) {
        this.plugin = plugin;
        this.gson = new Gson();
        // Registrar is initialized early as it has no external dependencies
        this.commandRegistrar = new NetworkCommandRegistrar(plugin.getLogger());
    }

    /**
     * Initializes all services in strict dependency order.
     * 
     * @throws ServiceInitializationException
     *             if a critical component fails.
     */
    public void initialize() {
        try {
            // Initialize Debug Subsystem (Base Layer)
            this.debugRegistry = new DebugSessionRegistry();
            this.visualizationRegistry = new VisualizationProviderRegistry();
            // Start Visualization Task (Depends on Registries)
            new DebugVisualizationTask(debugRegistry, visualizationRegistry)
                    .runTaskTimer(plugin, 0L, 1L);
            // Initialize Networking (Middle Layer)
            initializeNetworking();
            // Initialize Telemetry (Top Layer - Depends on Networking)
            this.telemetryManager = new TelemetryManager(plugin);
            this.telemetryManager.start();
            plugin.getLogger().info("Core services initialized successfully.");
        } catch (Exception e) {
            throw new ServiceInitializationException("Failed to initialize Core services", e);
        }
    }

    private void initializeNetworking() {
        String mode = plugin.getConfig().getString("mode", "disabled");
        if ("server".equalsIgnoreCase(mode)) {
            int port = plugin.getConfig().getInt("server.port", 8088);
            try {
                this.webSocketServer = new IonCoreWebSocketServer(port, commandRegistrar);
                this.webSocketServer.start();
                plugin.getLogger().info("[IonCore] Running in SERVER mode on port " + port);
            } catch (Exception e) {
                plugin.getLogger().severe("[IonCore] Failed to start WebSocket Server: " + e.getMessage());
            }
        } else if ("client".equalsIgnoreCase(mode)) {
            String url = plugin.getConfig().getString("client.broker-url");
            String key = plugin.getConfig().getString("api-key");
            try {
                this.webSocket = new IonCoreWebSocketClient(url, key, plugin, commandRegistrar);
                this.webSocket.connect();
            } catch (Exception e) {
                plugin.getLogger().severe("[IonCore] Failed to connect Client: " + e.getMessage());
            }
        } else {
            plugin.getLogger().info("[IonCore] Networking disabled via config.");
        }
    }

    /**
     * Shuts down services in reverse dependency order.
     */
    public void shutdown() {
        // Stop Telemetry (Stop producing data)
        if (telemetryManager != null) {
            telemetryManager.stop();
        }
        // Shutdown Networking (Stop transmitting data)
        try {
            if (webSocket != null)
                webSocket.close();
            if (webSocketServer != null)
                webSocketServer.stop();
        } catch (Exception e) {
            e.printStackTrace();
        }
        commandRegistrar.clear();
        // Cleanup Debug Subsystem (Clear state)
        if (debugRegistry != null) {
            int count = debugRegistry.size();
            debugRegistry.clear();
            if (count > 0)
                plugin.getLogger().info("Cleaned up " + count + " debug sessions.");
        }
        if (visualizationRegistry != null) {
            visualizationRegistry.clear();
        }
    }

    public NetworkCommandRegistrar getCommandRegistrar() {
        return commandRegistrar;
    }

    public DebugSessionRegistry getDebugRegistry() {
        return debugRegistry;
    }

    public VisualizationProviderRegistry getVisualizationRegistry() {
        return visualizationRegistry;
    }

    public TelemetryManager getTelemetryManager() {
        return telemetryManager;
    }

    public void broadcast(String type, Object payload) {
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject envelope = new JsonObject();
                envelope.addProperty("type", type);
                envelope.addProperty("timestamp", System.currentTimeMillis());
                envelope.add("payload", gson.toJsonTree(payload));
                String jsonStr = gson.toJson(envelope);
                if (webSocket != null && webSocket.isOpen()) {
                    webSocket.send(jsonStr);
                } else if (webSocketServer != null) {
                    webSocketServer.broadcast(jsonStr);
                }
            } catch (Exception e) {
                // Fail silently in async broadcast
            }
        });
    }
}