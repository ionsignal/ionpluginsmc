package com.ionsignal.minecraft.ioncore.database;

import com.ionsignal.minecraft.ioncore.IonCore;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class DatabaseManager {
    private final IonCore plugin;

    private Vertx vertx;
    private Pool pgPool;
    private PgConnectOptions connectOptions;

    private Properties secrets;

    public DatabaseManager(final IonCore plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        plugin.getLogger().info("Initializing DatabaseManager (Vert.x 5.0)...");

        // 1. Load Secrets
        File file = new File(plugin.getDataFolder(), "secrets.properties");
        if (file.exists()) {
            this.secrets = new Properties();
            try (FileInputStream fis = new FileInputStream(file)) {
                this.secrets.load(fis);
                plugin.getLogger().info("Loaded secrets.properties");
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to load secrets.properties: " + e.getMessage());
            }
        }

        // 2. Resolve Configuration
        String hostUrl = resolveValue("database.jdbc-url", "ION_DB_URL");
        String username = resolveValue("database.username", "ION_DB_USERNAME");
        String password = resolveValue("database.password", "ION_DB_PASSWORD");

        if (hostUrl == null || username == null || password == null) {
            throw new IllegalStateException("Database configuration is missing.");
        }

        // 3. Configure Connect Options
        try {
            // Remove jdbc: prefix if present to satisfy Vert.x URI parser
            String cleanUrl = hostUrl.replace("jdbc:", "");
            this.connectOptions = PgConnectOptions.fromUri(cleanUrl);
            this.connectOptions.setUser(username);
            this.connectOptions.setPassword(password);
            this.connectOptions.setCachePreparedStatements(true);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Database URL: " + hostUrl, e);
        }

        // 4. Initialize Vert.x Instance
        this.vertx = Vertx.vertx();

        // 5. Configure Pool Options
        int poolSize = plugin.getConfig().getInt("database.pool-settings.maximum-pool-size", 5);
        PoolOptions poolOptions = new PoolOptions()
                .setMaxSize(poolSize);

        // 6. Create Pool using the static factory method from Pool.java
        // This ensures we bind to our specific Vertx instance.
        this.pgPool = Pool.pool(vertx, connectOptions, poolOptions);

        // 7. Test Connection (Async)
        this.pgPool.getConnection()
                .onSuccess(conn -> {
                    plugin.getLogger().info("Reactive Database Connection Established Successfully.");
                    conn.close();
                })
                .onFailure(err -> {
                    plugin.getLogger().severe("Failed to establish initial database connection: " + err.getMessage());
                });
    }

    private String resolveValue(String configKey, String envVarKey) {
        String envVal = System.getenv(envVarKey);
        if (envVal != null && !envVal.isBlank())
            return envVal;
        if (secrets != null && secrets.containsKey(configKey))
            return secrets.getProperty(configKey);
        return plugin.getConfig().getString(configKey);
    }

    public void shutdown() {
        plugin.getLogger().info("Closing DatabaseManager...");
        if (pgPool != null) {
            pgPool.close();
        }
        if (vertx != null) {
            CountDownLatch latch = new CountDownLatch(1);
            vertx.close().onComplete(ar -> latch.countDown());
            try {
                if (!latch.await(2, TimeUnit.SECONDS)) {
                    plugin.getLogger().warning("Vert.x shutdown timed out.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public @NotNull Vertx getVertx() {
        if (vertx == null)
            throw new IllegalStateException("DatabaseManager not initialized");
        return vertx;
    }

    public @NotNull Pool getPgPool() {
        if (pgPool == null)
            throw new IllegalStateException("DatabaseManager not initialized");
        return pgPool;
    }

    public @NotNull PgConnectOptions getConnectOptions() {
        return connectOptions;
    }
}