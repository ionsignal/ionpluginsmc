package com.ionsignal.minecraft.ionnerrus.terra;

import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawPoolTemplate;
import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawPoolType;
import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawStructureType;
import com.ionsignal.minecraft.ionnerrus.terra.generation.JigsawNBTStructure;
import com.ionsignal.minecraft.ionnerrus.terra.model.NBTStructure;
import com.ionsignal.minecraft.ionnerrus.terra.provider.NBTStructureProvider;

import com.dfsek.terra.addons.manifest.api.AddonInitializer;
import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.addon.BaseAddon;
import com.dfsek.terra.api.event.events.config.pack.ConfigPackPreLoadEvent;
import com.dfsek.terra.api.event.functional.FunctionalEventHandler;
import com.dfsek.terra.api.inject.annotations.Inject;
import com.dfsek.terra.api.registry.CheckedRegistry;
import com.dfsek.terra.api.registry.exception.DuplicateEntryException;
import com.dfsek.terra.api.registry.key.RegistryKey;
import com.dfsek.terra.api.structure.Structure;
import com.dfsek.terra.api.util.FileUtil;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map.Entry;
import java.util.Objects;

/**
 * Entry point for the IonNerrus Terra addon.
 */
public class NerrusTerraAddon implements AddonInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(NerrusTerraAddon.class);
    private static Plugin terraPlugin;

    @Inject
    private Platform platform;

    @Inject
    private BaseAddon addon;

    @Override
    public void initialize() {
        LOGGER.info("Initializing IonNerrus Terra Addon v{}", addon.getVersion().getFormatted());
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Terra");
        if (plugin != null) {
            NerrusTerraAddon.terraPlugin = plugin;
        } else {
            LOGGER.error("Could not find the Terra plugin instance! The IonNerrus addon will not function correctly.");
            return;
        }
        FunctionalEventHandler eventHandler = platform.getEventManager().getHandler(FunctionalEventHandler.class);
        eventHandler.register(addon, ConfigPackPreLoadEvent.class)
                .priority(100)
                .then(event -> {
                    LOGGER.info("Registering jigsaw ConfigTypes for pack.");
                    try {
                        event.getPack().registerConfigType(new JigsawPoolType(platform), RegistryKey.of("ionnerrus", "jigsaw_pool"), 100);
                        event.getPack().registerConfigType(new JigsawStructureType(event.getPack()),
                                RegistryKey.of("ionnerrus", "jigsaw_structure"), 100);
                        event.getPack().applyLoader(JigsawPoolTemplate.PoolElement.class, JigsawPoolTemplate.PoolElement::new);
                    } catch (Exception e) {
                        LOGGER.error("Failed to register jigsaw ConfigTypes for pack.", e);
                    }
                    LOGGER.info("Scanning and registering NBT structures...");
                    CheckedRegistry<Structure> structureRegistry = event.getPack().getOrCreateRegistry(Structure.class);
                    try {
                        FileUtil.filesWithExtension(event.getPack().getRootPath(), ".nbt")
                                .entrySet()
                                .stream()
                                .sorted(Entry.comparingByKey())
                                .parallel() // Keep parallel for parsing (IO/CPU heavy)
                                .map(entry -> {
                                    try {
                                        String id = FileUtil.fileName(entry.getKey());
                                        NBTStructure.StructureData data = NBTStructureProvider.parse(Files.newInputStream(entry.getValue()),
                                                entry.getKey());
                                        if (data == null) {
                                            LOGGER.warn("Skipping invalid or empty NBT structure: {}", entry.getKey());
                                            return null;
                                        }
                                        RegistryKey key = addon.key(id);
                                        LOGGER.info("Loaded NBT structure: \"{}\" -> {}", entry.getKey(), key);
                                        return new JigsawNBTStructure(key, data, platform);
                                    } catch (IOException e) {
                                        LOGGER.error("Failed to load NBT structure \"{}\": {}", entry.getKey(), e.getMessage());
                                        return null;
                                    }
                                })
                                .filter(Objects::nonNull) // Remove failed parses
                                .forEachOrdered(structure -> { // Switch to sequential for registration
                                    try {
                                        structureRegistry.register(structure.getRegistryKey(), structure);
                                    } catch (DuplicateEntryException e) {
                                        LOGGER.warn("Duplicate structure ID found: {}. Ignoring duplicate.", structure.getRegistryKey());
                                    } catch (Exception e) {
                                        LOGGER.error("Failed to register structure {}: {}", structure.getRegistryKey(), e.getMessage());
                                    }
                                });
                    } catch (IOException e) {
                        throw new RuntimeException("Error occurred while reading config pack files for NBT structures", e);
                    }
                })
                .global();
        LOGGER.info("IonNerrus Terra Addon initialized successfully");
    }

    public static Plugin getTerraPlugin() {
        if (terraPlugin == null) {
            throw new IllegalStateException("The Terra plugin is not loaded, but the IonNerrus addon requires it.");
        }
        return terraPlugin;
    }
}