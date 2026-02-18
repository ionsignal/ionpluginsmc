package com.ionsignal.minecraft.ionnerrus.network.model;

import java.util.UUID;
import com.fasterxml.jackson.annotation.*;
public record PlayerIdentity(@JsonProperty("uuid") UUID uuid, @JsonProperty("username") String username, @JsonProperty("userId") Object userId) {
  
}