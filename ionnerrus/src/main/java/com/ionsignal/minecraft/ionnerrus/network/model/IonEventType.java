package com.ionsignal.minecraft.ionnerrus.network.model;

public enum IonEventType {
    EVENT_PLAYER_JOIN((String) "player.join"), // EVENT_PLAYER_JOIN
    EVENT_PLAYER_QUIT((String) "player.quit"), // EVENT_PLAYER_QUIT
    EVENT_PERSONA_STATE((String) "persona.state"), // EVENT_PERSONA_STATE
    REQUEST_PERSONA_SPAWN((String) "persona.spawn.request"), // REQUEST_PERSONA_SPAWN
    REQUEST_PERSONA_DESPAWN((String) "persona.despawn.request"); // REQUEST_PERSONA_DESPAWN

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