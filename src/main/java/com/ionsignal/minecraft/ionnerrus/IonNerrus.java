package com.ionsignal.minecraft.ionnerrus;

import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.llm.LLMService;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalDefinition;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.goals.ParameterDefinition;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.TaskFactory;
import com.ionsignal.minecraft.ionnerrus.commands.NerrusCommand;
import com.ionsignal.minecraft.ionnerrus.listeners.PlayerListener;
import com.ionsignal.minecraft.ionnerrus.persona.listeners.PersonaInteractionListener;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusManager;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

public class IonNerrus extends JavaPlugin {
    private static IonNerrus instance;

    private Executor mainThreadExecutor;
    private Executor offloadThreadExecutor;
    private AgentService agentService;
    private NerrusManager nerrusManager;
    private GoalRegistry goalRegistry;
    private GoalFactory goalFactory;
    private TaskFactory taskFactory;
    private BlockTagManager blockTagManager;
    private LLMService llmService;

    @SuppressWarnings("unused")
    private PluginConfig pluginConfig;

    @Override
    public void onEnable() {
        instance = this;

        // threading
        mainThreadExecutor = runnable -> Bukkit.getScheduler().runTask(this, runnable);
        offloadThreadExecutor = runnable -> getServer().getScheduler().runTaskAsynchronously(this, runnable);

        load();

        NerrusCommand nerrusCommand = new NerrusCommand(this, this.agentService, this.blockTagManager, this.goalFactory, this.goalRegistry);
        PluginCommand command = getCommand("nerrus");
        Objects.requireNonNull(command, "The 'nerrus' command is not registered in plugin.yml");
        command.setExecutor(nerrusCommand);
        command.setTabCompleter(nerrusCommand);

        getServer().getPluginManager().registerEvents(new PlayerListener(this, nerrusManager), this);
        getServer().getPluginManager().registerEvents(new PersonaInteractionListener(), this);
        getLogger().info("IonNerrus has been enabled.");
    }

    @Override
    public void onDisable() {
        if (llmService != null) {
            llmService.shutdown();
        }
        if (nerrusManager != null) {
            nerrusManager.shutdown();
        }
        getLogger().info("IonNerrus has been disabled.");
    }

    private void load() {
        saveDefaultConfig();
        reloadConfig();

        this.nerrusManager = new NerrusManager(this);
        if (!this.nerrusManager.initialize()) {
            return;
        }

        this.pluginConfig = new PluginConfig(getConfig());
        this.taskFactory = new TaskFactory(getLogger());
        this.blockTagManager = new BlockTagManager();
        this.agentService = new AgentService(this, this.nerrusManager);

        // Initialize the goal and director services
        this.goalRegistry = new GoalRegistry();
        registerGoals(); // Keep the load() method clean, maybe move to init() method

        this.goalFactory = new GoalFactory(this.goalRegistry, this.taskFactory, this.blockTagManager);
        this.llmService = new LLMService(this);

        // Load saved agents, future implementation
        // this.agentService.loadAgents();
    }

    /**
     * Registers all available GoalDefinitions with the GoalRegistry.
     * This is where new agent capabilities are "announced" to the system.
     */
    private void registerGoals() {
        goalRegistry.register(new GoalDefinition(
                "CANNOT_COMPLETE",
                "Call this function if and only if the user's objective is impossible to achieve with the other available tools. Use it to explain why the task cannot be done.",
                Map.of("reason",
                        new ParameterDefinition("String", "A clear explanation about why the objective failed.", true))));
        goalRegistry.register(new GoalDefinition(
                "GET_BLOCKS",
                "Navigates to and gathers a specified quantity of a block type from a predefined group.",
                Map.of(
                        "groupName",
                        new ParameterDefinition("String",
                                "The block group to collect (only 'wood', 'stone', 'dirt', 'sand', 'flowers' and 'mushroom' are supported).",
                                true),
                        "quantity", new ParameterDefinition("int", "The number of blocks to collect.", true))));
        // Future goals like CRAFT_ITEM would be registered here
    }

    public static IonNerrus getInstance() {
        return instance;
    }

    public Executor getOffloadThreadExecutor() {
        return offloadThreadExecutor;
    }

    public Executor getMainThreadExecutor() {
        return mainThreadExecutor;
    }

    public LLMService getLlmService() {
        return llmService;
    }
}