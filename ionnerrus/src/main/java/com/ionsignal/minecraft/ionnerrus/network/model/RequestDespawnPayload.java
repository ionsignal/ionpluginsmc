package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonEvent;
import com.ionsignal.minecraft.ioncore.network.model.IonUser;

import java.util.UUID;

import com.fasterxml.jackson.annotation.*;

public record RequestDespawnPayload(@JsonProperty("owner") IonUser owner, @JsonProperty("definitionId") UUID definitionId,
        @JsonProperty("sessionId") UUID sessionId, @JsonProperty("type") String type) implements IonEvent {

}