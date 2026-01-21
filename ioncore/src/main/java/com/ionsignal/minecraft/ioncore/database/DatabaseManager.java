package com.ionsignal.minecraft.ioncore.database;

import com.ionsignal.minecraft.ioncore.IonCore;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.jetbrains.annotations.NotNull;
import org.postgresql.Driver;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of the HikariCP Connection Pool.
 */
public final class DatabaseManager {
    private final IonCore plugin;
    private HikariDataSource dataSource;
    private String resolvedJdbcUrl;
    private String resolvedUsername;
    private String resolvedPassword;
    private Properties secrets;

    public DatabaseManager(final IonCore plugin) {
        this.plugin = plugin;
    }

    /**
     * Initializes the HikariCP data source using values from config.yml, secrets.properties, or
     * Environmental variables.
     *
     * @throws IllegalStateException
     *             if the database configuration is missing or invalid.
     */
    public void initialize() {
        plugin.getLogger().info("Initializing DatabaseManager (HikariCP)...");
        // Force load the PostgreSQL Driver
        // This is critical when shading/relocating dependencies.
        try {
            Class.forName(Driver.class.getName());
            plugin.getLogger().info("Loaded PostgreSQL Driver: " + Driver.class.getName());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to load PostgreSQL JDBC driver. Ensure dependencies are shaded correctly.", e);
        }
        // Load local secrets file if present
        loadSecretsFile();
        // Resolve credentials via Priority Strategy: Env Var > secrets.properties > config.yml
        this.resolvedJdbcUrl = resolveValue("database.jdbc-url", "ION_DB_URL");
        this.resolvedUsername = resolveValue("database.username", "ION_DB_USERNAME");
        this.resolvedPassword = resolveValue("database.password", "ION_DB_PASSWORD");
        final var config = plugin.getConfig();
        final int poolSize = config.getInt("database.pool-settings.maximum-pool-size", 5);
        final long timeout = config.getLong("database.pool-settings.connection-timeout", 5000);
        if (resolvedJdbcUrl == null || resolvedUsername == null || resolvedPassword == null) {
            throw new IllegalStateException(
                    "Database configuration is missing. Please check config.yml, secrets.properties, or Environment Variables.");
        }
        final HikariConfig hikariConfig = new HikariConfig();
        // Explicitly set the driver class name to ensure Hikari finds the relocated class
        hikariConfig.setDriverClassName(Driver.class.getName());
        hikariConfig.setJdbcUrl(resolvedJdbcUrl);
        hikariConfig.setUsername(resolvedUsername);
        hikariConfig.setPassword(resolvedPassword);
        // Pool Tuning for Minecraft Environment
        hikariConfig.setMaximumPoolSize(poolSize);
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setConnectionTimeout(timeout);
        hikariConfig.setIdleTimeout(TimeUnit.MINUTES.toMillis(10));
        hikariConfig.setMaxLifetime(TimeUnit.MINUTES.toMillis(30));
        // Driver specific optimizations
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        // Set pool name
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
     * Loads 'secrets.properties' from the plugin data folder if it exists.
     */
    private void loadSecretsFile() {
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
    }

    /**
     * Resolves a configuration value by checking sources in priority order:
     * 1. Environment Variable
     * 2. secrets.properties
     * 3. config.yml
     */
    private String resolveValue(String configKey, String envVarKey) {
        // Check Environment Variable
        String envVal = System.getenv(envVarKey);
        if (envVal != null && !envVal.isBlank()) {
            return envVal;
        }
        // Check secrets.properties
        if (secrets != null && secrets.containsKey(configKey)) {
            return secrets.getProperty(configKey);
        }
        // Check config.yml
        return plugin.getConfig().getString(configKey);
    }

    /**
     * Borrows a connection from the pool.
     * <p>
     * <b>Usage:</b> Must be used within a try-with-resources block to ensure the connection
     * is returned to the pool.
     *
     * @return A valid SQL Connection.
     * @throws SQLException
     *             if a connection cannot be obtained.
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

    public String getResolvedJdbcUrl() {
        return resolvedJdbcUrl;
    }

    public String getResolvedUsername() {
        return resolvedUsername;
    }

    public String getResolvedPassword() {
        return resolvedPassword;
    }
}