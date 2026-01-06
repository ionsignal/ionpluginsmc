package com.ionsignal.minecraft.ioncore.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The central hub for the PostgreSQL Event Bus architecture.
 * <p>
 * This class orchestrates:
 * <ul>
 *     <li><b>Inbound (Slow Lane):</b> Listening for {@code NOTIFY} events on a dedicated thread.</li>
 *     <li><b>Outbound (Fast Lane):</b> Broadcasting events via the connection pool.</li>
 * </ul>
 * <p>
 * <b>Thread Safety:</b>
 * <ul>
 *     <li>Inbound events are marshaled to the <b>Main Server Thread</b> before dispatch.</li>
 *     <li>Outbound I/O runs on a dedicated {@link ExecutorService} to avoid starving the common pool.</li>
 * </ul>
 */
public final class PostgresEventBus {

    private final IonCore plugin;
    private final DatabaseManager databaseManager;
    private final NetworkCommandRegistrar commandRegistrar;
    private final Gson gson = new Gson();

    // Dedicated Executor for Outbound I/O (Prevents ForkJoinPool starvation)
    private final ExecutorService ioExecutor;

    // Configuration (Cached on init to avoid async config access)
    private final String inboundChannel;
    private final String outboundChannel;
    private String jdbcUrl;
    private String listenerUsername;
    private String listenerPassword;

    // State
    private volatile boolean running = false;
    private Thread listenerThread;

    public PostgresEventBus(@NotNull IonCore plugin, @NotNull DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.commandRegistrar = new NetworkCommandRegistrar(plugin);
        
        // Load channel names from config or default
        this.inboundChannel = plugin.getConfig().getString("database.channels.inbound", "ion_ingress");
        this.outboundChannel = plugin.getConfig().getString("database.channels.outbound", "ion_telemetry");

        // Initialize dedicated I/O executor
        this.ioExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "IonCore-EventBus-IO");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the Event Bus.
     * <p>
     * This starts the dedicated Listener Thread which holds a permanent connection
     * to PostgreSQL to receive NOTIFY events.
     * 
     * @throws IllegalStateException if database configuration is incomplete.
     */
    public void initialize() {
        if (running) {
            return;
        }
        
        // Cache credentials on Main Thread (Safety Fix #4)
        this.jdbcUrl = plugin.getConfig().getString("database.jdbc-url");
        this.listenerUsername = plugin.getConfig().getString("database.username");
        this.listenerPassword = plugin.getConfig().getString("database.password");

        if (jdbcUrl == null || listenerUsername == null || listenerPassword == null) {
            throw new IllegalStateException(
                "Database configuration incomplete. Required: database.jdbc-url, database.username, database.password");
        }

        plugin.getLogger().info("Initializing PostgresEventBus...");
        plugin.getLogger().info("Channels -> Inbound: " + inboundChannel + " | Outbound: " + outboundChannel);
        
        this.running = true;
        this.startListenerThread();
    }

    private void startListenerThread() {
        this.listenerThread = new Thread(this::listenLoop, "IonCore-PG-Listener");
        this.listenerThread.setDaemon(true); // Ensure this doesn't prevent server shutdown
        this.listenerThread.start();
    }

