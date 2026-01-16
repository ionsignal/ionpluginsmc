package com.ionsignal.minecraft.iongenesis;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.iongenesis.debug.JigsawVisualizationProvider;
import com.ionsignal.minecraft.iongenesis.generation.StructureBlueprint;

import org.bukkit.Bukkit;

import java.util.logging.Logger;

/**
 * Dependency injection container for IonGenesis.
 */
public class ServiceContainer {
    @SuppressWarnings("unused")
    private final IonGenesis plugin;
    private final Logger logger;

    public ServiceContainer(IonGenesis plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void initialize() {
        // Register IonCore Debug Visualization
        if (Bukkit.getPluginManager().isPluginEnabled("IonCore")) {
            logger.info("IonCore detected. Registering Jigsaw Debugger.");
            IonCore.getVisualizationRegistry().register(StructureBlueprint.class, new JigsawVisualizationProvider());
        } else {
            logger.warning("IonCore not found. Debug visualizations will be disabled.");
        }
    }

    public void shutdown() {
        // Cleanup debug visualizations if IonCore is still present
        if (Bukkit.getPluginManager().isPluginEnabled("IonCore")) {
            IonCore.getVisualizationRegistry().unregister(StructureBlueprint.class);
        }
    }
}