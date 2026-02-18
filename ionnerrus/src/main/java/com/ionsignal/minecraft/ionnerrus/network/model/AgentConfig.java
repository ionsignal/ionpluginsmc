package com.ionsignal.minecraft.ionnerrus.network.model;
import com.ionsignal.minecraft.ionnerrus.network.model.Skin;
import java.util.UUID;
import com.fasterxml.jackson.annotation.*;
public record AgentConfig(@JsonProperty("id") UUID id, @JsonProperty("name") String name, @JsonProperty("skin") Skin skin) {
  
}