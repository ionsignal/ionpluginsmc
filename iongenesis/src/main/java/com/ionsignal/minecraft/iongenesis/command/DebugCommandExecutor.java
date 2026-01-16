package com.ionsignal.minecraft.iongenesis.command;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.registry.Registry;
import com.dfsek.terra.api.structure.Structure;
import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.debug.DebugSession;
import com.ionsignal.minecraft.ioncore.debug.ExecutionControllerFactory;
import com.ionsignal.minecraft.ioncore.debug.controllers.TickBasedController;
import com.ionsignal.minecraft.iongenesis.IonGenesis;
import com.ionsignal.minecraft.iongenesis.debug.JigsawDebugDriver;
import com.ionsignal.minecraft.iongenesis.generation.JigsawStructure;
import com.ionsignal.minecraft.iongenesis.generation.StructureBlueprint;
import com.ionsignal.minecraft.iongenesis.generation.StructurePlanner;
import com.ionsignal.minecraft.iongenesis.util.SystemContext;

import com.dfsek.seismic.type.vector.Vector3Int;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import org.jetbrains.annotations.NotNull;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

public class DebugCommandExecutor implements CommandExecutor {
    private final IonGenesis plugin;

    public DebugCommandExecutor(IonGenesis plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length < 1) {
            return false;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "debug" -> handleDebug(player, args);
            default -> player.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
        }
        return true;
    }

    private void handleDebug(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /iongenesis debug <start|step|finish|cancel>", NamedTextColor.RED));
            return;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "start" -> handleStart(player, args);
            case "step" -> IonCore.getDebugRegistry().getActiveSession(player.getUniqueId())
                    .ifPresent(s -> s.getController().ifPresent(c -> c.resume()));
            case "finish" -> IonCore.getDebugRegistry().getActiveSession(player.getUniqueId())
                    .ifPresent(s -> s.getController().ifPresent(c -> c.continueToEnd()));
            case "cancel" -> {
                if (IonCore.getDebugRegistry().cancelSession(player.getUniqueId())) {
                    player.sendMessage(Component.text("Session cancelled.", NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text("No active session.", NamedTextColor.YELLOW));
                }
            }
        }
    }

    private void handleStart(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /iongenesis debug start <structure_id> [pool] [seed]", NamedTextColor.RED));
            return;
        }
        String structureId = args[2];
        String poolOverride = args.length > 3 ? args[3] : "";
        long seed;
        if (args.length > 4) {
            try {
                seed = Long.parseLong(args[4]);
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("Invalid seed.", NamedTextColor.RED));
                return;
            }
        } else {
            seed = player.getLocation().getBlockX() ^ player.getLocation().getBlockZ();
        }
        // Access platform via integration
        if (IonGenesis.getInstance().getTerraIntegration() == null ||
                IonGenesis.getInstance().getTerraIntegration().getPlatform() == null) {
            player.sendMessage(Component.text("Terra platform not initialized yet.", NamedTextColor.RED));
            return;
        }
        Platform platform = IonGenesis.getInstance().getTerraIntegration().getPlatform();
        // Resolve structure and pack
        Structure foundStructure = null;
        ConfigPack foundPack = null;
        // Iterate all loaded config packs to find the structure
        for (ConfigPack pack : platform.getConfigRegistry().entries()) {
            Registry<Structure> structureRegistry = pack.getRegistry(Structure.class);
            // Try exact match or fuzzy match
            var opt = structureRegistry.getByID(structureId);
            if (opt.isPresent()) {
                foundStructure = opt.get();
                foundPack = pack;
                break;
            }
        }
        if (foundStructure == null) {
            player.sendMessage(Component.text("Structure not found: " + structureId, NamedTextColor.RED));
            return;
        }
        if (!(foundStructure instanceof JigsawStructure jigsawStructure)) {
            player.sendMessage(Component.text("Structure is not a JigsawStructure (configured). Raw NBT debug not supported yet.",
                    NamedTextColor.RED));
            return;
        }
        player.sendMessage(Component.text("Starting debug for " + structureId + "...", NamedTextColor.YELLOW));
        try (SystemContext ignored = new SystemContext()) {
            // Initialize Random
            RandomGenerator random = RandomGeneratorFactory.of("Xoroshiro128PlusPlus").create(seed);
            Vector3Int origin = Vector3Int.of(player.getLocation().getBlockX(), player.getLocation().getBlockY(),
                    player.getLocation().getBlockZ());
            // Create Planner
            StructurePlanner planner = new StructurePlanner(
                    foundPack,
                    jigsawStructure.getConfig(),
                    origin,
                    random,
                    seed,
                    null, // Listener
                    player.getUniqueId() // Session ID
            );
            // Initialize Planner State
            String startPool = (poolOverride != null && !poolOverride.isEmpty()) ? poolOverride
                    : jigsawStructure.getConfig().getStartPool();
            planner.initialize(startPool);
            // Create Debug Session
            StructureBlueprint initialBlueprint = planner.getBlueprint();
            TickBasedController controller = ExecutionControllerFactory.createTickBased(plugin);
            try {
                DebugSession<StructureBlueprint> session = IonCore.getDebugRegistry().createSession(
                        player.getUniqueId(),
                        initialBlueprint,
                        controller);
                // Start Driver Task
                JigsawDebugDriver driver = new JigsawDebugDriver(session, planner);
                driver.runTaskTimer(plugin, 1L, 1L);
                player.sendMessage(Component.text("Debug session started! Use /iongenesis debug step", NamedTextColor.GREEN));
            } catch (Exception e) {
                player.sendMessage(Component.text("Failed to start session: " + e.getMessage(), NamedTextColor.RED));
                e.printStackTrace();
            }
        } catch (Exception e) {
            // Catch errors related to RandomGenerator or Planner initialization
            player.sendMessage(Component.text("Initialization Error: " + e.getMessage(), NamedTextColor.RED));
            e.printStackTrace();
        }
    }
}