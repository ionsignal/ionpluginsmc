package com.ionsignal.minecraft.ionnerrus.agent.cognition.messages.system;

import org.bukkit.entity.Player;

public record AssignDirective(String directive, Player requester) {
}