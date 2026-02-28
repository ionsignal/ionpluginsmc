package com.ionsignal.minecraft.ionnerrus.network.model;

public enum IonEventType {
    EVENT_PLAYER_JOIN((String) "event:player:join"), // EVENT_PLAYER_JOIN
    EVENT_PLAYER_QUIT((String) "event:player:quit"), // EVENT_PLAYER_QUIT
    EVENT_PERSONA_STATE((String) "event:persona:state"), // EVENT_PERSONA_STATE
    REQUEST_PERSONA_SPAWN((String) "request:persona:spawn"), // REQUEST_PERSONA_SPAWN
    REQUEST_PERSONA_DESPAWN((String) "request:persona:despawn"), // REQUEST_PERSONA_DESPAWN
    REQUEST_PERSONA_LIST((String) "request:persona:list"); // REQUEST_PERSONA_LIST

    private final String value;

    IonEventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static IonEventType fromValue(String value) {
        for (IonEventType e : IonEventType.values()) {
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