package com.ionsignal.minecraft.ionnerrus;

import com.ionsignal.minecraft.ionnerrus.agent.AgentService;
import com.ionsignal.minecraft.ionnerrus.agent.content.BlockTagManager;
import com.ionsignal.minecraft.ionnerrus.agent.content.RecipeService;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalFactory;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistrar;
import com.ionsignal.minecraft.ionnerrus.agent.goals.GoalRegistry;
import com.ionsignal.minecraft.ionnerrus.agent.llm.LLMService;
import com.ionsignal.minecraft.ionnerrus.chat.ChatBubbleListener;
import com.ionsignal.minecraft.ionnerrus.chat.ChatBubbleService;
import com.ionsignal.minecraft.ionnerrus.commands.NerrusCognitiveDebugCommand;
import com.ionsignal.minecraft.ionnerrus.commands.NerrusCommand;
import com.ionsignal.minecraft.ionnerrus.commands.NerrusDebugCommand;
import com.ionsignal.minecraft.ionnerrus.listeners.PlayerListener;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusManager;
import com.ionsignal.minecraft.ionnerrus.persona.listeners.PersonaInteractionListener;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

public class IonNerrus extends JavaPlugin {
    private static IonNerrus instance;

    private Executor mainThreadExecutor;
    private Executor offloadThreadExecutor;
    private AgentService agentService;
    private NerrusManager nerrusManager;
    private GoalRegistry goalRegistry;
    private GoalFactory goalFactory;
    private BlockTagManager blockTagManager;
    private LLMService llmService;
    private RecipeService recipeService;
    private ChatBubbleService chatBubbleService;
    private PluginConfig pluginConfig;

    @Override
    public void onEnable() {
        instance = this;

        // threading
        mainThreadExecutor = runnable -> Bukkit.getScheduler().runTask(this, runnable);
        offloadThreadExecutor = runnable -> getServer().getScheduler().runTaskAsynchronously(this, runnable);

        load();

        NerrusCommand nerrusCommand = new NerrusCommand(this, this.agentService, this.blockTagManager, this.goalFactory, this.goalRegistry);

        NerrusDebugCommand nerrusDebugCommand = new NerrusDebugCommand(this);
        PluginCommand debugCmd = getCommand("nerrusdebug");
        Objects.requireNonNull(debugCmd, "The 'nerrusdebug' command is not registered in plugin.yml");
        debugCmd.setExecutor(nerrusDebugCommand);
        debugCmd.setTabCompleter(nerrusDebugCommand);

        NerrusCognitiveDebugCommand ugCommand = new NerrusCognitiveDebugCommand(this);
        PluginCommand cognitiveDebugCmd = getCommand("cognitivedebug");
        Objects.requireNonNull(cognitiveDebugCmd, "The 'cognitivedebug' command is not registered in plugin.yml");
        cognitiveDebugCmd.setExecutor(ugCommand);
        cognitiveDebugCmd.setTabCompleter(ugCommand);

        PluginCommand command = getCommand("nerrus");
        Objects.requireNonNull(command, "The 'nerrus' command is not registered in plugin.yml");
        command.setExecutor(nerrusCommand);
        command.setTabCompleter(nerrusCommand);

        getServer().getPluginManager().registerEvents(new PlayerListener(this, nerrusManager), this);
        getServer().getPluginManager().registerEvents(new PersonaInteractionListener(), this);

        if (pluginConfig.isChatBubblesEnabled()) {
            if (getServer().getPluginManager().getPlugin("FancyHolograms") == null) {
                getLogger().warning("Chat Bubbles feature is enabled, but the FancyHolograms plugin was not found. Feature disabled.");
            } else {
                getServer().getPluginManager().registerEvents(new ChatBubbleListener(this, this.chatBubbleService), this);
                getLogger().info("Chat Bubbles feature has been enabled.");
            }
        }

        Bukkit.getScheduler().runTaskLater(this, this::disableNonWoodCraftingRecipes, 1L);

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

        // Platform-specific and manager setup
        this.nerrusManager = new NerrusManager(this);
        if (!this.nerrusManager.initialize()) {
            getLogger().severe("Failed to initialize NerrusManager. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Configuration and content
        this.pluginConfig = new PluginConfig(getConfig());
        this.blockTagManager = new BlockTagManager();
        this.recipeService = new RecipeService(this.blockTagManager);

        // Core Factories and Services (in dependency order)
        // this.taskFactory = new TaskFactory(this.blockTagManager);
        this.goalRegistry = new GoalRegistry();
        this.goalFactory = new GoalFactory(this.blockTagManager, this.recipeService);
        this.llmService = new LLMService(this);
        this.agentService = new AgentService(this, this.nerrusManager, this.goalRegistry, this.goalFactory, this.llmService);

        // Registration of static tool definitions (now happens only once)
        GoalRegistrar registrar = new GoalRegistrar(this.goalRegistry, this.blockTagManager);
        registrar.registerAll();

        // Chat bubble service
        this.chatBubbleService = new ChatBubbleService(this);

        // Load saved agents, future implementation
        // this.agentService.loadAgents();
    }

    private void disableNonWoodCraftingRecipes() {
        Iterator<Recipe> recipeIterator = Bukkit.recipeIterator();
        List<NamespacedKey> toRemove = new ArrayList<>();
        Set<String> woodKeywords = Set.of("planks", "log", "wood", "stick", "crafting_table", "chest", "barrel");
        while (recipeIterator.hasNext()) {
            Recipe recipe = recipeIterator.next();
            if (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe) {
                NamespacedKey key = ((Keyed) recipe).getKey();
                if (key.getNamespace().equals(NamespacedKey.MINECRAFT)) {
                    boolean isWoodRecipe = woodKeywords.stream().anyMatch(keyword -> key.getKey().contains(keyword));
                    if (!isWoodRecipe) {
                        toRemove.add(key);
                    }
                }
            }
        }
        toRemove.forEach(Bukkit::removeRecipe);
        getLogger().info("Disabled " + toRemove.size() + " non-wood crafting recipes for testing");
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

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public ChatBubbleService getChatBubbleService() {
        return chatBubbleService;
    }
}