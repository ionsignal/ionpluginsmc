package com.ionsignal.minecraft.ionnerrus.network.model;

import com.ionsignal.minecraft.ioncore.network.model.IonCommand;
import com.ionsignal.minecraft.ioncore.network.model.IonUser;

import java.util.List;

import com.fasterxml.jackson.annotation.*;

public record PersonaListResponsePayload(
        @JsonProperty("owner") IonUser owner,
        @JsonProperty("type") String type,
        @JsonProperty("personas") List<PersonaListItem> personas) implements IonCommand {
}