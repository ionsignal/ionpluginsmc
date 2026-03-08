package com.ionsignal.minecraft.ioncore.network;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;

/**
 * Abstract interface protecting downstream plugins (IonNerrus) from direct dependency on the
 * underlying messaging implementation (NATS).
 */
public interface IonEventBroker {
    /**
     * Broadcasts an event to the network.
     */
    CompletableFuture<Void> broadcast(Object payload);

    /**
     * Sends a synchronous Request/Reply RPC over the network asynchronously.
     */
    CompletableFuture<JsonNode> requestAsync(String subject, Object payload);

    /**
     * Gets the command registrar used to route inbound messages.
     */
    NetworkCommandRegistrar getCommandRegistrar();

    void shutdown();
}