package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationParameters;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.NavigationResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Navigator;

// import org.bukkit.Effect;
import org.bukkit.Location;

import java.util.concurrent.CompletableFuture;

public class NavigateToLocationSkill implements Skill<Boolean> {
    private static final double ARRIVAL_DISTANCE = 0.25;
    private static final double ARRIVAL_DISTANCE_SQUARED = ARRIVAL_DISTANCE * ARRIVAL_DISTANCE;
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
        // Testing effect, this can probably be removed
        // if (target.getWorld() != null) {
        //     target.getWorld().playEffect(target, Effect.ENDER_SIGNAL, 0);
        // }
        Navigator navigator = persona.getNavigator();
        if (persona.getLocation().distanceSquared(target) < ARRIVAL_DISTANCE_SQUARED) {
            if (navigator.isNavigating()) {
                navigator.cancelNavigation(NavigationResult.SUCCESS);
            }
            return CompletableFuture.completedFuture(true);
        }
        // Call the new navigator method and simply return its result.
        return navigator.navigateTo(target, lookAt, NavigationParameters.DEFAULT)
                .thenApply(result -> result == NavigationResult.SUCCESS);
    }
}