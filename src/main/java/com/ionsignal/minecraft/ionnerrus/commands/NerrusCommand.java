package com.ionsignal.minecraft.ionnerrus.commands;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GetBlockGoal;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.TaskFactory;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class NerrusCommand implements CommandExecutor, TabCompleter {
    @SuppressWarnings("unused")
    private final IonNerrus plugin;
    private final AgentService agentService;
    private final TaskFactory taskFactory;
    private final BlockTagManager blockTagManager;

    public NerrusCommand(AgentService agentService, TaskFactory taskFactory, BlockTagManager blockTagManager) {
        this.plugin = IonNerrus.getInstance();
        this.agentService = agentService;
        this.taskFactory = taskFactory;
        this.blockTagManager = blockTagManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /nerrus <spawn|remove|stop|getblock|list> ...", NamedTextColor.GOLD));
            return true;
        }
        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "spawn" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("This command can only be run by a player.", NamedTextColor.RED));
                    return true;
                }
                return handleSpawn(player, args);
            }
            case "remove" -> {
                return handleRemove(sender, args);
            }
            case "stop" -> {
                return handleStop(sender, args);
            }
            case "getblock" -> {
                return handleGetBlock(sender, args);
            }
            case "list" -> {
                return handleList(sender);
            }
            default -> {
                sender.sendMessage(
                        Component.text("Unknown command. Usage: /nerrus <spawn|remove|stop|getblock|list> ...",
                                NamedTextColor.RED));
                return true;
            }
        }
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

    private boolean handleSpawn(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /nerrus spawn <name> [skin]", NamedTextColor.RED));
            return true;
        }
        String name = args[1];
        if (agentService.findAgentByName(name) != null) {
            player.sendMessage(Component.text("An agent with that name already exists.", NamedTextColor.RED));
            return true;
        }
        String skinNameToFetch = args.length > 2 ? args[2] : name;
        agentService.spawnAgent(name, findSafeSpawningLocation(player.getLocation()), skinNameToFetch);
        player.sendMessage(Component.text("Spawned agent " + name, NamedTextColor.GREEN));
        return true;
    }

    private boolean handleRemove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /nerrus remove <name>", NamedTextColor.RED));
            return true;
        }
        String name = args[1];
        if (agentService.removeAgent(name)) {
            sender.sendMessage(Component.text("Removed agent " + name, NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("Agent not found: " + name, NamedTextColor.RED));
        }
        return true;
    }

    private boolean handleStop(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /nerrus stop <name>", NamedTextColor.RED));
            return true;
        }
        String name = args[1];
        NerrusAgent agent = agentService.findAgentByName(name);
        if (agent == null) {
            sender.sendMessage(Component.text("Agent not found: " + name, NamedTextColor.RED));
            return true;
        }
        agent.assignGoal(null); // This stops the current goal and task
        sender.sendMessage(Component.text("Stopped current goal for agent " + name, NamedTextColor.YELLOW));
        return true;
    }

    private boolean handleGetBlock(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Component.text("Usage: /nerrus getblock <name> <type> <amount>", NamedTextColor.RED));
            return true;
        }
        String name = args[1];
        NerrusAgent agent = agentService.findAgentByName(name);
        if (agent == null) {
            sender.sendMessage(Component.text("Agent not found: " + name, NamedTextColor.RED));
            return true;
        }

        String type = args[2].toLowerCase();
        Set<Material> materials = blockTagManager.getMaterialSet(type);
        if (materials == null || materials.isEmpty()) {
            sender.sendMessage(Component.text("Invalid block type: " + type, NamedTextColor.RED));
            String availableTypes = String.join(", ", blockTagManager.getRegisteredGroupNames());
            sender.sendMessage(Component.text("Available types: " + availableTypes, NamedTextColor.GRAY));
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[3]);
            if (amount <= 0) {
                sender.sendMessage(Component.text("Amount must be a positive number.", NamedTextColor.RED));
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Invalid amount. Must be a number.", NamedTextColor.RED));
            return true;
        }

        Goal getBlockGoal = new GetBlockGoal(taskFactory, materials, type, amount);
        agent.assignGoal(getBlockGoal);
        return true;
    }

    private boolean handleList(CommandSender sender) {
        List<String> agentNames = agentService.getAgents().stream()
                .map(NerrusAgent::getName)
                .toList();
        if (agentNames.isEmpty()) {
            sender.sendMessage(Component.text("There are no active Nerrus agents.", NamedTextColor.YELLOW));
        } else {
            sender.sendMessage(Component.text("Active Nerrus agents:", NamedTextColor.GOLD));
            for (String name : agentNames) {
                sender.sendMessage(Component.text("- " + name, NamedTextColor.GRAY));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
            @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("spawn", "remove", "stop", "getblock", "list");
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "remove":
                case "stop":
                case "getblock":
                    return agentService.getAgents().stream()
                            .map(NerrusAgent::getName)
                            .collect(Collectors.toList());
                case "spawn":
                default:
                    return Collections.emptyList();
            }
        }
        if (args.length == 3) {
            switch (args[0].toLowerCase()) {
                case "spawn":
                    return Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .collect(Collectors.toList());
                case "getblock":
                    return blockTagManager.getRegisteredGroupNames().stream().sorted().collect(Collectors.toList());
                default:
                    return Collections.emptyList();
            }
        }
        if (args.length == 4 && "getblock".equalsIgnoreCase(args[0])) {
            return List.of("8", "16", "32", "64");
        }
        return Collections.emptyList();
    }
}