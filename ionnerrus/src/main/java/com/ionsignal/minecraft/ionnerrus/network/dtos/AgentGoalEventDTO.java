package com.ionsignal.minecraft.ionnerrus.network.dtos;

public record AgentGoalEventDTO(
    String personaId,
    String event,        // "STARTED", "COMPLETED", "FAILED"
    String goal,         // e.g. "CraftItemGoal"
    String description,  // e.g. "Successfully crafted 1x Stick"
    long timestamp
) {}