package com.ionsignal.minecraft.ioncore.network;

import java.util.Objects;

/**
 * Centralized Subject Builder for all NATS Egress.
 * Enforces the strict 4-part Universal Subject Taxonomy: prefix.target.domain.action
 */
public final class UniversalSubjectBuilder {
    private UniversalSubjectBuilder() {
    }

    /**
     * Constructs a standardized subject string across all ecosystem modules.
     */
    public static String build(String prefix, String target, String domain, String action) {
        Objects.requireNonNull(prefix, "Prefix cannot be null");
        Objects.requireNonNull(target, "Target cannot be null");
        Objects.requireNonNull(domain, "Domain cannot be null");
        Objects.requireNonNull(action, "Action cannot be null");
        return prefix + "." + target + "." + domain + "." + action;
    }

    /**
     * Parses a 'type' string (e.g., "persona.spawn") and constructs the full subject.
     * Fails fast if the type string does not contain a domain and action.
     */
    public static String buildEventOrCommand(String prefix, String target, String typeString) {
        Objects.requireNonNull(prefix, "Prefix cannot be null");
        Objects.requireNonNull(target, "Target cannot be null");
        Objects.requireNonNull(typeString, "Type string cannot be null");
        // Use indexOf instead of split("\\.") to avoid Regex overhead on the hot path
        int dotIndex = typeString.indexOf('.');
        if (dotIndex == -1) {
            throw new IllegalArgumentException("Payload type must contain a domain and action separated by a dot. Received: " + typeString);
        }
        String domain = typeString.substring(0, dotIndex);
        String action = typeString.substring(dotIndex + 1);
        return build(prefix, target, domain, action);
    }

    /**
     * Constructs a standardized wildcard subject for subscriptions.
     */
    public static String buildTargetWildcard(String prefix, String target) {
        Objects.requireNonNull(prefix, "Prefix cannot be null");
        Objects.requireNonNull(target, "Target cannot be null");
        return prefix + "." + target + ".>";
    }
}