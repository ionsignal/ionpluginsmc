package com.ionsignal.minecraft.ionnerrus.persona.network;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

public class EmptyPacketListener extends ServerGamePacketListenerImpl {
    public EmptyPacketListener(MinecraftServer minecraftServer, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        super(minecraftServer, connection, player, cookie);
    }

    @Override
    public void send(@SuppressWarnings("null") Packet<?> packet) {
    }
}