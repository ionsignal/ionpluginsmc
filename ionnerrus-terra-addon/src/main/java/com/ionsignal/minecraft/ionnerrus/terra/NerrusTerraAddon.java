package com.ionsignal.minecraft.ionnerrus.terra;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.debug.DebugSession;
import com.ionsignal.minecraft.ioncore.debug.ExecutionControllerFactory;
import com.ionsignal.minecraft.ioncore.debug.controllers.TickBasedController;
import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawPoolTemplate;
import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawPoolType;
import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawStructureTemplate;
import com.ionsignal.minecraft.ionnerrus.terra.config.JigsawStructureType;
import com.ionsignal.minecraft.ionnerrus.terra.debug.JigsawDebugDriver;
import com.ionsignal.minecraft.ionnerrus.terra.debug.JigsawVisualizationProvider;
import com.ionsignal.minecraft.ionnerrus.terra.generation.JigsawNBTStructure;
import com.ionsignal.minecraft.ionnerrus.terra.generation.JigsawStructure;
import com.ionsignal.minecraft.ionnerrus.terra.generation.StructureBlueprint;
import com.ionsignal.minecraft.ionnerrus.terra.generation.StructurePlanner;
import com.ionsignal.minecraft.ionnerrus.terra.model.NBTStructure;
import com.ionsignal.minecraft.ionnerrus.terra.provider.NBTStructureProvider;

