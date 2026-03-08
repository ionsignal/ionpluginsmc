package com.ionsignal.minecraft.ionnerrus.network.model;

public enum IonCommandType {
    COMMAND_PERSONA_SPAWN((String) "persona.spawn"), // COMMAND_PERSONA_SPAWN
    COMMAND_PERSONA_DESPAWN((String) "persona.despawn"), // COMMAND_PERSONA_DESPAWN
    COMMAND_PERSONA_TELEPORT((String) "persona.teleport"), // COMMAND_PERSONA_TELEPORT
    COMMAND_PERSONA_UPDATE((String) "persona.update"), // COMMAND_PERSONA_UPDATE
    SYSTEM_PERSONA_KILL((String) "system.kill"), // SYSTEM_PERSONA_KILL
    SYSTEM_COMMAND_FAILED((String) "system.command_failed"); // SYSTEM_COMMAND_FAILED

    private final String value;

    IonCommandType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static IonCommandType fromValue(String value) {
        for (IonCommandType e : IonCommandType.values()) {
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