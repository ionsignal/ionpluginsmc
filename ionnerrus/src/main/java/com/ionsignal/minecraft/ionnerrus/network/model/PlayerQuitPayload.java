package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ionnerrus.network.model.PlayerIdentity;
import com.fasterxml.jackson.annotation.*;
import com.ionsignal.minecraft.ioncore.network.model.IonEvent;
public record PlayerQuitPayload(@JsonProperty("type") String type, @JsonProperty("player") PlayerIdentity player) implements IonEvent {
  
}