package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;

/**
 * Handles external communication.
 * 
 * PHASE 0 MIGRATION STATUS:
 * Legacy WebSocket logic has been disabled.
 * This class is currently a stub to allow compilation.
 * 
 * PHASE 1 TODO:
 * This will be refactored to 'PersistenceBootstrap' to load Agent state 
 * from the Postgres database on startup.
 */
public class NetworkBootstrap {
    private final IonNerrus plugin;

    public NetworkBootstrap(IonNerrus plugin, AgentService agentService) {
        this.plugin = plugin;
    }

    public void registerAll() {
        // STUBBED FOR PHASE 0
        // The NetworkCommandRegistrar has been removed from IonCore.
        // We are temporarily disabling external commands until the Database layer is ready.
        plugin.getLogger().info("NetworkBootstrap: External commands disabled pending Database Migration (Phase 1).");
    }
}