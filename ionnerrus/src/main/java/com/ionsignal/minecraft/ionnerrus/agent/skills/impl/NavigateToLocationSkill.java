package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.NavigateToLocationResult;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationParameters;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Navigator;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.results.NavigationResult;

import org.bukkit.Location;

import java.util.concurrent.CompletableFuture;

public class NavigateToLocationSkill implements Skill<NavigateToLocationResult> {
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
    public CompletableFuture<NavigateToLocationResult> execute(NerrusAgent agent) {
        Persona persona = agent.getPersona();
        if (!persona.isSpawned()) {
            return CompletableFuture.completedFuture(NavigateToLocationResult.CANCELLED);
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
            return CompletableFuture.completedFuture(NavigateToLocationResult.UNREACHABLE);
        }
        return navFuture.thenApply(result -> {
            // IMPORTANT: We do NOT clear the look target here.
            // The calling Task is responsible for managing the end-state of the agent's gaze.
            return switch (result) {
                case SUCCESS -> NavigateToLocationResult.SUCCESS;
                case UNREACHABLE -> NavigateToLocationResult.UNREACHABLE;
                case STUCK, FAILURE -> NavigateToLocationResult.STUCK;
                case CANCELLED -> NavigateToLocationResult.CANCELLED;
            };
        });
    }
}