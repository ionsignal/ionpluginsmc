package com.ionsignal.minecraft.ionnerrus.hud;

import net.momirealms.craftengine.core.util.Key;

import java.util.Objects;

/**
 * Immutable configuration for a HUD element (status icon, health bar, etc.).
 * 
 * This record stores the visual properties needed to render a persistent UI element.
 * The actual rendering is handled by CraftEngine's shader system via encoded font characters.
 * 
 * Thread Safety: Immutable after construction (record semantics).
 */
public record HudElement(
        Key id,
        String atlasFile, // direct texture path
        int tileX, // 0-15 (tile column)
        int tileY, // 0-15 (tile row)
        int gridX, // 0-31
        int gridY, // 0-31
        int layer,
        float opacity,
        float scale,
        boolean outline) {

    public HudElement {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(atlasFile, "imageId cannot be null");
        if (!atlasFile.endsWith(".png")) {
            throw new IllegalArgumentException("Texture file must be a PNG: " + atlasFile);
        }
        if (tileX < 0 || tileX > 15) {
            throw new IllegalArgumentException("tileX must be in [0, 15], got: " + tileX);
        }
        if (tileY < 0 || tileY > 15) {
            throw new IllegalArgumentException("tileY must be in [0, 15], got: " + tileY);
        }
        if (opacity < 0.0f || opacity > 1.0f) {
            throw new IllegalArgumentException("Opacity must be in [0.0, 1.0], got: " + opacity);
        }
        if (layer < 0 || layer > 1000) {
            throw new IllegalArgumentException("Layer must be in [0, 1000], got: " + layer);
        }
        if (scale <= 0.0f) {
            throw new IllegalArgumentException("Scale must be positive, got: " + scale);
        }
    }

    public static class Builder {
        private Key id;
        private String atlasFile;
        private int tileX = 0;
        private int tileY = 0;
        private int gridX = 0;
        private int gridY = 0;
        private int layer = 0;
        private float opacity = 1.0f;
        private float scale = 1.0f;
        private boolean outline = false;

        public Builder id(String namespace, String value) {
            this.id = Key.of(namespace, value);
            return this;
        }

        public Builder texture(String namespace, String path) {
            this.atlasFile = namespace + ":" + path;
            return this;
        }

        public Builder tile(int x, int y) {
            this.tileX = x;
            this.tileY = y;
            return this;
        }

        public Builder grid(int x, int y) {
            this.gridX = x;
            this.gridY = y;
            return this;
        }

        public Builder layer(int layer) {
            this.layer = layer;
            return this;
        }

        public Builder opacity(float opacity) {
            this.opacity = opacity;
            return this;
        }

        public Builder scale(float scale) {
            this.scale = scale;
            return this;
        }

        public Builder outline(boolean outline) {
            this.outline = outline;
            return this;
        }

        public HudElement build() {
            if (id == null || atlasFile == null) {
                throw new IllegalStateException("id and textureFile are required");
            }
            return new HudElement(id, atlasFile, tileX, tileY, gridX, gridY, layer, opacity, scale, outline);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}