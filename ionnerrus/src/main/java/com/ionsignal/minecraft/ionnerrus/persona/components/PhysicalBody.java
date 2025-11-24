package com.ionsignal.minecraft.ionnerrus.persona.components;

import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;

/**
 * Represents the physical presence of a Persona in the world.
 * This is the ONLY interface that Skills and Goals should interact with.
 */
public interface PhysicalBody {
    MovementCapability movement();

    OrientationCapability orientation();

    ActionCapability actions();

    StateCapability state();

    void onInventoryOpen(HumanEntity player);

    void onInventoryClose();

    Entity getBukkitEntity();

    void tick();
}