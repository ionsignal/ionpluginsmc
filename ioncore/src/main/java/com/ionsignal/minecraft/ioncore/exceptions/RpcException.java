package com.ionsignal.minecraft.ioncore.exceptions;

/**
 * Thrown when the Node.js orchestrator explicitly rejects an RPC request. This represents a
 * business logic or validation failure (e.g., BAD_REQUEST, NOT_FOUND), not a network or
 * serialization failure.
 */
public class RpcException extends RuntimeException {
    private final String errorCode;
    private final String details;

    public RpcException(String errorCode, String details) {
        super("RPC Error [" + errorCode + "]: " + details);
        this.errorCode = errorCode;
        this.details = details;
    }

    /**
     * @return The specific error code returned by the Node.js orchestrator (e.g., "NOT_FOUND").
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * @return Additional context or validation details provided by the orchestrator.
     */
    public String getDetails() {
        return details;
    }
}