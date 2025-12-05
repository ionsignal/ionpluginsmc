package com.ionsignal.minecraft.ionnerrus.network.messages;

public record AgentGoalEvent(
        String personaId,
        String event, // "STARTED", "COMPLETED", "FAILED"
        String goal, // e.g. "CraftItemGoal"
        String description, // e.g. "Successfully crafted 1x Stick"
        long timestamp) {
}