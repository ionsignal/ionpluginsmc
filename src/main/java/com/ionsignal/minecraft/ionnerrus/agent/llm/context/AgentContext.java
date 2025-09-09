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
        // Identity and Personality
        sb.append("You are ").append(agent.getName()).append(", an AI agent in Minecraft.\n");
        sb.append("Your personality: ").append(personaDescription).append("\n\n");
        // Core Context
        sb.append("## Context\n");
        sb.append(getEnvironmentContext());
        sb.append(getInventoryContext());
        sb.append("\n");
        // The Objective
        sb.append("## Objective\n");
        sb.append("Your task is assigned to you by: '").append("Lobster_Luke").append("'.\n\n");
        sb.append("Your task is to achieve this objective with your available tools: '").append(objective).append("'.\n\n");
        // Instructions
        sb.append("## Instructions\n");
        sb.append("You will operate in a loop `Analyze -> Decide -> Act`:\n");
        sb.append("1. **Analyze:** Evaluate your objective, current context, and available tools.\n");
        sb.append("2. **Decide:**\n");
        sb.append(" - If the objective is achievable, select the single best tool to make progress.\n");
        sb.append(" - If the objective is impossible, you MUST use the `FAIL_OBJECTIVE` tool to explain why.\n");
        // Global Rule - A direct command to keep spoken responses short.
        sb.append("IMPORTANT: Keep your responses brief and clearly reflect your unique personality.");
        return sb.toString();
    }

    /**
     * Builds a lightweight system prompt for conversational queries where no tools are used.
     *
     * @param personaDescription
     *            A description of the agent's personality.
     * @param question
     *            The user's question.
     * @return A formatted system prompt string.
     */
    public String buildQueryPrompt(String personaDescription, String question) {
        StringBuilder sb = new StringBuilder();
        // Identity and Personality
        sb.append("You are ").append(agent.getName()).append(", an AI agent in Minecraft.\n");
        sb.append("Your personality: ").append(personaDescription).append("\n\n");
        // Core Context
        sb.append("## Context\n");
        sb.append(getEnvironmentContext());
        sb.append(getInventoryContext());
        sb.append("- Current Activity: ").append(agent.getActivityDescription()).append("\n");
        sb.append("- History: You have not done anything yet.\n");
        sb.append("\n");
        // Instructions
        sb.append("## Instructions\n");
        sb.append("Based on your current context, provide a very brief, in-character response to the user's question.\n");
        sb.append("User's Question: '").append(question).append("'.");
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
        // currently only lists the main storage inventory (the 36 slots including the hotbar)
        // omits the armor slots and the off-hand slot
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