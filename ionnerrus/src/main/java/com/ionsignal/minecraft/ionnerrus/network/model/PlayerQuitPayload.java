package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.MinecraftIdentity;
import com.ionsignal.minecraft.ioncore.network.model.IonEvent;

import com.fasterxml.jackson.annotation.*;

public record PlayerQuitPayload(
        @JsonProperty("type") String type,
        @JsonProperty("player") MinecraftIdentity player) implements IonEvent {

}