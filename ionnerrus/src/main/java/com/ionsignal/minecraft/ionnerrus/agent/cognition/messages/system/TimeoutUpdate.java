package com.ionsignal.minecraft.ionnerrus.agent.cognition.messages.system;

/**
 * Universal message indicating a time-bounded operation has expired.
 */
public record TimeoutUpdate(String reason) {
}