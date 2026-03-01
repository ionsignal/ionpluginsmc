package com.ionsignal.minecraft.ionnerrus.agent.cognition.messages.system;

/**
 * A generic error message for when a Skill crashes or throws an unexpected exception.
 */
public record SystemError(Throwable error) {
}