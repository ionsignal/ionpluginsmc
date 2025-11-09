package com.ionsignal.minecraft.ionnerrus;

import org.bukkit.Color;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public class PluginConfig {
    private final FileConfiguration handle;

    public PluginConfig(FileConfiguration handle) {
        this.handle = handle;
    }

    public List<String> getEnabledWorlds() {
        return this.handle.getStringList("enabled-worlds");
    }

    public boolean isHuskHomesIntegrationEnabled() {
        return this.handle.getBoolean("integrations.huskhomes", false);
    }

    public boolean isChatBubblesEnabled() {
        return this.handle.getBoolean("chat-bubbles.enabled", false);
    }

    public double getChatBubbleYOffset() {
        return this.handle.getDouble("chat-bubbles.y-offset", 1.2);
    }

    public int getChatBubbleVisibilityDistance() {
        return this.handle.getInt("chat-bubbles.visibility-distance", 20);
    }

    public int getChatBubbleMaxWidth() {
        return this.handle.getInt("chat-bubbles.max-line-width", 40);
    }

    public int getChatBubbleWPM() {
        return this.handle.getInt("chat-bubbles.words-per-minute", 180);
    }

    public double getChatBubbleMinSlideDuration() {
        return this.handle.getDouble("chat-bubbles.min-slide-duration-seconds", 2.0);
    }

    /**
     * Gets whether non-wood crafting recipes should be disabled for testing.
     * This is a development/testing utility and should always be false in production.
     *
     * @return true if non-wood recipes should be disabled, false otherwise
     */
    public boolean isDisableNonWoodRecipes() {
        return this.handle.getBoolean("testing.disableNonWoodRecipes", false);
    }

    public Color getChatBubbleBackgroundColor() {
        String hex = this.handle.getString("chat-bubbles.background-color-hex", "#BFFFFFFF");
        try {
            // Ensure the hex string is valid for parsing
            if (hex.startsWith("#")) {
                long colorValue = Long.parseLong(hex.substring(1), 16);
                // Handle RGB and ARGB hex strings
                if (hex.length() == 7) { // #RRGGBB
                    return Color.fromRGB((int) colorValue);
                } else if (hex.length() == 9) { // #AARRGGBB
                    return Color.fromARGB((int) colorValue);
                }
            }
        } catch (NumberFormatException e) {
            // Fallback to default if parsing fails
        }
        return Color.fromARGB(191, 255, 255, 255); // Default semi-transparent white
    }

    public float getChatBubbleScale() {
        return (float) this.handle.getDouble("chat-bubbles.scale", 1.2);
    }
}