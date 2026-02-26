package com.ionsignal.minecraft.ionnerrus.network.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Represents the specific debug control action sent from the Web UI to the Minecraft server.
 */
public enum DebugAction {
    STEP("step"), // Proceed with the current pending action (alias for resume)
    RESUME("resume"), // Resume execution normally
    CANCEL("cancel"), // Cancel the current execution controller/goal
    CONTINUE("continue"); // Skip all future debug pauses and finish the task

    private final String value;

    DebugAction(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static DebugAction fromValue(String value) {
        for (DebugAction e : DebugAction.values()) {
            if (e.value.equals(value)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}