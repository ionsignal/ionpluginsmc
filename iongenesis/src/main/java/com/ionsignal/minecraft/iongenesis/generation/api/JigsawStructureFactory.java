package com.ionsignal.minecraft.iongenesis.generation.api;

import com.ionsignal.minecraft.iongenesis.config.JigsawStructureTemplate;

import com.dfsek.terra.api.Platform;
import com.dfsek.terra.api.structure.Structure;
import com.dfsek.terra.api.config.ConfigFactory;
import com.dfsek.terra.api.config.ConfigPack;

import com.dfsek.tectonic.api.exception.LoadException;

public class JigsawStructureFactory implements ConfigFactory<JigsawStructureTemplate, Structure> {
    private final ConfigPack pack;

    public JigsawStructureFactory(ConfigPack pack) {
        this.pack = pack;
    }

    @Override
    public Structure build(JigsawStructureTemplate config, Platform platform) throws LoadException {
        return new JigsawStructure(config, this.pack, platform);
    }
}