package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonCommand;
import com.ionsignal.minecraft.ioncore.network.model.IonUser;

import java.util.UUID;

import com.fasterxml.jackson.annotation.*;

public record SkinUpdatePayload(
        @JsonProperty("owner") IonUser owner,
        @JsonProperty("definitionId") UUID definitionId,
        @JsonProperty("type") String type,
        @JsonProperty("skin") Skin skin)
        implements IonCommand {
}