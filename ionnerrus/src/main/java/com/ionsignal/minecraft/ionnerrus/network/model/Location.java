package com.ionsignal.minecraft.ionnerrus.network.model;

import com.fasterxml.jackson.annotation.*;
public record Location(@JsonProperty("world") String world, @JsonProperty("x") double x, @JsonProperty("y") double y, @JsonProperty("z") double z, @JsonProperty("yaw") double yaw, @JsonProperty("pitch") double pitch) {
  
}