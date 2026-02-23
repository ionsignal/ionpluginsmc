package com.ionsignal.minecraft.ionnerrus.commands;

import com.ionsignal.minecraft.ioncore.auth.IdentityService;
import com.ionsignal.minecraft.ioncore.network.PostgresEventBus;
import com.ionsignal.minecraft.ioncore.network.model.IonUser;
import com.ionsignal.minecraft.ionnerrus.network.PayloadFactory;
import com.ionsignal.minecraft.ionnerrus.network.model.AgentConfig;
import com.ionsignal.minecraft.ionnerrus.network.model.CoordinateSpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnLocationType;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GiveItemGoal.GiveItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.CraftItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.FollowPlayerParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.GatherBlockParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.AskDirector;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import org.incendo.cloud.annotation.specifier.Greedy;
import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Default;
import org.incendo.cloud.annotations.Permission;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Cloud Command implementation for /nerrus.
 * Replaces the monolithic NerrusCommand executor.
 */
public class NerrusCloudCommands {
    private final IonNerrus plugin;
    private final AgentService agentService;
    private final BlockTagManager blockTagManager;
    private final GoalFactory goalFactory;
    private final IdentityService identityService;
    private final PostgresEventBus eventBus;

    public NerrusCloudCommands(
            IonNerrus plugin,
            AgentService agentService,
            BlockTagManager blockTagManager,
            GoalFactory goalFactory,
            IdentityService identityService,
            PostgresEventBus eventBus) {
        this.plugin = plugin;
        this.agentService = agentService;
        this.blockTagManager = blockTagManager;
        this.goalFactory = goalFactory;
        this.identityService = identityService;
        this.eventBus = eventBus;
    }

    @Command("nerrus list")
    @Permission("ionnerrus.command.list")
    public void listAgents(CommandSourceStack stack) {
        CommandSender sender = stack.getSender();
        var agents = agentService.getAgents();
        if (agents.isEmpty()) {
            sender.sendMessage(Component.text("There are no active Nerrus agents.", NamedTextColor.YELLOW));
            return;
        }

        sender.sendMessage(Component.text("Active Nerrus agents:", NamedTextColor.GOLD));
        for (NerrusAgent agent : agents) {
            sender.sendMessage(Component.text("- " + agent.getName(), NamedTextColor.GRAY));
        }
    }

    @Command("nerrus stop <agent>")
    @Permission("ionnerrus.command.stop")
    public void stopAgent(
            CommandSourceStack stack,
            @Argument(value = "agent", parserName = "nerrus_agent") NerrusAgent agent) {
        CommandSender sender = stack.getSender();
        agent.assignGoal(null, null);
        sender.sendMessage(Component.text("Stopped current goal for agent " + agent.getName(), NamedTextColor.YELLOW));
    }

    // [MODIFIED] Switched from direct spawning to EventBus request
    @Command("nerrus spawn <definition>")
    @Permission("ionnerrus.command.spawn")
    public void spawnAgent(
            CommandSourceStack stack,
            @Argument(value = "definition", parserName = "nerrus_definition") AgentConfig config) {
        if (!(stack.getExecutor() instanceof Player player)) {
            stack.getSender().sendMessage(Component.text("This command can only be run by a player.", NamedTextColor.RED));
            return;
        }
        // Ensure closed-loop constraints: Player must be linked
        var userOpt = identityService.getCachedIdentity(player.getUniqueId());
        if (userOpt.isEmpty() || userOpt.get().isEmpty()) {
            player.sendMessage(Component.text("You must link your Runemind account to spawn agents.", NamedTextColor.RED));
            return;
        }
        IonUser owner = userOpt.get().get();
        Location safeLoc = findSafeSpawningLocation(player.getLocation());
        // Construct the location payload
        SpawnLocation spawnLocation = new CoordinateSpawnLocation(
                SpawnLocationType.COORDINATES.getValue(),
                safeLoc.getWorld().getName(),
                safeLoc.getX(), safeLoc.getY(), safeLoc.getZ(),
                safeLoc.getYaw(), safeLoc.getPitch());
        // Broadcast to web backend
        var envelope = PayloadFactory.createRequestSpawnEnvelope(owner, config.id(), spawnLocation);
        eventBus.broadcast(envelope);
        player.sendMessage(Component.text("Requested spawn for persona '" + config.name() + "'...", NamedTextColor.GRAY));
    }

