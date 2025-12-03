package com.ionsignal.minecraft.ioncore.network;

/**
 * Connection states for the IonCore WebSocket client.
 */
public enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}