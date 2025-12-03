package com.ionsignal.minecraft.ionnerrus.api.events;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class NerrusAgentSpawnEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final NerrusAgent agent;

    public NerrusAgentSpawnEvent(NerrusAgent agent) {
        this.agent = agent;
    }

    public NerrusAgent getAgent() {
        return agent;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}