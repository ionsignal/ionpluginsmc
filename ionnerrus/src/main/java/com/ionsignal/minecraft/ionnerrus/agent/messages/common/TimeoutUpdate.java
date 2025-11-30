package com.ionsignal.minecraft.ionnerrus.agent.messages.common;

/**
 * Universal message indicating a time-bounded operation has expired.
 */
public record TimeoutUpdate(String reason) {
}