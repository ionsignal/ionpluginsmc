package com.ionsignal.minecraft.ioncore.debug;

public interface VisualizationRegistry {
    <T> void register(Class<T> type, VisualizationProvider<T> provider);

    <T> void unregister(Class<T> type);
}