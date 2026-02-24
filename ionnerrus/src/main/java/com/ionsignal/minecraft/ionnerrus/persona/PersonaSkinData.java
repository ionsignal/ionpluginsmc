package com.ionsignal.minecraft.ionnerrus.persona;

import com.ionsignal.minecraft.ionnerrus.network.model.SkinType;

import org.jetbrains.annotations.Nullable;

/**
 * Internal domain model representing a fully resolved skin for a Persona.
 */
public record PersonaSkinData(String textureValue, @Nullable String textureSignature, @Nullable SkinType skinType) {
}