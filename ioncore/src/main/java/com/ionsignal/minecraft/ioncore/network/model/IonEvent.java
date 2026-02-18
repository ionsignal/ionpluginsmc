package com.ionsignal.minecraft.ioncore.network.model;

import com.ionsignal.minecraft.ioncore.network.IonEventTypeResolver;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver;

/**
 * Marker interface for all event payloads sent from Java to Web.
 * <p>
 * This interface uses a custom {@link JsonTypeIdResolver} to handle polymorphic serialization
 * and deserialization across the architectural boundary.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CUSTOM, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonTypeIdResolver(IonEventTypeResolver.class)
public interface IonEvent {
    /**
     * Returns the event type identifier (e.g., "event:persona:state").
     * Matches the generated Record accessor for the 'type' field.
     */
    String type();
}