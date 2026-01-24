package com.ionsignal.minecraft.ioncore.database.impl;

import com.ionsignal.minecraft.ioncore.api.data.DocumentStore;
import com.ionsignal.minecraft.ioncore.database.DatabaseManager;

import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class PostgresDocumentStore implements DocumentStore {
    private final DatabaseManager databaseManager;
    private final Set<String> allowedTables = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public PostgresDocumentStore(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public void registerCollection(String tableName) {
        // Basic sanity check for table names to prevent obvious bad inputs during registration
        if (tableName == null || !tableName.matches("^[a-z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid table name: " + tableName);
        }
        allowedTables.add(tableName);
    }

    @Override
    public CompletableFuture<Optional<String>> fetchDocument(String tableName, UUID id) {
        CompletableFuture<Optional<String>> future = new CompletableFuture<>();
        // Allow-List Check
        if (!allowedTables.contains(tableName)) {
            future.completeExceptionally(new SecurityException(
                    "Access denied: Table '" + tableName + "' is not a registered document collection."));
            return future;
        }
        // We assume the standard schema: ID (uuid) and PAYLOAD (jsonb)
        String query = "SELECT payload FROM " + tableName + " WHERE id = $1";
        databaseManager.getPgPool()
                .preparedQuery(query)
                .execute(Tuple.of(id))
                .onSuccess(rows -> {
                    if (rows.size() > 0) {
                        Row row = rows.iterator().next();
                        Object payloadObj = row.getValue("payload");
                        String payload = payloadObj != null ? payloadObj.toString() : null;
                        if (payload != null && !payload.isBlank()) {
                            future.complete(Optional.of(payload));
                        } else {
                            future.complete(Optional.empty());
                        }
                    } else {
                        future.complete(Optional.empty());
                    }
                })
                .onFailure(err -> {
                    // Wrap Vert.x exception to avoid leaking implementation details
                    future.completeExceptionally(new RuntimeException("Database fetch failed", err));
                });
        return future;
    }
}