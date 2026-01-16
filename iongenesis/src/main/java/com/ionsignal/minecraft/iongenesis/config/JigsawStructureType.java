package com.ionsignal.minecraft.iongenesis.config;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.config.ConfigFactory;
import com.dfsek.terra.api.config.ConfigPack;
import com.dfsek.terra.api.config.ConfigType;
import com.dfsek.terra.api.structure.Structure;
import com.dfsek.terra.api.util.reflection.TypeKey;
import com.ionsignal.minecraft.iongenesis.generation.JigsawStructureFactory;

public class JigsawStructureType implements ConfigType<JigsawStructureTemplate, Structure> {
    private static final TypeKey<Structure> JIGSAW_STRUCTURE_TYPE_KEY = new TypeKey<>() {
    };

    private final ConfigPack pack;

    public JigsawStructureType(ConfigPack pack) {
        this.pack = pack;
    }

    @Override
    public JigsawStructureTemplate getTemplate(ConfigPack pack, Platform platform) {
        return new JigsawStructureTemplate();
    }

    @Override
    public ConfigFactory<JigsawStructureTemplate, Structure> getFactory() {
        return new JigsawStructureFactory(pack);
    }

    @Override
    public TypeKey<Structure> getTypeKey() {
        return JIGSAW_STRUCTURE_TYPE_KEY;
    }
}