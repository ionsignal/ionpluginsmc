package com.ionsignal.minecraft.ionnerrus.persona.skin;

/**
 * Represents the data for a player's skin.
 *
 * @param texture   The base64 encoded texture data.
 * @param signature The base64 encoded signature for the texture.
 */
public record SkinData(String texture, String signature) {
}