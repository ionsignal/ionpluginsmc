package com.ionsignal.minecraft.ionnerrus;

import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.CannotCompleteParameters;
import com.ionsignal.minecraft.ionnerrus.agent.goals.parameters.GetBlockParameters;
import com.ionsignal.minecraft.ionnerrus.agent.llm.LLMService;
import com.ionsignal.minecraft.ionnerrus.agent.llm.tool.ToolDefinition;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.TaskFactory;
import com.ionsignal.minecraft.ionnerrus.commands.NerrusCommand;
import com.ionsignal.minecraft.ionnerrus.listeners.PlayerListener;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusManager;
import com.ionsignal.minecraft.ionnerrus.persona.listeners.PersonaInteractionListener;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import com.fasterxml.jackson.databind.node.ObjectNode;

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

        this.goalFactory = new GoalFactory(this.taskFactory, this.blockTagManager);
        this.llmService = new LLMService(this);

        // Load saved agents, future implementation
        // this.agentService.loadAgents();
    }

    /**
     * Registers all available GoalDefinitions with the GoalRegistry.
     * This is where new agent capabilities are "announced" to the system.
     */
    private void registerGoals() {
        goalRegistry.register(new ToolDefinition(
                "CANNOT_COMPLETE",
                "Call this function if and only if the user's objective is impossible to achieve with the other available tools. Use it to explain why the task cannot be done.",
                CannotCompleteParameters.class));
        goalRegistry.register(new ToolDefinition(
                "GET_BLOCKS",
                "Navigates to and gathers a specified quantity of a block type from a predefined group.",
                GetBlockParameters.class,
                // append to the `groupName` property description a valid list of `blockTagManager` group names
                schema -> {
                    String validGroups = String.join(", ", blockTagManager.getRegisteredGroupNames());
                    ObjectNode properties = (ObjectNode) schema.get("properties");
                    if (properties != null) {
                        ObjectNode groupNameProp = (ObjectNode) properties.get("groupName");
                        if (groupNameProp != null) {
                            String currentDesc = groupNameProp.get("description").asText();
                            groupNameProp.put("description", currentDesc + " Available groups: " + validGroups);
                        }
                    }
                    return schema;
                }));
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