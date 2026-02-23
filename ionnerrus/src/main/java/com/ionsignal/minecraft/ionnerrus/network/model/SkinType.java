package com.ionsignal.minecraft.ionnerrus.network.model;

public enum SkinType {
    STEVE((String) "STEVE"), ALEX((String) "ALEX");

    private final String value;

    SkinType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static SkinType fromValue(String value) {
        for (SkinType e : SkinType.values()) {
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