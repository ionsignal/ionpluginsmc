package com.ionsignal.minecraft.ionnerrus.hud;

import net.momirealms.craftengine.core.util.Key;

import java.util.Objects;

/**
 * Shader configuration metadata for a registered HUD element.
 * 
 * Stores the information needed to reconstruct the shader case statement that CraftEngine
 * compiles into the vertex shader. Mirrors BetterHud's shader generation approach.
 * 
 * Lifecycle:
 * 1. Created during HudManager.register()
 * 2. Will be passed to ShaderManager in Phase 2
 * 3. Stored in HudManager's cache for Component building
 */
public record HudShaderConfig(
        HudElement element,
        int shaderId,
        int codepoint,
        Key font) {
    public HudShaderConfig {
        Objects.requireNonNull(element, "element cannot be null");
        Objects.requireNonNull(font, "font cannot be null");
        if (shaderId <= 0) {
            throw new IllegalArgumentException("shaderId must be positive, got: " + shaderId);
        }
        if (codepoint < 0 || codepoint > 0x10FFFF) {
            throw new IllegalArgumentException("Invalid Unicode codepoint: 0x" + Integer.toHexString(codepoint));
        }
    }
}