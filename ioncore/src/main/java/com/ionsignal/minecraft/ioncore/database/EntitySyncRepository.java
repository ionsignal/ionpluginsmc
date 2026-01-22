package com.ionsignal.minecraft.ioncore.database;

import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Repository for accessing 'game_entity_sync' data.
 */
public class EntitySyncRepository {
    private final DatabaseManager databaseManager;

    public EntitySyncRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Fetches the raw JSON payload for a specific entity definition ID.
     *
     * @param entityId
     *            The UUID of the entity definition (Web Identity).
     * @return A Future containing an Optional with the raw JSON string if found, or empty if not
     *         found/null.
     */
    public CompletableFuture<Optional<String>> fetchPayload(UUID entityId) {
        CompletableFuture<Optional<String>> future = new CompletableFuture<>();
        // Use PostgreSQL specific parameter syntax ($1)
        String query = "SELECT payload FROM game_entity_sync WHERE id = $1";
        databaseManager.getPgPool()
                .preparedQuery(query)
                .execute(Tuple.of(entityId))
                .onComplete(ar -> {
                    if (ar.succeeded()) {
                        var rows = ar.result();
                        if (rows.size() > 0) {
                            Row row = rows.iterator().next();
                            String payload = row.getString("payload");
                            // Treat null or blank payloads as "Not Found" to prevent downstream NPEs
                            if (payload != null && !payload.isBlank()) {
                                future.complete(Optional.of(payload));
                            } else {
                                future.complete(Optional.empty());
                            }
                        } else {
                            // Row not found
                            future.complete(Optional.empty());
                        }
                    } else {
                        // Database error (Connection failed, SQL error, etc.)
                        future.completeExceptionally(ar.cause());
                    }
                });
        return future;
    }
}