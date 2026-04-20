package com.ionsignal.minecraft.ionnerrus.commands;

import com.ionsignal.minecraft.ioncore.auth.IdentityService;
import com.ionsignal.minecraft.ioncore.network.IonEventBroker;
import com.ionsignal.minecraft.ioncore.network.model.IonUser;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.lifecycle.AgentService;
import com.ionsignal.minecraft.ionnerrus.network.PayloadFactory;
import com.ionsignal.minecraft.ionnerrus.network.model.CoordinateSpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.PersonaListItem;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnLocationType;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Handles Agent Lifecycle commands.
 * MODIFIED: Refactored to consume Brigadier CommandContext.
 */
public class NerrusAgentCommands {
    private final AgentService agentService;
    private final IdentityService identityService;
    private final IonEventBroker eventBroker;
    private final PayloadFactory payloadFactory;

    public NerrusAgentCommands(
            AgentService agentService,
            IdentityService identityService,
            IonEventBroker eventBroker,
            PayloadFactory payloadFactory) {
        this.agentService = agentService;
        this.identityService = identityService;
        this.eventBroker = eventBroker;
        this.payloadFactory = payloadFactory;
    }

    public int listAgents(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        var agents = agentService.getAgents();
        if (agents.isEmpty()) {
            sender.sendMessage(Component.text("There are no active Nerrus agents.", NamedTextColor.YELLOW));
            return Command.SINGLE_SUCCESS;
        }

        sender.sendMessage(Component.text("Active Nerrus agents:", NamedTextColor.GOLD));
        for (NerrusAgent agent : agents) {
            sender.sendMessage(Component.text("- " + agent.getName(), NamedTextColor.GRAY));
        }
        return Command.SINGLE_SUCCESS;
    }

    public int stopAgent(CommandContext<CommandSourceStack> ctx) {
        NerrusAgent agent = ctx.getArgument("agent", NerrusAgent.class);
        CommandSender sender = ctx.getSource().getSender();

        agent.assignGoal(null, null);
        sender.sendMessage(Component.text("Stopped current goal for agent " + agent.getName(), NamedTextColor.YELLOW));
        return Command.SINGLE_SUCCESS;
    }

    public int spawnAgent(CommandContext<CommandSourceStack> ctx) {
        PersonaListItem config = ctx.getArgument("definition", PersonaListItem.class);
        CommandSourceStack stack = ctx.getSource();

        if (!(stack.getExecutor() instanceof Player player)) {
            stack.getSender().sendMessage(Component.text("This command can only be run by a player.", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }

        var userOpt = identityService.getCachedIdentity(player.getUniqueId());
        if (userOpt.isEmpty() || userOpt.get().isEmpty()) {
            player.sendMessage(Component.text("You must link your Runemind account to spawn agents.", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }

        IonUser owner = userOpt.get().get();
        Location safeLoc = findSafeSpawningLocation(player.getLocation());

        SpawnLocation spawnLocation = new CoordinateSpawnLocation(
                SpawnLocationType.COORDINATES.getValue(),
                safeLoc.getWorld().getName(),
                safeLoc.getX(), safeLoc.getY(), safeLoc.getZ(),
                safeLoc.getYaw(), safeLoc.getPitch());

        var payload = payloadFactory.createRequestSpawnEnvelope(owner.id(), config.id(), spawnLocation);
        eventBroker.broadcast(payload).whenCompleteAsync((v, ex) -> {
            if (ex != null) {
                player.sendMessage(Component.text("Failed to contact network.", NamedTextColor.RED));
            } else {
                player.sendMessage(Component.text("Requested spawn for persona '" + config.name() + "'...", NamedTextColor.GREEN));
            }
        }, IonNerrus.getInstance().getMainThreadExecutor());

        return Command.SINGLE_SUCCESS;
    }

    public int removeAgent(CommandContext<CommandSourceStack> ctx) {
        NerrusAgent agent = ctx.getArgument("agent", NerrusAgent.class);
        CommandSender sender = ctx.getSource().getSender();
        String name = agent.getName();

        if (sender instanceof Player player) {
            var userOpt = identityService.getCachedIdentity(player.getUniqueId());
            if (userOpt.isPresent() && userOpt.get().isPresent()) {
                IonUser owner = userOpt.get().get();
                var payload = payloadFactory.createRequestDespawnEnvelope(owner.id(), agent.getPersona().getDefinitionId());
                eventBroker.broadcast(payload).whenCompleteAsync((v, ex) -> {
                    if (ex != null) {
                        sender.sendMessage(Component.text("Failed to contact network.", NamedTextColor.RED));
                    } else {
                        sender.sendMessage(Component.text("Requested despawn for agent " + name + "...", NamedTextColor.GREEN));
                    }
                }, IonNerrus.getInstance().getMainThreadExecutor());
                return Command.SINGLE_SUCCESS;
            } else {
                sender.sendMessage(Component.text("You must link your Runemind account to remove agents.", NamedTextColor.RED));
                return Command.SINGLE_SUCCESS;
            }
        }

        if (agentService.despawnAgent(agent)) {
            sender.sendMessage(Component.text("Force-removed agent " + name, NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("Agent not found: " + name, NamedTextColor.RED));
        }
        return Command.SINGLE_SUCCESS;
    }

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
