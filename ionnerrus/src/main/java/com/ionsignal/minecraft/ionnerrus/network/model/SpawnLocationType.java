package com.ionsignal.minecraft.ionnerrus.network.model;


public enum SpawnLocationType {
  PLAYER((String)"PLAYER"), COORDINATES((String)"COORDINATES");

  private final String value;

  SpawnLocationType(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  public static SpawnLocationType fromValue(String value) {
    for (SpawnLocationType e : SpawnLocationType.values()) {
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