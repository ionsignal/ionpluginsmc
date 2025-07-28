package com.ionsignal.minecraft.ionnerrus.persona.action;

/**
 * Represents the status of an ongoing or completed action.
 */
public enum ActionStatus {
    /**
     * The action is currently in progress.
     */
    RUNNING,
    /**
     * The action completed successfully.
     */
    SUCCESS,
    /**
     * The action failed to complete.
     */
    FAILURE,
    /**
     * The action was cancelled before it could complete.
     */
    CANCELLED
}