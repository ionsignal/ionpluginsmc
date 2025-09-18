package com.ionsignal.minecraft.ionnerrus.util;

import org.bukkit.Bukkit;

public final class ServerVersion {

    private ServerVersion() {
    }

    private static final String MINECRAFT_VERSION = Bukkit.getMinecraftVersion();

    /**
     * Gets the Minecraft server version string.
     *
     * @return The Minecraft version string.
     */
    public static String getMinecraftVersion() {
        return MINECRAFT_VERSION;
    }
}