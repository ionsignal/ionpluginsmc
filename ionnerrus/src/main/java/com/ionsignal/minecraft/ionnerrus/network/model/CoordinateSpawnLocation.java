package com.ionsignal.minecraft.ionnerrus.network.model;

import com.fasterxml.jackson.annotation.*;

public record CoordinateSpawnLocation(
        @JsonProperty("type") String type,
        @JsonProperty("world") String world,
        @JsonProperty("x") double x,
        @JsonProperty("y") double y,
        @JsonProperty("z") double z,
        @JsonProperty("yaw") double yaw,
        @JsonProperty("pitch") double pitch)
        implements SpawnLocation {
}