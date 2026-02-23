package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonUser;
import com.ionsignal.minecraft.ioncore.network.model.IonEvent;

import com.fasterxml.jackson.annotation.*;

public record RequestPersonaListPayload(
        @JsonProperty("owner") IonUser owner,
        @JsonProperty("type") String type) implements IonEvent {
}