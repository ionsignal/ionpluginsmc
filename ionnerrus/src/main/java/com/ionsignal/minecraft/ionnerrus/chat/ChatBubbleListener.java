package com.ionsignal.minecraft.ionnerrus.chat;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;

import io.papermc.paper.event.player.AsyncChatEvent;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Listens for player chat events to create chat bubbles.
 */
public class ChatBubbleListener implements Listener {
    private final IonNerrus plugin;
    private final ChatBubbleService chatBubbleService;

    public ChatBubbleListener(IonNerrus plugin, ChatBubbleService chatBubbleService) {
        this.plugin = plugin;
        this.chatBubbleService = chatBubbleService;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerChat(AsyncChatEvent event) {
        // Cancel the event to create proximity-based chat bubbles instead of global chat.
        event.setCancelled(true);

        Player player = event.getPlayer();
        // Use legacy serializer to support color codes like &c, &l etc.
        String message = LegacyComponentSerializer.legacyAmpersand().serialize(event.message());

        // Schedule the bubble creation for the main server thread.
        player.getServer().getScheduler().runTask(
                plugin, // Use the plugin instance passed to the listener
                () -> chatBubbleService.showBubble(player, message));
    }
}