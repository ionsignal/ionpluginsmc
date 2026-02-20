package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonUser;
import com.ionsignal.minecraft.ionnerrus.network.model.Location;
import java.util.UUID;
import com.fasterxml.jackson.annotation.*;
import com.ionsignal.minecraft.ioncore.network.model.IonCommand;
public record TeleportPayload(@JsonProperty("owner") IonUser owner, @JsonProperty("definitionId") UUID definitionId, @JsonProperty("sessionId") UUID sessionId, @JsonProperty("type") String type, @JsonProperty("location") Location location) implements IonCommand {
  
}