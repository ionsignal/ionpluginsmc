package com.ionsignal.minecraft.ionnerrus.commands;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.impl.GiveItemGoal.GiveItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.FollowPlayerParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.GatherBlockParameters;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import org.bukkit.command.CommandSender;

import org.incendo.cloud.annotation.specifier.Range;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Default;
import org.incendo.cloud.annotations.Permission;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Handles physical Goal assignments.
 */
public class NerrusGoalCommands {
    @SuppressWarnings("unused")
    private final IonNerrus plugin;

    @SuppressWarnings("unused")
    private final AgentService agentService;

    private final GoalFactory goalFactory;
    private final BlockTagManager blockTagManager;

    public NerrusGoalCommands(
            AgentService agentService,
            GoalFactory goalFactory,
            BlockTagManager blockTagManager,
            IonNerrus plugin) {
        this.agentService = agentService;
        this.goalFactory = goalFactory;
        this.blockTagManager = blockTagManager;
        this.plugin = plugin;
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
}