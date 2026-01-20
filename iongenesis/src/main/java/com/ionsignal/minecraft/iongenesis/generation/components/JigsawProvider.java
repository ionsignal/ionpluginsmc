package com.ionsignal.minecraft.iongenesis.generation.components;

import com.ionsignal.minecraft.iongenesis.model.structure.NBTStructure;

/**
 * Interface for Structures that provide Jigsaw data. Allows the JigsawGenerator to access the
 * underlying StructureData (size, blocks, jigsaw points) from a generic Terra Structure object
 * retrieved from the registry.
 */
public interface JigsawProvider {

    /**
     * Gets the raw NBT structure data required for jigsaw planning.
     * 
     * @return The structure data.
     */
    NBTStructure.StructureData getStructureData();
}