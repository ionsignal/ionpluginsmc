package com.ionsignal.minecraft.ionnerrus.persona.util;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;

import com.mojang.datafixers.DataFixer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.world.entity.player.Player;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class EmptyServerStatsCounter extends ServerStatsCounter {

    public EmptyServerStatsCounter() {
        super(MinecraftServer.getServer(), IonNerrus.getPlugin(IonNerrus.class).getDataFolder().toPath());
    }

    @Override
    public void markAllDirty() {
    }

    @Override
    public void parse(@SuppressWarnings("null") DataFixer datafixer, @SuppressWarnings("null") JsonElement element) {
    }

    @Override
    public void save() {
    }

    @Override
    public void sendStats(@SuppressWarnings("null") ServerPlayer entityplayer) {
    }

    @Override
    public void setValue(@SuppressWarnings("null") Player entityhuman, @SuppressWarnings("null") Stat<?> statistic, int i) {
    }

    @Override
    protected JsonElement toJson() {
        return new JsonObject();
    }
}