import com.dfsek.seismic.type.vector.Vector3Int;
import com.dfsek.terra.addons.manifest.api.AddonInitializer;
import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.addon.BaseAddon;
import com.dfsek.terra.api.command.CommandSender;
import com.dfsek.terra.api.command.arguments.RegistryArgument;
import com.dfsek.terra.api.entity.Entity;
import com.dfsek.terra.api.event.events.config.pack.ConfigPackPreLoadEvent;
import com.dfsek.terra.api.event.events.platform.CommandRegistrationEvent;
import com.dfsek.terra.api.event.functional.FunctionalEventHandler;
import com.dfsek.terra.api.inject.annotations.Inject;
import com.dfsek.terra.api.registry.CheckedRegistry;
import com.dfsek.terra.api.registry.Registry;
import com.dfsek.terra.api.registry.exception.DuplicateEntryException;
import com.dfsek.terra.api.registry.key.RegistryKey;
import com.dfsek.terra.api.structure.Structure;
import com.dfsek.terra.api.util.FileUtil;
import com.dfsek.terra.api.util.reflection.TypeKey;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import org.incendo.cloud.CommandManager;
import org.incendo.cloud.component.DefaultValue;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.incendo.cloud.parser.standard.LongParser;
import org.incendo.cloud.parser.standard.StringParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.random.RandomGeneratorFactory;

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

    // Helper to resolve the Structure registry from the command sender's context
    // This is required by RegistryArgument to find valid structures for the player's current world/pack
    private static Registry<Structure> getStructureRegistry(CommandContext<CommandSender> context) {
        return context.sender().getEntity()
                .map(e -> e.world().getPack().getRegistry(Structure.class))
                .orElseThrow(() -> new IllegalArgumentException("Command must be run by an entity in a Terra world"));
    }

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
        // Register Debug Components
        if (Bukkit.getPluginManager().isPluginEnabled("IonCore")) {
            LOGGER.info("IonCore detected. Registering Jigsaw Debugger.");
            IonCore.getVisualizationRegistry().register(StructureBlueprint.class, new JigsawVisualizationProvider());
        }
        FunctionalEventHandler eventHandler = platform.getEventManager().getHandler(FunctionalEventHandler.class);
        // Register Commands via Terra/Cloud
        eventHandler.register(addon, CommandRegistrationEvent.class)
                .then(event -> {
                    // Root: /ionnerrus
                    // Command: /ionnerrus debug start <structure> [pool] [seed]
                    CommandManager<CommandSender> manager = event.getCommandManager();
                    var builder = manager.commandBuilder("ionnerrus", Description.of("IonNerrus Debug Tools"));
                    manager.command(
                            builder.literal("debug")
                                    .literal("start")
                                    .permission("ionnerrus.debug.start")
                                    // Use RegistryArgument to auto-complete structures from the player's active ConfigPack
                                    .argument(RegistryArgument.builder("structure", NerrusTerraAddon::getStructureRegistry,
                                            TypeKey.of(Structure.class)))
                                    // Use DefaultValue for optional arguments to avoid 'getOptional' issues
                                    .optional("pool", StringParser.stringParser(), DefaultValue.constant(""))
                                    .optional("seed", LongParser.longParser(), DefaultValue.constant(0L))
                                    .handler(context -> {
                                        CommandSender sender = context.sender();
                                        Entity entity = sender.getEntity().orElse(null);
                                        // Context Validation
                                        if (entity == null) {
                                            sender.sendMessage("You must be an entity to run this command.");
                                            return;
                                        }
                                        if (!(entity.getHandle() instanceof Player player)) {
                                            sender.sendMessage("You must be a player to run this command.");
                                            return;
                                        }
                                        if (IonCore.getInstance() == null) {
                                            sender.sendMessage("IonCore is not loaded. Debugging unavailable.");
                                            return;
                                        }
                                        Structure structure = context.get("structure");
                                        JigsawStructureTemplate config;
                                        // Resolve Configuration based on Structure Type
                                        if (structure instanceof JigsawStructure js) {
                                            config = js.getConfig();
                                        } else if (structure instanceof JigsawNBTStructure nbtStruct) {
                                            // Create synthetic config for raw NBT
                                            config = new JigsawStructureTemplate();
                                            config.setSyntheticID(nbtStruct.getRegistryKey().getID());
                                            sender.sendMessage("Debugging Raw NBT (using default settings)");
                                        } else {
                                            sender.sendMessage("Selected structure is not a Jigsaw structure.");
                                            return;
                                        }
                                        // Resolve Arguments
                                        // We use empty string as default to detect if user provided a pool override
                                        String poolArg = context.get("pool");
                                        String startPool = (poolArg.isEmpty()) ? config.getStartPool() : poolArg;
                                        long seedArg = context.get("seed");
                                        long seed = (seedArg == 0)
                                                ? player.getLocation().getBlockX() ^ player.getLocation().getBlockZ()
                                                : seedArg;
                                        // Initialize Planner
                                        Vector3Int origin = Vector3Int.of(
                                                player.getLocation().getBlockX(),
                                                player.getLocation().getBlockY(),
                                                player.getLocation().getBlockZ());
                                        StructurePlanner planner = new StructurePlanner(
                                                entity.world().getPack(),
                                                config,
                                                origin,
                                                RandomGeneratorFactory.of("Xoroshiro128PlusPlus").create(seed),
                                                seed,
                                                null,
                                                player.getUniqueId());
                                        // Initialize Planner State
                                        planner.initialize(startPool);
                                        // Create Session & Driver
                                        TickBasedController controller = ExecutionControllerFactory.createTickBased(getTerraPlugin());
                                        DebugSession<StructureBlueprint> session = IonCore.getDebugRegistry()
                                                .createSession(player.getUniqueId(), planner.getBlueprint(), controller);
                                        JigsawDebugDriver driver = new JigsawDebugDriver(session, planner);
                                        driver.runTaskTimer(getTerraPlugin(), 1L, 1L);
                                        sender.sendMessage("Debug session started. Use '/ionnerrus debug step' to advance.");
                                    }));
                    // Command: /ionnerrus debug step
                    manager.command(
                            builder.literal("debug")
                                    .literal("step")
                                    .permission("ionnerrus.debug.control")
                                    .handler(context -> {
                                        if (context.sender().getEntity().isPresent()
                                                && context.sender().getEntity().get().getHandle() instanceof Player player) {
                                            IonCore.getDebugRegistry().getActiveSession(player.getUniqueId())
                                                    .ifPresent(s -> s.getController().ifPresent(c -> c.resume()));
                                        }
                                    }));
                    // Command: /ionnerrus debug finish
                    manager.command(
                            builder.literal("debug")
                                    .literal("finish")
                                    .permission("ionnerrus.debug.control")
                                    .handler(context -> {
                                        if (context.sender().getEntity().isPresent()
                                                && context.sender().getEntity().get().getHandle() instanceof Player player) {
                                            IonCore.getDebugRegistry().getActiveSession(player.getUniqueId())
                                                    .ifPresent(s -> s.getController().ifPresent(c -> c.continueToEnd()));
                                        }
                                    }));
                    // Command: /ionnerrus debug cancel
                    manager.command(
                            builder.literal("debug")
                                    .literal("cancel")
                                    .permission("ionnerrus.debug.control")
                                    .handler(context -> {
                                        if (context.sender().getEntity().isPresent()
                                                && context.sender().getEntity().get().getHandle() instanceof Player player) {
                                            if (IonCore.getDebugRegistry().cancelSession(player.getUniqueId())) {
                                                context.sender().sendMessage("Debug session cancelled.");
                                            } else {
                                                context.sender().sendMessage("No active debug session found.");
                                            }
                                        }
                                    }));
                });
        // Register Config Types and Loaders
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