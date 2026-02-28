package com.ionsignal.minecraft.ioncore.debug;

import com.ionsignal.minecraft.ioncore.debug.controllers.TickBasedController;

import org.bukkit.plugin.Plugin;

public class ExecutionControllerFactory {

    public static TickBasedController createTickBased(Plugin plugin) {
        return new TickBasedController() {
            @Override
            public void resume() {
                // No-op
            }

            @Override
            public boolean isPaused() {
                return false; // Never pause
            }

            @Override
            public boolean isContinuingToEnd() {
                return true; // Always fast-forward
            }

            @Override
            public void pause(String reason, String detail) {
                // No-op
            }
        };
    }
}