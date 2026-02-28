package com.ionsignal.minecraft.ionnerrus.listeners;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.hud.HudElement;
import com.ionsignal.minecraft.ionnerrus.hud.HudManager;

import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens for CraftEngine reload events to register default HUD elements.
 * 
 * Lifecycle:
 * 1. IonNerrus.onEnable() → HudManager created → NO elements registered yet
 * 2. CraftEngine.delayedInit() → Loads images from YAML configs
 * 3. CraftEngine fires CraftEngineReloadEvent (isFirstReload = true)
 * 4. This handler fires → Registers default elements → Images now available
 * 
 * Design Rationale:
 * - Follows existing pattern from PlayerListener
 * - Isolates external plugin integration (same pattern as IntegrationBootstrap)
 * - Keeps ServiceContainer as pure dependency injection container
 */
public class CraftEngineReloadListener implements Listener {
    private final IonNerrus plugin;
    private final HudManager hudManager;
    private final AtomicBoolean hasRegisteredDefaultElements = new AtomicBoolean(false);

    public CraftEngineReloadListener(IonNerrus plugin, HudManager hudManager) {
        this.plugin = plugin;
        this.hudManager = hudManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        if (!event.isFirstReload() || !hasRegisteredDefaultElements.compareAndSet(false, true)) {
            return;
        }
        plugin.getLogger().info("CraftEngine has loaded images. Registering default HUD elements...");
        try {
            // NEW: All elements reference the SAME atlas with different tile coordinates
            List<HudElement> defaultElements = List.of(
                    // Icon at tile (0, 0)
                    HudElement.builder()
                            .id("ionnerrus", "a1")
                            .texture("ionnerrus", "hud/atlas.png")
                            .tile(0, 0)
                            .grid(0, 0)
                            .layer(1)
                            .opacity(1.0f)
                            .scale(1.0f)
                            .outline(false)
                            .build(),
                    // WoIcon at tile (1, 0)
                    HudElement.builder()
                            .id("ionnerrus", "a2")
                            .texture("ionnerrus", "hud/atlas.png")
                            .tile(1, 0)
                            .grid(1, 0)
                            .layer(1)
                            .opacity(1.0f)
                            .scale(1.0f)
                            .outline(false)
                            .build(),
                    // Icon at tile (2, 0)
                    HudElement.builder()
                            .id("ionnerrus", "a3")
                            .texture("ionnerrus", "hud/atlas.png")
                            .tile(2, 0)
                            .grid(2, 0)
                            .layer(1)
                            .opacity(1.0f)
                            .scale(1.0f)
                            .outline(false)
                            .build(),
                    // Icon at tile (3, 0)
                    HudElement.builder()
                            .id("ionnerrus", "a4")
                            .texture("ionnerrus", "hud/atlas.png")
                            .tile(3, 0)
                            .grid(3, 0)
                            .layer(1)
                            .opacity(1.0f)
                            .scale(1.0f)
                            .outline(false)
                            .build(),
                    // Icon at tile (4, 0)
                    HudElement.builder()
                            .id("ionnerrus", "a5")
                            .texture("ionnerrus", "hud/atlas.png")
                            .tile(4, 0)
                            .grid(4, 0)
                            .layer(1)
                            .opacity(1.0f)
                            .scale(1.0f)
                            .outline(false)
                            .build());
            // Per-element error handling (unchanged)
            int registeredCount = 0;
            for (HudElement element : defaultElements) {
                try {
                    hudManager.register(element);
                    registeredCount++;
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning(
                            "Failed to register " + element.id().asString() + ": " + e.getMessage());
                }
            }
            hudManager.finishRegistration();
            plugin.getLogger().info(String.format(
                    "Registered %d default HUD element(s). Total elements: %d",
                    registeredCount,
                    hudManager.getElementCount()));
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "Unexpected error registering default HUD element: " + e.getMessage());
            e.printStackTrace();
        }
    }
}