package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonCommand;
import com.ionsignal.minecraft.ioncore.network.model.IonUser;

import java.util.UUID;

import com.fasterxml.jackson.annotation.*;

public record TeleportPayload(
        @JsonProperty("owner") IonUser owner,
        @JsonProperty("definitionId") UUID definitionId,
        @JsonProperty("type") String type,
        @JsonProperty("location") Location location)
        implements IonCommand {

}