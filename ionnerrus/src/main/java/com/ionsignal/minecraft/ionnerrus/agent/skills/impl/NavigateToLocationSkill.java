package com.ionsignal.minecraft.ionnerrus.agent.skills.impl;

import com.ionsignal.minecraft.ioncore.IonCore;
import com.ionsignal.minecraft.ioncore.debug.DebugSession;
import com.ionsignal.minecraft.ionnerrus.agent.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.debug.AgentDebugState;
import com.ionsignal.minecraft.ionnerrus.agent.skills.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.skills.results.NavigateToLocationResult;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.components.PhysicalBody;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.MovementResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;

import org.bukkit.Location;

import java.util.Optional;
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
        // [DEBUG START] Inspect intent before physical execution
        Optional<DebugSession<AgentDebugState>> debugSession = IonCore.getDebugRegistry()
                .getActiveSession(agent.getPersona().getUniqueId(), AgentDebugState.class);
        if (debugSession.isPresent()) {
            var session = debugSession.get();
            session.setState(AgentDebugState.snapshot(agent));
            String targetDesc = (target != null) ? target.toVector().toString()
                    : (preCalculatedPath != null) ? "Path[" + preCalculatedPath.size() + "]" : "Unknown";
            session.getController().ifPresent(c -> c.pause("Skill: NavigateToLocation", "Target: " + targetDesc));
        }
        // [DEBUG END]
        PhysicalBody body = persona.getPhysicalBody();
        if (lookAt != null) {
            body.orientation().face(lookAt, false);
        } else {
            // If no specific look target is requested, clear any previous tracking
            // so the agent looks naturally towards the path/movement direction.
            body.orientation().clearLookTarget();
        }
        CompletableFuture<MovementResult> navFuture;
        if (preCalculatedPath != null) {
            navFuture = body.movement().moveTo(preCalculatedPath);
        } else if (target != null) {
            navFuture = body.movement().moveTo(target);
        } else {
            return CompletableFuture.completedFuture(NavigateToLocationResult.UNREACHABLE);
        }
        return navFuture.thenApply(result -> {
            return switch (result) {
                case SUCCESS -> NavigateToLocationResult.SUCCESS;
                case UNREACHABLE -> NavigateToLocationResult.UNREACHABLE;
                case STUCK, FAILURE -> NavigateToLocationResult.STUCK;
                case CANCELLED -> NavigateToLocationResult.CANCELLED;
                case TARGET_LOST -> throw new UnsupportedOperationException("Unimplemented case: " + result);
                case TIMEOUT -> throw new UnsupportedOperationException("Unimplemented case: " + result);
                default -> throw new IllegalArgumentException("Unexpected value: " + result);
            };
        });
    }
}