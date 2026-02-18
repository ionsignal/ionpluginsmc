package com.ionsignal.minecraft.ionnerrus.network.model;


public enum AgentStatus {
  IDLE((String)"idle"), WALKING((String)"walking"), OFFLINE((String)"offline"), UNKNOWN((String)"unknown");

  private final String value;

  AgentStatus(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  public static AgentStatus fromValue(String value) {
    for (AgentStatus e : AgentStatus.values()) {
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