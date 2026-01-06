package com.ionsignal.minecraft.ioncore.database;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.NotNull;
import org.postgresql.Driver;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of the HikariCP Connection Pool.
 * <p>
 * This class is responsible for establishing the connection to PostgreSQL
 * and providing pooled connections for the "Fast Lane" (Writes).
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. {@link #getConnection()} can be called concurrently.
 */
public final class DatabaseManager {

    private final IonCore plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(final IonCore plugin) {
        this.plugin = plugin;
    }

    /**
     * Initializes the HikariCP data source using values from config.yml.
     *
     * @throws IllegalStateException if the database configuration is missing or invalid.
     */
    public void initialize() {
        plugin.getLogger().info("Initializing DatabaseManager (HikariCP)...");

        // 1. Force load the PostgreSQL Driver
        // This is critical when shading/relocating dependencies.
        // We use the class reference so the Shadow plugin updates the package name in the bytecode.
        try {
            Class.forName(Driver.class.getName());
            plugin.getLogger().info("Loaded PostgreSQL Driver: " + Driver.class.getName());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to load PostgreSQL JDBC driver. Ensure dependencies are shaded correctly.", e);
        }

        final var config = plugin.getConfig();
        final String jdbcUrl = config.getString("database.jdbc-url");
        final String username = config.getString("database.username");
        final String password = config.getString("database.password");
        final int poolSize = config.getInt("database.pool-settings.maximum-pool-size", 5);
        final long timeout = config.getLong("database.pool-settings.connection-timeout", 5000);

        if (jdbcUrl == null || username == null || password == null) {
            throw new IllegalStateException("Database configuration is missing in config.yml. Please check 'database' section.");
        }

        final HikariConfig hikariConfig = new HikariConfig();
        
        // Explicitly set the driver class name to ensure Hikari finds the relocated class
        hikariConfig.setDriverClassName(Driver.class.getName());
        
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);

        // Pool Tuning for Minecraft Environment
        hikariConfig.setMaximumPoolSize(poolSize);
        hikariConfig.setMinimumIdle(1); // Keep at least one connection ready
        hikariConfig.setConnectionTimeout(timeout);
        hikariConfig.setIdleTimeout(TimeUnit.MINUTES.toMillis(10));
        hikariConfig.setMaxLifetime(TimeUnit.MINUTES.toMillis(30));
        
        // Driver specific optimizations
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        hikariConfig.setPoolName("IonCore-HikariPool");

        try {
            this.dataSource = new HikariDataSource(hikariConfig);
            // Test connection immediately to fail fast
            try (Connection conn = this.dataSource.getConnection()) {
                if (!conn.isValid(2)) {
                    throw new SQLException("Connection validation failed.");
                }
            }
            plugin.getLogger().info("Database connection established successfully.");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to connect to PostgreSQL: " + e.getMessage());
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    /**
     * Borrows a connection from the pool.
     * <p>
     * <b>Usage:</b> Must be used within a try-with-resources block to ensure the connection
     * is returned to the pool.
     *
     * @return A valid SQL Connection.
     * @throws SQLException if a connection cannot be obtained.
     */
    public @NotNull Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("DataSource is not initialized or has been closed.");
        }
        return dataSource.getConnection();
    }

    /**
     * Closes the connection pool. Should be called on server shutdown.
     */
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            plugin.getLogger().info("Closing DatabaseManager...");
            dataSource.close();
        }
    }
    
    /**
     * @return The raw HikariDataSource, primarily for metrics or advanced config.
     */
    public HikariDataSource getDataSource() {
        return dataSource;
    }
}