    // [MODIFIED] Switched from direct removal to EventBus request
    @Command("nerrus remove <agent>")
    @Permission("ionnerrus.command.remove")
    public void removeAgent(
            CommandSourceStack stack,
            @Argument(value = "agent", parserName = "nerrus_agent") NerrusAgent agent) {
        CommandSender sender = stack.getSender();
        String name = agent.getName();
        if (sender instanceof Player player) {
            // Closed-loop despawn for players
            var userOpt = identityService.getCachedIdentity(player.getUniqueId());
            if (userOpt.isPresent() && userOpt.get().isPresent()) {
                IonUser owner = userOpt.get().get();
                UUID sessionId = agent.getPersona().getSessionId();
                if (sessionId != null) {
                    var envelope = PayloadFactory.createRequestDespawnEnvelope(owner, agent.getPersona().getDefinitionId(), sessionId);
                    eventBus.broadcast(envelope);
                    sender.sendMessage(Component.text("Requested despawn for agent " + name + "...", NamedTextColor.GRAY));
                    return;
                }
            } else {
                sender.sendMessage(Component.text("You must link your Runemind account to remove agents.", NamedTextColor.RED));
                return;
            }
        }
        // Fallback for console or agents spawned outside the closed loop
        if (agent.getPersona().getSessionId() != null) {
            if (agentService.despawnAgent(agent.getPersona().getSessionId())) {
                sender.sendMessage(Component.text("Force-removed agent " + name, NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("Agent not found: " + name, NamedTextColor.RED));
            }
        } else {
            sender.sendMessage(Component.text("Agent has no session ID (cannot despawn via new API): " + name, NamedTextColor.RED));
        }
    }

    @Command("nerrus gather <agent> <block_type> <amount>")
    @Permission("ionnerrus.command.gather")
    public void gatherBlock(
            CommandSourceStack stack,
            @Argument(value = "agent", parserName = "nerrus_agent") NerrusAgent agent,
            @Argument(value = "block_type", suggestions = "block_tags") String blockType,
            @Argument("amount") @Range(min = "1", max = "6400") int amount) {

        CommandSender sender = stack.getSender();
        String groupName = blockType.toLowerCase();
        if (blockTagManager.getMaterialSet(groupName) == null) {
            sender.sendMessage(Component.text("Invalid block type: " + groupName, NamedTextColor.RED));
            String availableTypes = String.join(", ", blockTagManager.getRegisteredGroupNames());
            sender.sendMessage(Component.text("Available types: " + availableTypes, NamedTextColor.GRAY));
            return;
        }
        try {
            GatherBlockParameters params = new GatherBlockParameters(groupName, amount);
            Goal gatherGoal = goalFactory.createGoal("GATHER_BLOCK", params);
            agent.assignGoal(gatherGoal, params);
            sender.sendMessage(
                    Component.text("Instructing " + agent.getName() + " to gather " + amount + " " + groupName, NamedTextColor.GREEN));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Error creating goal: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    @Command("nerrus craft <agent> <item> <amount>")
    @Permission("ionnerrus.command.craft")
    public void craftItem(
            CommandSourceStack stack,
            @Argument(value = "agent", parserName = "nerrus_agent") NerrusAgent agent,
            @Argument(value = "item", suggestions = "materials") String itemName,
            @Argument("amount") @Range(min = "1", max = "6400") int amount) {
        CommandSender sender = stack.getSender();
        String upperItem = itemName.toUpperCase();
        try {
            Material.valueOf(upperItem);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Unknown item: " + itemName, NamedTextColor.RED));
            return;
        }
        try {
            CraftItemParameters params = new CraftItemParameters(upperItem, amount);
            Goal craftGoal = goalFactory.createGoal("CRAFT_ITEM", params);
            agent.assignGoal(craftGoal, params);
            sender.sendMessage(
                    Component.text("Instructing " + agent.getName() + " to craft " + amount + " " + itemName + ".", NamedTextColor.GREEN));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Error creating goal: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().log(Level.WARNING, "Failed to create CraftItemGoal from command.", e);
        }
    }

    @Command("nerrus give <agent> <target> <item> <amount>")
    @Permission("ionnerrus.command.give")
    public void giveItem(
            CommandSourceStack stack,
            @Argument(value = "agent", parserName = "nerrus_agent") NerrusAgent agent,
            @Argument(value = "target", suggestions = "online_players") String targetName,
            @Argument(value = "item", suggestions = "materials") String itemName,
            @Argument("amount") @Range(min = "1", max = "6400") int amount) {
        CommandSender sender = stack.getSender();
        String upperItem = itemName.toUpperCase();
        try {
            GiveItemParameters params = new GiveItemParameters(upperItem, amount, targetName);
            Goal giveItemGoal = goalFactory.createGoal("GIVE_ITEM", params);
            agent.assignGoal(giveItemGoal, params);
            sender.sendMessage(Component.text(
                    "Instructing " + agent.getName() + " to give " + amount + " " + itemName + " to " + targetName, NamedTextColor.GREEN));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Error creating goal: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    @Command("nerrus follow <agent> <target> [distance]")
    @Permission("ionnerrus.command.follow")
    public void follow(
            CommandSourceStack stack,
            @Argument(value = "agent", parserName = "nerrus_agent") NerrusAgent agent,
            @Argument(value = "target", suggestions = "online_players") String targetName,
            @Argument("distance") @Default("6.0") @Range(min = "1.0", max = "100.0") double targetDistance) {
        CommandSender sender = stack.getSender();
        double stopDist = Math.max(2.0, targetDistance);
        double followDist = stopDist + 4.0;
        int duration = 30; // 30 seconds default
        try {
            FollowPlayerParameters params = new FollowPlayerParameters(targetName, followDist, stopDist, duration);
            Goal followGoal = goalFactory.createGoal("FOLLOW_PLAYER", params);
            agent.assignGoal(followGoal, params);
            sender.sendMessage(Component.text("Instructing " + agent.getName() + " to follow " + targetName, NamedTextColor.GREEN));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Error: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    @Command("nerrus do <agent> <directive>")
    @Permission("ionnerrus.command.do")
    public void doDirective(
            CommandSourceStack stack,
            @Argument(value = "agent", parserName = "nerrus_agent") NerrusAgent agent,
            @Argument("directive") @Greedy String directive) {
        if (!(stack.getExecutor() instanceof Player player)) {
            stack.getSender().sendMessage(Component.text("This command can only be run by a player.", NamedTextColor.RED));
            return;
        }
        agent.assignDirective(directive, player);
        player.sendMessage(Component.text("Directive issued to " + agent.getName() + ": '" + directive + "'", NamedTextColor.GREEN));
    }

    @Command("nerrus ask <agent> <question>")
    @Permission("ionnerrus.command.ask")
    public void askAgent(
            CommandSourceStack stack,
            @Argument(value = "agent", parserName = "nerrus_agent") NerrusAgent agent,
            @Argument("question") @Greedy String question) {
        if (!(stack.getExecutor() instanceof Player player)) {
            stack.getSender().sendMessage(Component.text("This command can only be run by a player.", NamedTextColor.RED));
            return;
        }
        player.sendMessage(Component.text("Asking " + agent.getName() + ": '" + question + "'", NamedTextColor.GRAY));
        AskDirector askDirector = new AskDirector(plugin.getLlmService());
        askDirector.executeQuery(agent, question, player);
    }

    /**
     * Ported logic from legacy NerrusCommand to ensure agents don't spawn in walls.
     */
    private Location findSafeSpawningLocation(Location loc) {
        if (loc.getWorld() == null)
            return loc;
        Location safeLoc = loc.clone();
        int y = safeLoc.getBlockY();
        while (y > loc.getWorld().getMinHeight()) {
            safeLoc.setY(y);
            Block feetBlock = safeLoc.getBlock();
            Block groundBlock = feetBlock.getRelative(0, -1, 0);
            Block headBlock = feetBlock.getRelative(0, 1, 0);
            if (groundBlock.getType().isSolid() && !feetBlock.getType().isSolid() && !headBlock.getType().isSolid()) {
                safeLoc.setY(y);
                return safeLoc;
            }
            y--;
        }
        return loc;
    }
}