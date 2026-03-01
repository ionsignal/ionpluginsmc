package com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.common;

import com.ionsignal.minecraft.ionnerrus.IonNerrus;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.NerrusAgent;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.behaviors.core.Skill;
import com.ionsignal.minecraft.ionnerrus.agent.cognition.execution.ExecutionToken;
import com.ionsignal.minecraft.ionnerrus.persona.Persona;
import com.ionsignal.minecraft.ionnerrus.persona.components.PhysicalBody;
import com.ionsignal.minecraft.ionnerrus.persona.components.results.MovementResult;
import com.ionsignal.minecraft.ionnerrus.persona.navigation.Path;
import com.ionsignal.minecraft.ionnerrus.util.DebugVisualizer;

import net.kyori.adventure.text.format.NamedTextColor;

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
    public CompletableFuture<NavigateToLocationResult> execute(NerrusAgent agent, ExecutionToken token) {
        return CompletableFuture.supplyAsync(() -> {
            Persona persona = agent.getPersona();
            if (!persona.isSpawned()) {
                return CompletableFuture.completedFuture(MovementResult.CANCELLED);
            }
            // Check cancellation before starting heavy work
            if (!token.isActive()) {
                return CompletableFuture.completedFuture(MovementResult.CANCELLED);
            }
            PhysicalBody body = persona.getPhysicalBody();
            if (lookAt != null) {
                body.orientation().face(lookAt, false, token);
            } else {
                body.orientation().clearLookTarget();
            }
            Location debugTarget = null;
            if (preCalculatedPath != null && !preCalculatedPath.isEmpty()) {
                // Get the very last point of the path
                debugTarget = preCalculatedPath.getPointAtDistance(preCalculatedPath.getLength());
            } else if (target != null) {
                debugTarget = target;
            }
            if (debugTarget != null) {
                // Visualize with a small magenta block for 60 ticks (3 seconds)
                DebugVisualizer.highlightPoint(
                        debugTarget,
                        60,
                        NamedTextColor.LIGHT_PURPLE);
            }
            // Pass the token to the physical body!
            if (preCalculatedPath != null) {
                return body.movement().moveTo(preCalculatedPath, token);
            } else if (target != null) {
                return body.movement().moveTo(target, token);
            } else {
                return CompletableFuture.completedFuture(MovementResult.UNREACHABLE);
            }
        }, IonNerrus.getInstance().getMainThreadExecutor())
                .thenCompose(future -> future)
                .thenApply(result -> {
                    if (result == null) {
                        return NavigateToLocationResult.FAILURE;
                    }
                    return switch (result) {
                        case SUCCESS -> NavigateToLocationResult.SUCCESS;
                        case UNREACHABLE -> NavigateToLocationResult.UNREACHABLE;
                        case STUCK, FAILURE -> NavigateToLocationResult.STUCK;
                        case CANCELLED -> NavigateToLocationResult.CANCELLED;
                        default -> NavigateToLocationResult.STUCK;
                    };
                });
    }
}