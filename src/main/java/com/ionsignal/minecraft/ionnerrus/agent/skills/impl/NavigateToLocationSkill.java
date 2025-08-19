package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationParameters;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Navigator;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;

import org.bukkit.Location;

import java.util.concurrent.CompletableFuture;

public class NavigateToLocationSkill implements Skill<Boolean> {
    private final Location target;
    private final Location lookAt;
    private final Path preCalculatedPath;

    public NavigateToLocationSkill(Location target) {
        this(target, null, null);
    }

    public NavigateToLocationSkill(Location target, Location lookAt) {
        this(target, lookAt, null);
    }

    public NavigateToLocationSkill(Path preCalculatedPath, Location lookAt) {
        this(null, lookAt, preCalculatedPath);
    }

    private NavigateToLocationSkill(Location target, Location lookAt, Path preCalculatedPath) {
        this.target = target;
        this.lookAt = lookAt;
        this.preCalculatedPath = preCalculatedPath;
    }

    @Override
    public CompletableFuture<Boolean> execute(NerrusAgent agent) {
        Persona persona = agent.getPersona();
        if (!persona.isSpawned()) {
            return CompletableFuture.completedFuture(false);
        }
        if (lookAt != null && persona.getPersonaEntity() != null) {
            persona.getPersonaEntity().getLookControl().setLookAt(
                    lookAt.getX(), lookAt.getY() + 0.5, lookAt.getZ()); // Look at center of block
        }
        Navigator navigator = persona.getNavigator();
        CompletableFuture<NavigationResult> navFuture;
        if (preCalculatedPath != null) {
            navFuture = navigator.navigateTo(preCalculatedPath);
        } else if (target != null) {
            navFuture = navigator.navigateTo(target, NavigationParameters.DEFAULT);
        } else {
            // No valid target or path provided
            return CompletableFuture.completedFuture(false);
        }

        return navFuture.thenApply(result -> {
            // IMPORTANT: We do NOT clear the look target here.
            // The calling Task is responsible for managing the end-state of the agent's gaze.
            return result == NavigationResult.SUCCESS;
        });
    }
}