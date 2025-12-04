package com.ionsignal.minecraft.ionnerrus.commands;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GiveItemGoal.GiveItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.GatherBlockParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.CraftItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.FollowPlayerParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.AskDirector;
import com.ionsignal.minecraft.ionnerrus.util.DebugPath;

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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class NerrusCommand implements CommandExecutor, TabCompleter {
    private final IonNerrus plugin;
    private final AgentService agentService;
    private final BlockTagManager blockTagManager;
    private final GoalFactory goalFactory;

    public NerrusCommand(IonNerrus plugin, AgentService agentService, BlockTagManager blockTagManager, GoalFactory goalFactory,
            GoalRegistry goalRegistry) {
        this.plugin = plugin;
        this.agentService = agentService;
        this.blockTagManager = blockTagManager;
        this.goalFactory = goalFactory;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(
                    Component.text("Usage: /nerrus <spawn|remove|stop|gather|give|do|ask|list|follow|craft> ...", NamedTextColor.GOLD));
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
            case "gather" -> {
                return handleGetBlock(sender, args);
            }
            case "give" -> {
                return handleGive(sender, args);
            }
            case "craft" -> {
                return handleCraft(sender, args);
            }
            case "do" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("This command can only be run by a player.", NamedTextColor.RED));
                    return true;
                }
                return handleDo(player, args);
            }
            case "ask" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("This command can only be run by a player.", NamedTextColor.RED));
                    return true;
                }
                return handleAsk(player, args);
            }
            case "list" -> {
                return handleList(sender);
            }
            case "follow" -> {
                return handleFollow(sender, args);
            }
            default -> {
                sender.sendMessage(
                        Component.text("Unknown command. Usage: /nerrus <spawn|remove|stop|gather|give|do|ask|list|follow|craft> ...",
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
        agent.assignGoal(null, null); // This stops the current goal and task
        sender.sendMessage(Component.text("Stopped current goal for agent " + name, NamedTextColor.YELLOW));
        DebugPath.logAreaAround(agent.getPersona().getLocation(), 5);
        return true;
    }

    private boolean handleGetBlock(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Component.text("Usage: /nerrus gather <name> <type> <amount>", NamedTextColor.RED));
            return true;
        }
        String name = args[1];
        NerrusAgent agent = agentService.findAgentByName(name);
        if (agent == null) {
            sender.sendMessage(Component.text("Agent not found: " + name, NamedTextColor.RED));
            return true;
        }
        String groupName = args[2].toLowerCase();
        // We still check here for good UX, but the GoalFactory will also validate.
        if (blockTagManager.getMaterialSet(groupName) == null) {
            sender.sendMessage(Component.text("Invalid block type: " + groupName, NamedTextColor.RED));
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
        // Use the GoalFactory to create the goal and assign it as a single-item plan.
        try {
            GatherBlockParameters params = new GatherBlockParameters(groupName, amount);
            Goal gatherGoal = goalFactory.createGoal("GATHER_BLOCK", params);
            agent.assignGoal(gatherGoal, params);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Error creating goal: " + e.getMessage(), NamedTextColor.RED));
            return true;
        }
        return true;
    }

    private boolean handleGive(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage(Component.text("Usage: /nerrus give <agentName> <targetName> <material> <quantity>", NamedTextColor.RED));
            return true;
        }
        String agentName = args[1];
        NerrusAgent agent = agentService.findAgentByName(agentName);
        if (agent == null) {
            sender.sendMessage(Component.text("Agent not found: " + agentName, NamedTextColor.RED));
            return true;
        }
        String targetName = args[2];
        String materialName = args[3].toUpperCase();
        int quantity;
        try {
            quantity = Integer.parseInt(args[4]);
            if (quantity <= 0) {
                sender.sendMessage(Component.text("Quantity must be a positive number.", NamedTextColor.RED));
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Invalid quantity. Must be a number.", NamedTextColor.RED));
            return true;
        }
        try {
            GiveItemParameters params = new GiveItemParameters(materialName, quantity, targetName);
            Goal giveItemGoal = goalFactory.createGoal("GIVE_ITEM", params);
            agent.assignGoal(giveItemGoal, params);
            sender.sendMessage(Component.text("Instructing " + agentName + " to give items.", NamedTextColor.GREEN));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Error creating goal: " + e.getMessage(), NamedTextColor.RED));
            return true;
        }
        return true;
    }

    private boolean handleCraft(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Component.text("Usage: /nerrus craft <agent_name> <item_name> <quantity>", NamedTextColor.RED));
            return true;
        }
        String agentName = args[1];
        NerrusAgent agent = agentService.findAgentByName(agentName);
        if (agent == null) {
            sender.sendMessage(Component.text("Agent not found: " + agentName, NamedTextColor.RED));
            return true;
        }
        String itemName = args[2].toUpperCase();
        int quantity;
        try {
            quantity = Integer.parseInt(args[3]);
            if (quantity <= 0) {
                sender.sendMessage(Component.text("Quantity must be a positive number.", NamedTextColor.RED));
                return true;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Invalid quantity. Must be a number.", NamedTextColor.RED));
            return true;
        }
        try {
            // This is just a pre-check for user-friendliness. The Goal itself will validate.
            Material.valueOf(itemName);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Unknown item: " + itemName, NamedTextColor.RED));
            return true;
        }
        try {
            CraftItemParameters params = new CraftItemParameters(itemName, quantity);
            Goal craftGoal = goalFactory.createGoal("CRAFT_ITEM", params);
            agent.assignGoal(craftGoal, params);
            sender.sendMessage(Component.text("Instructing " + agent.getName() + " to craft " + quantity + " " + itemName + ".",
                    NamedTextColor.GREEN));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Error creating goal: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().log(Level.WARNING, "Failed to create CraftItemGoal from command.", e);
        }
        return true;
    }

    private boolean handleDo(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /nerrus do <name> <directive...>", NamedTextColor.RED));
            return true;
        }
        String name = args[1];
        NerrusAgent agent = agentService.findAgentByName(name);
        if (agent == null) {
            player.sendMessage(Component.text("Agent not found: " + name, NamedTextColor.RED));
            return true;
        }
        String directive = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        agent.assignDirective(directive, player);
        player.sendMessage(Component.text("Directive issued to " + name + ": '" + directive + "'", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleAsk(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /nerrus ask <name> <question...>", NamedTextColor.RED));
            return true;
        }
        String name = args[1];
        NerrusAgent agent = agentService.findAgentByName(name);
        if (agent == null) {
            player.sendMessage(Component.text("Agent not found: " + name, NamedTextColor.RED));
            return true;
        }
        String question = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        player.sendMessage(Component.text("Asking " + name + ": '" + question + "'", NamedTextColor.GRAY));
        AskDirector askDirector = new AskDirector(plugin.getLlmService());
        askDirector.executeQuery(agent, question, player);
        return true;
    }

    private boolean handleFollow(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(
                    Component.text("Usage: /nerrus follow <agentName> <targetName> [distance]", NamedTextColor.RED));
            return true;
        }
        NerrusAgent agent = agentService.findAgentByName(args[1]);
        if (agent == null) {
            sender.sendMessage(Component.text("Agent not found: " + args[1], NamedTextColor.RED));
            return true;
        }
        String targetName = args[2];
        // The distance the agent tries to maintain (Stop Distance)
        double targetDistance = args.length > 3 ? Double.parseDouble(args[3]) : 6.0;
        // We enforce a 3-block buffer (Hysteresis) to prevent the "Bounce" effect.
        // The agent stops at targetDistance, but won't start pathfinding again until targetDistance + 3.
        double stopDist = Math.max(2.0, targetDistance); // Clamp minimum distance
        double followDist = stopDist + 4.0;
        int duration = 30; // injected follow defaults to 10 seconds
        try {
            FollowPlayerParameters params = new FollowPlayerParameters(targetName, followDist, stopDist, duration);
            Goal followGoal = goalFactory.createGoal("FOLLOW_PLAYER", params);
            agent.assignGoal(followGoal, params);
            sender.sendMessage(Component.text("Instructing " + agent.getName() + " to follow " + targetName, NamedTextColor.GREEN));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Error: " + e.getMessage(), NamedTextColor.RED));
        }
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
            for (String agentName : agentNames) {
                sender.sendMessage(Component.text("- " + agentName, NamedTextColor.GRAY));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
            @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("spawn", "remove", "stop", "gather", "give", "do", "ask", "list", "follow", "craft");
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "remove", "stop", "gather", "do", "give", "follow", "ask", "craft" -> {
                    return agentService.getAgents().stream()
                            .map(NerrusAgent::getName)
                            .collect(Collectors.toList());
                }
                default -> {
                    return Collections.emptyList();
                }
            }
        }
        if (args.length == 3) {
            switch (args[0].toLowerCase()) {
                case "spawn" -> {
                    return Bukkit.getOnlinePlayers().stream()
                            .map(Player::getName)
                            .collect(Collectors.toList());
                }
                case "gather" -> {
                    return blockTagManager.getRegisteredGroupNames().stream().sorted().collect(Collectors.toList());
                }
                case "give", "follow" -> {
                    Stream<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName);
                    Stream<String> agents = agentService.getAgents().stream().map(NerrusAgent::getName);
                    return Stream.concat(players, agents).distinct().sorted().collect(Collectors.toList());
                }
                case "craft" -> {
                    return Arrays.stream(Material.values())
                            .filter(Material::isItem)
                            .map(m -> m.name().toLowerCase())
                            .sorted()
                            .collect(Collectors.toList());
                }
                default -> {
                    return Collections.emptyList();
                }
            }
        }
        if (args.length == 4) {
            switch (args[0].toLowerCase()) {
                case "give":
                    return Arrays.stream(Material.values())
                            .filter(Material::isItem)
                            .map(m -> m.name().toLowerCase())
                            .sorted()
                            .collect(Collectors.toList());
                case "gather":
                case "craft":
                    return List.of("1", "8", "16", "32", "64");
                case "follow":
                    return List.of("3.0", "5.0", "8.0", "12.0");
            }
        }
        if (args.length == 5) {
            if ("give".equalsIgnoreCase(args[0])) {
                return List.of("1", "8", "16", "32", "64");
            }
        }
        return Collections.emptyList();
    }
}