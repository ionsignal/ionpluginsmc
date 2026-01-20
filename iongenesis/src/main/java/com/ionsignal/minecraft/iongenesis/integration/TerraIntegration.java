package com.ionsignal.minecraft.iongenesis.integration;

import com.ionsignal.minecraft.iongenesis.IonGenesis;
import com.ionsignal.minecraft.iongenesis.config.JigsawPoolTemplate;
import com.ionsignal.minecraft.iongenesis.config.JigsawPoolType;
import com.ionsignal.minecraft.iongenesis.config.JigsawStructureType;
import com.ionsignal.minecraft.iongenesis.generation.JigsawNBTStructure;
import com.ionsignal.minecraft.iongenesis.model.structure.NBTStructure;
import com.ionsignal.minecraft.iongenesis.provider.NBTStructureProvider;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.event.EventHandler;
import com.dfsek.terra.api.event.events.config.pack.ConfigPackPostLoadEvent;
import com.dfsek.terra.api.event.events.config.pack.ConfigPackPreLoadEvent;
import com.dfsek.terra.api.event.functional.FunctionalEventHandler;
import com.dfsek.terra.api.registry.CheckedRegistry;
import com.dfsek.terra.api.registry.exception.DuplicateEntryException;
import com.dfsek.terra.api.registry.key.RegistryKey;
import com.dfsek.terra.api.structure.Structure;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles all interaction with the Terra API.
 * Manages the lifecycle of IonGenesis components within the Terra ecosystem.
 */
public class TerraIntegration implements EventHandler {
    private final IonGenesis plugin;
    private Platform terraPlatform;

    public TerraIntegration(IonGenesis plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Plugin terraPlugin = Bukkit.getPluginManager().getPlugin("Terra");
        if (terraPlugin == null) {
            throw new IllegalStateException("Terra plugin not found!");
        }
        try {
            // Access the getPlatform method from the Terra plugin instance
            RegisteredServiceProvider<Platform> provider = Bukkit.getServicesManager().getRegistration(Platform.class);
            if (provider != null) {
                this.terraPlatform = provider.getProvider();
            }
            if (this.terraPlatform == null) {
                throw new IllegalStateException("Terra platform is null! Ensure Terra is loaded before IonGenesis.");
            }
            // Register this class as an event handler (generic)
            terraPlatform.getEventManager().registerHandler(TerraIntegration.class, this);
            // Register Functional Listeners for Lifecycle Management
            FunctionalEventHandler functionalHandler = terraPlatform.getEventManager().getHandler(FunctionalEventHandler.class);
            // Pre-Load: Register Config Types
            functionalHandler.register(null, ConfigPackPreLoadEvent.class)
                    .priority(100)
                    .then(this::onConfigPackPreLoad)
                    .global();
            // Post-Load: Register Content (NBTs)
            functionalHandler.register(null, ConfigPackPostLoadEvent.class)
                    .priority(100)
                    .then(this::onConfigPackPostLoad)
                    .global();
            // Hot-Load: Handle existing packs
            var registry = terraPlatform.getConfigRegistry();
            if (registry.entries().isEmpty()) {
                // Standard Startup Path: Terra hasn't run its scheduled task yet.
                // We rely on the listeners registered above.
                plugin.getLogger().info("Terra registry empty - waiting for delayed init (Standard Startup).");
            } else {
                // Hot-Load Path: Terra is already running.
                registry.forEach(pack -> {
                    plugin.getLogger().info("Hot-loading IonGenesis into existing pack: " + pack.getRegistryKey());
                    // We cannot re-fire PreLoad safely as YAMLs are already parsed,
                    // but we CAN inject NBT structures which are lazy-loaded.
                    registerNBTStructures(pack);
                });
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to hook into Terra platform: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public Platform getPlatform() {
        return terraPlatform;
    }

    @Override
    public void handle(com.dfsek.terra.api.event.events.Event event) {
        // No-op, we use functional registration
    }

    /**
     * Event Handler: ConfigPack Pre-Load
     * Registers custom configuration types (JigsawPool, JigsawStructure) so Terra can parse them.
     */
    private void onConfigPackPreLoad(ConfigPackPreLoadEvent event) {
        // RegistryKey might be null during PreLoad as the pack.yml hasn't been fully parsed yet.
        RegistryKey key = event.getPack().getRegistryKey();
        String packId = (key != null) ? key.toString() : "(Initializing Context)";
        plugin.getLogger().info("Registering IonGenesis config types for pack: " + packId);
        ConfigPack pack = event.getPack();
        try {
            // Register Config Types
            pack.registerConfigType(new JigsawPoolType(terraPlatform), RegistryKey.of("ionnerrus", "jigsaw_pool"), 100);
            pack.registerConfigType(new JigsawStructureType(pack), RegistryKey.of("ionnerrus", "jigsaw_structure"), 100);
            pack.applyLoader(JigsawPoolTemplate.PoolElement.class, JigsawPoolTemplate.PoolElement::new);
            pack.applyLoader(JigsawPoolTemplate.MetadataTemplate.class, JigsawPoolTemplate.MetadataTemplate::new);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to register IonGenesis types for pack " + pack.getRegistryKey() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Event Handler: ConfigPack Post-Load
     * Scans for .nbt files and registers them as Structures.
     */
    private void onConfigPackPostLoad(ConfigPackPostLoadEvent event) {
        plugin.getLogger().info("Scanning NBT structures for pack: " + event.getPack().getRegistryKey());
        registerNBTStructures(event.getPack());
    }

    private void registerNBTStructures(ConfigPack pack) {
        CheckedRegistry<Structure> structureRegistry = pack.getOrCreateRegistry(Structure.class);
        Path rootPath = pack.getRootPath();
        // Safety check for packs that might not have a filesystem path (e.g. virtual packs)
        if (rootPath == null || !Files.exists(rootPath)) {
            return;
        }
        // Counter for summary log
        AtomicInteger count = new AtomicInteger(0);
        try (Stream<Path> stream = Files.walk(rootPath)) {
            stream.filter(path -> !Files.isDirectory(path))
                    .filter(path -> path.toString().endsWith(".nbt"))
                    .parallel() // Keep parallel processing for performance
                    .map(path -> {
                        try {
                            // Extract ID from filename manually
                            String fileName = path.getFileName().toString();
                            String id = fileName.substring(0, fileName.lastIndexOf('.'));
                            // Parse NBT Data
                            NBTStructure.StructureData data = NBTStructureProvider.parse(Files.newInputStream(path), fileName);
                            if (data == null)
                                return null;
                            // Namespace Strategy: Use Pack ID to prevent collisions
                            String namespace = pack.getRegistryKey().getID();
                            RegistryKey key = RegistryKey.of(namespace, id);
                            return new JigsawNBTStructure(key, data, terraPlatform);
                        } catch (IOException e) {
                            plugin.getLogger().warning("Failed to load NBT: " + path);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .forEachOrdered(structure -> {
                        try {
                            structureRegistry.register(structure.getRegistryKey(), structure);
                            count.incrementAndGet();
                            plugin.getLogger().fine("Registered NBT Structure: " + structure.getRegistryKey());
                        } catch (DuplicateEntryException e) {
                            plugin.getLogger().warning("Duplicate structure ignored: " + structure.getRegistryKey());
                        }
                    });
            // Summary Log
            if (count.get() > 0) {
                plugin.getLogger().info("Registered " + count.get() + " NBT structures for pack: " + pack.getRegistryKey());
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Error scanning for NBT files in pack " + pack.getRegistryKey() + ": " + e.getMessage());
        }
    }
}