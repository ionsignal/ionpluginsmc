package com.ionsignal.minecraft.ionnerrus;

import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.tasks.TaskFactory;
import com.ionsignal.minecraft.ionnerrus.commands.NerrusCommand;
import com.ionsignal.minecraft.ionnerrus.listeners.PlayerListener;
import com.ionsignal.minecraft.ionnerrus.persona.listeners.PersonaInteractionListener;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusManager;

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
    private TaskFactory taskFactory;
    private NerrusManager nerrusManager;

    @SuppressWarnings("unused")
    private PluginConfig pluginConfig;

    @Override
    public void onEnable() {
        instance = this;

        // threading
        mainThreadExecutor = runnable -> Bukkit.getScheduler().runTask(this, runnable);
        offloadThreadExecutor = runnable -> getServer().getScheduler().runTaskAsynchronously(this, runnable);

        load();

        NerrusCommand nerrusCommand = new NerrusCommand(this.agentService, this.taskFactory);
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
        this.agentService = new AgentService(this, this.nerrusManager);

        // TODO: saved agent loading
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
}