package com.ionsignal.minecraft.ionnerrus.network.model;
import com.ionsignal.minecraft.ionnerrus.network.model.SkinType;
import com.fasterxml.jackson.annotation.*;
public record Skin(@JsonProperty("type") SkinType type, @JsonProperty("value") String value, @JsonProperty("signature") String signature) {
  
}