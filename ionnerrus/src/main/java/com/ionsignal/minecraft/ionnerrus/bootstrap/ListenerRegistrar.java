package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.chat.ChatBubbleListener;
import com.ionsignal.minecraft.ionnerrus.chat.ChatBubbleService;
import com.ionsignal.minecraft.ionnerrus.listeners.PlayerListener;
import com.ionsignal.minecraft.ionnerrus.network.NetworkService;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusManager;
import com.ionsignal.minecraft.ionnerrus.persona.listeners.PersonaInteractionListener;

import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;

import org.jetbrains.annotations.Nullable;

public class ListenerRegistrar {
    private final IonNerrus plugin;
    private final NerrusManager nerrusManager;
    private final ChatBubbleService chatBubbleService;

    @Nullable
    private final NetworkService nerrusBridge;

    public ListenerRegistrar(
            IonNerrus plugin,
            NerrusManager nerrusManager,
            ChatBubbleService chatBubbleService,
            @Nullable NetworkService networkService) {
        this.plugin = plugin;
        this.nerrusManager = nerrusManager;
        this.chatBubbleService = chatBubbleService;
        this.nerrusBridge = networkService;
    }

    public void registerAll() {
        PluginManager manager = plugin.getServer().getPluginManager();
        manager.registerEvents(new PlayerListener(plugin, nerrusManager), plugin);
        manager.registerEvents(new PersonaInteractionListener(), plugin);
        if (plugin.getPluginConfig().isChatBubblesEnabled() && chatBubbleService != null) {
            manager.registerEvents(new ChatBubbleListener(plugin, chatBubbleService), plugin);
            plugin.getLogger().info("Chat Bubbles feature enabled.");
        } else if (plugin.getPluginConfig().isChatBubblesEnabled()) {
            plugin.getLogger().warning("Chat Bubbles enabled in config, but service failed to initialize.");
        }
        if (nerrusBridge != null) {
            manager.registerEvents(nerrusBridge, plugin);
            plugin.getLogger().info("NerrusBridge registered (IonCore connected).");
        } else {
            plugin.getLogger().info("NerrusBridge is null — running in standalone mode, outbound events disabled.");
        }
        plugin.getLogger().info("Registered event listeners.");
    }

    public void unregisterAll() {
        HandlerList.unregisterAll(plugin);
        plugin.getLogger().info("Unregistered all event listeners.");
    }
}