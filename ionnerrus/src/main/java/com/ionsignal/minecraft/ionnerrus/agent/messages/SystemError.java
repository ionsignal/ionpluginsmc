package com.ionsignal.minecraft.ionnerrus.agent.messages;

/**
 * A generic error message for when a Skill crashes or throws an unexpected exception.
 */
public record SystemError(Throwable error) {
}