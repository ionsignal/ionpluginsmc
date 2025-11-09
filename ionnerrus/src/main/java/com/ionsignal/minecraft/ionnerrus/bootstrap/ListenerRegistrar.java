package com.ionsignal.minecraft.ionnerrus.bootstrap;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.chat.ChatBubbleListener;
import com.ionsignal.minecraft.ionnerrus.chat.ChatBubbleService;
import com.ionsignal.minecraft.ionnerrus.listeners.PlayerListener;
import com.ionsignal.minecraft.ionnerrus.persona.NerrusManager;
import com.ionsignal.minecraft.ionnerrus.persona.listeners.PersonaInteractionListener;

import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;

/**
 * Registers all event listeners with the Bukkit event system.
 * Handles conditional registration based on plugin configuration.
 */
public class ListenerRegistrar {
    private final IonNerrus plugin;
    private final NerrusManager nerrusManager;
    private final ChatBubbleService chatBubbleService;

    public ListenerRegistrar(IonNerrus plugin, NerrusManager nerrusManager, ChatBubbleService chatBubbleService) {
        this.plugin = plugin;
        this.nerrusManager = nerrusManager;
        this.chatBubbleService = chatBubbleService;
    }

    /**
     * Registers all listeners. Must be called during plugin enable.
     */
    public void registerAll() {
        PluginManager manager = plugin.getServer().getPluginManager();
        // Core listeners (always registered)
        manager.registerEvents(new PlayerListener(plugin, nerrusManager), plugin);
        manager.registerEvents(new PersonaInteractionListener(), plugin);
        // Conditional listener: Chat bubbles (requires FancyHolograms AND successful initialization)
        if (plugin.getPluginConfig().isChatBubblesEnabled() && chatBubbleService != null) {
            manager.registerEvents(new ChatBubbleListener(plugin, chatBubbleService), plugin);
            plugin.getLogger().info("Chat Bubbles feature enabled.");
        } else if (plugin.getPluginConfig().isChatBubblesEnabled()) {
            // Config enabled but service unavailable - inform admin
            plugin.getLogger().warning("Chat Bubbles enabled in config, but service failed to initialize.");
        }
        plugin.getLogger().info("Registered event listeners.");
    }

    /**
     * Unregisters all event listeners from the Bukkit event system and must be called during plugin
     * disable to prevent stale references.
     */
    public void unregisterAll() {
        HandlerList.unregisterAll(plugin);
        plugin.getLogger().info("Unregistered all event listeners.");
    }
}