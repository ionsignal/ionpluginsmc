package com.ionsignal.minecraft.ionnerrus.network.model;
import com.ionsignal.minecraft.ionnerrus.network.model.SpawnLocation;
import com.ionsignal.minecraft.ioncore.network.model.MinecraftIdentity;
import com.fasterxml.jackson.annotation.*;
public record PlayerSpawnLocation(@JsonProperty("type") String type, @JsonProperty("target") MinecraftIdentity target) implements SpawnLocation {
  
}