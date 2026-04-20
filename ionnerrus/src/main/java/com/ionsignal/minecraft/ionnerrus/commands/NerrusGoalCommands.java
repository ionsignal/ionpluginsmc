package com.ionsignal.minecraft.ionnerrus.commands;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.Goal;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.following.FollowPlayerParameters;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.gathering.GatherBlockParameters;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.interaction.GiveItemGoal.GiveItemParameters;
import com.ionsignal.minecraft.ionnerrus.agent.lifecycle.AgentService;
import com.ionsignal.minecraft.ionnerrus.content.BlockTagManager;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Handles physical Goal assignments.
 * MODIFIED: Refactored to consume Brigadier CommandContext and utilize Paper's native arguments.
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

    public int gatherBlock(CommandContext<CommandSourceStack> ctx) {
        NerrusAgent agent = ctx.getArgument("agent", NerrusAgent.class);
        String groupName = ctx.getArgument("block_type", String.class);
        int amount = ctx.getArgument("amount", Integer.class);
        CommandSender sender = ctx.getSource().getSender();

        try {
            GatherBlockParameters params = new GatherBlockParameters(groupName, amount);
            Goal gatherGoal = goalFactory.createGoal("GATHER_BLOCK", params);
            agent.assignGoal(gatherGoal, params);
            sender.sendMessage(
                    Component.text("Instructing " + agent.getName() + " to gather " + amount + " " + groupName, NamedTextColor.GREEN));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Error creating goal: " + e.getMessage(), NamedTextColor.RED));
        }
        return Command.SINGLE_SUCCESS;
    }

    public int giveItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        NerrusAgent agent = ctx.getArgument("agent", NerrusAgent.class);
        // Resolve the target player using Paper's native selector
        List<Player> targets = ctx.getArgument("target", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        if (targets.isEmpty()) {
            ctx.getSource().getSender().sendMessage(Component.text("No player found matching target.", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        String targetName = targets.get(0).getName();

        // Extract the material name from the natively parsed ItemStack
        ItemStack itemStack = ctx.getArgument("item", ItemStack.class);
        String itemName = itemStack.getType().name();
        int amount = ctx.getArgument("amount", Integer.class);
        CommandSender sender = ctx.getSource().getSender();

        try {
            GiveItemParameters params = new GiveItemParameters(itemName, amount, targetName);
            Goal giveItemGoal = goalFactory.createGoal("GIVE_ITEM", params);
            agent.assignGoal(giveItemGoal, params);
            sender.sendMessage(Component.text(
                    "Instructing " + agent.getName() + " to give " + amount + " " + itemName + " to " + targetName, NamedTextColor.GREEN));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Error creating goal: " + e.getMessage(), NamedTextColor.RED));
        }
        return Command.SINGLE_SUCCESS;
    }

    public int follow(CommandContext<CommandSourceStack> ctx, double targetDistance) throws CommandSyntaxException {
        NerrusAgent agent = ctx.getArgument("agent", NerrusAgent.class);
        List<Player> targets = ctx.getArgument("target", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        if (targets.isEmpty()) {
            ctx.getSource().getSender().sendMessage(Component.text("No player found matching target.", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        String targetName = targets.get(0).getName();
        CommandSender sender = ctx.getSource().getSender();
        double stopDist = Math.max(2.0, targetDistance);
        double followDist = stopDist + 4.0;
        int duration = 30;
        try {
            FollowPlayerParameters params = new FollowPlayerParameters(targetName, followDist, stopDist, duration);
            Goal followGoal = goalFactory.createGoal("FOLLOW_PLAYER", params);
            agent.assignGoal(followGoal, params);
            sender.sendMessage(Component.text("Instructing " + agent.getName() + " to follow " + targetName, NamedTextColor.GREEN));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Error: " + e.getMessage(), NamedTextColor.RED));
        }
        return Command.SINGLE_SUCCESS;
    }
}