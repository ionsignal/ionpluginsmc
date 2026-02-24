package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonCommand;
import com.ionsignal.minecraft.ioncore.network.model.IonUser;

import com.fasterxml.jackson.annotation.*;

public record CommandFailedPayload(
        @JsonProperty("owner") IonUser owner,
        @JsonProperty("reason") String reason,
        @JsonProperty("command") String command,
        @JsonProperty("type") String type) implements IonCommand {
}