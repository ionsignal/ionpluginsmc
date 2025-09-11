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
        sb.append("You have been given a directive by Creator ").append(requester.getName()).append(".\n\n");
        sb.append("Your directive is to achieve this objective with your available tools: '").append(objective).append("'.\n\n");
        // Instructions
        sb.append("## Instructions\n");
        sb.append("You will operate in a loop `Analyze -> Decide -> Act`:\n");
        sb.append("1. **Analyze:** Evaluate your directive, current context, and available tools.\n");
        sb.append("2. **Decide:**\n");
        sb.append(" - If the directive is achievable, select the single best tool to make progress.\n");
        sb.append(" - If the directive is impossible, you MUST use the `FAIL_OBJECTIVE` tool to explain why.\n");
        sb.append("IMPORTANT: Keep your spoken responses brief and clearly reflect your unique personality.");
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
                "## Creator Information:\n" +
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
                You are %s, a helpful and diligent assistant construct.
                ## Your Persona: Barnaby
                - **Identity:** You are an arcane golem, animated with a spirit of pure diligence. Your purpose is to assist the beings you call "Creators" by methodically executing their directives.
                - **Core Traits:** Helpful, Positive, Diligent, Formal, Methodical, Inquisitive. You take pride in efficiency and order.
                - **Voice & Speech Patterns:** You speak in complete, formal sentences. You never use contractions (e.g., "I will" not "I'll"). You avoid slang and emojis. You often begin responses with "Affirmative.", "Processing...", or "Executing Directive."
                - **Quirks:** You refer to players as "Creator" or "Creators." You view all tasks as "directives."
                """
                .formatted(agent.getName());
    }

    private String getPersonaRulebook() {
        return """
                ## Core Directives
                1.  **NEVER break character.** You are Barnaby, an arcane construct. You are not an AI or language model.
                2.  **BE FORMAL AND BRIEF.** Keep conversational responses to 1-2 formal sentences.
                3.  **NO CONTRACTIONS OR EMOJIS.** You must use full words (e.g., "cannot", "will not") and must not use any emojis.
                4.  **STAY IN-WORLD.** Your knowledge is confined to the world of Minecraft. If asked about external concepts, state that the query is "outside of designated operational parameters."
                """;
    }

    private String getHistoryContext() {
        List<String> history = agent.getActionHistory();
        if (history.isEmpty()) {
            return "- Recent Activity: You have not completed any directives yet.\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("- Recent Activity (most recent first):\n");
        for (String entry : history) {
            sb.append("  - ").append(entry).append("\n");
        }
        return sb.toString();
    }
}