package com.ionsignal.minecraft.ionnerrus.terra.debug;

import com.ionsignal.minecraft.ioncore.debug.DebugSession;
import com.ionsignal.minecraft.ioncore.debug.ExecutionController;
import com.ionsignal.minecraft.ioncore.debug.SessionStatus;
import com.ionsignal.minecraft.ionnerrus.terra.generation.StructureBlueprint;
import com.ionsignal.minecraft.ionnerrus.terra.generation.StructurePlanner;

import org.bukkit.scheduler.BukkitRunnable;

public class JigsawDebugDriver extends BukkitRunnable {
    private static final long MAX_MS_PER_TICK = 40;

    private final DebugSession<StructureBlueprint> session;
    private final StructurePlanner planner;
    private final ExecutionController controller;

    public JigsawDebugDriver(DebugSession<StructureBlueprint> session, StructurePlanner planner) {
        this.session = session;
        this.planner = planner;
        this.controller = session.getController().orElseThrow();
    }

    @Override
    public void run() {
        if (session.getStatus() == SessionStatus.CANCELLED || session.getStatus() == SessionStatus.FAILED) {
            this.cancel();
            return;
        }

        if (controller.isPaused()) {
            return; // Wait for resume
        }

        boolean finished = false;
        long startTime = System.currentTimeMillis();

        if (controller.isContinuingToEnd()) {
            // Fast mode: Run as many steps as possible within time budget
            while (System.currentTimeMillis() - startTime < MAX_MS_PER_TICK) {
                if (!planner.tick()) {
                    finished = true;
                    break;
                }
            }
        } else {
            // Step mode: Run one logical step (place one piece)
            // Planner.tick() might process a failed connection and return true without placing a piece.
            // We want to step until a piece is placed or finished to avoid "empty" steps for the user.
            int initialPieceCount = session.getState().pieces().size();
            while (true) {
                boolean working = planner.tick();
                if (!working) {
                    finished = true;
                    break;
                }
                // Check if we placed a piece
                // Note: We need to peek at the planner's internal state via blueprint
                // Optimization: Planner could return a result enum instead of boolean
                if (planner.getBlueprint().pieces().size() > initialPieceCount) {
                    break; // Piece placed, stop stepping
                }
                // Safety break for infinite loops in non-placing ticks
                if (System.currentTimeMillis() - startTime > MAX_MS_PER_TICK)
                    break;
            }
        }

        // Update Session State
        session.setState(planner.getBlueprint());

        if (finished) {
            session.transitionTo(SessionStatus.COMPLETED);
            this.cancel();
        } else if (!controller.isContinuingToEnd()) {
            // Auto-pause after step
            controller.pause("Placed Piece", "Count: " + planner.getBlueprint().pieces().size());
        }
    }
}