package com.ionsignal.minecraft.ionnerrus.persona.locomotion.maneuvers;

import com.ionsignal.minecraft.ionnerrus.persona.PersonaEntity;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.Maneuver;
import com.ionsignal.minecraft.ionnerrus.persona.locomotion.ManeuverResult;
import com.ionsignal.minecraft.ionnerrus.persona.movement.PersonaLookControl.BodyMode;
import com.ionsignal.minecraft.ionnerrus.persona.orientation.OrientationIntent;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Optional;

/**
 * Handles the "Heave" physics required to transition from Water to Land.
 * This maneuver forces a Jump + Forward input to breach surface tension.
 */
public class WaterExitManeuver implements Maneuver {
    private static final int HARD_TIMEOUT = 40;

    private final Location targetLandSpot;

    private ExitState state;
    private int ticks;

    private enum ExitState {
        ALIGNING, HEAVING, LANDED, FAILED
    }

    public WaterExitManeuver(Location targetLandSpot) {
        this.targetLandSpot = targetLandSpot;
        this.state = ExitState.ALIGNING;
    }

    @Override
    public void start(PersonaEntity entity, Optional<Vector> exitHeading) {
        this.ticks = 0;
        entity.setSprinting(true); // Maintain momentum
    }

    @Override
    public ManeuverResult stop(PersonaEntity entity) {
        entity.getJumpControl().stop();
        entity.setSprinting(false); // Clean up sprint state

        if (state == ExitState.LANDED) {
            return new ManeuverResult(ManeuverResult.Status.SUCCESS, entity.getBukkitEntity().getLocation(), "Exited water");
        }
        return new ManeuverResult(ManeuverResult.Status.FAILED, entity.getBukkitEntity().getLocation(), "Water exit failed");
    }

    @Override
    public void tick(PersonaEntity entity) {
        ticks++;
        if (ticks > HARD_TIMEOUT) {
            state = ExitState.FAILED;
            return;
        }
        switch (state) {
            case ALIGNING -> {
                // Move towards the ledge
                entity.getMoveControl().setWantedPosition(
                        targetLandSpot.getX(),
                        targetLandSpot.getY(),
                        targetLandSpot.getZ(),
                        1.2); // Slight speed boost
                // If close enough, start heaving
                double distSq = entity.getBukkitEntity().getLocation().distanceSquared(targetLandSpot);
                if (distSq < 2.5) { // Within 1.5 blocks
                    state = ExitState.HEAVING;
                    ticks = 0;
                }
            }
            case HEAVING -> {
                // Hold Jump to breach surface
                entity.getJumpControl().jump();
                // Drive forward onto land
                entity.getMoveControl().setWantedPosition(
                        targetLandSpot.getX(),
                        targetLandSpot.getY(),
                        targetLandSpot.getZ(),
                        1.0);

                if (entity.onGround()) {
                    state = ExitState.LANDED;
                }
            }
            case LANDED, FAILED -> {
                // Terminal states
            }
        }
    }

    @Override
    public boolean isFinished() {
        return state == ExitState.LANDED || state == ExitState.FAILED;
    }

    @Override
    public OrientationIntent getOrientation() {
        // Look at the landing spot to ensure correct forward vector
        return new OrientationIntent.FocusOnLocation(targetLandSpot, BodyMode.LOCKED);
    }
}