package com.ionsignal.minecraft.ionnerrus.commands;

import com.ionsignal.minecraft.ioncore.auth.IdentityService;
import com.ionsignal.minecraft.ioncore.network.PostgresEventBus;
import com.ionsignal.minecraft.ioncore.network.model.IonUser;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.lifecycle.AgentService;
import com.ionsignal.minecraft.ionnerrus.network.PayloadFactory;
import com.ionsignal.minecraft.ionnerrus.network.model.CoordinateSpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.PersonaListItem;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnLocationType;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Handles Agent Lifecycle commands.
 */
public class NerrusAgentCommands {
    private final AgentService agentService;
    private final IdentityService identityService;
    private final PostgresEventBus eventBus;
    private final PayloadFactory payloadFactory;

    public NerrusAgentCommands(
            AgentService agentService,
            IdentityService identityService,
            PostgresEventBus eventBus,
            PayloadFactory payloadFactory) {
        this.agentService = agentService;
        this.identityService = identityService;
        this.eventBus = eventBus;
        this.payloadFactory = payloadFactory;
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

    @Command("nerrus spawn <definition>")
    @Permission("ionnerrus.command.spawn")
    public void spawnAgent(
            CommandSourceStack stack,
            @Argument(value = "definition", parserName = "nerrus_definition") PersonaListItem config) {
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
        var envelope = payloadFactory.createRequestSpawnEnvelope(owner, config.id(), spawnLocation);
        eventBus.broadcast(envelope).whenComplete((v, ex) -> {
            if (ex != null) {
                player.sendMessage(Component.text("Failed to contact network.", NamedTextColor.RED));
            } else {
                player.sendMessage(Component.text("Requested spawn for persona '" + config.name() + "'...", NamedTextColor.GREEN));
            }
        });
    }

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
                var envelope = payloadFactory.createRequestDespawnEnvelope(owner, agent.getPersona().getDefinitionId());
                eventBus.broadcast(envelope).whenComplete((v, ex) -> {
                    if (ex != null) {
                        sender.sendMessage(Component.text("Failed to contact network.", NamedTextColor.RED));
                    } else {
                        sender.sendMessage(Component.text("Requested despawn for agent " + name + "...", NamedTextColor.GREEN));
                    }
                });
                return;
            } else {
                sender.sendMessage(Component.text("You must link your Runemind account to remove agents.", NamedTextColor.RED));
                return;
            }
        }
        // Fallback for console or agents spawned outside the closed loop
        if (agentService.despawnAgent(agent)) {
            sender.sendMessage(Component.text("Force-removed agent " + name, NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("Agent not found: " + name, NamedTextColor.RED));
        }
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