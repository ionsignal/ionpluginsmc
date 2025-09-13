package com.ionsignal.minecraft.ionnerrus.agent.messages;

import org.bukkit.entity.Player;

public record AssignDirective(String directive, Player requester) {
}