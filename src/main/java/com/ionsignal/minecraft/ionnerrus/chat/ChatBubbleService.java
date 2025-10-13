package com.ionsignal.minecraft.ionnerrus.chat;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.PluginConfig;
import com.ionsignal.minecraft.ionnerrus.util.ChatWrapper;

import com.fancyinnovations.fancyholograms.api.FancyHolograms;
import com.fancyinnovations.fancyholograms.api.data.TextHologramData;
import com.fancyinnovations.fancyholograms.api.hologram.Hologram;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages the lifecycle of temporary chat bubble holograms for entities.
 */
public class ChatBubbleService {
    private final IonNerrus plugin;
    private final Map<UUID, HologramTrackerTask> activeBubbleTasks = new ConcurrentHashMap<>();

    public ChatBubbleService(IonNerrus plugin) {
        this.plugin = plugin;
    }

    /**
     * Creates and displays a chat bubble above the specified entity. If a bubble
     * already exists for this entity, it is replaced.
     *
     * @param entity
     *            The entity to display the bubble above (Player or Persona).
     * @param message
     *            The message content for the bubble.
     */
    public void showBubble(Entity entity, String message) {
        UUID entityUuid = entity.getUniqueId();
        // If a bubble task already exists, cancel it and remove the old hologram.
        if (activeBubbleTasks.containsKey(entityUuid)) {
            activeBubbleTasks.remove(entityUuid).cancelAndCleanup();
        }
        PluginConfig config = plugin.getPluginConfig();
        Location location = entity.getLocation().add(0, entity.getHeight() + config.getChatBubbleYOffset(), 0);
        String hologramName = "chatbubble-" + entityUuid;
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        TextHologramData holoData = new TextHologramData(hologramName, location);
        holoData.setText(List.of()); // Start with an empty hologram
        holoData.setBillboard(Display.Billboard.CENTER);
        holoData.setBackground(config.getChatBubbleBackgroundColor());
        holoData.setTextShadow(false);
        holoData.setPersistent(false); // Critical: Do not save temporary holograms.
        holoData.setVisibilityDistance(config.getChatBubbleVisibilityDistance());
        float scale = config.getChatBubbleScale();
        holoData.setScale(new Vector3f(scale, scale, scale));
        Hologram hologram = FancyHolograms.get().getHologramFactory().apply(holoData);
        FancyHolograms.get().getRegistry().register(hologram);
        HologramTrackerTask task = new HologramTrackerTask(entity, hologram, message);
        task.runTaskTimer(plugin, 0L, 1L); // Update position every tick for smoothness
        activeBubbleTasks.put(entityUuid, task);
    }

    /**
     * Cleans up any active chat bubble for a player who is quitting the server.
     *
     * @param playerUuid
     *            The UUID of the quitting player.
     */
    public void onPlayerQuit(UUID playerUuid) {
        if (activeBubbleTasks.containsKey(playerUuid)) {
            activeBubbleTasks.remove(playerUuid).cancelAndCleanup();
        }
    }

    /**
     * A self-contained task that manages a single hologram's lifecycle, including
     * following an entity and displaying sentences sequentially.
     */
    private class HologramTrackerTask extends BukkitRunnable {
        private final Entity entity;
        private final Hologram hologram;
        private final List<String> slides;
        private int currentSlideIndex = -1;
        private int ticksUntilNextSlide = 0;

        HologramTrackerTask(Entity entity, Hologram hologram, String fullMessage) {
            this.entity = entity;
            this.hologram = hologram;
            // Split message into sentences (slides) using punctuation as delimiters.
            this.slides = Arrays.stream(fullMessage.replace("...", ".").split("(?<=[.?!])\\s*"))
                    .filter(s -> !s.trim().isEmpty())
                    .collect(Collectors.toList());
        }

        @Override
        public void run() {
            if (!entity.isValid()) {
                cancelAndCleanup();
                return;
            }
            // Update hologram position to follow the entity every tick.
            Location newLocation = entity.getLocation().add(0, entity.getHeight() + plugin.getPluginConfig().getChatBubbleYOffset(), 0);
            hologram.getData().setLocation(newLocation);
            FancyHolograms.get().getController().updateHologramData(hologram, entity.getServer().getOnlinePlayers().toArray(new Player[0]));
            ticksUntilNextSlide--;
            if (ticksUntilNextSlide <= 0) {
                displayNextSlide();
            }
        }

        private void displayNextSlide() {
            currentSlideIndex++;
            if (currentSlideIndex >= slides.size()) {
                cancelAndCleanup();
                return;
            }
            String sentence = slides.get(currentSlideIndex).trim();
            if (sentence.isEmpty()) {
                displayNextSlide(); // Skip empty slides
                return;
            }
            PluginConfig config = plugin.getPluginConfig();
            List<String> wrappedSentence = ChatWrapper.wrap(sentence, config.getChatBubbleMaxWidth());
            ((TextHologramData) hologram.getData()).setText(wrappedSentence);
            // Calculate display time based on word count
            int wordCount = sentence.split("\\s+").length;
            double wordsPerSecond = (double) config.getChatBubbleWPM() / 60.0;
            double durationSeconds = Math.max(config.getChatBubbleMinSlideDuration(), wordCount / wordsPerSecond);
            this.ticksUntilNextSlide = (int) (durationSeconds * 20);
        }

        /**
         * Cancels the task and ensures the associated hologram is destroyed.
         */
        void cancelAndCleanup() {
            if (!isCancelled()) {
                this.cancel();
            }
            activeBubbleTasks.remove(entity.getUniqueId());
            FancyHolograms.get().getRegistry().unregister(hologram);
        }
    }
}