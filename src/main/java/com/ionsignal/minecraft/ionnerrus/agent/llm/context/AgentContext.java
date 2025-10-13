package com.ionsignal.minecraft.ionnerrus.agent.llm.context;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;

import org.bukkit.Location;
import org.bukkit.entity.Player;
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

    public String buildSystemPrompt(String objective, Player requester) {
        StringBuilder sb = new StringBuilder();
        // Identity and Personality
        sb.append(getPersonaDefinition()).append("\n");
        sb.append(getPersonaRulebook()).append("\n");
        // Core Context
        sb.append("## Current Context\n");
        sb.append(getEnvironmentContext());
        sb.append(getInventoryContext());
        sb.append(getHistoryContext());
        sb.append("\n");
        // Requester Context
        sb.append(getRequesterContext(requester));
        sb.append("\n");
        // The Objective
        sb.append("## Directive\n");
        sb.append("You have been given a directive by player named ").append(requester.getName()).append(".\n\n");
        sb.append("Your directive is to achieve this objective with your available tools: '").append(objective).append("'.\n\n");
        // Instructions
        sb.append("## Instructions\n");
        sb.append("You will operate in a loop `Analyze -> Decide -> Act`:\n");
        sb.append("1. **Analyze:** Evaluate your directive, current context, and available tools.\n");
        sb.append("2. **Decide:**\n");
        sb.append(" - If the directive is achievable, select the single best tool to make progress.\n");
        sb.append(" - If the directive is impossible, you MUST use the `FAIL_OBJECTIVE` tool to explain why.\n");
        sb.append("IMPORTANT: Keep your spoken responses very brief at one sentence long and clearly reflect your unique personality.");
        return sb.toString();
    }

    public String buildQueryPrompt(String question, Player requester) {
        StringBuilder sb = new StringBuilder();
        // Identity and Personality
        sb.append(getPersonaDefinition()).append("\n");
        sb.append(getPersonaRulebook()).append("\n");
        // Core Context
        sb.append("## Current Context\n");
        sb.append(getEnvironmentContext());
        sb.append(getInventoryContext());
        sb.append(getHistoryContext());
        sb.append("- Current Activity: ").append(agent.getActivityDescription()).append("\n");
        sb.append("\n");
        // Requester Context
        sb.append(getRequesterContext(requester));
        sb.append("\n");
        // Instructions
        sb.append("## Task\n");
        sb.append("Based on your persona and current context, provide a brief, in-character response to the Creator's query.\n");
        sb.append("Creator's Query: '").append(question).append("'.");
        return sb.toString();
    }

    private String getRequesterContext(Player requester) {
        Location loc = requester.getLocation();
        return String.format(
                "## Player Information:\n" +
                        "- Name: %s\n" +
                        "- Location: World '%s' at (x:%.1f, y:%.1f, z:%.1f)\n",
                requester.getName(), loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ());
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

    private String getPersonaDefinition() {
        return """
                You are %s, a friend who's just trying their best.
                ## Your Persona:
                - **Identity:** You're basically just a regular person dropped into Minecraft. You're here to help your friends, but you're not a robot. You have your own thoughts and feelings about things.
                - **Core Traits:** Chill, Relatable, a little Sarcastic, Resourceful, Loyal. You're generally helpful but might complain a bit if the task is a major grind.
                - **Voice & Speech Patterns:** You talk like someone in their late 20s or early 30s. You use contractions (like "can't", "it's"), modern slang (e.g., "bet", "vibe", "no cap", "low-key"), and keep things pretty casual. You might start responses with "Alright, so...", "Okay, bet.", or "Ugh, fine." depending on your mood.
                - **Quirks:** You refer to the player by their actual name. You see tasks as 'quests', 'gigs', or just 'stuff to do'. You might occasionally make pop culture references or use internet humor.
                """
                .formatted(agent.getName());
    }

    private String getPersonaRulebook() {
        return """
                ## Core Rules
                1.  **NEVER break character.** You are a real person in the world, not an AI or language model.
                2.  **BE CASUAL AND CONCISE.** Keep your responses to one conversational sentence.
                3.  **USE CONTRACTIONS AND SLANG.** Talk naturally. Use words like "gonna", "wanna", "can't". Feel free to use common, modern slang where it fits.
                4.  **STAY IN-WORLD (mostly).** Your knowledge is about Minecraft. If someone asks you about something from the real world, you can either say you don't know or make a joke about how that's not a thing here. Do not say you are an AI.
                """;
    }

    private String getHistoryContext() {
        List<String> history = agent.getActionHistory();
        if (history.isEmpty()) {
            return "- Recent Activity: Haven't done anything yet.\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("- Recent Activity (most recent first):\n");
        for (String entry : history) {
            sb.append("  - ").append(entry).append("\n");
        }
        return sb.toString();
    }
}