    /**
     * The main loop for the inbound listener.
     * Uses a raw JDBC connection (bypassing Hikari) to maintain a persistent LISTEN state.
     * Includes automatic reconnection logic.
     */
    private void listenLoop() {
        plugin.getLogger().info("EventBus Listener Thread started.");

        while (running) {
            try {
                runListenerConnection();
            } catch (SQLException e) {
                if (running) {
                    plugin.getLogger().warning("EventBus connection lost: " + e.getMessage());
                    plugin.getLogger().info("Attempting reconnection in 5 seconds...");
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        plugin.getLogger().info("EventBus Listener Thread terminated.");
    }

    /**
     * Inner connection logic for the listener loop.
     */
    private void runListenerConnection() throws SQLException, InterruptedException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, listenerUsername, listenerPassword);
             Statement stmt = connection.createStatement()) {

            // 1. Issue the LISTEN command
            stmt.execute("LISTEN " + inboundChannel);
            plugin.getLogger().info("Listening on channel: " + inboundChannel);

            // Unwrap to Postgres specific API
            PGConnection pgConn = connection.unwrap(PGConnection.class);

            while (running) {
                // 2. Poll for notifications
                // Postgres JDBC 'getNotifications' is not blocking, so we must sleep.
                PGNotification[] notifications = pgConn.getNotifications();

                if (notifications != null) {
                    for (PGNotification notification : notifications) {
                        handleNotification(notification.getParameter());
                    }
                }

                // 3. Sleep to prevent CPU starvation
                // Postgres JDBC 'getNotifications' is not blocking, so we must sleep.
                Thread.sleep(50); 
            }
        }
    }

    /**
     * Broadcasts an event to the PostgreSQL network asynchronously.
     * <p>
     * This method:
     * 1. Wraps the payload in a standard envelope (type, timestamp, data).
     * 2. Serializes it to JSON.
     * 3. Borrows a connection from the Hikari pool.
     * 4. Executes 'SELECT pg_notify(...)'
     * 5. Returns the connection to the pool.
     *
     * @param type    The event type (e.g., "AGENT_STATE", "LOG_ENTRY").
     * @param payload The data object (will be serialized to JSON).
     */
    public void broadcast(@NotNull String type, @NotNull Object payload) {
        if (!running) return;

        // 1. Construct Envelope
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", type);
        envelope.addProperty("timestamp", System.currentTimeMillis());
        
        // Serialize payload to JsonElement tree to avoid double-string-escaping issues
        envelope.add("payload", gson.toJsonTree(payload));

        final String jsonString = gson.toJson(envelope);

        // 2. Run Async on Dedicated Executor (Critical Fix #2)
        CompletableFuture.runAsync(() -> {
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT pg_notify(?, ?)")) {
                
                ps.setString(1, outboundChannel);
                ps.setString(2, jsonString);
                ps.execute();
                
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to broadcast event [" + type + "]: " + e.getMessage());
            }
        }, ioExecutor);
    }

    /**
     * Processes a raw JSON payload received from the database.
     * <p>
     * Expected Format: { "type": "COMMAND_NAME", "payload": { ... } }
     */
    private void handleNotification(String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isEmpty()) return;

        try {
            // Parse the envelope
            JsonObject root = gson.fromJson(jsonPayload, JsonObject.class);
            
            if (!root.has("type")) {
                plugin.getLogger().warning("Received malformed event (missing 'type'): " + jsonPayload);
                return;
            }

            String type = root.get("type").getAsString();
            String data = root.has("payload") ? root.get("payload").toString() : "{}";

            // Dispatch to Registrar
            // CRITICAL FIX #1: Marshal to Main Thread
            // The listener thread is async. If the handler touches Bukkit API, it must be on the main thread.
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    commandRegistrar.dispatch(type, data);
                } catch (Exception e) {
                    plugin.getLogger().severe("Error executing network handler for " + type + ": " + e.getMessage());
                    e.printStackTrace();
                }
            });

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse inbound event: " + e.getMessage());
        }
    }

    /**
     * Stops the Event Bus and releases resources.
     */
    public void shutdown() {
        this.running = false;
        
        // Shutdown I/O Executor
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (listenerThread != null && listenerThread.isAlive()) {
            plugin.getLogger().info("Stopping EventBus Listener Thread...");
            listenerThread.interrupt();
            try {
                // Wait briefly for clean exit
                listenerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        commandRegistrar.clear();
        plugin.getLogger().info("PostgresEventBus stopped.");
    }

    /**
     * @return The registrar for handling inbound network commands.
     */
    public @NotNull NetworkCommandRegistrar getCommandRegistrar() {
        return commandRegistrar;
    }
    
    /**
     * @return The database manager instance.
     */
    public @NotNull DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
    
    public boolean isRunning() {
        return running;
    }
}