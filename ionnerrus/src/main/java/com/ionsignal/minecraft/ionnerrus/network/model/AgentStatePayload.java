package com.ionsignal.minecraft.ionnerrus.network.model;
import com.ionsignal.minecraft.ionnerrus.network.model.PlayerIdentity;
import com.ionsignal.minecraft.ionnerrus.network.model.AgentStatus;
import com.ionsignal.minecraft.ionnerrus.network.model.Location;
import java.util.UUID;
import com.fasterxml.jackson.annotation.*;
import com.ionsignal.minecraft.ioncore.network.model.IonEvent;
public record AgentStatePayload(@JsonProperty("owner") PlayerIdentity owner, @JsonProperty("definitionId") UUID definitionId, @JsonProperty("sessionId") UUID sessionId, @JsonProperty("type") String type, @JsonProperty("name") String name, @JsonProperty("status") AgentStatus status, @JsonProperty("location") Location location) implements IonEvent {
  
}