package com.ionsignal.minecraft.ionnerrus.agent.llm.context;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class AgentContext {
    private final NerrusAgent agent;

    public AgentContext(NerrusAgent agent) {
        this.agent = agent;
    }

    public String buildSystemPrompt(String personaDescription, String objective) {
        StringBuilder sb = new StringBuilder();

        // Identity and Persona
        sb.append("You are ").append(agent.getName()).append(", an AI agent in Minecraft.\n");
        sb.append("Your persona: ").append(personaDescription).append("\n\n");

        // Core Context - The get...() methods provide the pre-formatted, concise data.
        sb.append("## Context\n");
        sb.append(getEnvironmentContext());
        sb.append(getInventoryContext());
        sb.append("\n");

        // The Objective
        sb.append("## Objective\n");
        sb.append("Your task is to achieve this objective: '").append(objective).append("'.\n\n");

        // Instructions - Rewritten for conciseness and to include the failure condition.
        sb.append("## Instructions\n");
        sb.append("You will operate in a loop: Analyze -> Decide -> Act.\n");
        sb.append("Limitations: You cannot dig deeper than a few blocks down. You cannot craft items.\n");
        sb.append("1. **Analyze:** Evaluate the objective against your limitations, current context, and available tools.\n");
        sb.append("2. **Decide:**\n");
        sb.append("   - If the objective is achievable, select the single best tool to make progress.\n");
        sb.append("   - If the objective is impossible with your tools, you MUST use the `CANNOT_COMPLETE` tool to explain why.\n");
        sb.append("3. **Act:** After the tool result is returned, repeat the loop.\n");
        sb.append("4. **Report:** Once the objective is fully achieved or you have used `CANNOT_COMPLETE`,");
        sb.append(" respond with a brief message to the user instead of calling another tool.\n\n");

        // Global Rule - A direct command to keep spoken responses short.
        sb.append("IMPORTANT: Keep your responses (your `content` replies) brief and to the point.");

        return sb.toString();
    }

    private String getEnvironmentContext() {
        if (!agent.getPersona().isSpawned()) {
            return "- Status: Despawned\n";
        }
        var location = agent.getPersona().getLocation();
        var world = location.getWorld();
        var time = LocalTime.ofSecondOfDay(world.getTime() / 1000 * 3600).format(DateTimeFormatter.ofPattern("HH:mm"));
        return String.format(
                "- Location: World '%s' at (x:%.1f, y:%.1f, z:%.1f)\n" +
                        "- Biome: %s\n" +
                        "- Time: %s (isDay: %b)\n",
                world.getName(),
                location.getX(), location.getY(), location.getZ(),
                world.getComputedBiome(location.getBlockX(), location.getBlockY(), location.getBlockZ()).getKey().getKey(),
                time,
                world.isDayTime());
    }

    private String getInventoryContext() {
        if (!agent.getPersona().isSpawned()) {
            return "- Inventory: Unknown\n";
        }
        PlayerInventory inventory = agent.getPersona().getInventory();
        List<String> items = new ArrayList<>();
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && item.getAmount() > 0) {
                items.add(item.getAmount() + "x " + item.getType().name());
            }
        }
        if (items.isEmpty()) {
            return "- Inventory: Empty\n";
        } else {
            return "- Inventory: " + String.join(", ", items) + "\n";
        }
    }
}