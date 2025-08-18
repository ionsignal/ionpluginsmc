package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationParameters;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Navigator;

import org.bukkit.Location;

import java.util.concurrent.CompletableFuture;

public class NavigateToLocationSkill implements Skill<Boolean> {
    private final Location target;
    private final Location lookAt;

    public NavigateToLocationSkill(Location target) {
        this(target, null);
    }

    public NavigateToLocationSkill(Location target, Location lookAt) {
        this.target = target;
        this.lookAt = lookAt;
    }

    @Override
    public CompletableFuture<Boolean> execute(NerrusAgent agent) {
        Persona persona = agent.getPersona();
        if (!persona.isSpawned()) {
            return CompletableFuture.completedFuture(false);
        }
        Navigator navigator = persona.getNavigator();
        return navigator.navigateTo(target, lookAt, NavigationParameters.DEFAULT)
                .thenApply(result -> result == NavigationResult.SUCCESS);
    }
}