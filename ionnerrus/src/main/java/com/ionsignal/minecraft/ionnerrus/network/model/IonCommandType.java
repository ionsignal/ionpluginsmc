package com.ionsignal.minecraft.ionnerrus.network.model;

public enum IonCommandType {
    COMMAND_PERSONA_SPAWN((String) "command:persona:spawn"), // COMMAND_PERSONA_SPAWN
    COMMAND_PERSONA_DESPAWN(
            (String) "command:persona:despawn"), // COMMAND_PERSONA_DESPAWN
    COMMAND_PERSONA_TELEPORT(
            (String) "command:persona:teleport"), // COMMAND_PERSONA_TELEPORT
    COMMAND_PERSONA_SKIN_UPDATE((String) "command:persona:skin:update"), // COMMAND_PERSONA_SKIN_UPDATE
    COMMAND_PERSONA_LIST((String) "command:persona:list"), // COMMAND_PERSONA_LIST
    SYSTEM_PERSONA_KILL((String) "system:persona:kill"), // SYSTEM_PERSONA_KILL
    SYSTEM_COMMAND_FAILED((String) "system:command:failed"); // SYSTEM_COMMAND_FAILED

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