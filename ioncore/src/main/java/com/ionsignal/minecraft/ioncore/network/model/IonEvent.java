package com.ionsignal.minecraft.ioncore.network.model;

/**
 * Marker interface for all event payloads sent from Java to Web.
 */
public interface IonEvent {
    String type();
}