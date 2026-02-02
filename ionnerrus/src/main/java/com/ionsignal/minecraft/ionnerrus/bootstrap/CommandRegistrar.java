package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.commands.NerrusCognitiveDebugCommand;
import com.ionsignal.minecraft.ionnerrus.commands.NerrusCommand;
import com.ionsignal.minecraft.ionnerrus.commands.NerrusDebugCommand;

import com.ionsignal.minecraft.ioncore.auth.IdentityService;

import org.bukkit.command.PluginCommand;

import java.util.Objects;

/**
 * Registers all plugin commands with the Bukkit command system.
 * This class is responsible for wiring command executors and tab completers.
 */
public class CommandRegistrar {
    private final IonNerrus plugin;
    private final AgentService agentService;
    private final BlockTagManager blockTagManager;
    private final GoalFactory goalFactory;
    private final GoalRegistry goalRegistry;
    private final IdentityService identityService;

    public CommandRegistrar(
            IonNerrus plugin,
            AgentService agentService,
            BlockTagManager blockTagManager,
            GoalFactory goalFactory,
            GoalRegistry goalRegistry,
            IdentityService identityService) {
        this.plugin = plugin;
        this.agentService = agentService;
        this.blockTagManager = blockTagManager;
        this.goalFactory = goalFactory;
        this.goalRegistry = goalRegistry;
        this.identityService = identityService;
    }

    /**
     * Registers all commands. Must be called during plugin enable.
     */
    public void registerAll() {
        // /nerrus command
        NerrusCommand nerrusCommand = new NerrusCommand(plugin, agentService, blockTagManager, goalFactory, goalRegistry, identityService);
        PluginCommand nerrusCmd = plugin.getCommand("nerrus");
        Objects.requireNonNull(nerrusCmd, "The 'nerrus' command is not registered in plugin.yml");
        nerrusCmd.setExecutor(nerrusCommand);
        nerrusCmd.setTabCompleter(nerrusCommand);
        // /nerrusdebug command
        NerrusDebugCommand nerrusDebugCommand = new NerrusDebugCommand(plugin);
        PluginCommand debugCmd = plugin.getCommand("nerrusdebug");
        Objects.requireNonNull(debugCmd, "The 'nerrusdebug' command is not registered in plugin.yml");
        debugCmd.setExecutor(nerrusDebugCommand);
        debugCmd.setTabCompleter(nerrusDebugCommand);
        // /cognitivedebug command
        NerrusCognitiveDebugCommand cognitiveDebugCommand = new NerrusCognitiveDebugCommand(plugin);
        PluginCommand cognitiveDebugCmd = plugin.getCommand("cognitivedebug");
        Objects.requireNonNull(cognitiveDebugCmd, "The 'cognitivedebug' command is not registered in plugin.yml");
        cognitiveDebugCmd.setExecutor(cognitiveDebugCommand);
        cognitiveDebugCmd.setTabCompleter(cognitiveDebugCommand);
        plugin.getLogger().info("Registered '/nerrus', '/nerrusdebug', and '/cognitivedebug' commands.");
    }

    /**
     * Unregisters commands from the Bukkit system even (technically) a no-op in Bukkit but we clear
     * executors/completers to prevent stale references.
     */
    public void unregisterAll() {
        PluginCommand nerrusCmd = plugin.getCommand("nerrus");
        if (nerrusCmd != null) {
            nerrusCmd.setExecutor(null);
            nerrusCmd.setTabCompleter(null);
        }
        PluginCommand debugCmd = plugin.getCommand("nerrusdebug");
        if (debugCmd != null) {
            debugCmd.setExecutor(null);
            debugCmd.setTabCompleter(null);
        }
        PluginCommand cognitiveDebugCmd = plugin.getCommand("cognitivedebug");
        if (cognitiveDebugCmd != null) {
            cognitiveDebugCmd.setExecutor(null);
            cognitiveDebugCmd.setTabCompleter(null);
        }
        plugin.getLogger().info("Unregistered all commands.");
    }
}