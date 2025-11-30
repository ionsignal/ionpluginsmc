package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.inventory.CraftingUtil;

import org.bukkit.Location;
import org.bukkit.inventory.CraftingRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * A unified skill to perform any craft, either in-inventory (2x2) or at a crafting table (3x3).
 * It acts as a wrapper around the NMS-level CraftingUtil, ensuring the operation is executed
 * on the main server thread.
 */
public class ExecuteCraftSkill implements Skill<Boolean> {

    private final CraftingRecipe recipe;
    private final @Nullable Location craftingTableLocation;

    public ExecuteCraftSkill(CraftingRecipe recipe, @Nullable Location craftingTableLocation) {
        this.recipe = recipe;
        this.craftingTableLocation = craftingTableLocation;
    }

    @Override
    public CompletableFuture<Boolean> execute(NerrusAgent agent, ExecutionToken token) {
        PersonaEntity personaEntity = agent.getPersona().getPersonaEntity();
        if (personaEntity == null) {
            return CompletableFuture.completedFuture(false);
        }

        // Delegate the entire crafting logic to the NMS utility on the main server thread.
        return CompletableFuture.supplyAsync(
                () -> CraftingUtil.performCraft(personaEntity, recipe, craftingTableLocation),
                IonNerrus.getInstance().getMainThreadExecutor());
    }
}