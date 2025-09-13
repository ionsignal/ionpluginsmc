package com.ionsignal.minecraft.ionnerrus;

import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistrar;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.llm.LLMService;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.TaskFactory;
import com.ionsignal.minecraft.ionnerrus.commands.NerrusCommand;
import com.ionsignal.minecraft.ionnerrus.listeners.PlayerListener;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusManager;
import com.ionsignal.minecraft.ionnerrus.persona.listeners.PersonaInteractionListener;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

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

        // Platform-specific and Manager setup
        this.nerrusManager = new NerrusManager(this);
        if (!this.nerrusManager.initialize()) {
            getLogger().severe("Failed to initialize NerrusManager. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Configuration and Content
        this.pluginConfig = new PluginConfig(getConfig());
        this.blockTagManager = new BlockTagManager();

        // Core Factories and Services (in dependency order)
        this.taskFactory = new TaskFactory(getLogger());
        this.goalRegistry = new GoalRegistry();
        this.goalFactory = new GoalFactory(this.taskFactory, this.blockTagManager);
        this.llmService = new LLMService(this);
        this.agentService = new AgentService(this, this.nerrusManager, this.goalRegistry, this.goalFactory, this.llmService);

        // Registration of static tool definitions (now happens only once)
        GoalRegistrar registrar = new GoalRegistrar(this.goalRegistry, this.blockTagManager);
        registrar.registerAll();

        // Load saved agents, future implementation
        // this.agentService.loadAgents();
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

    public AgentService getAgentService() {
        return agentService;
    }
}