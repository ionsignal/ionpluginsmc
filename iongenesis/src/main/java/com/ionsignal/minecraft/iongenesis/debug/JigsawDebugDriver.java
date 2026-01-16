package com.ionsignal.minecraft.iongenesis.debug;

import com.ionsignal.minecraft.ioncore.debug.DebugSession;
import com.ionsignal.minecraft.ioncore.debug.ExecutionController;
import com.ionsignal.minecraft.ioncore.debug.SessionStatus;
import com.ionsignal.minecraft.iongenesis.generation.StructureBlueprint;
import com.ionsignal.minecraft.iongenesis.generation.StructurePlanner;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.scheduler.BukkitRunnable;

public class JigsawDebugDriver extends BukkitRunnable {
    private static final Logger LOGGER = Logger.getLogger(JigsawDebugDriver.class.getName());
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
        try {
            if (session.getStatus() == SessionStatus.CANCELLED ||
                    session.getStatus() == SessionStatus.FAILED ||
                    session.getStatus() == SessionStatus.COMPLETED) {
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
                boolean working = planner.tick();
                if (!working) {
                    finished = true;
                }
            }
            // Update Session State
            session.setState(planner.getBlueprint());
            if (finished) {
                LOGGER.info("[Driver] Finished detected. Transitioning to COMPLETED.");
                session.transitionTo(SessionStatus.COMPLETED);
                this.cancel();
            } else if (!controller.isContinuingToEnd()) {
                // Auto-pause after step
                controller.pause("Placed Piece", "Count: " + planner.getBlueprint().pieces().size());
            }
        } catch (Exception e) {
            // THIS is where we catch the crash and print the stack trace
            LOGGER.log(Level.SEVERE, "[Driver] CRITICAL EXCEPTION:", e);
            session.setStatus(SessionStatus.FAILED); // Force status
            this.cancel();
        }
    }
}