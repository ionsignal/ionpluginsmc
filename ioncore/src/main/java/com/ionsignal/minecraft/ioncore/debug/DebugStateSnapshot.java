package com.ionsignal.minecraft.ioncore.debug;

/**
 * Marker interface for immutable debug state snapshots.
 * All state objects stored in DebugSession must implement this interface.
 * 
 * Thread Safety: All implementations MUST be immutable records or equivalent.
 * 
 * @see DebugSession#setState(Object)
 */
public interface DebugStateSnapshot {
    /**
     * Gets a human-readable label identifying the type of debug state.
     * Used by visualization providers to determine rendering strategy.
     * 
     * Examples: "Agent Execution", "Structure Generation", "LLM Reasoning"
     * 
     * @return A non-null, non-empty string label
     */
    String getDebugLabel();
}