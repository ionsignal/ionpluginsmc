package com.ionsignal.minecraft.ionnerrus.persona.platform.v1_21_R7.util;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.mojang.datafixers.DataFixer;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.world.entity.player.Player;

public class EmptyServerStatsCounter extends ServerStatsCounter {

    public EmptyServerStatsCounter() {
        super(MinecraftServer.getServer(), IonNerrus.getPlugin(IonNerrus.class).getDataFolder());
    }

    @Override
    public void markAllDirty() {
    }

    @Override
    public void parseLocal(@SuppressWarnings("null") DataFixer datafixer, @SuppressWarnings("null") String s) {
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
    protected String toJson() {
        return "{\"stats\":{},\"DataVersion\":"
                + SharedConstants.getCurrentVersion().dataVersion().version() + "}";
    }
}