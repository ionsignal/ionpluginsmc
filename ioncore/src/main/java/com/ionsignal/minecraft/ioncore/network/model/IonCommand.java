package com.ionsignal.minecraft.ioncore.network.model;

import com.ionsignal.minecraft.ioncore.network.IonCommandTypeResolver;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver;

/**
 * Marker interface for all command payloads in the Ion ecosystem.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CUSTOM, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonTypeIdResolver(IonCommandTypeResolver.class)
public interface IonCommand {
    String type();
}