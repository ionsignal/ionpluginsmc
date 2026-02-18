package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ionnerrus.network.model.PlayerIdentity;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnLocation;
import java.util.UUID;
import com.fasterxml.jackson.annotation.*;
import com.ionsignal.minecraft.ioncore.network.model.IonEvent;
public record RequestSpawnPayload(@JsonProperty("owner") PlayerIdentity owner, @JsonProperty("definitionId") UUID definitionId, @JsonProperty("type") String type, @JsonProperty("location") SpawnLocation location) implements IonEvent {
  
}