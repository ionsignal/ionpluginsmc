package com.ionsignal.minecraft.ionnerrus.network.model;
import com.ionsignal.minecraft.ionnerrus.network.model.PlayerSpawnLocation;
import com.ionsignal.minecraft.ionnerrus.network.model.CoordinateSpawnLocation;
import com.fasterxml.jackson.annotation.*;
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = PlayerSpawnLocation.class, name = "PLAYER"),
    @JsonSubTypes.Type(value = CoordinateSpawnLocation.class, name = "COORDINATES")
})
/**
 * SpawnLocation represents a union of types: PlayerSpawnLocation, CoordinateSpawnLocation
 */
public interface SpawnLocation {
  
}