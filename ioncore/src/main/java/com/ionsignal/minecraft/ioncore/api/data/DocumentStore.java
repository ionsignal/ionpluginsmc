package com.ionsignal.minecraft.ioncore.api.data;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A generic interface for fetching JSON documents from the database. This allows plugins to access
 * data without depending on specific repository implementations or Vert.x classes in the Core.
 */
public interface DocumentStore {

    /**
     * Registers a table name as a valid document collection. This is a security measure to prevent SQL
     * injection via table name parameters.
     *
     * @param tableName
     *            The database table name (e.g., "persona_manifests").
     */
    void registerCollection(String tableName);

    /**
     * Fetches the raw JSON payload from the specified table for the given ID.
     *
     * @param tableName
     *            The registered table name.
     * @param id
     *            The UUID key of the document.
     * @return A Future containing the raw JSON string if found.
     * @throws SecurityException
     *             if the table name has not been registered.
     */
    CompletableFuture<Optional<String>> fetchDocument(String tableName, UUID id);
}