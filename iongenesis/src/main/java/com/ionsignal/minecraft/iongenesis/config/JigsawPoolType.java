package com.ionsignal.minecraft.iongenesis.config;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.config.ConfigFactory;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.config.ConfigType;
import com.dfsek.terra.api.util.reflection.TypeKey;
import com.ionsignal.minecraft.iongenesis.generation.components.JigsawPool;

/**
 * ConfigType for registering jigsaw pools with Terra.
 */
public class JigsawPoolType implements ConfigType<JigsawPoolTemplate, JigsawPool> {
    private static final TypeKey<JigsawPool> POOL_TYPE_KEY = new TypeKey<>() {
    };

    @SuppressWarnings("unused")
    private final Platform platform;

    public JigsawPoolType(Platform platform) {
        this.platform = platform;
    }

    @Override
    public JigsawPoolTemplate getTemplate(ConfigPack pack, Platform platform) {
        return new JigsawPoolTemplate();
    }

    @Override
    public ConfigFactory<JigsawPoolTemplate, JigsawPool> getFactory() {
        return (config, platform) -> new JigsawPool(config, platform);
    }

    @Override
    public TypeKey<JigsawPool> getTypeKey() {
        return POOL_TYPE_KEY;
    }
}